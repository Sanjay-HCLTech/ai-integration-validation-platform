package com.hcl.execution.jms;

import com.hcl.observability.trace.UnifiedTraceContext;

public class JmsProcessingResult {

    private String bookingId;
    private String corrId;
    private String jobId;
    private String sourceSystem;
    private String mode;
    private String status;
    private String message;
    private int logLinesFound;
    private boolean partialTrace;
    private long timestamp;
    private transient UnifiedTraceContext traceContext;

    public static JmsProcessingResult fromMessage(JmsMessage message, String mode, String status, String detail) {
        JmsProcessingResult result = new JmsProcessingResult();
        result.setBookingId(message.getBookingId());
        result.setCorrId(message.getCorrId());
        result.setJobId(message.getJobId());
        result.setSourceSystem(message.getSourceSystem());
        result.setMode(mode);
        result.setStatus(status);
        result.setMessage(detail);
        result.setTimestamp(System.currentTimeMillis());
        return result;
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

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getLogLinesFound() {
        return logLinesFound;
    }

    public void setLogLinesFound(int logLinesFound) {
        this.logLinesFound = logLinesFound;
    }

    public boolean isPartialTrace() {
        return partialTrace;
    }

    public void setPartialTrace(boolean partialTrace) {
        this.partialTrace = partialTrace;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public UnifiedTraceContext traceContext() {
        return traceContext;
    }

    public void setTraceContext(UnifiedTraceContext traceContext) {
        this.traceContext = traceContext;
    }
}
