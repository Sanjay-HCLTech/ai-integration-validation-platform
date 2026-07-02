package com.hcl.execution.rest;

public class RestTriggerOutcome {

    private String endpoint;
    private String method;
    private String collection;
    private String brand;
    private String payloadSource;
    private Integer httpStatus;
    private String trackingId;
    private String responseBody;
    private String message;
    private RestValidationResult validationResult;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
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

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(String trackingId) {
        this.trackingId = trackingId;
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

    public RestValidationResult getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(RestValidationResult validationResult) {
        this.validationResult = validationResult;
    }

    public boolean isSuccess() {
        return validationResult != null && validationResult.isPassed();
    }
}
