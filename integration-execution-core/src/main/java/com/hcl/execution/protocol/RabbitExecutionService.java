package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.rabbit.RabbitFlowConfig;
import com.hcl.execution.rabbit.RabbitTriggerOutcome;
import com.hcl.execution.trigger.RabbitMqTriggerService;
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
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class RabbitExecutionService {

    private final RabbitMqTriggerService rabbitMqTriggerService;
    private final RabbitFlowConfig rabbitFlowConfig;
    private final SftpService sftpService;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final String rabbitNordicsRemoteLogPath;
    private final String localLogDir;

    public RabbitExecutionService(
            RabbitMqTriggerService rabbitMqTriggerService,
            RabbitFlowConfig rabbitFlowConfig,
            SftpService sftpService,
            UnifiedTraceContextHolder traceContextHolder,
            @Value("${rabbit.nordics.sftp.payload.log.dir}") String rabbitNordicsRemoteLogPath,
            @Value("${local.log.dir}") String localLogDir) {
        this.rabbitMqTriggerService = rabbitMqTriggerService;
        this.rabbitFlowConfig = rabbitFlowConfig;
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
            result.putMetadata("RABBIT_BOOKING_ID", rabbitEvidenceBookingId(testCase, outcome));
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

        String requestBookingId = testCase == null ? null : testCase.getBookingId();
        String messageBookingId = rabbitBookingId(outcome.getMessageProperties());
        String bookingId = firstText(requestBookingId, messageBookingId);
        String corrId = outcome.getCorrId();
        String jobId = outcome.getJobId();
        String trackingId = outcome.getTrackingId();
        String processContextInstanceId = rabbitFlowConfig.processContextInstanceId(
                outcome.getEnv(),
                outcome.getSystem(),
                service);
        long scanStartNanos = System.nanoTime();
        try (AutoCloseable ignored = useSftpProfile("rabbit-nordics")) {
            String evidenceScope = firstText(bookingId, jobId, corrId, trackingId, processContextInstanceId);
            List<SearchAttempt> attempts = rabbitSearchAttempts(
                    bookingId,
                    jobId,
                    corrId,
                    trackingId,
                    processContextInstanceId);
            StringBuilder misses = new StringBuilder();
            for (SearchAttempt attempt : attempts) {
                RabbitAuditSearchResult result = searchRabbitAuditLog(attempt);
                if (result.found) {
                    String transferScope = firstText(attempt.token, evidenceScope);
                    String transferMessage = result.transferMessage;
                    LogScanOutcome found = LogScanOutcome.found(attempt.label + " trace search matched: "
                            + result.message + "; " + transferMessage);
                    addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.SUCCESS,
                            elapsedMs(scanStartNanos), attempt.label + ": " + result.message
                                    + "; " + transferMessage);
                    printLogScan("FOUND_" + attempt.labelKey(), bookingId, corrId, jobId, trackingId,
                            attempt.label + ": " + result.message + "; " + transferMessage);
                    return found;
                }
                if (misses.length() > 0) {
                    misses.append("; ");
                }
                misses.append(attempt.label).append(": ").append(result.message);
            }
            String transferMessage = transferRabbitNordicsAuditLog(evidenceScope);
            String message = "No Rabbit/Nordics audit trace found by BookingID > JobID > CorrID > TrackingID > processContext.instanceId: "
                    + value(misses.toString()) + "; " + transferMessage;
            LogScanOutcome pending = LogScanOutcome.pending(message);
            addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.UNKNOWN,
                    elapsedMs(scanStartNanos), message);
            printLogScan("NOT_FOUND", bookingId, corrId, jobId, trackingId, message);
            return pending;
        } catch (Exception e) {
            LogScanOutcome failed = LogScanOutcome.pending("Rabbit/Nordics audit scan failed: " + e.getMessage());
            addLogScanTrace(bookingId, corrId, jobId, trackingId, TraceStatus.ERROR,
                    elapsedMs(scanStartNanos), e.getMessage());
            printLogScan("FAILED", bookingId, corrId, jobId, trackingId, e.getMessage());
            return failed;
        }
    }

    private List<SearchAttempt> rabbitSearchAttempts(
            String bookingId,
            String jobId,
            String corrId,
            String trackingId,
            String processContextInstanceId) {
        List<SearchAttempt> attempts = new ArrayList<>();
        if (hasText(bookingId)) {
            attempts.add(new SearchAttempt("BookingID", bookingId, bookingId, null, null));
        }
        if (hasText(jobId) && !sameText(jobId, bookingId)) {
            attempts.add(new SearchAttempt("JobID", jobId, null, null, jobId));
        }
        if (hasText(corrId) && !sameText(corrId, bookingId) && !sameText(corrId, jobId)) {
            attempts.add(new SearchAttempt("CorrID", corrId, null, corrId, null));
        }
        if (hasText(trackingId)
                && !sameText(trackingId, bookingId)
                && !sameText(trackingId, jobId)
                && !sameText(trackingId, corrId)) {
            attempts.add(new SearchAttempt("TrackingID", trackingId, null, trackingId, null));
        }
        if (hasText(processContextInstanceId)
                && !sameText(processContextInstanceId, bookingId)
                && !sameText(processContextInstanceId, jobId)
                && !sameText(processContextInstanceId, corrId)
                && !sameText(processContextInstanceId, trackingId)) {
            attempts.add(new SearchAttempt(
                    "processContext.instanceId",
                    processContextInstanceId,
                    null,
                    processContextInstanceId,
                    null));
        }
        return attempts;
    }

    private String rabbitBookingId(Map<String, String> messageProperties) {
        if (messageProperties == null || messageProperties.isEmpty()) {
            return "";
        }
        return firstText(
                typedValue(messageProperties.get("BookingID")),
                typedValue(messageProperties.get("Booking_Id")),
                typedValue(messageProperties.get("Res_Id")),
                typedValue(messageProperties.get("ResId")));
    }

    private String rabbitEvidenceBookingId(TestCase testCase, RabbitTriggerOutcome outcome) {
        return firstText(
                testCase == null ? null : testCase.getBookingId(),
                rabbitBookingId(outcome == null ? null : outcome.getMessageProperties()));
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
        context.addSummaryLine("RabbitAuditLogTransfer=" + (status == TraceStatus.SUCCESS ? "TRACE_FOUND" : "ATTEMPTED"));
    }

    private String retryResult(TraceStatus status) {
        return status == TraceStatus.SUCCESS ? "FOUND" : status == TraceStatus.ERROR ? "ERROR" : "NOT_FOUND";
    }

    private String validationStatus(TraceStatus status) {
        return status == TraceStatus.SUCCESS ? "SUCCESS" : status == TraceStatus.ERROR ? "ERROR" : "PENDING";
    }

    private RabbitAuditSearchResult searchRabbitAuditLog(SearchAttempt attempt) {
        String localAuditLog = localAuditLogPath(attempt.token);
        List<String> localMatches = matchingAuditLines(localAuditLog, attempt.token);
        if (!localMatches.isEmpty()) {
            traceContextHolder.currentOrCreate().addFileLine("File=" + padRight("audit.log", 48)
                    + " Exists=Y Action=REUSED LocalPath=" + localAuditLog
                    + " Reason=LOCAL_COMPLETE_RABBIT_NORDICS_AUDIT_LOG");
            traceContextHolder.currentOrCreate().addValidationLine("RabbitAuditLogTransfer=REUSED_LOCAL_COMPLETE_EVIDENCE");
            traceContextHolder.currentOrCreate().addSummaryLine("RabbitAuditMatchedLines=" + localMatches.size());
            return RabbitAuditSearchResult.found(
                    "Local Rabbit audit evidence reused: lines=" + localMatches.size()
                            + ", file=audit.log, remote skipped=true",
                    "Rabbit audit log transfer skipped because local complete evidence already exists");
        }

        String transferMessage = transferRabbitNordicsAuditLog(attempt.token);
        List<String> transferredMatches = matchingAuditLines(localAuditLog, attempt.token);
        if (!transferredMatches.isEmpty()) {
            traceContextHolder.currentOrCreate().addSummaryLine("RabbitAuditMatchedLines=" + transferredMatches.size());
            return RabbitAuditSearchResult.found(
                    "Rabbit audit evidence matched: lines=" + transferredMatches.size()
                            + ", file=audit.log",
                    transferMessage);
        }

        return RabbitAuditSearchResult.missed(
                "No Rabbit audit evidence found in " + localAuditLog + " for " + attempt.label + "="
                        + value(attempt.token),
                transferMessage);
    }

    private List<String> matchingAuditLines(String localAuditLog, String token) {
        List<String> matches = new ArrayList<>();
        if (!hasText(localAuditLog) || !hasText(token)) {
            return matches;
        }
        File file = new File(localAuditLog);
        if (!file.isFile() || file.length() <= 0) {
            return matches;
        }
        try {
            matches.addAll(matchingAuditLines(file, token, StandardCharsets.UTF_8));
        } catch (IOException utf8Failure) {
            try {
                matches.addAll(matchingAuditLines(file, token, Charset.defaultCharset()));
            } catch (IOException ignored) {
            }
        }
        return matches;
    }

    private List<String> matchingAuditLines(File file, String token, Charset charset) throws IOException {
        List<String> matches = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file.toPath(), charset)) {
            lines.filter(line -> line != null && line.contains(token))
                    .limit(200)
                    .forEach(matches::add);
        }
        return matches;
    }

    private String transferRabbitNordicsAuditLog(String evidenceScope) {
        if (!hasText(evidenceScope)) {
            return "Rabbit audit log transfer skipped because Rabbit evidence id is unavailable";
        }
        String remoteAuditLog = resolvedRabbitNordicsAuditLogPath();
        if (!hasText(remoteAuditLog)) {
            return "Rabbit audit log transfer skipped because rabbit.nordics.sftp.payload.log.dir is not configured";
        }
        String localAuditLog = localAuditLogPath(evidenceScope);
        try {
            sftpService.download(remoteAuditLog, localAuditLog);
            traceContextHolder.currentOrCreate().addFileLine("File=" + padRight("audit.log", 48)
                    + " Exists=Y Action=DOWNLOADED RemotePath=" + remoteAuditLog
                    + " LocalPath=" + localAuditLog
                    + " Reason=RABBIT_NORDICS_AUDIT_LOG");
            traceContextHolder.currentOrCreate().addValidationLine("RabbitAuditLogTransfer=DOWNLOADED");
            return "Rabbit audit log transferred to " + localAuditLog;
        } catch (Exception e) {
            traceContextHolder.currentOrCreate().addFileLine("File=" + padRight("audit.log", 48)
                    + " Exists=N Action=FAILED RemotePath=" + remoteAuditLog
                    + " LocalPath=" + localAuditLog
                    + " Reason=" + oneLine(e.getMessage()));
            traceContextHolder.currentOrCreate().addValidationLine("RabbitAuditLogTransfer=FAILED");
            return "Rabbit audit log transfer failed: " + e.getMessage();
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

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String item : values) {
            if (hasText(item)) {
                return item.trim();
            }
        }
        return "";
    }

    private String typedValue(String value) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        int colon = trimmed.indexOf(':');
        if (colon >= 0 && colon < trimmed.length() - 1) {
            return trimmed.substring(colon + 1).trim();
        }
        return trimmed;
    }

    private String safePathSegment(String value) {
        String text = hasText(value) ? value.trim() : "NA";
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String padRight(String value, int width) {
        String safeValue = value(value);
        if (safeValue.length() >= width) {
            return safeValue;
        }
        StringBuilder builder = new StringBuilder(safeValue);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
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

    private static class SearchAttempt {
        private final String label;
        private final String token;
        private final String bookingId;
        private final String corrId;
        private final String jobId;

        private SearchAttempt(String label, String token, String bookingId, String corrId, String jobId) {
            this.label = label;
            this.token = token;
            this.bookingId = bookingId;
            this.corrId = corrId;
            this.jobId = jobId;
        }

        private String labelKey() {
            return label.replace('.', '_').toUpperCase();
        }
    }

    private static class RabbitAuditSearchResult {
        private final boolean found;
        private final String message;
        private final String transferMessage;

        private RabbitAuditSearchResult(boolean found, String message, String transferMessage) {
            this.found = found;
            this.message = message;
            this.transferMessage = transferMessage;
        }

        private static RabbitAuditSearchResult found(String message, String transferMessage) {
            return new RabbitAuditSearchResult(true, message, transferMessage);
        }

        private static RabbitAuditSearchResult missed(String message, String transferMessage) {
            return new RabbitAuditSearchResult(false, message, transferMessage);
        }
    }
}
