package com.hcl.observability.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UnifiedTraceContext {

    private String testCaseId;
    private String bookingId;
    private String corrId;
    private String jobId;
    private String apiEndpoint;
    private String apiStatus;
    private long totalTimeMs;
    private final List<String> fileLines = new ArrayList<>();
    private final List<String> retryLines = new ArrayList<>();
    private final Map<String, List<String>> protocolLines = new LinkedHashMap<>();
    private final List<String> validationLines = new ArrayList<>();
    private final List<String> summaryLines = new ArrayList<>();
    private final List<NormalizedTraceEvent> events = new ArrayList<>();

    public String getTestCaseId() {
        return testCaseId;
    }

    public void setTestCaseId(String testCaseId) {
        this.testCaseId = testCaseId;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getCorrId() {
        return corrId;
    }

    public void setCorrId(String corrId) {
        this.corrId = corrId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getApiStatus() {
        return apiStatus;
    }

    public void setApiStatus(String apiStatus) {
        this.apiStatus = apiStatus;
    }

    public long getTotalTimeMs() {
        return totalTimeMs;
    }

    public void setTotalTimeMs(long totalTimeMs) {
        this.totalTimeMs = totalTimeMs;
    }

    public void addEvent(NormalizedTraceEvent event) {
        if (event != null && !events.contains(event)) {
            events.add(event);
        }
    }

    public void addEvents(List<NormalizedTraceEvent> traceEvents) {
        if (traceEvents == null) {
            return;
        }
        for (NormalizedTraceEvent event : traceEvents) {
            addEvent(event);
        }
    }

    public List<NormalizedTraceEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public void addFileLine(String line) {
        if (hasText(line) && !fileLines.contains(line)) {
            fileLines.add(line);
        }
    }

    public List<String> getFileLines() {
        return Collections.unmodifiableList(fileLines);
    }

    public void addRetryLine(String line) {
        if (hasText(line) && !retryLines.contains(line)) {
            retryLines.add(line);
        }
    }

    public List<String> getRetryLines() {
        return Collections.unmodifiableList(retryLines);
    }

    public void addProtocolLine(String protocol, String line) {
        if (!hasText(protocol) || !hasText(line)) {
            return;
        }
        List<String> lines = protocolLines.computeIfAbsent(protocol.trim().toUpperCase(), key -> new ArrayList<>());
        if (!lines.contains(line)) {
            lines.add(line);
        }
    }

    public List<String> getProtocolLines(String protocol) {
        if (protocol == null) {
            return Collections.emptyList();
        }
        List<String> lines = protocolLines.get(protocol.trim().toUpperCase());
        return lines == null ? Collections.emptyList() : Collections.unmodifiableList(lines);
    }

    public List<String> getProtocolNames() {
        return Collections.unmodifiableList(new ArrayList<>(protocolLines.keySet()));
    }

    public void addValidationLine(String line) {
        if (hasText(line) && !validationLines.contains(line)) {
            validationLines.add(line);
        }
    }

    public List<String> getValidationLines() {
        return Collections.unmodifiableList(validationLines);
    }

    public void addSummaryLine(String line) {
        if (hasText(line) && !summaryLines.contains(line)) {
            summaryLines.add(line);
        }
    }

    public List<String> getSummaryLines() {
        return Collections.unmodifiableList(summaryLines);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
