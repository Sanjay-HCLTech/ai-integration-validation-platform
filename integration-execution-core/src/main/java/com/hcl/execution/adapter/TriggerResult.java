package com.hcl.execution.adapter;

import com.hcl.execution.core.ExecutionMode;
import com.hcl.execution.core.FlowType;

import java.util.LinkedHashMap;
import java.util.Map;

public class TriggerResult {

    private FlowType flowType;
    private ExecutionMode executionMode;
    private boolean success;
    private String status;
    private String corrId;
    private String jobId;
    private String messageId;
    private String responseBody;
    private Integer httpStatus;
    private long timeMs;
    private String message;
    private final Map<String, String> metadata = new LinkedHashMap<>();

    public FlowType getFlowType() {
        return flowType;
    }

    public void setFlowType(FlowType flowType) {
        this.flowType = flowType;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public void setTimeMs(long timeMs) {
        this.timeMs = timeMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void putMetadata(String key, String value) {
        if (key != null && !key.trim().isEmpty() && value != null && !value.trim().isEmpty()) {
            metadata.put(key.trim(), value.trim());
        }
    }
}
