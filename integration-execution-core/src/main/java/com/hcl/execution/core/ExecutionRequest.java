package com.hcl.execution.core;

import com.hcl.execution.model.TestCase;

import java.util.LinkedHashMap;
import java.util.Map;

public class ExecutionRequest {

    private FlowType flowType;
    private ExecutionMode executionMode;
    private String env;
    private String system;
    private String trigger;
    private String payloadPath;
    private String bookingId;
    private String corrId;
    private String jobId;
    private TestCase testCase;
    private final Map<String, String> attributes = new LinkedHashMap<>();

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

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public String getPayloadPath() {
        return payloadPath;
    }

    public void setPayloadPath(String payloadPath) {
        this.payloadPath = payloadPath;
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

    public TestCase getTestCase() {
        return testCase;
    }

    public void setTestCase(TestCase testCase) {
        this.testCase = testCase;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void putAttribute(String key, String value) {
        if (hasText(key) && hasText(value)) {
            attributes.put(key.trim(), value.trim());
        }
    }

    public ExecutionMode effectiveMode() {
        if (executionMode != null) {
            return executionMode;
        }
        if (flowType == FlowType.JMS || flowType == FlowType.RABBIT) {
            return ExecutionMode.ASYNC;
        }
        return ExecutionMode.SYNC;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
