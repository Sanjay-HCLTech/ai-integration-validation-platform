package com.hcl.observability.trace;

import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class UnifiedTraceReportService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceValidationService validationService;

    public UnifiedTraceReportService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceValidationService validationService) {
        this.timelineBuilder = timelineBuilder;
        this.validationService = validationService;
    }

    public String buildReport(UnifiedTraceContext context) {
        UnifiedTraceContext safeContext = context == null ? new UnifiedTraceContext() : context;
        List<NormalizedTraceEvent> timeline = timelineBuilder.build(safeContext.getEvents());
        UnifiedTraceValidationResult validation = validationService.validate(timeline);
        List<String> triggeredProtocols = new ArrayList<>();
        List<String> skippedProtocols = new ArrayList<>();

        StringBuilder report = new StringBuilder();
        appendApi(report, safeContext);
        appendFiles(report, safeContext);
        appendRetries(report, safeContext);
        appendProtocolSection(report, safeContext, "JMS", TraceSystem.JMS, timeline,
                triggeredProtocols, skippedProtocols);
        appendProtocolSection(report, safeContext, "REST", TraceSystem.REST, timeline,
                triggeredProtocols, skippedProtocols);
        appendProtocolSection(report, safeContext, "SOAP", TraceSystem.SOAP, timeline,
                triggeredProtocols, skippedProtocols);
        appendProtocolSection(report, safeContext, "RabbitMQ", TraceSystem.RABBITMQ, timeline,
                triggeredProtocols, skippedProtocols);
        appendTimeline(report, timeline);
        appendValidation(report, safeContext, validation);
        appendSummary(report, safeContext, validation, triggeredProtocols, skippedProtocols);
        return report.toString();
    }

    private void appendApi(StringBuilder report, UnifiedTraceContext context) {
        report.append("[API]").append(System.lineSeparator());
        report.append("Endpoint=").append(value(context.getApiEndpoint())).append(System.lineSeparator());
        report.append("Status=").append(value(context.getApiStatus())).append(System.lineSeparator());
    }

    private void appendFiles(StringBuilder report, UnifiedTraceContext context) {
        report.append(System.lineSeparator());
        report.append("-------------------- FILE HANDLING ---------------------").append(System.lineSeparator());
        report.append("[FILES]").append(System.lineSeparator());
        if (context.getFileLines().isEmpty()) {
            report.append("Name=NO_REMOTE_FILES ExistsLocal=N Action=SKIP Reason=NO_FILE_ACTIVITY")
                    .append(System.lineSeparator());
            return;
        }
        for (String line : context.getFileLines()) {
            report.append(line).append(System.lineSeparator());
        }
    }

    private void appendRetries(StringBuilder report, UnifiedTraceContext context) {
        report.append(System.lineSeparator());
        report.append("-------------------- RETRY LOGIC -----------------------").append(System.lineSeparator());
        report.append("[RETRY]").append(System.lineSeparator());
        if (context.getRetryLines().isEmpty()) {
            report.append("Stage=Trace      Attempt=0/0 Result=SKIPPED Action=STOP")
                    .append(System.lineSeparator());
            return;
        }
        for (String line : context.getRetryLines()) {
            report.append(line).append(System.lineSeparator());
        }
    }

    private void appendProtocolSection(
            StringBuilder report,
            UnifiedTraceContext context,
            String label,
            TraceSystem system,
            List<NormalizedTraceEvent> timeline,
            List<String> triggeredProtocols,
            List<String> skippedProtocols) {
        NormalizedTraceEvent first = firstEvent(system, timeline);
        List<String> protocolLines = context.getProtocolLines(label);
        if (first == null && protocolLines.isEmpty()) {
            skippedProtocols.add(label);
            return;
        }

        triggeredProtocols.add(label);
        report.append(System.lineSeparator());
        report.append("-------------------- ").append(label).append(" FLOW --------------------------")
                .append(System.lineSeparator());
        report.append("[").append(label).append("]").append(System.lineSeparator());
        if (!protocolLines.isEmpty()) {
            for (String line : protocolLines) {
                report.append(line).append(System.lineSeparator());
            }
            return;
        }
        report.append("Status=").append(first == null ? "NOT_EXECUTED" : value(first.getStatus()))
                .append(System.lineSeparator());
    }

    private void appendTimeline(StringBuilder report, List<NormalizedTraceEvent> timeline) {
        report.append(System.lineSeparator());
        report.append("-------------------- TIMELINE --------------------------").append(System.lineSeparator());
        report.append("[TIMELINE]").append(System.lineSeparator());

        if (timeline.isEmpty()) {
            report.append("No trace events found").append(System.lineSeparator());
            return;
        }

        for (int i = 0; i < timeline.size(); i++) {
            report.append(i + 1).append(". ").append(timelineLine(timeline.get(i))).append(System.lineSeparator());
        }
    }

    private void appendValidation(
            StringBuilder report,
            UnifiedTraceContext context,
            UnifiedTraceValidationResult validation) {
        report.append(System.lineSeparator());
        report.append("-------------------- VALIDATION ------------------------").append(System.lineSeparator());
        report.append("[VALIDATION]").append(System.lineSeparator());
        report.append("BookingID=").append(value(context.getBookingId()))
                .append(" CorrID=").append(value(context.getCorrId()))
                .append(" JobID=").append(value(context.getJobId()))
                .append(System.lineSeparator());

        for (Map.Entry<TraceSystem, TraceStatus> entry : validation.getSystemStatuses().entrySet()) {
            report.append(entry.getKey()).append("=").append(entry.getValue()).append(System.lineSeparator());
        }
        for (String line : context.getValidationLines()) {
            report.append(line).append(System.lineSeparator());
        }

        report.append("EndToEnd=").append(validation.isEndToEndSuccess() ? "SUCCESS" : "FAILED")
                .append(System.lineSeparator());
    }

    private void appendSummary(
            StringBuilder report,
            UnifiedTraceContext context,
            UnifiedTraceValidationResult validation,
            List<String> triggeredProtocols,
            List<String> skippedProtocols) {
        report.append(System.lineSeparator());
        report.append("Orchestrator: Execution completed").append(System.lineSeparator());
        report.append(System.lineSeparator());
        report.append("-------------------- SUMMARY ---------------------------").append(System.lineSeparator());
        report.append("[SUMMARY]").append(System.lineSeparator());
        report.append("TotalTimeMs=").append(Math.max(0, context.getTotalTimeMs()))
                .append(" Status=").append(validation.isEndToEndSuccess() ? "SUCCESS" : "FAILED")
                .append(System.lineSeparator());
        for (String line : context.getSummaryLines()) {
            report.append(line).append(System.lineSeparator());
        }
        report.append("TriggeredService=").append(listValue(triggeredProtocols)).append(System.lineSeparator());
        report.append("SkippedServices=").append(listValue(skippedProtocols)).append(System.lineSeparator());
    }

    private NormalizedTraceEvent firstEvent(TraceSystem system, List<NormalizedTraceEvent> timeline) {
        for (NormalizedTraceEvent event : timeline) {
            if (system == event.getSystem()) {
                return event;
            }
        }
        return null;
    }

    private String timelineLine(NormalizedTraceEvent event) {
        StringBuilder line = new StringBuilder();
        line.append("Svc=").append(value(event.getSystem()));
        line.append(" Phase=").append(value(event.getPhase()));
        line.append(" Op=").append(value(event.getOperation()));
        if (hasText(event.getFromEndpoint())) {
            line.append(" From=").append(event.getFromEndpoint());
        }
        if (hasText(event.getToEndpoint())) {
            line.append(" To=").append(event.getToEndpoint());
        }
        line.append(" TS=").append(event.getTimestamp() == null
                ? "NA"
                : TIME_FORMATTER.format(event.getTimestamp().toInstant()));
        line.append(" Result=").append(value(event.getStatus()));
        return line.toString();
    }

    private String value(Object value) {
        return value == null || !hasText(value.toString()) ? "NA" : value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String listValue(List<String> values) {
        return values == null || values.isEmpty() ? "NA" : String.join(",", values);
    }
}
