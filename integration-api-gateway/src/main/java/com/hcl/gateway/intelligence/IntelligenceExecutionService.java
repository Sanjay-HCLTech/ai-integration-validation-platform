package com.hcl.gateway.intelligence;

import com.hcl.ai.audit.IntelligenceAuditRecord;
import com.hcl.ai.audit.IntelligenceAuditStore;
import com.hcl.ai.audit.IntelligenceAuditSummary;
import com.hcl.ai.index.ObservabilityIndexService;
import com.hcl.ai.intent.IntelligenceIntent;
import com.hcl.ai.intent.IntelligenceIntentRequest;
import com.hcl.ai.intent.IntentExtractionService;
import com.hcl.ai.plan.ExecutionPlan;
import com.hcl.ai.plan.ExecutionPlanBuilder;
import com.hcl.ai.report.IntelligenceExecutionRow;
import com.hcl.ai.report.IntelligenceExecutionSnapshot;
import com.hcl.ai.report.IntelligenceReport;
import com.hcl.ai.report.IntelligenceReportService;
import com.hcl.gateway.console.ConsoleExecutionRequest;
import com.hcl.gateway.console.ConsoleExecutionResponse;
import com.hcl.gateway.console.ConsoleExecutionSummary;
import com.hcl.gateway.console.ConsoleResultRow;
import com.hcl.gateway.console.UnifiedExecutionConsoleService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Path;

@Service
public class IntelligenceExecutionService {

    private final IntentExtractionService intentExtractionService;
    private final ExecutionPlanBuilder executionPlanBuilder;
    private final IntelligenceReportService reportService;
    private final IntelligenceAuditStore auditStore;
    private final UnifiedExecutionConsoleService consoleService;
    private final ObservabilityIndexService observabilityIndexService;

    public IntelligenceExecutionService(
            IntentExtractionService intentExtractionService,
            ExecutionPlanBuilder executionPlanBuilder,
            IntelligenceReportService reportService,
            IntelligenceAuditStore auditStore,
            UnifiedExecutionConsoleService consoleService,
            ObservabilityIndexService observabilityIndexService) {
        this.intentExtractionService = intentExtractionService;
        this.executionPlanBuilder = executionPlanBuilder;
        this.reportService = reportService;
        this.auditStore = auditStore;
        this.consoleService = consoleService;
        this.observabilityIndexService = observabilityIndexService;
    }

    public IntelligenceExecutionResponse execute(IntelligenceExecutionRequest request) {
        IntelligenceExecutionRequest safeRequest = request == null ? new IntelligenceExecutionRequest() : request;
        IntelligenceIntentRequest intentRequest = intentRequest(safeRequest);
        IntelligenceIntent intent = intentExtractionService.extract(intentRequest);
        ExecutionPlan plan = executionPlanBuilder.build(intent, intentRequest);
        ConsoleExecutionRequest consoleRequest = consoleRequest(safeRequest, plan);
        ConsoleExecutionResponse execution = consoleService.executeAll(consoleRequest);
        IntelligenceReport report = reportService.build(intent, plan, snapshot(execution));
        auditStore.save(execution.getExecutionId(), intentRequest, intent, plan, execution, report);
        return response(execution.getExecutionId(), intent, plan, execution, report);
    }

    public IntelligenceExecutionResponse intent(IntelligenceExecutionRequest request) {
        IntelligenceExecutionRequest safeRequest = request == null ? new IntelligenceExecutionRequest() : request;
        IntelligenceIntentRequest intentRequest = intentRequest(safeRequest);
        IntelligenceIntent intent = intentExtractionService.extract(intentRequest);
        ExecutionPlan plan = executionPlanBuilder.build(intent, intentRequest);
        return response(null, intent, plan, null, null);
    }

