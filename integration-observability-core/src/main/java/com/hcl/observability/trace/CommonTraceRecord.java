package com.hcl.observability.trace;

import java.time.OffsetDateTime;

public class CommonTraceRecord {

    private String bookingId;
    private String corrId;
    private String jobId;
    private TraceSystem system;
    private TraceProtocol protocol;
    private OffsetDateTime timestamp;
    private TraceStatus status;

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

    public TraceSystem getSystem() {
        return system;
    }

    public void setSystem(TraceSystem system) {
        this.system = system;
    }

    public TraceProtocol getProtocol() {
        return protocol;
    }

    public void setProtocol(TraceProtocol protocol) {
        this.protocol = protocol;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public TraceStatus getStatus() {
        return status;
    }

    public void setStatus(TraceStatus status) {
        this.status = status;
    }
}
