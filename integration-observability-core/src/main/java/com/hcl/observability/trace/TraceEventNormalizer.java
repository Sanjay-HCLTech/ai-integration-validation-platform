package com.hcl.observability.trace;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TraceEventNormalizer {

    private static final Pattern TIMESTAMP = Pattern.compile(
            "^(\\d{4})\\s+([A-Za-z]{3})\\s+(\\d{1,2})\\s+"
                    + "(\\d{2}):(\\d{2}):(\\d{2}):(\\d{1,3})\\s+\\S+\\s+([+-]\\d{4})");

    private static final Pattern EVENT = Pattern.compile(
            "(^|\\s|\\t)(REQUEST|REPLY|ERROR|FAULT|EXCEPTION|TIMEOUT)(\\s|\\t|:)",
            Pattern.CASE_INSENSITIVE);

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

    public List<NormalizedTraceEvent> normalize(
            List<String> logLines,
            String bookingId,
            String corrId,
            String jobId) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        if (logLines == null) {
            return events;
        }

        for (String line : logLines) {
            if (line == null || !containsAnyCorrelationKey(line, bookingId, corrId, jobId)) {
                continue;
            }

            TracePhase phase = phase(line);
            if (phase == TracePhase.UNKNOWN) {
                continue;
            }

            String operation = operation(line);
            events.add(NormalizedTraceEvent.of(
                    bookingId,
                    corrId,
                    jobId,
                    system(operation, line),
                    protocol(operation, line),
                    phase,
                    operationName(operation),
                    timestamp(line),
                    status(line),
                    line));
        }

        return events;
    }

    private boolean containsAnyCorrelationKey(String line, String bookingId, String corrId, String jobId) {
        return contains(line, bookingId) || contains(line, corrId) || contains(line, jobId);
    }

    private boolean contains(String line, String value) {
        return value != null && !value.trim().isEmpty() && line.contains(value);
    }

    private TracePhase phase(String line) {
        Matcher matcher = EVENT.matcher(line);
        if (!matcher.find()) {
            return TracePhase.UNKNOWN;
        }

        String value = matcher.group(2).toUpperCase(Locale.ROOT);
        if ("REQUEST".equals(value)) {
            return TracePhase.REQUEST;
        }
        if ("REPLY".equals(value)) {
            return TracePhase.REPLY;
        }
        return TracePhase.ERROR;
    }

    private String operation(String line) {
        String[] fields = line.split("\\t");
        if (fields.length >= 8) {
            return fields[4].trim() + "/" + fields[5].trim() + "/" + fields[6].trim();
        }
        return "UNKNOWN_OPERATION";
    }

    private String operationName(String operation) {
        String[] parts = operation.split("/");
        return parts.length == 0 ? operation : parts[parts.length - 1];
    }

    private TraceSystem system(String operation, String line) {
        String value = (operation + " " + line).toUpperCase(Locale.ROOT);
        if (value.contains("JMS") || value.contains("QUEUE") || value.contains("EMS")) {
            return TraceSystem.JMS;
        }
        if (value.contains("SOAP") || value.contains("VRP")) {
            return TraceSystem.SOAP;
        }
        if (value.contains("RABBIT")) {
            return TraceSystem.RABBITMQ;
        }
        if (value.contains("APIGEE") || value.contains("REST")) {
            return TraceSystem.REST;
        }
        if (value.contains("GIP") || value.contains("DATAHUB")) {
            return TraceSystem.DATAHUB;
        }
        return TraceSystem.UNKNOWN;
    }

    private TraceProtocol protocol(String operation, String line) {
        TraceSystem system = system(operation, line);
        if (system == TraceSystem.JMS) {
            return TraceProtocol.JMS;
        }
        if (system == TraceSystem.SOAP) {
            return TraceProtocol.SOAP_HTTP;
        }
        if (system == TraceSystem.RABBITMQ) {
            return TraceProtocol.RABBITMQ;
        }
        if (system == TraceSystem.REST) {
            return TraceProtocol.REST;
        }
        return TraceProtocol.SFTP_LOG;
    }

    private TraceStatus status(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("SUCCESS")) {
            return TraceStatus.SUCCESS;
        }
        if (upper.contains("ERROR") || upper.contains("FAULT") || upper.contains("EXCEPTION")) {
            return TraceStatus.ERROR;
        }
        return TraceStatus.UNKNOWN;
    }

    private OffsetDateTime timestamp(String line) {
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

        String offset = matcher.group(8);
        return OffsetDateTime.of(localDateTime, ZoneOffset.of(offset.substring(0, 3) + ":" + offset.substring(3)));
    }
}
