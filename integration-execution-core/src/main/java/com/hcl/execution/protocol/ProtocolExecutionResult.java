package com.hcl.execution.protocol;

public class ProtocolExecutionResult {

    private String protocol;
    private String mode;
    private String status;
    private String jobId;
    private String corrId;
    private String trackingId;
    private Integer httpStatus;
    private String responseBody;
    private long latencyMs;
    private String message;
    private boolean validationComplete;
    private String processStatus;
    private String downstreamStatus;
    private String errorFound;
    private String payloadSource;
    private String endpointOrDestination;

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
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

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getCorrId() {
        return corrId;
    }

    public void setCorrId(String corrId) {
        this.corrId = corrId;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(String trackingId) {
        this.trackingId = trackingId;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isValidationComplete() {
        return validationComplete;
    }

    public void setValidationComplete(boolean validationComplete) {
        this.validationComplete = validationComplete;
    }

    public String getProcessStatus() {
        return processStatus;
    }

    public void setProcessStatus(String processStatus) {
        this.processStatus = processStatus;
    }

    public String getDownstreamStatus() {
        return downstreamStatus;
    }

    public void setDownstreamStatus(String downstreamStatus) {
        this.downstreamStatus = downstreamStatus;
    }

    public String getErrorFound() {
        return errorFound;
    }

    public void setErrorFound(String errorFound) {
        this.errorFound = errorFound;
    }

    public String getPayloadSource() {
        return payloadSource;
    }

    public void setPayloadSource(String payloadSource) {
        this.payloadSource = payloadSource;
    }

    public String getEndpointOrDestination() {
        return endpointOrDestination;
    }

    public void setEndpointOrDestination(String endpointOrDestination) {
        this.endpointOrDestination = endpointOrDestination;
    }
}
