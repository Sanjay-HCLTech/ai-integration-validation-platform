package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.rabbit.RabbitFlowConfig;
import com.hcl.execution.rabbit.RabbitTriggerOutcome;
import com.hcl.execution.trigger.RabbitMqTriggerService;
import com.hcl.observability.log.LogAnalyzerService;
import com.hcl.observability.log.LogSearchResult;
import com.hcl.observability.sftp.SftpService;
import com.hcl.observability.trace.NormalizedTraceEvent;
import com.hcl.observability.trace.TracePhase;
import com.hcl.observability.trace.TraceProtocol;
import com.hcl.observability.trace.TraceStatus;
import com.hcl.observability.trace.TraceSystem;
import com.hcl.observability.trace.UnifiedTraceContext;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class RabbitExecutionService {

    private final RabbitMqTriggerService rabbitMqTriggerService;
    private final RabbitFlowConfig rabbitFlowConfig;
    private final LogAnalyzerService logAnalyzerService;
    private final SftpService sftpService;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final String rabbitNordicsRemoteLogPath;
    private final String localLogDir;

    public RabbitExecutionService(
            RabbitMqTriggerService rabbitMqTriggerService,
            RabbitFlowConfig rabbitFlowConfig,
            LogAnalyzerService logAnalyzerService,
            SftpService sftpService,
            UnifiedTraceContextHolder traceContextHolder,
            @Value("${rabbit.nordics.sftp.payload.log.dir:${rabbit.nordics.log.remote.path:}}") String rabbitNordicsRemoteLogPath,
            @Value("${local.log.dir:C:/logs}") String localLogDir) {
        this.rabbitMqTriggerService = rabbitMqTriggerService;
        this.rabbitFlowConfig = rabbitFlowConfig;
        this.logAnalyzerService = logAnalyzerService;
        this.sftpService = sftpService;
        this.traceContextHolder = traceContextHolder;
        this.rabbitNordicsRemoteLogPath = rabbitNordicsRemoteLogPath;
        this.localLogDir = localLogDir;
    }

    public ProtocolExecutionResult execute(TestCase testCase) {
        long startNanos = System.nanoTime();
        try {
            RabbitTriggerOutcome outcome = rabbitMqTriggerService.execute(testCase);
            ProtocolExecutionResult result = result("RABBITMQ", "ASYNC",
                    outcome.getStatus(), elapsedMs(startNanos), outcome.getMessage());
            result.setCorrId(outcome.getCorrId());
            result.setTrackingId(outcome.getTrackingId());
            result.setJobId(outcome.getJobId());
            result.setPayloadSource(outcome.getPayloadSource());
            result.setEndpointOrDestination(outcome.getExchange() + "/" + outcome.getRoutingKey() + " -> " + outcome.getQueue());
            LogScanOutcome logScanOutcome = scanNordicsLogsIfEnabled(testCase, outcome);
            result.setProcessStatus("ASYNC_VALIDATION_PENDING");
            result.setDownstreamStatus("ASYNC_VALIDATION_PENDING");
            result.setErrorFound("NO");
            result.setValidationComplete(false);
            if (logScanOutcome.attempted) {
                result.setProcessStatus(logScanOutcome.found ? "PASS" : "TRACE_PENDING");
                result.setDownstreamStatus(logScanOutcome.found ? "SUCCESS" : "TRACE_PENDING");
                result.setValidationComplete(logScanOutcome.found);
                result.setStatus(logScanOutcome.found ? "SUCCESS" : outcome.getStatus());
                result.setMessage(outcome.getMessage() + "; " + logScanOutcome.message);
            }
            log(result);
            return result;
        } catch (RuntimeException e) {
            ProtocolExecutionResult result = result("RABBITMQ", "ASYNC",
                    "ERROR", elapsedMs(startNanos), e.getMessage());
            result.setProcessStatus("FAIL");
            result.setDownstreamStatus("FAILED");
            result.setErrorFound("YES");
            result.setValidationComplete(true);
            log(result);
            return result;
        }
    }

    private ProtocolExecutionResult result(String protocol, String mode, String status, long latencyMs, String message) {
        ProtocolExecutionResult result = new ProtocolExecutionResult();
        result.setProtocol(protocol);
        result.setMode(mode);
        result.setStatus(status);
        result.setLatencyMs(latencyMs);
        result.setMessage(message);
        return result;
    }

    private LogScanOutcome scanNordicsLogsIfEnabled(TestCase testCase, RabbitTriggerOutcome outcome) {
        String service = testCase == null ? null : testCase.getScenario();
        if (!rabbitFlowConfig.logScanEnabled(outcome.getEnv(), outcome.getSystem(), service)) {
            return LogScanOutcome.skipped("Nordics Rabbit log scan disabled");
        }

        String bookingId = testCase == null ? null : testCase.getBookingId();
        String corrId = outcome.getCorrId();
        String jobId = outcome.getJobId();
        String trackingId = outcome.getTrackingId();
        long scanStartNanos = System.nanoTime();
        try (AutoCloseable ignored = useSftpProfile("rabbit-nordics")) {
            LogSearchResult primary = logAnalyzerService.searchFinalTraceDetailed(bookingId, corrId, jobId);
            if (hasEvidence(primary)) {
                LogScanOutcome found = LogScanOutcome.found("Primary trace search matched: " + primary.getMessage());
                addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.SUCCESS,
                        elapsedMs(scanStartNanos), primary.getMessage());
                printLogScan("FOUND_PRIMARY", bookingId, corrId, jobId, trackingId, primary.getMessage());
                return found;
            }
            if (hasText(trackingId) && !sameText(trackingId, corrId)) {
                LogSearchResult fallback = logAnalyzerService.searchFinalTraceDetailed(bookingId, trackingId, null);
                if (hasEvidence(fallback)) {
                    LogScanOutcome found = LogScanOutcome.found("TrackingID fallback matched: " + fallback.getMessage());
                    addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.SUCCESS,
                            elapsedMs(scanStartNanos), fallback.getMessage());
                    printLogScan("FOUND_TRACKING_ID", bookingId, corrId, jobId, trackingId, fallback.getMessage());
                    return found;
                }
                String snapshotMessage = forceRabbitNordicsAuditSnapshot(bookingId);
                LogScanOutcome pending = LogScanOutcome.pending("No Rabbit/Nordics audit trace found by primary IDs or TrackingID: "
                        + fallback.getMessage() + "; " + snapshotMessage);
                addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.UNKNOWN,
                        elapsedMs(scanStartNanos), fallback.getMessage() + "; " + snapshotMessage);
                printLogScan("NOT_FOUND", bookingId, corrId, jobId, trackingId,
                        fallback.getMessage() + "; " + snapshotMessage);
                return pending;
            }
            String snapshotMessage = forceRabbitNordicsAuditSnapshot(bookingId);
            LogScanOutcome pending = LogScanOutcome.pending("No Rabbit/Nordics audit trace found by primary IDs: "
                    + primary.getMessage() + "; " + snapshotMessage);
            addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.UNKNOWN,
                    elapsedMs(scanStartNanos), primary.getMessage() + "; " + snapshotMessage);
            printLogScan("NOT_FOUND", bookingId, corrId, jobId, trackingId,
                    primary.getMessage() + "; " + snapshotMessage);
            return pending;
        } catch (Exception e) {
            LogScanOutcome failed = LogScanOutcome.pending("Rabbit/Nordics audit scan failed: " + e.getMessage());
            addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.ERROR,
                    elapsedMs(scanStartNanos), e.getMessage());
            printLogScan("FAILED", bookingId, corrId, jobId, trackingId, e.getMessage());
            return failed;
        }
    }

    private void addLogScanTrace(
            String bookingId,
            String corrId,
            String jobId,
            String trackingId,
            TraceStatus status,
            long logAnalyzerMs,
            String message) {
        UnifiedTraceContext context = traceContextHolder.currentOrCreate();
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                bookingId,
                corrId,
                jobId,
                TraceSystem.OBSERVABILITY,
                TraceProtocol.RABBITMQ,
                TracePhase.PROCESS,
                "RabbitAuditLogAnalyzer",
                java.time.OffsetDateTime.now(),
                status,
                message);
        event.setFromEndpoint("SFTP");
        event.setToEndpoint("LocalLogAnalyzer");
        context.addEvent(event);
        context.addRetryLine("Stage=RabbitAudit Attempt=1/1 Result=" + retryResult(status) + " Action=STOP");
        context.addValidationLine("RabbitObservability=" + validationStatus(status));
        context.addValidationLine("RabbitTrackingID=" + value(trackingId));
        context.addSummaryLine("RabbitLogAnalyzerTimeMs=" + logAnalyzerMs);
        context.addSummaryLine("RabbitAuditSnapshot=" + (status == TraceStatus.SUCCESS ? "TRACE_FOUND" : "SNAPSHOT_ATTEMPTED"));
    }

    private String retryResult(TraceStatus status) {
        return status == TraceStatus.SUCCESS ? "FOUND" : status == TraceStatus.ERROR ? "ERROR" : "NOT_FOUND";
    }

    private String validationStatus(TraceStatus status) {
        return status == TraceStatus.SUCCESS ? "SUCCESS" : status == TraceStatus.ERROR ? "ERROR" : "PENDING";
    }

    private boolean hasEvidence(LogSearchResult result) {
        return result != null
                && (!result.getLines().isEmpty()
                || result.getFilesTransferred() > 0
                || result.getCompleteLocalFiles() > 0);
    }

    private String forceRabbitNordicsAuditSnapshot(String bookingId) {
        if (!hasText(bookingId)) {
            return "Raw audit snapshot skipped because BookingID is unavailable";
        }
        String remoteAuditLog = resolvedRabbitNordicsAuditLogPath();
        if (!hasText(remoteAuditLog)) {
            return "Raw audit snapshot skipped because rabbit.nordics.sftp.payload.log.dir is not configured";
        }
        String localAuditLog = localAuditLogPath(bookingId);
        try {
            sftpService.download(remoteAuditLog, localAuditLog);
            return "Raw audit snapshot transferred to " + localAuditLog;
        } catch (Exception e) {
            return "Raw audit snapshot transfer failed: " + e.getMessage();
        }
    }

    private String resolvedRabbitNordicsAuditLogPath() {
        String value = rabbitNordicsRemoteLogPath == null ? "" : rabbitNordicsRemoteLogPath.trim();
        if (!hasText(value)) {
            return "";
        }
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return value
                .replace("<todays date>", today)
                .replace("${today}", today)
                .replace("{today}", today);
    }

    private String localAuditLogPath(String bookingId) {
        File bookingDir = new File(localLogDir, safePathSegment(bookingId));
        return new File(bookingDir, "audit.log").getPath();
    }

    private String safePathSegment(String value) {
        String text = hasText(value) ? value.trim() : "NA";
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private AutoCloseable useSftpProfile(String profile) {
        try {
            Class<?> context = Class.forName("com.hcl.observability.sftp.SftpProfileContext");
            Object scope = context.getMethod("use", String.class).invoke(null, profile);
            if (scope instanceof AutoCloseable) {
                return (AutoCloseable) scope;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return () -> {
        };
    }

    private void printLogScan(String status, String bookingId, String corrId, String jobId, String trackingId, String message) {
        System.out.println("[RABBIT_LOG_SCAN]");
        System.out.println("Status=" + value(status));
        System.out.println("BookingID=" + value(bookingId));
        System.out.println("JobID=" + value(jobId));
        System.out.println("CorrID=" + value(corrId));
        System.out.println("TrackingID=" + value(trackingId));
        System.out.println("Message=" + value(oneLine(message)));
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void log(ProtocolExecutionResult result) {
        System.out.println("[PROTOCOL_EXECUTION]");
        System.out.println("Protocol=" + result.getProtocol()
                + " Mode=" + result.getMode()
                + " Status=" + result.getStatus()
                + " LatencyMs=" + result.getLatencyMs());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean sameText(String first, String second) {
        return hasText(first) && hasText(second) && first.trim().equals(second.trim());
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }

    private static class LogScanOutcome {
        private final boolean attempted;
        private final boolean found;
        private final String message;

        private LogScanOutcome(boolean attempted, boolean found, String message) {
            this.attempted = attempted;
            this.found = found;
            this.message = message;
        }

        private static LogScanOutcome skipped(String message) {
            return new LogScanOutcome(false, false, message);
        }

        private static LogScanOutcome found(String message) {
            return new LogScanOutcome(true, true, message);
        }

        private static LogScanOutcome pending(String message) {
            return new LogScanOutcome(true, false, message);
        }
    }
}
