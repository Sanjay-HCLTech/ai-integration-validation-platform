package com.hcl.observability.report;

import com.hcl.observability.trace.TimelineEvent;
import com.hcl.observability.validation.BusinessValidationResult;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ReportService {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    public String buildReport(ReportContext context) {
        ReportContext safeContext = context == null ? new ReportContext() : context;
        StringBuilder report = new StringBuilder();
        appendSummary(report, safeContext);
        appendFlow(report, safeContext);
        appendExecution(report, safeContext);
        appendData(report, safeContext);
        appendTrace(report, safeContext);
        appendTimeline(report, safeContext);
        appendValidation(report, safeContext);
        appendIssues(report, safeContext);
        appendPerf(report, safeContext);
        return report.toString();
    }

    private void appendSummary(StringBuilder report, ReportContext context) {
        BusinessValidationResult validation = context.getValidation();
        report.append("[SUMMARY] BookingID=").append(value(context.getBookingId()))
                .append(" Status=").append(validation == null ? "UNKNOWN" : validation.getStatus())
                .append(" Total=").append(Math.max(0, context.getTotalTimeMs()) / 1000)
                .append("s")
                .append(System.lineSeparator());
    }

    private void appendFlow(StringBuilder report, ReportContext context) {
        report.append("[FLOW] Category=").append(value(context.getCategory()))
                .append(" Flow=").append(value(context.getFlow()))
                .append(" Scenario=").append(value(context.getScenario()))
                .append(System.lineSeparator());
    }

    private void appendExecution(StringBuilder report, ReportContext context) {
        report.append("[EXECUTION] Protocol=").append(value(context.getProtocol()))
                .append(" Mode=").append(value(context.getMode()))
                .append(" Ack=").append(Math.max(0, context.getAckMs()))
                .append("ms")
                .append(System.lineSeparator());
    }

    private void appendData(StringBuilder report, ReportContext context) {
        report.append("[DATA] Files=").append(Math.max(0, context.getFilesFound()))
                .append(" Processed=").append(Math.max(0, context.getFilesProcessed()))
                .append(" Merged=").append(Math.max(0, context.getFilesMerged()))
                .append(" Lines=").append(Math.max(0, context.getLines()))
                .append(System.lineSeparator());
    }

    private void appendTrace(StringBuilder report, ReportContext context) {
        report.append("[TRACE] JobIDs=").append(Math.max(0, context.getJobIds()))
                .append(" CorrIDs=").append(Math.max(0, context.getCorrIds()))
                .append(" Depth=").append(Math.max(0, context.getDepth()))
                .append(System.lineSeparator());
    }

    private void appendTimeline(StringBuilder report, ReportContext context) {
        report.append(System.lineSeparator()).append("[TIMELINE]").append(System.lineSeparator());
        List<TimelineEvent> timeline = context.getTimeline();
        if (timeline.isEmpty()) {
            report.append("No timeline events found").append(System.lineSeparator());
            return;
        }

        for (int index = 0; index < timeline.size(); index++) {
            TimelineEvent event = timeline.get(index);
            report.append(index + 1)
                    .append(". ")
                    .append(timestamp(event))
                    .append(" ")
                    .append(value(event.getSystem()))
                    .append(" ")
                    .append(value(event.getPhase()))
                    .append(" ")
                    .append(value(event.getOperation()))
                    .append(" ")
                    .append(value(event.getStatus()));
            if (event.getLatencyMsFromPrevious() >= 0) {
                report.append(" LatencyMs=").append(event.getLatencyMsFromPrevious());
            }
            report.append(System.lineSeparator());
        }
    }

    private void appendValidation(StringBuilder report, ReportContext context) {
        BusinessValidationResult validation = context.getValidation();
        report.append(System.lineSeparator()).append("[VALIDATION]").append(System.lineSeparator());
        if (validation == null) {
            report.append("Expected=NA Actual=NA Result=UNKNOWN").append(System.lineSeparator());
            return;
        }
        report.append("Expected=").append(value(validation.getExpected()))
                .append(" Actual=").append(value(validation.getActual()))
                .append(" Result=").append(value(validation.getResult()))
                .append(System.lineSeparator());
        report.append("FailurePoint=").append(value(validation.getFailurePoint()))
                .append(System.lineSeparator());
    }

    private void appendIssues(StringBuilder report, ReportContext context) {
        BusinessValidationResult validation = context.getValidation();
        report.append(System.lineSeparator()).append("[ISSUE]").append(System.lineSeparator());
        if (validation == null || validation.getGaps().isEmpty()) {
            report.append("None").append(System.lineSeparator());
        } else {
            for (String gap : validation.getGaps()) {
                report.append(gap).append(System.lineSeparator());
            }
        }

        report.append(System.lineSeparator()).append("[ACTION]").append(System.lineSeparator());
        if (validation == null || validation.getSupportActions().isEmpty()) {
            report.append("None").append(System.lineSeparator());
        } else {
            for (String action : validation.getSupportActions()) {
                report.append(action).append(System.lineSeparator());
            }
        }
    }

    private void appendPerf(StringBuilder report, ReportContext context) {
        report.append(System.lineSeparator()).append("[PERF]").append(System.lineSeparator());
        report.append("Exec=").append(Math.max(0, context.getExecutionTimeMs())).append("ms")
                .append(" Log=").append(Math.max(0, context.getLogTimeMs())).append("ms")
                .append(" Merge=").append(Math.max(0, context.getMergeTimeMs())).append("ms")
                .append(System.lineSeparator());
    }

    private String timestamp(TimelineEvent event) {
        if (event == null || event.getTimestamp() == null) {
            return "NA";
        }
        return TIMESTAMP_FORMATTER.format(event.getTimestamp());
    }

    private String value(Object value) {
        if (value == null) {
            return "NA";
        }
        String text = value.toString();
        return text.trim().isEmpty() ? "NA" : text.trim();
    }

    public static class ReportContext {
        private String testCaseName;
        private String bookingId;
        private String category;
        private String flow;
        private String scenario;
        private String protocol;
        private String mode;
        private long ackMs;
        private int filesFound;
        private int filesProcessed;
        private int filesMerged;
        private int lines;
        private int jobIds;
        private int corrIds;
        private int depth;
        private long executionTimeMs;
        private long logTimeMs;
        private long mergeTimeMs;
        private long totalTimeMs;
        private List<TimelineEvent> timeline = new ArrayList<>();
        private BusinessValidationResult validation;

        public String getTestCaseName() {
            return testCaseName;
        }

        public void setTestCaseName(String testCaseName) {
            this.testCaseName = testCaseName;
        }

        public String getBookingId() {
            return bookingId;
        }

        public void setBookingId(String bookingId) {
            this.bookingId = bookingId;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getFlow() {
            return flow;
        }

        public void setFlow(String flow) {
            this.flow = flow;
        }

        public String getScenario() {
            return scenario;
        }

        public void setScenario(String scenario) {
            this.scenario = scenario;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public long getAckMs() {
            return ackMs;
        }

        public void setAckMs(long ackMs) {
            this.ackMs = ackMs;
        }

        public int getFilesFound() {
            return filesFound;
        }

        public void setFilesFound(int filesFound) {
            this.filesFound = filesFound;
        }

        public int getFilesProcessed() {
            return filesProcessed;
        }

        public void setFilesProcessed(int filesProcessed) {
            this.filesProcessed = filesProcessed;
        }

        public int getFilesMerged() {
            return filesMerged;
        }

        public void setFilesMerged(int filesMerged) {
            this.filesMerged = filesMerged;
        }

        public int getLines() {
            return lines;
        }

        public void setLines(int lines) {
            this.lines = lines;
        }

        public int getJobIds() {
            return jobIds;
        }

        public void setJobIds(int jobIds) {
            this.jobIds = jobIds;
        }

        public int getCorrIds() {
            return corrIds;
        }

        public void setCorrIds(int corrIds) {
            this.corrIds = corrIds;
        }

        public int getDepth() {
            return depth;
        }

        public void setDepth(int depth) {
            this.depth = depth;
        }

        public long getExecutionTimeMs() {
            return executionTimeMs;
        }

        public void setExecutionTimeMs(long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
        }

        public long getLogTimeMs() {
            return logTimeMs;
        }

        public void setLogTimeMs(long logTimeMs) {
            this.logTimeMs = logTimeMs;
        }

        public long getMergeTimeMs() {
            return mergeTimeMs;
        }

        public void setMergeTimeMs(long mergeTimeMs) {
            this.mergeTimeMs = mergeTimeMs;
        }

        public long getTotalTimeMs() {
            return totalTimeMs;
        }

        public void setTotalTimeMs(long totalTimeMs) {
            this.totalTimeMs = totalTimeMs;
        }

        public List<TimelineEvent> getTimeline() {
            return timeline == null ? Collections.emptyList() : Collections.unmodifiableList(timeline);
        }

        public void setTimeline(List<TimelineEvent> timeline) {
            this.timeline = timeline == null ? new ArrayList<>() : new ArrayList<>(timeline);
        }

        public BusinessValidationResult getValidation() {
            return validation;
        }

        public void setValidation(BusinessValidationResult validation) {
            this.validation = validation;
        }
    }
}
