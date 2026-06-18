package com.hcl.execution.jms;

import com.hcl.observability.log.LogAnalyzerService;
import com.hcl.observability.log.LogSearchResult;
import com.hcl.observability.trace.NormalizedTraceEvent;
import com.hcl.observability.trace.TracePhase;
import com.hcl.observability.trace.TraceProtocol;
import com.hcl.observability.trace.TraceStatus;
import com.hcl.observability.trace.TraceSystem;
import com.hcl.observability.trace.UnifiedTraceContext;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import com.hcl.observability.trace.UnifiedTraceReportService;
import com.hcl.observability.trace.UnifiedTimelineBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class JmsProcessingService {

    private static final AtomicLong JOB_SEQUENCE = new AtomicLong(100000);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final List<String> BOOKING_FLOW_LOG_ORDER = Arrays.asList(
            "BookingDetails_v2_GetBookingIDs.log",
            "ManageBooking_v2_UpdateCustomerId_v2.log",
            "ManageBooking_v2_UpdateCustomerId_v2.log.1",
            "MongoDBBookingSubscriber_v1.log.1",
            "PostBookFlowSubscriber_v2_SubscribeBookingDetails_v2.log.3",
            "PostBookFlowSubscriber_v2_UpdateCustomerID.log.1");

    private final LogAnalyzerService logAnalyzerService;
    private final JmsValidationService validationService;
    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final UnifiedTraceReportService traceReportService;
    private final long processingDelayMs;
    private final boolean logAnalyzerEnabled;
    private final boolean unifiedTraceReportEnabled;
    private final String localLogDir;

    public JmsProcessingService(
            LogAnalyzerService logAnalyzerService,
            JmsValidationService validationService,
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder,
            UnifiedTraceReportService traceReportService,
            @Value("${jms.simulation.processing.delay.ms:2000}") long processingDelayMs,
            @Value("${jms.simulation.log-analyzer.enabled:true}") boolean logAnalyzerEnabled,
            @Value("${unified.trace.report.enabled:false}") boolean unifiedTraceReportEnabled,
            @Value("${local.log.dir:C:/logs}") String localLogDir) {
        this.logAnalyzerService = logAnalyzerService;
        this.validationService = validationService;
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
        this.traceReportService = traceReportService;
        this.processingDelayMs = Math.max(0, processingDelayMs);
        this.logAnalyzerEnabled = logAnalyzerEnabled;
        this.unifiedTraceReportEnabled = unifiedTraceReportEnabled;
        this.localLogDir = localLogDir;
    }

    public JmsProcessingResult processDirectly(JmsMessage message) {
        return process(message, "SYNC");
    }

    public JmsProcessingResult processAsync(JmsMessage message) {
        return process(message, "ASYNC");
    }

    private JmsProcessingResult process(JmsMessage message, String mode) {
        long processingStartMs = System.currentTimeMillis();
        ensureCorrelation(message);
        UnifiedTraceContext parentContext = traceContextHolder.current();
        boolean ownsTraceContext = parentContext == null;
        if (ownsTraceContext) {
            traceContextHolder.begin("TC_JMS_SIMULATION", message.getBookingId());
        }
        progress("JMS processing started");
        simulateDelay();

        String validationError = validationService.validate(message);
        if (validationError != null) {
            JmsProcessingResult result = JmsProcessingResult.fromMessage(message, mode, "FAIL", validationError);
            List<NormalizedTraceEvent> events = buildJmsTimeline(message, mode, processingStartMs,
                    System.currentTimeMillis(), result);
            result.setTraceContext(buildTraceContext(message, processingStartMs, System.currentTimeMillis(), events));
            mergeIntoParentContext(parentContext, result.traceContext());
            printProcessingOutput(message, mode, result, events);
            clearOwnedTraceContext(ownsTraceContext);
            return result;
        }

        JmsProcessingResult result = JmsProcessingResult.fromMessage(
                message, mode, "SUCCESS", "JMS message processed and validated");

        long ackTimeMs = System.currentTimeMillis();
        long jmsAckMs = Math.max(0, ackTimeMs - processingStartMs);
        progress("JMS acknowledged in " + jmsAckMs + " ms");
        List<NormalizedTraceEvent> events = buildJmsTimeline(message, mode, processingStartMs, ackTimeMs, result);
        long logAnalyzerStartMs = System.currentTimeMillis();
        TraceStatus logAnalyzerStatus = TraceStatus.SUCCESS;
        if (logAnalyzerEnabled) {
            logAnalyzerStatus = enrichWithLogTrace(message, result);
            events.add(observabilityEvent(message, logAnalyzerStartMs, System.currentTimeMillis(), logAnalyzerStatus));
        } else {
            printLogAnalyzerSkipped();
        }

        long processingEndMs = System.currentTimeMillis();
        long logAnalyzerMs = Math.max(0, processingEndMs - logAnalyzerStartMs);
        events = timelineBuilder.build(events);
        result.setTraceContext(buildTraceContext(message, processingStartMs, processingEndMs, events,
                jmsAckMs, logAnalyzerMs));
        mergeIntoParentContext(parentContext, result.traceContext());
        printProcessingOutput(message, mode, result, events);
        clearOwnedTraceContext(ownsTraceContext);
        return result;
    }

    private void clearOwnedTraceContext(boolean ownsTraceContext) {
        if (ownsTraceContext) {
            traceContextHolder.clear();
        }
    }

    private void mergeIntoParentContext(UnifiedTraceContext parentContext, UnifiedTraceContext sourceContext) {
        if (parentContext == null || sourceContext == null || parentContext == sourceContext) {
            return;
        }

        if (isBlank(parentContext.getCorrId())) {
            parentContext.setCorrId(sourceContext.getCorrId());
        }
        if (isBlank(parentContext.getJobId())) {
            parentContext.setJobId(sourceContext.getJobId());
        }
        if (isBlank(parentContext.getApiEndpoint())) {
            parentContext.setApiEndpoint(sourceContext.getApiEndpoint());
        }
        if (isBlank(parentContext.getApiStatus())) {
            parentContext.setApiStatus(sourceContext.getApiStatus());
        }
        if (parentContext.getTotalTimeMs() <= 0) {
            parentContext.setTotalTimeMs(sourceContext.getTotalTimeMs());
        }

        parentContext.addEvents(sourceContext.getEvents());
        for (String fileLine : sourceContext.getFileLines()) {
            parentContext.addFileLine(fileLine);
        }
        for (String retryLine : sourceContext.getRetryLines()) {
            parentContext.addRetryLine(retryLine);
        }
        for (String protocol : sourceContext.getProtocolNames()) {
            for (String protocolLine : sourceContext.getProtocolLines(protocol)) {
                parentContext.addProtocolLine(protocol, protocolLine);
            }
        }
        for (String validationLine : sourceContext.getValidationLines()) {
            parentContext.addValidationLine(validationLine);
        }
        for (String summaryLine : sourceContext.getSummaryLines()) {
            parentContext.addSummaryLine(summaryLine);
        }
    }

    private void printProcessingOutput(
            JmsMessage message,
            String mode,
            JmsProcessingResult result,
            List<NormalizedTraceEvent> events) {
        if (unifiedTraceReportEnabled) {
            printUnifiedTraceReport(result.traceContext());
            return;
        }

        printValidation(message, mode, result);
        printJmsFlow(message, result);
        printTimeline(events);
    }

    private void printUnifiedTraceReport(UnifiedTraceContext context) {
        if (context == null) {
            return;
        }
        System.out.println();
        System.out.println("==================== UNIFIED TRACE REPORT ====================");
        System.out.print(traceReportService.buildReport(context));
        System.out.println("==================== UNIFIED TRACE END =======================");
    }

    private void ensureCorrelation(JmsMessage message) {
        if (isBlank(message.getCorrId())) {
            message.setCorrId(UUID.randomUUID().toString());
        }
        if (isBlank(message.getJobId())) {
            message.setJobId("JOB-" + JOB_SEQUENCE.incrementAndGet());
        }
        if (message.getTimestamp() <= 0) {
            message.setTimestamp(System.currentTimeMillis());
        }
    }

    private TraceStatus enrichWithLogTrace(JmsMessage message, JmsProcessingResult result) {
        try {
            LogSearchResult logSearchResult = logAnalyzerService.analyze(message.getBookingId());
            result.setLogLinesFound(logSearchResult.getLines().size());
            result.setPartialTrace(logSearchResult.isPartialCoverage());
            result.setMessage(result.getMessage()
                    + " | LogAnalyzer lines=" + logSearchResult.getLines().size()
                    + " partialTrace=" + logSearchResult.isPartialCoverage());
            addFallbackFileLinesIfMissing(message.getBookingId(), logSearchResult);
            return TraceStatus.SUCCESS;
        } catch (Exception e) {
            result.setPartialTrace(true);
            result.setMessage(result.getMessage() + " | LogAnalyzer unavailable: " + e.getMessage());
            return TraceStatus.ERROR;
        }
    }

    private void addFallbackFileLinesIfMissing(String bookingId, LogSearchResult logSearchResult) {
        UnifiedTraceContext currentContext = traceContextHolder.current();
        if (currentContext == null || !currentContext.getFileLines().isEmpty() || logSearchResult == null) {
            return;
        }

        for (String remoteFile : logSearchResult.getRemoteFiles()) {
            String fileName = fileName(remoteFile);
            if (isBlank(fileName)) {
                continue;
            }

            File localFile = new File(localLogDir + "/" + bookingId + "/" + fileName);
            boolean existsLocal = localFile.exists() && localFile.isFile() && localFile.length() > 0;
            currentContext.addFileLine("File=" + pad(fileName, 48)
                    + " Exists=" + yesNo(existsLocal)
                    + " Action=" + (existsLocal ? "SKIP" : "FILTER_FETCH")
                    + " Reason=" + (existsLocal ? "LOCAL_PRESENT" : "REMOTE_HITS"));
        }
    }

    private String fileName(String remoteFileRecord) {
        if (remoteFileRecord == null || remoteFileRecord.trim().isEmpty()) {
            return "";
        }

        String remotePath = remoteFileRecord.trim().split("\\s+", 2)[0];
        return remotePath.contains("/") ? remotePath.substring(remotePath.lastIndexOf("/") + 1) : remotePath;
    }

    private void printLogAnalyzerSkipped() {
        System.out.println();
        System.out.println("-------------------- FILE HANDLING ---------------------");
        System.out.println("[FILES]");
        System.out.println("File=LogAnalyzer Exists=N Action=SKIP Reason=NOT_INVOKED");
        System.out.println();
        System.out.println("-------------------- RETRY LOGIC -----------------------");
        System.out.println("[RETRY]");
        System.out.println("Stage=BookingID Attempt=0/0 Result=SKIPPED Action=STOP");
    }

    private void printValidation(JmsMessage message, String mode, JmsProcessingResult result) {
        System.out.println();
        System.out.println("-------------------- VALIDATION ------------------------");
        System.out.println("[VALIDATION]");
        System.out.println("BookingID=" + value(message.getBookingId())
                + " CorrID=" + value(message.getCorrId())
                + " JobID=" + value(message.getJobId()));
        System.out.println("Atcore=N QueuePublish=" + yesNo(isQueueVisible(mode))
                + " QueueConsume=" + yesNo(isQueueVisible(mode)));
        System.out.println("QueueFlow=" + ("SUCCESS".equalsIgnoreCase(result.getStatus()) ? "SUCCESS" : "FAIL"));
        System.out.println("EndToEnd=" + ("SUCCESS".equalsIgnoreCase(result.getStatus()) ? "SUCCESS" : "FAIL"));
    }

    private void printJmsFlow(JmsMessage message, JmsProcessingResult result) {
        System.out.println();
        System.out.println("-------------------- JMS FLOW --------------------------");
        System.out.println("[JMS]");
        System.out.println("SenderQueue=" + value(message.getSenderQueue()));
        System.out.println("ReceiverQueue=" + value(message.getReceiverQueue()));
        System.out.println("MessageType=" + value(message.getMessageType()));
        System.out.println("CorrID=" + value(message.getCorrId()));
        System.out.println("JobID=" + value(message.getJobId()));
        System.out.println("FlowStatus=" + ("SUCCESS".equalsIgnoreCase(result.getStatus()) ? "DELIVERED" : "FAILED"));
    }

    private void printTimeline(List<NormalizedTraceEvent> events) {
        System.out.println();
        System.out.println("-------------------- TIMELINE --------------------------");
        System.out.println("[TIMELINE]");
        for (int i = 0; i < events.size(); i++) {
            System.out.println((i + 1) + ". " + timelineLine(events.get(i)));
        }
    }

    private List<NormalizedTraceEvent> buildJmsTimeline(
            JmsMessage message,
            String mode,
            long startMs,
            long endMs,
            JmsProcessingResult result) {
        return timelineBuilder.build(jmsTimelineEvents(message, startMs, endMs, result));
    }

    private UnifiedTraceContext buildTraceContext(
            JmsMessage message,
            long startMs,
            long endMs,
            List<NormalizedTraceEvent> events,
            long jmsAckMs,
            long logAnalyzerMs) {
        UnifiedTraceContext context = new UnifiedTraceContext();
        context.setTestCaseId("TC_JMS_SIMULATION");
        context.setBookingId(message.getBookingId());
        context.setCorrId(message.getCorrId());
        context.setJobId(message.getJobId());
        context.setApiEndpoint("/execute/jms");
        context.setApiStatus("200");
        context.setTotalTimeMs(Math.max(0, endMs - startMs));
        context.addEvents(events);
        context.addProtocolLine("JMS", "SenderQueue=" + value(message.getSenderQueue()));
        context.addProtocolLine("JMS", "ReceiverQueue=" + value(message.getReceiverQueue()));
        context.addProtocolLine("JMS", "MessageType=" + value(message.getMessageType()));
        context.addProtocolLine("JMS", "CorrID=" + value(message.getCorrId()));
        context.addProtocolLine("JMS", "JobID=" + value(message.getJobId()));
        context.addProtocolLine("JMS", "FlowStatus="
                + ("SUCCESS".equalsIgnoreCase(statusFromEvents(events)) ? "DELIVERED" : "FAILED"));
        context.addValidationLine("QueuePublish=Y");
        context.addValidationLine("QueueConsume=Y");
        context.addValidationLine("QueueFlow="
                + ("SUCCESS".equalsIgnoreCase(statusFromEvents(events)) ? "SUCCESS" : "FAILED"));
        context.addSummaryLine("JmsAckTimeMs=" + jmsAckMs);
        context.addSummaryLine("LogAnalyzerTimeMs=" + logAnalyzerMs);
        context.addSummaryLine("LogAnalyzerEnabled=" + yesNo(logAnalyzerEnabled));
        UnifiedTraceContext holderContext = traceContextHolder.current();
        if (holderContext != null) {
            context.addEvents(holderContext.getEvents());
            for (String fileLine : holderContext.getFileLines()) {
                context.addFileLine(fileLine);
            }
            for (String retryLine : holderContext.getRetryLines()) {
                context.addRetryLine(retryLine);
            }
        }
        mergeLocalBookingLogLines(context, message.getBookingId());
        return context;
    }

    private void mergeLocalBookingLogLines(UnifiedTraceContext context, String bookingId) {
        if (context == null || isBlank(bookingId)) {
            return;
        }

        File bookingDir = new File(localLogDir + "/" + bookingId);
        File[] localFiles = bookingDir.listFiles(file -> file.isFile()
                && file.length() > 0
                && isScopedEvidenceFile(file.getName(), bookingId));
        if (localFiles == null || localFiles.length == 0) {
            return;
        }

        Set<String> existingNames = existingFileNames(context);
        Set<String> orderedLocalNames = new LinkedHashSet<>();
        Set<String> actualLocalNames = new LinkedHashSet<>();

        Arrays.sort(localFiles, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        for (File localFile : localFiles) {
            actualLocalNames.add(localFile.getName());
        }

        for (String expectedName : BOOKING_FLOW_LOG_ORDER) {
            if (actualLocalNames.contains(expectedName)) {
                orderedLocalNames.add(expectedName);
            }
        }
        orderedLocalNames.addAll(actualLocalNames);

        for (String localName : orderedLocalNames) {
            if (existingNames.add(localName)) {
                context.addFileLine("File=" + pad(localName, 48)
                        + " Exists=Y Action=SKIP Reason=LOCAL_PRESENT");
            }
        }

        if (context.getRetryLines().isEmpty()) {
            context.addRetryLine("Stage=BookingID  Attempt=1/3 Result=FOUND Action=STOP");
        }
    }

    private boolean isScopedEvidenceFile(String fileName, String bookingId) {
        if (isBlank(fileName)) {
            return false;
        }
        if (fileName.endsWith(".part")
                || fileName.endsWith(".partial")
                || fileName.endsWith(".partial.previous")
                || fileName.startsWith("processed_files_")) {
            return false;
        }
        return !fileName.equals(safeScope(bookingId) + ".log");
    }

    private Set<String> existingFileNames(UnifiedTraceContext context) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : context.getFileLines()) {
            String name = extractFileName(line);
            if (!isBlank(name) && !name.endsWith(".part")) {
                names.add(name);
            }
        }
        return names;
    }

    private String extractFileName(String fileLine) {
        if (isBlank(fileLine)) {
            return "";
        }

        String marker = fileLine.contains("File=") ? "File=" : "Name=";
        int start = fileLine.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = fileLine.indexOf(" Exists", start);
        if (end < 0) {
            end = fileLine.length();
        }
        return fileLine.substring(start, end).trim();
    }

    private UnifiedTraceContext buildTraceContext(
            JmsMessage message,
            long startMs,
            long endMs,
            List<NormalizedTraceEvent> events) {
        return buildTraceContext(message, startMs, endMs, events, Math.max(0, endMs - startMs), 0);
    }

    private List<NormalizedTraceEvent> jmsTimelineEvents(
            JmsMessage message,
            long startMs,
            long endMs,
            JmsProcessingResult result) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(event(
                message,
                TraceSystem.API,
                TraceProtocol.REST,
                TracePhase.REQUEST,
                "JmsSubmit",
                null,
                null,
                message.getTimestamp(),
                TraceStatus.SUCCESS));
        events.add(event(
                message,
                TraceSystem.JMS,
                TraceProtocol.JMS,
                TracePhase.SEND,
                value(message.getMessageType()),
                message.getSenderQueue(),
                message.getReceiverQueue(),
                startMs,
                TraceStatus.SUCCESS));
        events.add(event(
                message,
                TraceSystem.JMS,
                TraceProtocol.JMS,
                TracePhase.ACK,
                "Acknowledgement",
                message.getReceiverQueue(),
                message.getSenderQueue(),
                endMs,
                "SUCCESS".equalsIgnoreCase(result.getStatus()) ? TraceStatus.DELIVERED : TraceStatus.FAILED));
        return events;
    }

    private NormalizedTraceEvent observabilityEvent(
            JmsMessage message,
            long startMs,
            long endMs,
            TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                message.getBookingId(),
                message.getCorrId(),
                message.getJobId(),
                TraceSystem.OBSERVABILITY,
                TraceProtocol.SFTP_LOG,
                TracePhase.PROCESS,
                "LogAnalyzer",
                timestampValue(endMs),
                status,
                null);
        event.setFromEndpoint("SFTP");
        event.setToEndpoint("LocalLogAnalyzer");
        return event;
    }

    private NormalizedTraceEvent event(
            JmsMessage message,
            TraceSystem system,
            TraceProtocol protocol,
            TracePhase phase,
            String operation,
            String from,
            String to,
            long epochMs,
            TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                message.getBookingId(),
                message.getCorrId(),
                message.getJobId(),
                system,
                protocol,
                phase,
                operation,
                timestampValue(epochMs),
                status,
                null);
        event.setFromEndpoint(from);
        event.setToEndpoint(to);
        return event;
    }

    private String timelineLine(NormalizedTraceEvent event) {
        StringBuilder line = new StringBuilder();
        line.append("Svc=").append(pad(valueOf(event.getSystem()), 6));
        line.append(" Phase=").append(pad(valueOf(event.getPhase()), 6));
        line.append(" Op=").append(value(event.getOperation()));
        if (!isBlank(event.getFromEndpoint())) {
            line.append(" From=").append(value(event.getFromEndpoint()));
        }
        if (!isBlank(event.getToEndpoint())) {
            line.append(" To=").append(value(event.getToEndpoint()));
        }
        line.append(" TS=").append(timeValue(event.getTimestamp()));
        line.append(" Result=").append(valueOf(event.getStatus()));
        return line.toString();
    }

    private String statusFromEvents(List<NormalizedTraceEvent> events) {
        for (NormalizedTraceEvent event : events) {
            if (event.getSystem() == TraceSystem.JMS && event.getStatus() == TraceStatus.FAILED) {
                return "FAILED";
            }
        }
        return "SUCCESS";
    }

    private boolean isQueueVisible(String mode) {
        return "ASYNC".equalsIgnoreCase(mode) || "SYNC".equalsIgnoreCase(mode);
    }

    private String value(String value) {
        return isBlank(value) ? "NA" : value;
    }

    private String valueOf(Object value) {
        return value == null ? "NA" : value.toString();
    }

    private String yesNo(boolean value) {
        return value ? "Y" : "N";
    }

    private String timeValue(OffsetDateTime timestamp) {
        return timestamp == null ? "NA" : TIME_FORMATTER.format(timestamp.toInstant());
    }

    private OffsetDateTime timestampValue(long epochMs) {
        return epochMs <= 0 ? null : OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
    }

    private String pad(String value, int width) {
        String text = value(value);
        return text.length() >= width ? text : String.format("%-" + width + "s", text);
    }

    private void simulateDelay() {
        if (processingDelayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(processingDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void progress(String message) {
        if (unifiedTraceReportEnabled) {
            System.out.println("[PROGRESS] " + message);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeScope(String value) {
        return value == null ? "NA" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

}
