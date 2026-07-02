package com.hcl.ai.audit;

import com.hcl.ai.intent.IntelligenceIntent;
import com.hcl.ai.intent.IntelligenceIntentRequest;
import com.hcl.ai.plan.ExecutionPlan;
import com.hcl.ai.report.IntelligenceReport;

import java.time.Instant;

public class IntelligenceAuditRecord {

    private String executionId;
    private Instant createdAt;
    private String prompt;
    private IntelligenceIntentRequest request;
    private IntelligenceIntent intent;
    private ExecutionPlan plan;
    private Object executionResult;
    private IntelligenceReport result;

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

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public IntelligenceIntentRequest getRequest() {
        return request;
    }

    public void setRequest(IntelligenceIntentRequest request) {
        this.request = request;
    }

    public IntelligenceIntent getIntent() {
        return intent;
    }

    public void setIntent(IntelligenceIntent intent) {
        this.intent = intent;
    }

    public ExecutionPlan getPlan() {
        return plan;
    }

    public void setPlan(ExecutionPlan plan) {
        this.plan = plan;
    }

    public Object getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(Object executionResult) {
        this.executionResult = executionResult;
    }

    public IntelligenceReport getResult() {
        return result;
    }

    public void setResult(IntelligenceReport result) {
        this.result = result;
    }
}
