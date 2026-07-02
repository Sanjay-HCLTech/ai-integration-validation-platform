package com.hcl.observability.correlation;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TimelineValidator {

    private static final long FLOW_GAP_SECONDS = 60;

    private static final Pattern TIMESTAMP = Pattern.compile(
            "^(\\d{4})\\s+([A-Za-z]{3})\\s+(\\d{1,2})\\s+" +
                    "(\\d{2}):(\\d{2}):(\\d{2}):(\\d{1,3})\\s+\\S+\\s+([+-]\\d{4})");

    private static final Pattern EVENT = Pattern.compile(
            "(^|\\s|\\t)(REQUEST|REPLY|NOTIFY|PUBLISH|CONFIRM|ERROR|FAULT|EXCEPTION|TIMEOUT)(\\s|\\t|:)",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Month> MONTHS = new HashMap<>();

    @Value("${unified.trace.report.enabled}")
    private boolean unifiedTraceReportEnabled;

    static {
        MONTHS.put("JAN", Month.JANUARY);
        MONTHS.put("FEB", Month.FEBRUARY);
        MONTHS.put("MAR", Month.MARCH);
        MONTHS.put("APR", Month.APRIL);
        MONTHS.put("MAY", Month.MAY);
        MONTHS.put("JUN", Month.JUNE);
        MONTHS.put("JUL", Month.JULY);
        MONTHS.put("AUG", Month.AUGUST);
        MONTHS.put("SEP", Month.SEPTEMBER);
        MONTHS.put("OCT", Month.OCTOBER);
        MONTHS.put("NOV", Month.NOVEMBER);
        MONTHS.put("DEC", Month.DECEMBER);
    }

    public boolean validateTimeline(List<String> logLines, String corrId) {
        return validateTimelineDetailed(logLines, corrId).isValid();
    }

    public TimelineValidationResult validateTimelineDetailed(List<String> logLines, String corrId) {
        return validateTimelineDetailed(logLines, corrId, null, null);
    }

    public TimelineValidationResult validateTimelineDetailed(
            List<String> logLines,
            String corrId,
            String bookingId,
            String jobId) {
        List<String> correlationKeys = correlationKeys(corrId, bookingId, jobId);
        if (correlationKeys.isEmpty()) {
            return TimelineValidationResult.fail("No BookingID, CorrID, or JobID available for timeline validation");
        }

        List<TimelineEvent> events = extractEvents(logLines, correlationKeys);

        if (events.isEmpty()) {
            return TimelineValidationResult.fail("No REQUEST/REPLY timeline events found for keys: "
                    + correlationKeys);
        }

        events.sort(Comparator
                .comparing(TimelineEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(TimelineEvent::getSequence));

        printTimelineEvents(events);

        TimelineValidationResult structureResult = validateOperationPairs(events, correlationKeys.toString());
        if (!structureResult.isValid()) {
            return structureResult;
        }

        TimelineEvent firstRequest = firstOf(events, "REQUEST");
        TimelineEvent firstReply = firstOf(events, "REPLY");

        return TimelineValidationResult.pass("Timeline valid for keys: " + correlationKeys
                + " | operations=" + operationGroups(events).size()
                + " | systems=" + systemLabels(events)
                + " | eventTypes=" + eventTypeCounts(events)
                + " | REQUEST=" + firstRequest.summary()
                + " | REPLY=" + firstReply.summary());
    }

    private List<String> correlationKeys(String corrId, String bookingId, String jobId) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addKey(keys, corrId);
        addKey(keys, bookingId);
        addKey(keys, jobId);
        return new ArrayList<>(keys);
    }

    private void addKey(Set<String> keys, String value) {
        if (value != null && !value.trim().isEmpty()) {
            keys.add(value.trim());
        }
    }

    private List<TimelineEvent> extractEvents(List<String> logLines, List<String> correlationKeys) {
        List<TimelineEvent> events = new ArrayList<>();

        if (logLines == null) {
            return events;
        }

        int sequence = 0;
        int relatedContextLinesRemaining = 0;
        Set<String> seenEvents = new LinkedHashSet<>();
        for (String line : logLines) {
            sequence++;

            if (line == null) {
                continue;
            }

            boolean lineHasKey = containsAnyKey(line, correlationKeys);
            if (lineHasKey) {
                relatedContextLinesRemaining = 200;
            } else if (relatedContextLinesRemaining <= 0) {
                continue;
            } else {
                relatedContextLinesRemaining--;
            }

            String type = eventType(line);
            if (type == null) {
                continue;
            }

            OffsetDateTime timestamp = parseTimestamp(line);
            String operationKey = operationKey(line);
            String systemLabel = systemLabel(operationKey, line);
            String eventKey = type + "|" + operationKey + "|" + timestamp + "|" + compact(line);

            if (seenEvents.add(eventKey)) {
                events.add(new TimelineEvent(type, operationKey, systemLabel, timestamp, sequence, compact(line)));
            }
        }

        return events;
    }

    private boolean containsAnyKey(String line, List<String> correlationKeys) {
        for (String key : correlationKeys) {
            if (line.contains(key)) {
                return true;
            }
        }

        return false;
    }

    private TimelineValidationResult validateOperationPairs(List<TimelineEvent> events, String corrId) {
        Map<String, List<TimelineEvent>> groupedEvents = operationGroups(communicationEvents(events));

        for (Map.Entry<String, List<TimelineEvent>> entry : groupedEvents.entrySet()) {
            String operation = entry.getKey();
            List<TimelineEvent> operationEvents = entry.getValue();

            int pendingRequests = 0;
            int pairs = 0;
            TimelineEvent firstUnpairedRequest = null;

            for (TimelineEvent event : operationEvents) {
                if ("REQUEST".equals(event.getType())) {
                    pendingRequests++;
                    if (firstUnpairedRequest == null) {
                        firstUnpairedRequest = event;
                    }
                    continue;
                }

                if ("REPLY".equals(event.getType())) {
                    if (pendingRequests == 0) {
                        return TimelineValidationResult.fail("Timeline violation: REPLY before REQUEST for operation "
                                + operation + " and CorrID: " + corrId
                                + " | REPLY=" + event.summary());
                    }

                    pendingRequests--;
                    pairs++;
                    if (pendingRequests == 0) {
                        firstUnpairedRequest = null;
                    }
                }
            }

            if (pairs == 0) {
                return TimelineValidationResult.fail("Timeline violation: no REQUEST/REPLY pair found for operation "
                        + operation + " and CorrID: " + corrId);
            }

            if (pendingRequests > 0) {
                return TimelineValidationResult.fail("Timeline violation: REQUEST found but REPLY is missing for operation "
                        + operation + " and CorrID: " + corrId
                        + " | REQUEST=" + firstUnpairedRequest.summary());
            }
        }

        return TimelineValidationResult.pass("Timeline operation pairs valid");
    }

    private Map<String, List<TimelineEvent>> operationGroups(List<TimelineEvent> events) {
        Map<String, List<TimelineEvent>> groupedEvents = new LinkedHashMap<>();

        for (TimelineEvent event : events) {
            groupedEvents.computeIfAbsent(event.getOperationKey(), key -> new ArrayList<>()).add(event);
        }

        return groupedEvents;
    }

    private void printTimelineEvents(List<TimelineEvent> events) {
        if (unifiedTraceReportEnabled) {
            return;
        }

        System.out.println();
        System.out.println("-------------------- TIMELINE --------------------------");
        System.out.println("[TIMELINE]");

        List<List<TimelineEvent>> flows = executionFlows(events);
        for (int flowIndex = 0; flowIndex < flows.size(); flowIndex++) {
            List<TimelineEvent> flow = flows.get(flowIndex);
            System.out.println();
            System.out.println("-- Flow #" + (flowIndex + 1) + " (" + flowWindow(flow) + ")");

            Map<String, List<TimelineEvent>> pendingRequests = new LinkedHashMap<>();
            for (int eventIndex = 0; eventIndex < flow.size(); eventIndex++) {
                TimelineEvent event = flow.get(eventIndex);
                String latency = latencyValue(event, pendingRequests);

                System.out.println((eventIndex + 1) + ". "
                        + "Svc=" + padRight(event.getSystemLabel(), 7)
                        + " Phase=" + padPhase(event.getType())
                        + " Op=" + operationName(event.getOperationKey())
                        + " TS=" + timestampValue(event)
                        + " Latency=" + latency
                        + " Result=" + resultStatus(event.getLine()));
            }
        }
    }

    private List<List<TimelineEvent>> executionFlows(List<TimelineEvent> events) {
        List<TimelineEvent> sortedEvents = new ArrayList<>(events);
        sortedEvents.sort(Comparator
                .comparing(TimelineEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(TimelineEvent::getSequence));

        List<List<TimelineEvent>> flows = new ArrayList<>();
        List<TimelineEvent> currentFlow = new ArrayList<>();
        TimelineEvent previous = null;

        for (TimelineEvent event : sortedEvents) {
            if (!currentFlow.isEmpty() && startsNewFlow(previous, event)) {
                flows.add(currentFlow);
                currentFlow = new ArrayList<>();
            }

            currentFlow.add(event);
            previous = event;
        }

        if (!currentFlow.isEmpty()) {
            flows.add(currentFlow);
        }

        return flows;
    }

    private boolean startsNewFlow(TimelineEvent previous, TimelineEvent current) {
        if (previous == null || previous.getTimestamp() == null || current.getTimestamp() == null) {
            return false;
        }

        return Duration.between(previous.getTimestamp(), current.getTimestamp()).getSeconds() > FLOW_GAP_SECONDS;
    }

    private String flowWindow(List<TimelineEvent> flow) {
        if (flow.isEmpty()) {
            return "NA";
        }

        TimelineEvent first = flow.get(0);
        TimelineEvent last = flow.get(flow.size() - 1);
        return timestampValue(first) + " - " + timestampValue(last);
    }

    private String latencyValue(TimelineEvent event, Map<String, List<TimelineEvent>> pendingRequests) {
        if ("REQUEST".equals(event.getType())) {
            pendingRequests
                    .computeIfAbsent(event.getOperationKey(), key -> new ArrayList<>())
                    .add(event);
            return "NA";
        }

        if (!"REPLY".equals(event.getType())) {
            return "NA";
        }

        List<TimelineEvent> requests = pendingRequests.get(event.getOperationKey());
        if (requests == null || requests.isEmpty()) {
            return "NA";
        }

        TimelineEvent request = requests.remove(0);
        if (request.getTimestamp() == null || event.getTimestamp() == null) {
            return "NA";
        }

        long millis = Duration.between(request.getTimestamp(), event.getTimestamp()).toMillis();
        if (millis < 0) {
            return "NA";
        }
        if (millis < 1_000) {
            return millis + "ms";
        }
        return String.format(Locale.ROOT, "%.2fs", millis / 1_000.0);
    }

    private String timestampValue(TimelineEvent event) {
        if (event.getTimestamp() == null) {
            return "NA";
        }

        return event.getTimestamp().toLocalTime().toString();
    }

    private String padPhase(String type) {
        return padRight(type, 7);
    }

    private String padRight(String value, int width) {
        String safeValue = value == null ? "" : value;
        if (safeValue.length() >= width) {
            return safeValue;
        }
        return String.format("%-" + width + "s", safeValue);
    }

    private String operationName(String operationKey) {
        if (operationKey == null || operationKey.trim().isEmpty()) {
            return "UNKNOWN";
        }

        String[] parts = operationKey.split("/");
        return parts.length == 0 ? operationKey : parts[parts.length - 1];
    }

    private String resultStatus(String line) {
        if (line == null) {
            return "UNKNOWN";
        }

        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("SUCCESS")) {
            return "SUCCESS";
        }
        if (upper.contains("ERROR") || upper.contains("FAULT") || upper.contains("EXCEPTION")) {
            return "ERROR";
        }
        if (upper.contains("REQUEST") || upper.contains("REPLY")
                || upper.contains("NOTIFY") || upper.contains("PUBLISH")
                || upper.contains("CONFIRM")) {
            return "SUCCESS";
        }
        return "UNKNOWN";
    }

    private List<TimelineEvent> communicationEvents(List<TimelineEvent> events) {
        List<TimelineEvent> communicationEvents = new ArrayList<>();

        for (TimelineEvent event : events) {
            if ("REQUEST".equals(event.getType()) || "REPLY".equals(event.getType())) {
                communicationEvents.add(event);
            }
        }

        return communicationEvents;
    }

    private Set<String> systemLabels(List<TimelineEvent> events) {
        Set<String> labels = new LinkedHashSet<>();

        for (TimelineEvent event : events) {
            labels.add(event.getSystemLabel());
        }

        return labels;
    }

    private Map<String, Integer> eventTypeCounts(List<TimelineEvent> events) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (TimelineEvent event : events) {
            counts.put(event.getType(), counts.getOrDefault(event.getType(), 0) + 1);
        }

        return counts;
    }

    private TimelineEvent firstOf(List<TimelineEvent> events, String type) {
        return events.stream()
                .filter(event -> type.equals(event.getType()))
                .findFirst()
                .orElse(null);
    }

    private String eventType(String line) {
        Matcher matcher = EVENT.matcher(line);
        if (!matcher.find()) {
            return null;
        }

        return matcher.group(2).toUpperCase(Locale.ROOT);
    }

    private String operationKey(String line) {
        String[] fields = line.split("\\t");
        if (fields.length >= 8) {
            return fields[4].trim() + "/" + fields[5].trim() + "/" + fields[6].trim();
        }

        return "UNKNOWN_OPERATION";
    }

    private String systemLabel(String operationKey, String line) {
        String value = (operationKey + " " + line).toUpperCase(Locale.ROOT);

        if (value.contains("GIP")) {
            return "GIP";
        }
        if (value.contains("CDS")) {
            return "CDS";
        }
        if (value.contains("MONGO")) {
            return "Mongo";
        }
        if (value.contains("ATCORE")) {
            return "Atcore";
        }
        if (value.contains("QUEUE") || value.contains("EMS") || value.contains("JMS")) {
            return "Queue";
        }
        if (value.contains("BOOK")) {
            return "Booking";
        }

        String[] parts = operationKey.split("/");
        return parts.length > 0 && !parts[0].trim().isEmpty() ? parts[0].trim() : "Unknown";
    }

    private OffsetDateTime parseTimestamp(String line) {
        Matcher matcher = TIMESTAMP.matcher(line);
        if (!matcher.find()) {
            return null;
        }

        Month month = MONTHS.get(matcher.group(2).toUpperCase(Locale.ROOT));
        if (month == null) {
            return null;
        }

        int millis = Integer.parseInt(matcher.group(7));
        LocalDateTime localDateTime = LocalDateTime.of(
                Integer.parseInt(matcher.group(1)),
                month,
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)),
                Integer.parseInt(matcher.group(6)),
                millis * 1_000_000);

        return OffsetDateTime.of(localDateTime, zoneOffset(matcher.group(8)));
    }

    private ZoneOffset zoneOffset(String offset) {
        String normalized = offset.substring(0, 3) + ":" + offset.substring(3);
        return ZoneOffset.of(normalized);
    }

    private String compact(String line) {
        String compact = line.replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }

    private static class TimelineEvent {
        private final String type;
        private final String operationKey;
        private final String systemLabel;
        private final OffsetDateTime timestamp;
        private final int sequence;
        private final String line;

        private TimelineEvent(
                String type,
                String operationKey,
                String systemLabel,
                OffsetDateTime timestamp,
                int sequence,
                String line) {
            this.type = type;
            this.operationKey = operationKey;
            this.systemLabel = systemLabel;
            this.timestamp = timestamp;
            this.sequence = sequence;
            this.line = line;
        }

        private String getType() {
            return type;
        }

        private String getOperationKey() {
            return operationKey;
        }

        private String getSystemLabel() {
            return systemLabel;
        }

        private OffsetDateTime getTimestamp() {
            return timestamp;
        }

        private int getSequence() {
            return sequence;
        }

        private String getLine() {
            return line;
        }

        private String summary() {
            return "[" + systemLabel + "] "
                    + (timestamp == null ? "sequence " + sequence : timestamp.toString()) + " " + line;
        }

        @Override
        public String toString() {
            return "[" + systemLabel + "] " + type + ":" + operationKey + "@"
                    + (timestamp == null ? "seq:" + sequence : timestamp);
        }
    }
}
