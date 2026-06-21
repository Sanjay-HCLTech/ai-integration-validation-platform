package com.hcl.execution.core;

import com.hcl.execution.adapter.TriggerResult;

public class ExecutionReport {

    private ExecutionRequest request;
    private TriggerResult triggerResult;
    private TraceContext traceContext;
    private ExecutionStatus status;
    private String message;
    private boolean validationComplete;

    public ExecutionRequest getRequest() {
        return request;
    }

    public void setRequest(ExecutionRequest request) {
        this.request = request;
    }

    public TriggerResult getTriggerResult() {
        return triggerResult;
    }

    public void setTriggerResult(TriggerResult triggerResult) {
        this.triggerResult = triggerResult;
    }

    public TraceContext getTraceContext() {
        return traceContext;
    }

    public void setTraceContext(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
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
}
