package com.hcl.gateway.service;

import com.hcl.execution.executor.TestCaseExecutorService;
import com.hcl.execution.model.ExecutionResult;
import com.hcl.execution.model.TestCase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OrchestratorService {

    private final TestCaseExecutorService executorService;
    private final boolean unifiedTraceReportEnabled;

    public OrchestratorService(
            TestCaseExecutorService executorService,
            @Value("${unified.trace.report.enabled:false}") boolean unifiedTraceReportEnabled) {
        this.executorService = executorService;
        this.unifiedTraceReportEnabled = unifiedTraceReportEnabled;
    }

    public ExecutionResult execute(TestCase testCase) {

        System.out.println("Orchestrator: Starting execution...");

        // ✅ Correct call
        ExecutionResult result = executorService.execute(testCase);

        if (!unifiedTraceReportEnabled) {
            System.out.println("Orchestrator: Execution completed");
        }

        return result;
    }
}
