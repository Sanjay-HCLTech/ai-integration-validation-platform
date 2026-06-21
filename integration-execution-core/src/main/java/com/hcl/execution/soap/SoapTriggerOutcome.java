package com.hcl.execution.soap;

public class SoapTriggerOutcome {

    private String protocol;
    private String mode;
    private String endpoint;
    private String payloadSource;
    private Integer httpStatus;
    private String responseBody;
    private String message;
    private SoapValidationResult validationResult;
    private boolean validationComplete;
    private String processStatus;
    private String downstreamStatus;
    private String errorFound;
    private String corrId;
    private String jobId;

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

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPayloadSource() {
        return payloadSource;
    }

    public void setPayloadSource(String payloadSource) {
        this.payloadSource = payloadSource;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public SoapValidationResult getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(SoapValidationResult validationResult) {
        this.validationResult = validationResult;
    }

    public boolean isSuccess() {
        return validationResult == null || validationResult.isPassed();
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
}
