package com.hcl.observability.autotest;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LogParser {

    private static final Pattern REAL_LOG_PATTERN = Pattern.compile(
            "(?:Line\\s+\\d+:\\s*)?"
                    + "(?<date>\\d{4}\\s+[A-Za-z]{3}\\s+\\d{1,2})\\s+"
                    + "(?<time>\\d{2}:\\d{2}:\\d{2}:\\d{3})\\s+"
                    + "(?<zone>[A-Za-z]+)\\s+"
                    + "(?<offset>[+-]\\d{4})\\s+"
                    + "JobID:(?<jobId>\\d+)\\s+"
                    + "(?<phase>REQUEST|REPLY|NOTIFY|PUBLISH|CONFIRM)\\s+"
                    + "(?<status>SUCCESS|ERROR|FAIL|FAILED)\\s+"
                    + "\\S+\\s+"
                    + "(?<system>\\S+)\\s+"
                    + "(?<component>\\S+)\\s+"
                    + "(?<operation>\\S+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UNIFIED_PATTERN = Pattern.compile(
            ".*Svc=(?<system>\\S+)\\s+Phase=(?<phase>\\S+)\\s+Op=(?<operation>\\S+).*"
                    + "\\s+TS=(?<timestamp>\\S+).*?(?:Result=(?<status>\\S+))?.*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CORR_ID_PATTERN = Pattern.compile(
            "CorrID:\\s*([0-9a-fA-F-]{20,})|CorrID=([0-9a-fA-F-]{20,})");
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("JobID:(\\d+)|JobID=(\\S+)");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final DateTimeFormatter REAL_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy MMM d HH:mm:ss:SSS", Locale.ENGLISH);

    public List<FlowEvent> parse(String bookingId, Iterable<String> rawLines) {
        if (rawLines == null) {
            return new ArrayList<>();
        }

        List<FlowEvent> events = new ArrayList<>();
        Set<String> acceptedJobIds = new LinkedHashSet<>();
        String selectedCorrId = "";
        for (String line : rawLines) {
            FlowEvent event = parseLine(line);
            if (event == null) {
                continue;
            }

            if (hasUsableBookingId(bookingId) && line != null && line.contains(bookingId)) {
                if (hasText(event.getJobId())) {
                    acceptedJobIds.add(event.getJobId());
                }
                if (!hasText(selectedCorrId) && hasText(event.getCorrId())) {
                    selectedCorrId = event.getCorrId();
                }
            }

            if (!hasText(selectedCorrId) && hasText(event.getCorrId())) {
                selectedCorrId = event.getCorrId();
            }
            events.add(event);
        }

        if (!acceptedJobIds.isEmpty()) {
            List<FlowEvent> filtered = new ArrayList<>();
            for (FlowEvent event : events) {
                if (acceptedJobIds.contains(event.getJobId())) {
                    if (!hasText(event.getCorrId())) {
                        event.setCorrId(selectedCorrId);
                    }
                    filtered.add(event);
                }
            }
            return filtered;
        }

        for (FlowEvent event : events) {
            if (!hasText(event.getCorrId())) {
                event.setCorrId(selectedCorrId);
            }
        }
        return events;
    }

    public List<FlowEvent> parseAll(Iterable<String> rawLines) {
        if (rawLines == null) {
            return new ArrayList<>();
        }

        List<FlowEvent> events = new ArrayList<>();
        for (String line : rawLines) {
            FlowEvent event = parseLine(line);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    public FlowEvent parseLine(String line) {
        if (!hasText(line)) {
            return null;
        }

        FlowEvent realEvent = parseRealLogLine(line);
        if (realEvent != null) {
            return realEvent;
        }
        return parseUnifiedLine(line);
    }

    private FlowEvent parseRealLogLine(String line) {
        Matcher matcher = REAL_LOG_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }

        String system = normalizeSystem(firstText(matcher.group("component"), matcher.group("system")));
        String phase = normalizePhase(matcher.group("phase"));
        String operation = clean(matcher.group("operation"));
        String corrId = extractCorrId(line);
        String jobId = clean(matcher.group("jobId"));
        long timestamp = parseRealTimestamp(
                matcher.group("date"),
                matcher.group("time"),
                matcher.group("offset"));
        String status = normalizeStatus(matcher.group("status"), line);

        return new FlowEvent(system, phase, operation, corrId, jobId, timestamp, status, line);
    }

    private FlowEvent parseUnifiedLine(String line) {
        Matcher matcher = UNIFIED_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }

        String system = normalizeSystem(matcher.group("system"));
        String phase = normalizePhase(matcher.group("phase"));
        String operation = clean(matcher.group("operation"));
        String corrId = extractCorrId(line);
        String jobId = extractJobId(line);
        long timestamp = parseUnifiedTimestamp(matcher.group("timestamp"));
        String status = normalizeStatus(matcher.group("status"), line);

        return new FlowEvent(system, phase, operation, corrId, jobId, timestamp, status, line);
    }

    private String extractCorrId(String line) {
        Matcher matcher = CORR_ID_PATTERN.matcher(line);
        if (matcher.find()) {
            return firstText(matcher.group(1), matcher.group(2));
        }
        Matcher uuidMatcher = UUID_PATTERN.matcher(line);
        return uuidMatcher.find() ? uuidMatcher.group() : "";
    }

    private String extractJobId(String line) {
        Matcher matcher = JOB_ID_PATTERN.matcher(line);
        if (matcher.find()) {
            return firstText(matcher.group(1), matcher.group(2));
        }
        return "";
    }

    private long parseRealTimestamp(String date, String time, String offset) {
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(date + " " + time, REAL_DATE_FORMATTER);
            return localDateTime.toInstant(parseOffset(offset)).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private long parseUnifiedTimestamp(String timestamp) {
        if (!hasText(timestamp) || "NA".equalsIgnoreCase(timestamp)) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
        }
        try {
            String[] parts = timestamp.split(":");
            if (parts.length == 3) {
                LocalDate today = LocalDate.now();
                String[] secondParts = parts[2].split("\\.");
                int millis = secondParts.length > 1 ? Integer.parseInt(secondParts[1]) : 0;
                LocalDateTime dateTime = LocalDateTime.of(
                        today.getYear(),
                        Month.of(today.getMonthValue()),
                        today.getDayOfMonth(),
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(secondParts[0]),
                        millis * 1_000_000);
                return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    private ZoneOffset parseOffset(String value) {
        if (!hasText(value) || value.length() != 5) {
            return ZoneOffset.UTC;
        }
        return ZoneOffset.of(value.substring(0, 3) + ":" + value.substring(3));
    }

    private String normalizePhase(String phase) {
        String normalized = clean(phase).toUpperCase(Locale.ROOT);
        if ("NOTIFY".equals(normalized)) {
            return "PROCESS";
        }
        return normalized;
    }

    private String normalizeStatus(String status, String line) {
        String normalized = clean(status).toUpperCase(Locale.ROOT);
        if (!hasText(normalized)) {
            normalized = "SUCCESS";
        }
        if ("FAIL".equals(normalized)) {
            normalized = "FAILED";
        }
        if ("SUCCESS".equals(normalized) && hasText(line)
                && line.toLowerCase(Locale.ROOT).contains("error")) {
            return "ERROR";
        }
        return normalized;
    }

    private String normalizeSystem(String rawSystem) {
        String system = clean(rawSystem);
        String upper = system.toUpperCase(Locale.ROOT);
        if (upper.contains("GIPBOOKINGADAPTER")) {
            return "GIP";
        }
        if (upper.startsWith("GCA")) {
            return "GCA";
        }
        if (upper.contains("POSTBOOKFLOW") || upper.contains("BOOKFLOW")) {
            return "PBF";
        }
        if (upper.contains("MONGODB")) {
            return "MONGODB";
        }
        if (upper.contains("ATCORE")) {
            return "ATCORE";
        }
        if (upper.contains("RABBIT") || upper.contains("NORDIC")) {
            return "NORDICS";
        }
        if (upper.contains("SAP")) {
            return "SAP";
        }
        return system;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first.trim() : clean(second);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean hasUsableBookingId(String value) {
        return hasText(value) && !"NA".equalsIgnoreCase(value.trim());
    }
}
