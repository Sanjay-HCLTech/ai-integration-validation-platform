package com.hcl.execution.core;

import com.hcl.execution.adapter.TriggerAdapter;
import com.hcl.execution.adapter.TriggerAdapterRegistry;
import com.hcl.execution.adapter.TriggerResult;
import com.hcl.observability.trace.UnifiedTraceContext;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import com.hcl.observability.trace.UnifiedTraceReportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ExecutionEngine {

    private final TriggerAdapterRegistry adapterRegistry;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final UnifiedTraceReportService traceReportService;
    private final boolean unifiedTraceReportEnabled;
    private final ExecutionReportFormatter reportFormatter = new ExecutionReportFormatter();

    public ExecutionEngine(
            TriggerAdapterRegistry adapterRegistry,
            UnifiedTraceContextHolder traceContextHolder,
            UnifiedTraceReportService traceReportService,
            @Value("${unified.trace.report.enabled}") boolean unifiedTraceReportEnabled) {
        this.adapterRegistry = adapterRegistry;
        this.traceContextHolder = traceContextHolder;
        this.traceReportService = traceReportService;
        this.unifiedTraceReportEnabled = unifiedTraceReportEnabled;
    }

    public ExecutionReport execute(ExecutionRequest request) {
        ExecutionContext context = new ExecutionContext(request);
        long startNanos = System.nanoTime();
        String executionStatus = "UNKNOWN";
        beginTrace(context);
        printExecutionStart(context);
        try {
            TriggerAdapter adapter = adapterRegistry.get(context.getFlowType());
            TriggerResult triggerResult = adapter.trigger(context);
            if (triggerResult.getTimeMs() <= 0) {
                triggerResult.setTimeMs(elapsedMs(startNanos));
            }
            ExecutionReport report = report(context, triggerResult, null);
            executionStatus = statusValue(report);
            printUnifiedTraceReport(context, elapsedMs(startNanos));
            System.out.print(reportFormatter.format(report));
            printExecutionComplete(context, executionStatus, elapsedMs(startNanos));
            return report;
        } catch (Exception e) {
            TriggerResult triggerResult = new TriggerResult();
            triggerResult.setFlowType(context.getFlowType());
            triggerResult.setExecutionMode(context.getExecutionMode());
            triggerResult.setSuccess(false);
            triggerResult.setStatus("ERROR");
            triggerResult.setMessage(e.getMessage());
            triggerResult.setTimeMs(elapsedMs(startNanos));

            ExecutionReport report = report(context, triggerResult, e.getMessage());
            executionStatus = "ERROR";
            printUnifiedTraceReport(context, elapsedMs(startNanos));
            System.out.print(reportFormatter.format(report));
            printExecutionComplete(context, executionStatus, elapsedMs(startNanos));
            return report;
        } finally {
            System.out.println("==================== EXECUTION END Status=" + executionStatus + " ======================");
            traceContextHolder.clear();
        }
    }

    private void beginTrace(ExecutionContext context) {
        ExecutionRequest request = context.getRequest();
        String bookingId = context.getFlowType() == FlowType.REST ? null : firstText(request.getBookingId(),
                context.getTestCase() == null ? null : context.getTestCase().getBookingId());
        String testCaseId = context.getTestCase() == null ? "TC_POST_EXECUTE" : context.getTestCase().getTestCaseId();
        traceContextHolder.begin(firstText(testCaseId, "TC_POST_EXECUTE"), bookingId);
        traceContextHolder.currentOrCreate().setApiEndpoint("/execute/executeAll");
        traceContextHolder.currentOrCreate().setApiStatus("200");
    }

    private void printExecutionStart(ExecutionContext context) {
        String testCaseId = context.getTestCase() == null ? "TC_POST_EXECUTE" : context.getTestCase().getTestCaseId();
        String bookingId = traceContextHolder.currentOrCreate().getBookingId();
        System.out.println("==================== EXECUTION START ====================");
        System.out.println("TestCase=" + value(firstText(testCaseId, "TC_POST_EXECUTE"))
                + " | BookingID=" + value(bookingId));
        System.out.println("[PROGRESS] Execution started TestCase=" + value(firstText(testCaseId, "TC_POST_EXECUTE"))
                + " BookingID=" + value(bookingId)
                + " Endpoint=/execute/executeAll");
    }

    private void printUnifiedTraceReport(ExecutionContext context, long totalMs) {
        if (!unifiedTraceReportEnabled) {
            return;
        }
        UnifiedTraceContext traceContext = traceContextHolder.current();
        if (traceContext == null) {
            return;
        }
        traceContext.setTotalTimeMs(totalMs);
        System.out.println();
        System.out.println("==================== UNIFIED TRACE REPORT ====================");
        System.out.print(traceReportService.buildReport(traceContext));
        System.out.println("==================== UNIFIED TRACE END =======================");
    }

    private void printExecutionComplete(ExecutionContext context, String status, long totalMs) {
        String testCaseId = context.getTestCase() == null ? "TC_POST_EXECUTE" : context.getTestCase().getTestCaseId();
        String bookingId = traceContextHolder.currentOrCreate().getBookingId();
        System.out.println("[PROGRESS] Execution completed TestCase=" + value(firstText(testCaseId, "TC_POST_EXECUTE"))
                + " BookingID=" + value(bookingId)
                + " Status=" + value(status)
                + " TotalTimeMs=" + totalMs);
    }

    private ExecutionReport report(ExecutionContext context, TriggerResult triggerResult, String message) {
        enrichTraceMetadata(triggerResult);
        ExecutionReport report = new ExecutionReport();
        report.setRequest(context.getRequest());
        report.setTriggerResult(triggerResult);
        report.setTraceContext(trace(context, triggerResult));
        report.setStatus(triggerResult.isSuccess() ? ExecutionStatus.PASS : ExecutionStatus.FAIL);
        report.setMessage(message == null ? triggerResult.getMessage() : message);
        report.setValidationComplete(validationComplete(triggerResult));
        return report;
    }

    private void enrichTraceMetadata(TriggerResult triggerResult) {
        if (triggerResult == null) {
            return;
        }
        UnifiedTraceContext unifiedTraceContext = traceContextHolder.current();
        if (unifiedTraceContext == null) {
            return;
        }
        if (!unifiedTraceContext.getFileLines().isEmpty()) {
            triggerResult.putMetadata("TRACE_FILE_LINES", String.join("\n", unifiedTraceContext.getFileLines()));
        }
        if (!unifiedTraceContext.getRetryLines().isEmpty()) {
            triggerResult.putMetadata("TRACE_RETRY_LINES", String.join("\n", unifiedTraceContext.getRetryLines()));
        }
    }

    private boolean validationComplete(TriggerResult triggerResult) {
        return triggerResult != null
                && "true".equalsIgnoreCase(triggerResult.getMetadata().get("VALIDATION_COMPLETE"));
    }

    private String statusValue(ExecutionReport report) {
        if (report == null || report.getStatus() == null) {
            return "UNKNOWN";
        }
        return report.getStatus() == ExecutionStatus.PASS ? "SUCCESS" : report.getStatus().name();
    }

    private TraceContext trace(ExecutionContext context, TriggerResult triggerResult) {
        TraceContext trace = new TraceContext();
        ExecutionRequest request = context.getRequest();
        trace.setBookingId(context.getFlowType() == FlowType.REST
                ? null
                : firstText(request.getBookingId(), context.getTestCase().getBookingId()));
        trace.setCorrId(firstText(triggerResult.getCorrId(), request.getCorrId()));
        trace.setJobId(firstText(triggerResult.getJobId(), request.getJobId()));
        return trace;
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
