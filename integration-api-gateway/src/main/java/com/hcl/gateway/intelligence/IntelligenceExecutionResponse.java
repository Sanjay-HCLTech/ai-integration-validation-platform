package com.hcl.gateway.intelligence;

import com.hcl.ai.intent.IntelligenceIntent;
import com.hcl.ai.plan.ExecutionPlan;
import com.hcl.ai.report.IntelligenceReport;
import com.hcl.gateway.console.ConsoleExecutionResponse;

public class IntelligenceExecutionResponse {

    private String executionId;
    private IntelligenceIntent intent;
    private ExecutionPlan plan;
    private ConsoleExecutionResponse execution;
    private IntelligenceReport report;

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
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

    public ConsoleExecutionResponse getExecution() {
        return execution;
    }

    public void setExecution(ConsoleExecutionResponse execution) {
        this.execution = execution;
    }

    public IntelligenceReport getReport() {
        return report;
    }

    public void setReport(IntelligenceReport report) {
        this.report = report;
    }
}