    public IntelligenceExecutionResponse replay(String executionId) {
        ConsoleExecutionResponse previous = consoleService.execution(executionId);
        List<ConsoleResultRow> failedRows = failedRows(previous);
        if (failedRows.isEmpty()) {
            IntelligenceReport report = report(executionId)
                    .orElseGet(() -> reportService.build(null, null, snapshot(previous)));
            return response(executionId, null, null, previous, report);
        }

        ConsoleExecutionRequest replayRequest = replayRequest(failedRows);
        ConsoleExecutionResponse execution = consoleService.executeAll(replayRequest);
        IntelligenceIntentRequest intentRequest = new IntelligenceIntentRequest();
        intentRequest.setPrompt("Replay failed execution " + executionId);
        intentRequest.setBookingId(firstBookingId(failedRows));
        IntelligenceIntent intent = intentExtractionService.extract(intentRequest);
        ExecutionPlan plan = executionPlanBuilder.build(intent, intentRequest);
        IntelligenceReport report = reportService.build(intent, plan, snapshot(execution));
        auditStore.save(execution.getExecutionId(), intentRequest, intent, plan, execution, report);
        return response(execution.getExecutionId(), intent, plan, execution, report);
    }

    public Optional<IntelligenceReport> report(String executionId) {
        return auditStore.get(executionId).map(IntelligenceAuditRecord::getResult);
    }

    public Optional<IntelligenceAuditRecord> audit(String executionId) {
        return auditStore.get(executionId);
    }

    public List<IntelligenceAuditSummary> audits(int limit) {
        return auditStore.recent(limit);
    }

    public Optional<Path> evidenceFile(String executionId, String fileName) {
        return report(executionId)
                .map(IntelligenceReport::getExecutionSummary)
                .flatMap(summary -> observabilityIndexService.evidenceFile(
                        summary == null ? null : summary.getBookingId(), fileName));
    }

    public String logs(String executionId) {
        return consoleService.logs(executionId);
    }

    private IntelligenceIntentRequest intentRequest(IntelligenceExecutionRequest request) {
        IntelligenceIntentRequest intentRequest = new IntelligenceIntentRequest();
        intentRequest.setMode(request.getMode());
        intentRequest.setPrompt(request.getPrompt());
        intentRequest.setBookingId(request.getBookingId());
        intentRequest.setEnv(request.getEnv());
        intentRequest.setSystems(copy(request.getSystems()));
        intentRequest.setFlowTypes(copy(request.getFlowTypes()));
        intentRequest.setServices(copy(request.getServices()));
        intentRequest.setPayloadMode(request.getPayloadMode());
        intentRequest.setRunAllServices(request.isRunAllServices());
        intentRequest.setParallel(request.isParallel());
        intentRequest.setTraceEnabled(request.isTraceEnabled());
        return intentRequest;
    }

    private ConsoleExecutionRequest consoleRequest(IntelligenceExecutionRequest request, ExecutionPlan plan) {
        ConsoleExecutionRequest consoleRequest = new ConsoleExecutionRequest();
        consoleRequest.setExecutionId(firstText(request.getExecutionId(), UUID.randomUUID().toString()));
        consoleRequest.setEnv(plan.getEnv());
        consoleRequest.setSystems(copy(plan.getSystems()));
        consoleRequest.setFlowTypes(copy(plan.getFlowTypes()));
        consoleRequest.setServices(copy(plan.getServices()));
        consoleRequest.setPayloadMode(plan.getPayloadMode());
        consoleRequest.setBookingId(plan.getBookingId());
        consoleRequest.setBookingIdExplicit(request.isBookingIdExplicit());
        consoleRequest.setRunAllServices(request.isRunAllServices());
        consoleRequest.setParallel(plan.isParallel());
        consoleRequest.setTraceEnabled(plan.isTraceEnabled());
        return consoleRequest;
    }

