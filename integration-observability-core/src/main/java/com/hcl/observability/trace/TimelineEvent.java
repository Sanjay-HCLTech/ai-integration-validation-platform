package com.hcl.observability.trace;

import java.time.OffsetDateTime;

public class TimelineEvent {

    private OffsetDateTime timestamp;
    private String system;
    private String operation;
    private TracePhase phase;
    private TraceStatus status;
    private String note;
    private long latencyMsFromPrevious;
    private int sequence;
    private String sourceFile;
    private String rawLine;

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public TracePhase getPhase() {
        return phase;
    }

    public void setPhase(TracePhase phase) {
        this.phase = phase;
    }

    public TraceStatus getStatus() {
        return status;
    }

    public void setStatus(TraceStatus status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public long getLatencyMsFromPrevious() {
        return latencyMsFromPrevious;
    }

    public void setLatencyMsFromPrevious(long latencyMsFromPrevious) {
        this.latencyMsFromPrevious = latencyMsFromPrevious;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public String getRawLine() {
        return rawLine;
    }

    public void setRawLine(String rawLine) {
        this.rawLine = rawLine;
    }
}
