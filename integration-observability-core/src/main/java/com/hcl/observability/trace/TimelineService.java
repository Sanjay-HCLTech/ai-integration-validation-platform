package com.hcl.observability.trace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TimelineService {

    private static final Pattern TIBCO_TIMESTAMP_WITH_OFFSET = Pattern.compile(
            "^(\\d{4})\\s+([A-Za-z]{3})\\s+(\\d{1,2})\\s+"
                    + "(\\d{2}):(\\d{2}):(\\d{2}):(\\d{1,3}).*?([+-]\\d{4})");
    private static final Pattern TIBCO_TIMESTAMP = Pattern.compile(
            "^(\\d{4})\\s+([A-Za-z]{3})\\s+(\\d{1,2})\\s+"
                    + "(\\d{2}):(\\d{2}):(\\d{2}):(\\d{1,3})");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2})(?:[,.](\\d{1,3}))?");
    private static final Pattern EVENT = Pattern.compile(
            "(^|\\s|\\t|\\|)(REQUEST|PROCESS|PROCESSING|SEND|ACK|CONSUME|REPLY|RESPONSE|"
                    + "NOTIFY|PUBLISH|PUBLISHED|CONFIRM|ERROR|FAULT|EXCEPTION|TIMEOUT)(\\s|\\t|:|\\||$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_OPERATION = Pattern.compile(
            "([A-Za-z0-9]+(?:_[A-Za-z0-9]+)+)\\.(?:log)(?:\\.\\d+)?$");
    private static final Map<String, Month> MONTHS = new HashMap<>();

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

    private final String localDir;

    public TimelineService(
            @Value("${local.log.dir}") String localDir) {
        this.localDir = localDir;
    }

    public List<TimelineEvent> buildTimeline(String scope) throws Exception {
        if (!hasText(scope)) {
            return Collections.emptyList();
        }

        File scopeDir = new File(localDir, safeSegment(scope, "UNKNOWN"));
        if (!scopeDir.exists() || !scopeDir.isDirectory()) {
            return Collections.emptyList();
        }

        List<File> files = timelineSourceFiles(scopeDir);
        List<TimelineEvent> events = new ArrayList<>();
        int sequence = 0;
        for (File file : files) {
            List<String> lines = readLines(file);
            List<TimelineEvent> fileEvents = buildTimeline(lines, file.getName(), sequence);
            events.addAll(fileEvents);
            sequence += lines.size();
        }
        return sortAndApplyLatency(events);
    }

    public List<TimelineEvent> buildTimeline(List<String> lines, String sourceFile) {
        return sortAndApplyLatency(buildTimeline(lines, sourceFile, 0));
    }

    private List<TimelineEvent> buildTimeline(List<String> lines, String sourceFile, int sequenceOffset) {
        List<TimelineEvent> events = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return events;
        }

        String defaultSystem = serviceName(sourceFile);
        String defaultOperation = operationName(sourceFile);
        Set<String> seen = new LinkedHashSet<>();
        int sequence = sequenceOffset;

        for (String line : lines) {
            sequence++;
            TracePhase phase = phase(line);
            if (phase == TracePhase.UNKNOWN) {
                continue;
            }

            OffsetDateTime timestamp = timestamp(line);
            String system = system(defaultSystem, line);
            String operation = operation(defaultOperation, line);
            String key = timestamp + "|" + system + "|" + operation + "|" + phase + "|" + compact(line);
            if (!seen.add(key)) {
                continue;
            }

            TimelineEvent event = new TimelineEvent();
            event.setTimestamp(timestamp);
            event.setSystem(system);
            event.setOperation(operation);
            event.setPhase(phase);
            event.setStatus(status(line, phase));
            event.setNote(note(phase, system, operation));
            event.setLatencyMsFromPrevious(-1);
            event.setSequence(sequence);
            event.setSourceFile(sourceFile);
            event.setRawLine(line);
            events.add(event);
        }

        return events;
    }

    private List<File> timelineSourceFiles(File scopeDir) {
        File[] rawFiles = scopeDir.listFiles(file -> file.isFile()
                && file.getName().contains(".log")
                && !file.getName().startsWith("processed_files_"));
        return rawFiles == null ? Collections.emptyList() : sortedFiles(rawFiles);
    }

    private List<File> sortedFiles(File[] files) {
        List<File> result = new ArrayList<>();
        Collections.addAll(result, files);
        result.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        return result;
    }

    private List<String> readLines(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private List<TimelineEvent> sortAndApplyLatency(List<TimelineEvent> events) {
        List<TimelineEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator
                .comparing(TimelineEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(TimelineEvent::getSequence));

        TimelineEvent previous = null;
        for (TimelineEvent event : sorted) {
            if (previous != null && previous.getTimestamp() != null && event.getTimestamp() != null) {
                event.setLatencyMsFromPrevious(Math.max(0,
                        Duration.between(previous.getTimestamp(), event.getTimestamp()).toMillis()));
            }
            previous = event;
        }
        return sorted;
    }

    private TracePhase phase(String line) {
        if (line == null) {
            return TracePhase.UNKNOWN;
        }

        Matcher matcher = EVENT.matcher(line);
        if (!matcher.find()) {
            return TracePhase.UNKNOWN;
        }

        String value = matcher.group(2).toUpperCase(Locale.ROOT);
        if ("REQUEST".equals(value)) {
            return TracePhase.REQUEST;
        }
        if ("PROCESS".equals(value) || "PROCESSING".equals(value)) {
            return TracePhase.PROCESS;
        }
        if ("SEND".equals(value)) {
            return TracePhase.SEND;
        }
        if ("ACK".equals(value)) {
            return TracePhase.ACK;
        }
        if ("CONSUME".equals(value)) {
            return TracePhase.CONSUME;
        }
        if ("REPLY".equals(value) || "RESPONSE".equals(value)) {
            return TracePhase.REPLY;
        }
        if ("NOTIFY".equals(value)) {
            return TracePhase.NOTIFY;
        }
        if ("PUBLISH".equals(value) || "PUBLISHED".equals(value)) {
            return TracePhase.PUBLISH;
        }
        if ("CONFIRM".equals(value)) {
            return TracePhase.CONFIRM;
        }
        return TracePhase.ERROR;
    }

    private TraceStatus status(String line, TracePhase phase) {
        String upper = line == null ? "" : line.toUpperCase(Locale.ROOT);
        if (phase == TracePhase.ERROR || upper.contains("ERROR") || upper.contains("FAULT")
                || upper.contains("EXCEPTION") || upper.contains("TIMEOUT")) {
            return TraceStatus.ERROR;
        }
        if (upper.contains("FAIL")) {
            return TraceStatus.FAILED;
        }
        if (phase == TracePhase.CONSUME) {
            return TraceStatus.CONSUMED;
        }
        if (phase == TracePhase.ACK || phase == TracePhase.CONFIRM) {
            return TraceStatus.DELIVERED;
        }
        if (phase == TracePhase.PROCESS || phase == TracePhase.NOTIFY) {
            return TraceStatus.PROCESSED;
        }
        return TraceStatus.SUCCESS;
    }

    private OffsetDateTime timestamp(String line) {
        OffsetDateTime tibcoWithOffset = tibcoTimestampWithOffset(line);
        if (tibcoWithOffset != null) {
            return tibcoWithOffset;
        }

        OffsetDateTime tibco = tibcoTimestamp(line);
        if (tibco != null) {
            return tibco;
        }

        return isoTimestamp(line);
    }

    private OffsetDateTime tibcoTimestampWithOffset(String line) {
        Matcher matcher = TIBCO_TIMESTAMP_WITH_OFFSET.matcher(line == null ? "" : line);
        if (!matcher.find()) {
            return null;
        }

        Month month = MONTHS.get(matcher.group(2).toUpperCase(Locale.ROOT));
        if (month == null) {
            return null;
        }

        LocalDateTime dateTime = LocalDateTime.of(
                Integer.parseInt(matcher.group(1)),
                month,
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)),
                Integer.parseInt(matcher.group(6)),
                Integer.parseInt(padMillis(matcher.group(7))) * 1_000_000);
        return OffsetDateTime.of(dateTime, zoneOffset(matcher.group(8)));
    }

    private OffsetDateTime tibcoTimestamp(String line) {
        Matcher matcher = TIBCO_TIMESTAMP.matcher(line == null ? "" : line);
        if (!matcher.find()) {
            return null;
        }

        Month month = MONTHS.get(matcher.group(2).toUpperCase(Locale.ROOT));
        if (month == null) {
            return null;
        }

        LocalDateTime dateTime = LocalDateTime.of(
                Integer.parseInt(matcher.group(1)),
                month,
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)),
                Integer.parseInt(matcher.group(6)),
                Integer.parseInt(padMillis(matcher.group(7))) * 1_000_000);
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private OffsetDateTime isoTimestamp(String line) {
        Matcher matcher = ISO_TIMESTAMP.matcher(line == null ? "" : line);
        if (!matcher.find()) {
            return null;
        }

        String millis = matcher.group(3) == null ? "000" : padMillis(matcher.group(3));
        LocalDateTime dateTime = LocalDateTime.parse(matcher.group(1)
                + "T"
                + matcher.group(2)
                + "."
                + millis);
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private ZoneOffset zoneOffset(String value) {
        if (!hasText(value) || value.length() != 5) {
            return ZoneOffset.UTC;
        }
        return ZoneOffset.of(value.substring(0, 3) + ":" + value.substring(3));
    }

    private String padMillis(String value) {
        String millis = value == null ? "000" : value;
        if (millis.length() >= 3) {
            return millis.substring(0, 3);
        }
        return String.format("%-3s", millis).replace(' ', '0');
    }

    private String serviceName(String sourceFile) {
        String base = baseName(sourceFile);
        int split = base.lastIndexOf('_');
        return split > 0 ? base.substring(0, split) : base;
    }

    private String operationName(String sourceFile) {
        String base = baseName(sourceFile);
        int split = base.lastIndexOf('_');
        return split > 0 ? base.substring(split + 1) : "UNKNOWN";
    }

    private String baseName(String sourceFile) {
        if (!hasText(sourceFile)) {
            return "UNKNOWN";
        }

        Matcher matcher = SERVICE_OPERATION.matcher(sourceFile);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return sourceFile.replaceAll("\\.log(?:\\.\\d+)?$", "");
    }

    private String system(String defaultSystem, String line) {
        String upper = line == null ? "" : line.toUpperCase(Locale.ROOT);
        if (upper.contains("MONGODB")) {
            return "MONGODB";
        }
        if (upper.contains("ATCORE")) {
            return "ATCORE";
        }
        if (upper.contains("ANITEVRP") || upper.contains("VRP")) {
            return "AniteVRP";
        }
        if (upper.contains("APIGEE")) {
            return "APIGEE";
        }
        if (upper.contains("RABBIT") || upper.contains("NORDIC")) {
            return "NORDICS";
        }
        return hasText(defaultSystem) ? defaultSystem : "UNKNOWN";
    }

    private String operation(String defaultOperation, String line) {
        String[] fields = line == null ? new String[0] : line.split("\\t");
        if (fields.length >= 7 && hasText(fields[6])) {
            return fields[6].trim();
        }
        return hasText(defaultOperation) ? defaultOperation : "UNKNOWN";
    }

    private String note(TracePhase phase, String system, String operation) {
        return defaultValue(system) + " " + defaultValue(operation) + " " + phase;
    }

    private String compact(String line) {
        if (line == null) {
            return "";
        }
        return line.trim().replaceAll("\\s+", " ");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String defaultValue(String value) {
        return hasText(value) ? value : "UNKNOWN";
    }

    private static String safeSegment(String value, String defaultValue) {
        String candidate = hasText(value) ? value.trim() : defaultValue;
        return candidate.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
