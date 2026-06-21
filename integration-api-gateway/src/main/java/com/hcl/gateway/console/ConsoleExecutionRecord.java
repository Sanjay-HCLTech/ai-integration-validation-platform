package com.hcl.gateway.console;

import java.time.Instant;

public class ConsoleExecutionRecord {

    private String executionId;
    private Instant createdAt;
    private String status;
    private ConsoleExecutionRequest request;
    private ConsoleExecutionResponse response;

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public ConsoleExecutionRequest getRequest() {
        return request;
    }

    public void setRequest(ConsoleExecutionRequest request) {
        this.request = request;
    }

    public ConsoleExecutionResponse getResponse() {
        return response;
    }

    public void setResponse(ConsoleExecutionResponse response) {
        this.response = response;
    }
}
