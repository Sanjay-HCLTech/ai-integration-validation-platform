package com.hcl.ai.report;

import java.util.ArrayList;
import java.util.List;

public class IntelligenceExecutionSnapshot {

    private String executionId;
    private String status;
    private int total;
    private int pass;
    private int fail;
    private long durationMs;
    private List<IntelligenceExecutionRow> rows = new ArrayList<>();

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPass() {
        return pass;
    }

    public void setPass(int pass) {
        this.pass = pass;
    }

    public int getFail() {
        return fail;
    }

    public void setFail(int fail) {
        this.fail = fail;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public List<IntelligenceExecutionRow> getRows() {
        return rows;
    }

    public void setRows(List<IntelligenceExecutionRow> rows) {
        this.rows = rows;
    }
}
