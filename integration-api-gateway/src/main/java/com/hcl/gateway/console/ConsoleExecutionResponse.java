package com.hcl.gateway.console;

import java.util.ArrayList;
import java.util.List;

public class ConsoleExecutionResponse {

    private String executionId;
    private String executionStatus;
    private ConsoleExecutionSummary summary = new ConsoleExecutionSummary();
    private List<ConsoleResultRow> rows = new ArrayList<>();

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(String executionStatus) {
        this.executionStatus = executionStatus;
    }

    public ConsoleExecutionSummary getSummary() {
        return summary;
    }

    public void setSummary(ConsoleExecutionSummary summary) {
        this.summary = summary;
    }

    public List<ConsoleResultRow> getRows() {
        return rows;
    }

    public void setRows(List<ConsoleResultRow> rows) {
        this.rows = rows;
    }
}
