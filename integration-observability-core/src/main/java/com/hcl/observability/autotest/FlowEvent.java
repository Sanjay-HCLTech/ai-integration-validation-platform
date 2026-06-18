package com.hcl.observability.autotest;

import java.util.Objects;

public class FlowEvent {

    private String system;
    private String phase;
    private String operation;
    private String corrId;
    private String jobId;
    private long timestamp;
    private String status;
    private String rawLine;

    public FlowEvent() {
    }

    public FlowEvent(
            String system,
            String phase,
            String operation,
            String corrId,
            String jobId,
            long timestamp,
            String status,
            String rawLine) {
        this.system = system;
        this.phase = phase;
        this.operation = operation;
        this.corrId = corrId;
        this.jobId = jobId;
        this.timestamp = timestamp;
        this.status = status;
        this.rawLine = rawLine;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
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

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRawLine() {
        return rawLine;
    }

    public void setRawLine(String rawLine) {
        this.rawLine = rawLine;
    }

    public String dedupKey() {
        return value(system) + "|" + value(phase) + "|" + value(operation) + "|"
                + value(corrId) + "|" + value(jobId) + "|" + timestamp + "|" + value(status);
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowEvent)) {
            return false;
        }
        FlowEvent event = (FlowEvent) other;
        return Objects.equals(dedupKey(), event.dedupKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(dedupKey());
    }
}