    private ConsoleExecutionRequest replayRequest(List<ConsoleResultRow> failedRows) {
        ConsoleExecutionRequest request = new ConsoleExecutionRequest();
        request.setExecutionId(UUID.randomUUID().toString());
        request.setEnv(firstValue(rowValues(failedRows, "env"), "ST5"));
        request.setSystems(rowValues(failedRows, "system"));
        request.setFlowTypes(rowValues(failedRows, "flow"));
        request.setServices(rowValues(failedRows, "service"));
        request.setBookingId(firstBookingId(failedRows));
        request.setPayloadMode("AUTO");
        request.setParallel(true);
        request.setTraceEnabled(true);
        return request;
    }

    private IntelligenceExecutionSnapshot snapshot(ConsoleExecutionResponse response) {
        IntelligenceExecutionSnapshot snapshot = new IntelligenceExecutionSnapshot();
        if (response == null) {
            return snapshot;
        }
        snapshot.setExecutionId(response.getExecutionId());
        snapshot.setStatus(response.getExecutionStatus());
        ConsoleExecutionSummary summary = response.getSummary();
        if (summary != null) {
            snapshot.setTotal(summary.getTotal());
            snapshot.setPass(summary.getPass());
            snapshot.setFail(summary.getFail());
            snapshot.setDurationMs(summary.getDurationMs());
        }
        List<IntelligenceExecutionRow> rows = new ArrayList<>();
        if (response.getRows() != null) {
            for (ConsoleResultRow row : response.getRows()) {
                rows.add(row(row));
            }
        }
        snapshot.setRows(rows);
        return snapshot;
    }

    private IntelligenceExecutionRow row(ConsoleResultRow row) {
        IntelligenceExecutionRow target = new IntelligenceExecutionRow();
        target.setService(row.getService());
        target.setFlow(row.getFlow());
        target.setSystem(row.getSystem());
        target.setEnv(row.getEnv());
        target.setStatus(row.getStatus());
        target.setBookingId(row.getBookingId());
        target.setTrackingId(row.getTrackingId());
        target.setCorrId(row.getCorrId());
        target.setJobId(row.getJobId());
        target.setTimeMs(row.getTimeMs());
        target.setMessage(row.getMessage());
        target.setTrace(copy(row.getTrace()));
        target.setAssertions(copy(row.getAssertions()));
        target.setTimeline(copy(row.getTimeline()));
        return target;
    }

    private IntelligenceExecutionResponse response(
            String executionId,
            IntelligenceIntent intent,
            ExecutionPlan plan,
            ConsoleExecutionResponse execution,
            IntelligenceReport report) {
        IntelligenceExecutionResponse response = new IntelligenceExecutionResponse();
        response.setExecutionId(executionId);
        response.setIntent(intent);
        response.setPlan(plan);
        response.setExecution(execution);
        response.setReport(report);
        return response;
    }

    private List<ConsoleResultRow> failedRows(ConsoleExecutionResponse response) {
        if (response == null || response.getRows() == null) {
            return Collections.emptyList();
        }
        List<ConsoleResultRow> rows = new ArrayList<>();
        for (ConsoleResultRow row : response.getRows()) {
            if (row != null && "FAIL".equalsIgnoreCase(row.getStatus())) {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<String> rowValues(List<ConsoleResultRow> rows, String field) {
        List<String> values = new ArrayList<>();
        for (ConsoleResultRow row : rows) {
            String value = rowValue(row, field);
            if (hasText(value) && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private String rowValue(ConsoleResultRow row, String field) {
        if (row == null) {
            return null;
        }
        if ("env".equals(field)) {
            return row.getEnv();
        }
        if ("system".equals(field)) {
            return row.getSystem();
        }
        if ("flow".equals(field)) {
            return row.getFlow();
        }
        if ("service".equals(field)) {
            return row.getService();
        }
        return null;
    }

    private String firstBookingId(List<ConsoleResultRow> rows) {
        for (ConsoleResultRow row : rows) {
            if (row != null && hasText(row.getBookingId()) && !"NA".equalsIgnoreCase(row.getBookingId())) {
                return row.getBookingId();
            }
        }
        return null;
    }

    private String firstValue(List<String> values, String fallback) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return fallback;
    }

    private List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
