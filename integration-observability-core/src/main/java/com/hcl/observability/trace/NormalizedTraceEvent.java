package com.hcl.observability.trace;

import java.time.OffsetDateTime;

public class NormalizedTraceEvent extends CommonTraceRecord {

    private TracePhase phase;
    private String operation;
    private String fromEndpoint;
    private String toEndpoint;
    private String rawLine;

    public TracePhase getPhase() {
        return phase;
    }

    public void setPhase(TracePhase phase) {
        this.phase = phase;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getFromEndpoint() {
        return fromEndpoint;
    }

    public void setFromEndpoint(String fromEndpoint) {
        this.fromEndpoint = fromEndpoint;
    }

    public String getToEndpoint() {
        return toEndpoint;
    }

    public void setToEndpoint(String toEndpoint) {
        this.toEndpoint = toEndpoint;
    }

    public String getRawLine() {
        return rawLine;
    }

    public void setRawLine(String rawLine) {
        this.rawLine = rawLine;
    }

    public static NormalizedTraceEvent of(
            String bookingId,
            String corrId,
            String jobId,
            TraceSystem system,
            TraceProtocol protocol,
            TracePhase phase,
            String operation,
            OffsetDateTime timestamp,
            TraceStatus status,
            String rawLine) {
        NormalizedTraceEvent event = new NormalizedTraceEvent();
        event.setBookingId(bookingId);
        event.setCorrId(corrId);
        event.setJobId(jobId);
        event.setSystem(system);
        event.setProtocol(protocol);
        event.setPhase(phase);
        event.setOperation(operation);
        event.setTimestamp(timestamp);
        event.setStatus(status);
        event.setRawLine(rawLine);
        return event;
    }
}
