package com.hcl.execution.core;

import com.hcl.execution.model.TestCase;

public class ExecutionContext {

    private final ExecutionRequest request;
    private final TestCase testCase;

    public ExecutionContext(ExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ExecutionRequest is required");
        }
        if (request.getFlowType() == null) {
            throw new IllegalArgumentException("FlowType is required");
        }
        this.request = request;
        this.testCase = request.getTestCase() == null ? createTestCase(request) : request.getTestCase();
    }

    public ExecutionRequest getRequest() {
        return request;
    }

    public TestCase getTestCase() {
        return testCase;
    }

    public FlowType getFlowType() {
        return request.getFlowType();
    }

    public ExecutionMode getExecutionMode() {
        return request.effectiveMode();
    }

    private TestCase createTestCase(ExecutionRequest request) {
        TestCase testCase = new TestCase();
        testCase.setBookingId(request.getBookingId());
        testCase.setPayload(request.getPayloadPath());
        testCase.setExecutionMode(request.effectiveMode().name());
        testCase.setEnv(request.getEnv());
        testCase.setDownstreamSystem(request.getSystem());
        return testCase;
    }
}
