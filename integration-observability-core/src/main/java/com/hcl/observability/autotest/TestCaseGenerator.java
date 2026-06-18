package com.hcl.observability.autotest;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TestCaseGenerator {
    private static final DateTimeFormatter BUSINESS_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy MMM dd HH:mm:ss:SSS", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private final LogParser logParser;
    private final FlowBuilder flowBuilder;
    private final String localDir;
    private final String remoteLogDir;

    public TestCaseGenerator(
            LogParser logParser,
            FlowBuilder flowBuilder,
            @Value("${local.log.dir:C:/logs}") String localDir,
            @Value("${sftp.payload.log.dir:/tui/tibco/tra/domain/TIB_DOM_ST5/application/logs/Bookings/Payload}") String remoteLogDir) {
        this.logParser = logParser;
        this.flowBuilder = flowBuilder;
        this.localDir = localDir;
        this.remoteLogDir = remoteLogDir;
    }

    public String generateJson(String bookingId, Iterable<String> rawLines) {
        GeneratedTestCase testCase = generate(bookingId, rawLines);
        return toJson(testCase);
    }

    public GeneratedTestCase generate(String bookingId, Iterable<String> rawLines) {
        List<FlowEvent> events = logParser.parse(bookingId, rawLines);
        return generateFromEvents(bookingId, events);
    }

    public String generateJsonFromAllEvents(String bookingId, Iterable<String> rawLines) {
        GeneratedTestCase testCase = generateFromAllEvents(bookingId, rawLines);
        return toJson(testCase);
    }

    public GeneratedTestCase generateFromAllEvents(String bookingId, Iterable<String> rawLines) {
        List<FlowEvent> events = logParser.parseAll(rawLines);
        return generateFromEvents(bookingId, events);
    }

    private GeneratedTestCase generateFromEvents(String bookingId, List<FlowEvent> events) {
        FlowBuilder.FlowPattern pattern = flowBuilder.build(events);
        Correlation correlation = extractCorrelation(pattern.getTimeline());
        String status = classify(pattern, correlation);

        GeneratedTestCase testCase = new GeneratedTestCase();
        testCase.testCaseName = "TC_BOOKING_FLOW";
        testCase.bookingId = value(bookingId);
        testCase.corrId = correlation.corrId;
        testCase.jobId = correlation.jobId;
        testCase.corrIds = correlation.corrIds;
        testCase.jobIds = correlation.jobIds;
        testCase.expectedFlow = pattern.getSystems();
        testCase.expectedSteps = pattern.getSteps();
        testCase.timeline = pattern.getTimeline();
        testCase.corrIdPresent = hasText(correlation.corrId);
        testCase.jobIdPresent = hasText(correlation.jobId);
        testCase.timelineEventsMin = pattern.getTimeline().size();
        testCase.endToEndStatus = status;
        testCase.failurePoint = failurePoint(pattern, correlation, status);
        return testCase;
    }

    private String classify(FlowBuilder.FlowPattern pattern, Correlation correlation) {
        if (!hasText(correlation.corrId) || !hasText(correlation.jobId)
                || pattern.getTimeline().size() < 2) {
            return "FAIL";
        }

        String lastStep = pattern.getSteps().isEmpty()
                ? ""
                : pattern.getSteps().get(pattern.getSteps().size() - 1);
        if (lastEventWithPhase(pattern.getTimeline(), "CONFIRM") != null) {
            return "SUCCESS";
        }
        if (lastEventWithPhase(pattern.getTimeline(), "PUBLISH") != null) {
            return "PARTIAL";
        }
        if (lastStep.endsWith("_REPLY")) {
            return "SUCCESS";
        }
        return "PARTIAL";
    }

    private Correlation extractCorrelation(List<FlowEvent> timeline) {
        Correlation correlation = new Correlation();
        Set<String> corrIds = new LinkedHashSet<>();
        Set<String> jobIds = new LinkedHashSet<>();
        for (FlowEvent event : timeline) {
            if (hasText(event.getCorrId())) {
                corrIds.add(event.getCorrId());
            }
            if (hasText(event.getJobId())) {
                jobIds.add(event.getJobId());
            }
            if (!hasText(correlation.corrId) && hasText(event.getCorrId())) {
                correlation.corrId = event.getCorrId();
            }
            if (!hasText(correlation.jobId) && hasText(event.getJobId())) {
                correlation.jobId = event.getJobId();
            }
        }
        correlation.corrIds = new ArrayList<>(corrIds);
        correlation.jobIds = new ArrayList<>(jobIds);
        return correlation;
    }

    private String failurePoint(FlowBuilder.FlowPattern pattern, Correlation correlation, String status) {
        if ("SUCCESS".equals(status)) {
            return "NONE";
        }
        if (!hasText(correlation.corrId)) {
            return "CORRELATION_ID_EXTRACTION";
        }
        if (!hasText(correlation.jobId)) {
            return "JOB_ID_EXTRACTION";
        }
        FlowEvent publishEvent = lastEventWithPhase(pattern.getTimeline(), "PUBLISH");
        if (publishEvent != null && hasText(publishEvent.getSystem())) {
            return value(publishEvent.getSystem());
        }
        if (!pattern.getTimeline().isEmpty()) {
            return value(pattern.getTimeline().get(pattern.getTimeline().size() - 1).getSystem());
        }
        return "LOG_ANALYZER";
    }

    private long totalDurationMs(List<FlowEvent> timeline) {
        if (timeline == null || timeline.size() < 2) {
            return 0;
        }
        long first = Math.max(0, timeline.get(0).getTimestamp());
        long last = Math.max(0, timeline.get(timeline.size() - 1).getTimestamp());
        if (first == 0 || last == 0 || last < first) {
            return 0;
        }
        return last - first;
    }

    private String toJson(GeneratedTestCase testCase) {
        StringBuilder json = new StringBuilder();
        json.append("{").append(newLine());
        appendField(json, 1, "testCaseName", testCase.testCaseName, true);
        appendResult(json, testCase);
        appendStrictSummary(json, testCase);
        appendStrictTimeline(json, testCase);
        appendStrictValidation(json, testCase);
        appendField(json, 1, "gap", gapLine(testCase), true);
        appendAction(json, testCase);
        json.append("}");
        return json.toString();
    }

    private void appendResult(StringBuilder json, GeneratedTestCase testCase) {
        indent(json, 1).append("\"result\": {").append(newLine());
        appendField(json, 2, "status", passFail(testCase), true);
        appendField(json, 2, "reason", resultReason(testCase), true);
        appendField(json, 2, "failurePoint", testCase.failurePoint, true);
        appendField(json, 2, "color", "PASS".equals(passFail(testCase)) ? "GREEN" : "RED", false);
        indent(json, 1).append("},").append(newLine());
    }

    private void appendStrictSummary(StringBuilder json, GeneratedTestCase testCase) {
        indent(json, 1).append("\"summary\": {").append(newLine());
        appendField(json, 2, "bookingId", testCase.bookingId, true);
        appendField(json, 2, "flow", flowLine(testCase), true);
        appendNumberField(json, 2, "systems", testCase.expectedFlow.size(), true);
        appendNumberField(json, 2, "events", testCase.timeline.size(), true);
        appendLongField(json, 2, "durationSec", totalDurationMs(testCase.timeline) / 1000, true);
        appendField(json, 2, "lastEventTime", businessTimestamp(lastEvent(testCase.timeline)), true);
        appendField(json, 2, "expected", summaryExpected(testCase), true);
        appendField(json, 2, "actual", summaryActual(testCase), true);
        appendField(json, 2, "status", testCase.endToEndStatus, false);
        indent(json, 1).append("},").append(newLine());
    }

    private void appendStrictTimeline(StringBuilder json, GeneratedTestCase testCase) {
        List<FlowEvent> timeline = compactTimeline(testCase.timeline);
        indent(json, 1).append("\"timeline\": [").append(newLine());
        for (int i = 0; i < timeline.size(); i++) {
            FlowEvent event = timeline.get(i);
            indent(json, 2).append("{").append(newLine());
            appendField(json, 3, "system", businessTimestamp(event) + " "
                    + defaultValue(event.getSystem(), "UNKNOWN"), true);
            appendField(json, 3, "event", defaultValue(event.getPhase(), "EVENT"), true);
            appendField(json, 3, "status", eventStatus(event), false);
            indent(json, 2).append("}");
            appendComma(json, i < timeline.size() - 1);
        }
        indent(json, 1).append("],").append(newLine());
    }

    private void appendStrictValidation(StringBuilder json, GeneratedTestCase testCase) {
        indent(json, 1).append("\"validation\": {").append(newLine());
        appendField(json, 2, "expected", expectedEvent(testCase), true);
        appendField(json, 2, "actual", actualEvent(testCase), true);
        appendField(json, 2, "result", "PASS".equals(passFail(testCase)) ? "MATCH" : "MISMATCH", false);
        indent(json, 1).append("},").append(newLine());
    }

    private void appendAction(StringBuilder json, GeneratedTestCase testCase) {
        EvidenceFile evidence = evidenceFile(testCase);
        String timeRange = timeRange(testCase.timeline);
        List<String> actions = new ArrayList<>();
        actions.add("File=" + evidence.fileName);
        actions.add("Path=" + evidence.path);
        actions.add("Search=JobID:" + defaultValue(testCase.jobId, "NA")
                + " OR CorrID:" + defaultValue(testCase.corrId, "NA"));
        actions.add("TimeRange=" + timeRange);
        actions.add("Expected=" + expectedEvent(testCase));
        actions.add("Actual=" + actualEvent(testCase));
        actions.add("Conclusion=" + gapLine(testCase));

        indent(json, 1).append("\"action\": [").append(newLine());
        for (int i = 0; i < actions.size(); i++) {
            indent(json, 2).append("\"").append(escape(actions.get(i))).append("\"");
            appendComma(json, i < actions.size() - 1);
        }
        indent(json, 1).append("]").append(newLine());
    }

    private String passFail(GeneratedTestCase testCase) {
        return "SUCCESS".equals(testCase.endToEndStatus) ? "PASS" : "FAIL";
    }

    private String resultReason(GeneratedTestCase testCase) {
        if ("PASS".equals(passFail(testCase))) {
            return "Flow completed with " + actualEvent(testCase);
        }
        return expectedEvent(testCase) + " missing after " + actualEvent(testCase);
    }

    private String expectedEvent(GeneratedTestCase testCase) {
        return "SUCCESS".equals(testCase.endToEndStatus) ? actualEvent(testCase) : "CONFIRM";
    }

    private String actualEvent(GeneratedTestCase testCase) {
        FlowEvent event = finalEvidenceEvent(testCase);
        return event == null ? "NONE" : defaultValue(event.getPhase(), "EVENT");
    }

    private String gapLine(GeneratedTestCase testCase) {
        if ("PASS".equals(passFail(testCase))) {
            return "No gap detected";
        }
        return "Missing " + expectedEvent(testCase) + " after " + actualEvent(testCase);
    }

    private String flowLine(GeneratedTestCase testCase) {
        return testCase.expectedFlow.isEmpty() ? "UNKNOWN" : String.join(" \u2192 ", testCase.expectedFlow);
    }

    private String summaryExpected(GeneratedTestCase testCase) {
        return finalSystem(testCase) + " " + expectedEvent(testCase);
    }

    private String summaryActual(GeneratedTestCase testCase) {
        return finalSystem(testCase) + " " + actualEvent(testCase);
    }

    private String finalSystem(GeneratedTestCase testCase) {
        FlowEvent event = finalEvidenceEvent(testCase);
        if (event != null && hasText(event.getSystem())) {
            return event.getSystem();
        }
        return defaultValue(testCase.failurePoint, "UNKNOWN");
    }

    private FlowEvent finalEvidenceEvent(GeneratedTestCase testCase) {
        if (testCase == null) {
            return null;
        }
        if (!"SUCCESS".equals(testCase.endToEndStatus)) {
            FlowEvent publishEvent = lastEventWithPhase(testCase.timeline, "PUBLISH");
            if (publishEvent != null) {
                return publishEvent;
            }
        }
        return lastEvent(testCase.timeline);
    }

    private List<FlowEvent> compactTimeline(List<FlowEvent> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return Collections.emptyList();
        }

        List<FlowEvent> keyEvents = new ArrayList<>();
        Set<String> accepted = new LinkedHashSet<>();
        accepted.add("REQUEST");
        accepted.add("REPLY");
        accepted.add("PUBLISH");
        accepted.add("CONFIRM");

        for (FlowEvent event : timeline) {
            if (event != null && accepted.contains(defaultValue(event.getPhase(), "").toUpperCase(Locale.ROOT))) {
                keyEvents.add(event);
                if (keyEvents.size() == 8) {
                    return keyEvents;
                }
            }
        }

        if (!keyEvents.isEmpty()) {
            return keyEvents;
        }

        int limit = Math.min(8, timeline.size());
        return new ArrayList<>(timeline.subList(0, limit));
    }

    private FlowEvent lastEvent(List<FlowEvent> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return null;
        }
        return timeline.get(timeline.size() - 1);
    }

    private FlowEvent lastEventWithPhase(List<FlowEvent> timeline, String phase) {
        if (timeline == null || phase == null) {
            return null;
        }
        for (int i = timeline.size() - 1; i >= 0; i--) {
            FlowEvent event = timeline.get(i);
            if (event != null && phase.equalsIgnoreCase(defaultValue(event.getPhase(), ""))) {
                return event;
            }
        }
        return null;
    }

    private String businessTimestamp(FlowEvent event) {
        if (event == null || event.getTimestamp() <= 0) {
            return "NA";
        }
        return BUSINESS_TIME_FORMATTER.format(Instant.ofEpochMilli(event.getTimestamp()));
    }

    private String timeRange(List<FlowEvent> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return "NA-NA";
        }
        FlowEvent first = timeline.get(0);
        FlowEvent last = timeline.get(timeline.size() - 1);
        return businessTimestamp(first) + "-" + businessTimestamp(last);
    }

    private String eventStatus(FlowEvent event) {
        String status = event == null ? "" : defaultValue(event.getStatus(), "SUCCESS");
        String normalized = status.toUpperCase(Locale.ROOT);
        return normalized.contains("FAIL") || normalized.contains("ERROR") ? "FAIL" : "SUCCESS";
    }

    private EvidenceFile evidenceFile(GeneratedTestCase testCase) {
        String bookingId = defaultValue(testCase.bookingId, "UNKNOWN");
        File bookingDir = new File(localDir, bookingId);
        String fallbackPath = remoteEvidencePath();
        if (!bookingDir.exists() || !bookingDir.isDirectory()) {
            return new EvidenceFile("UNKNOWN", fallbackPath);
        }

        File[] files = bookingDir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return new EvidenceFile("UNKNOWN", fallbackPath);
        }

        for (File file : files) {
            if (containsEvidence(file, testCase)) {
                return new EvidenceFile(file.getName(), remoteEvidencePath());
            }
        }

        return new EvidenceFile(files[0].getName(), remoteEvidencePath());
    }

    private String remoteEvidencePath() {
        String normalized = normalizePath(remoteLogDir);
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private boolean containsEvidence(File file, GeneratedTestCase testCase) {
        List<String> tokens = new ArrayList<>();
        if (hasText(testCase.jobId)) {
            tokens.add(testCase.jobId);
        }
        if (hasText(testCase.corrId)) {
            tokens.add(testCase.corrId);
        }
        if (hasText(testCase.bookingId)) {
            tokens.add(testCase.bookingId);
        }
        if (tokens.isEmpty()) {
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String token : tokens) {
                    if (line.contains(token)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private String normalizePath(String path) {
        return value(path).replace("\\", "/");
    }

    private void appendField(StringBuilder json, int level, String name, String value, boolean comma) {
        indent(json, level)
                .append("\"").append(escape(name)).append("\": ")
                .append("\"").append(escape(value(value))).append("\"");
        appendComma(json, comma);
    }

    private void appendNumberField(StringBuilder json, int level, String name, int value, boolean comma) {
        indent(json, level)
                .append("\"").append(escape(name)).append("\": ")
                .append(value);
        appendComma(json, comma);
    }

    private void appendLongField(StringBuilder json, int level, String name, long value, boolean comma) {
        indent(json, level)
                .append("\"").append(escape(name)).append("\": ")
                .append(value);
        appendComma(json, comma);
    }

    private void appendComma(StringBuilder json, boolean comma) {
        if (comma) {
            json.append(",");
        }
        json.append(newLine());
    }

    private StringBuilder indent(StringBuilder json, int level) {
        for (int i = 0; i < level; i++) {
            json.append("  ");
        }
        return json;
    }

    private String newLine() {
        return System.lineSeparator();
    }

    private String escape(String value) {
        String safe = value(value);
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < safe.length(); i++) {
            char ch = safe.charAt(i);
            switch (ch) {
                case '"':
                case '\\':
                    escaped.append('\\').append(ch);
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static class Correlation {
        private String corrId;
        private String jobId;
        private List<String> corrIds = new ArrayList<>();
        private List<String> jobIds = new ArrayList<>();
    }

    private static class EvidenceFile {
        private final String fileName;
        private final String path;

        private EvidenceFile(String fileName, String path) {
            this.fileName = fileName;
            this.path = path;
        }
    }

    public static class GeneratedTestCase {
        private String testCaseName;
        private String bookingId;
        private String corrId;
        private String jobId;
        private List<String> corrIds = new ArrayList<>();
        private List<String> jobIds = new ArrayList<>();
        private List<String> expectedFlow = new ArrayList<>();
        private List<String> expectedSteps = new ArrayList<>();
        private List<FlowEvent> timeline = new ArrayList<>();
        private boolean corrIdPresent;
        private boolean jobIdPresent;
        private int timelineEventsMin;
        private String endToEndStatus;
        private String failurePoint;

        public String getTestCaseName() {
            return testCaseName;
        }

        public String getBookingId() {
            return bookingId;
        }

        public String getCorrId() {
            return corrId;
        }

        public String getJobId() {
            return jobId;
        }

        public List<String> getCorrIds() {
            return Collections.unmodifiableList(corrIds);
        }

        public List<String> getJobIds() {
            return Collections.unmodifiableList(jobIds);
        }

        public List<String> getExpectedFlow() {
            return Collections.unmodifiableList(expectedFlow);
        }

        public List<String> getExpectedSteps() {
            return Collections.unmodifiableList(expectedSteps);
        }

        public boolean isCorrIdPresent() {
            return corrIdPresent;
        }

        public boolean isJobIdPresent() {
            return jobIdPresent;
        }

        public int getTimelineEventsMin() {
            return timelineEventsMin;
        }

        public String getEndToEndStatus() {
            return endToEndStatus;
        }
    }
}
