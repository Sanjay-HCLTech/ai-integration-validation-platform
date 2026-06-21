package com.hcl.gateway.console;

import com.hcl.execution.adapter.TriggerResult;
import com.hcl.execution.core.ExecutionEngine;
import com.hcl.execution.core.ExecutionMode;
import com.hcl.execution.core.ExecutionReport;
import com.hcl.execution.core.ExecutionRequest;
import com.hcl.execution.core.ExecutionStatus;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.core.TraceContext;
import com.hcl.execution.model.TestCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class UnifiedExecutionConsoleService {

    private static final List<String> DEFAULT_SYSTEMS = Collections.singletonList("DMS");
    private static final List<String> DEFAULT_FLOWS = Collections.singletonList("JMS");
    private static final List<String> DEFAULT_SERVICES = Arrays.asList("BookingDetails", "AccomOffers");

    private final ExecutionEngine executionEngine;
    private final ConsoleExecutionHistoryStore historyStore;
    private final long parallelTimeoutMs;
    private final String defaultBookingId;

    public UnifiedExecutionConsoleService(
            ExecutionEngine executionEngine,
            ConsoleExecutionHistoryStore historyStore,
            @Value("${console.execution.parallel.timeout.ms:60000}") long parallelTimeoutMs,
            @Value("${console.execution.default.booking-id:31835146}") String defaultBookingId) {
        this.executionEngine = executionEngine;
        this.historyStore = historyStore;
        this.parallelTimeoutMs = Math.max(0, parallelTimeoutMs);
        this.defaultBookingId = firstText(defaultBookingId, "31835146");
    }

    public ConsoleExecutionResponse executeAll(ConsoleExecutionRequest request) {
        ConsoleExecutionRequest safeRequest = request == null ? new ConsoleExecutionRequest() : request;
        ConsoleExecutionRecord record = historyStore.create(safeRequest);
        long startNanos = System.nanoTime();
        try {
            List<ExecutionRequest> matrix = matrix(safeRequest);
            List<ConsoleResultRow> rows = safeRequest.isParallel()
                    ? executeParallel(record.getExecutionId(), matrix)
                    : executeSequential(record.getExecutionId(), matrix);

            ConsoleExecutionResponse response = new ConsoleExecutionResponse();
            response.setExecutionId(record.getExecutionId());
            response.setRows(rows);
            response.setSummary(summary(rows, elapsedMs(startNanos)));
            historyStore.complete(record.getExecutionId(), response);
            return response;
        } catch (RuntimeException e) {
            ConsoleExecutionResponse response = failedResponse(record, e, elapsedMs(startNanos));
            historyStore.fail(record.getExecutionId(), response);
            return response;
        }
    }

    public boolean stop(String executionId) {
        return historyStore.stop(executionId);
    }

    public boolean exists(String executionId) {
        return historyStore.exists(executionId);
    }

    public List<ConsoleExecutionHistoryItem> history() {
        return historyStore.recent();
    }

    public ConsoleExecutionResponse execution(String executionId) {
        ConsoleExecutionRecord record = historyStore.get(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown executionId: " + executionId));
        ConsoleExecutionResponse response = record.getResponse();
        if (response != null) {
            response.setExecutionStatus(record.getStatus());
            return response;
        }

        ConsoleExecutionResponse pending = new ConsoleExecutionResponse();
        pending.setExecutionId(record.getExecutionId());
        pending.setExecutionStatus(record.getStatus());
        return pending;
    }

    public String logs(String executionId) {
        ConsoleExecutionRecord record = historyStore.get(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown executionId: " + executionId));
        ConsoleExecutionResponse response = record.getResponse();
        StringBuilder builder = new StringBuilder();
        builder.append("[EXECUTION]\n");
        builder.append("ExecutionId=").append(record.getExecutionId()).append('\n');
        builder.append("Status=").append(record.getStatus()).append('\n');
        builder.append("CreatedAt=").append(record.getCreatedAt()).append("\n\n");
        if (response == null || response.getRows() == null || response.getRows().isEmpty()) {
            builder.append("[RESULT]\nStatus=").append(record.getStatus()).append('\n');
            return builder.toString();
        }

        for (ConsoleResultRow row : response.getRows()) {
            builder.append("[EXEC]\n");
            builder.append("Service=").append(value(row.getService())).append('\n');
            builder.append("Flow=").append(value(row.getFlow())).append('\n');
            builder.append("System=").append(value(row.getSystem())).append('\n');
            builder.append("Env=").append(value(row.getEnv())).append('\n');
            builder.append("Status=").append(value(row.getStatus())).append('\n');
            builder.append("TimeMs=").append(row.getTimeMs()).append("\n\n");
            builder.append("[TRACE]\n").append(lines(row.getTrace())).append("\n\n");
            builder.append("[ASSERT]\n").append(lines(row.getAssertions())).append("\n\n");
            builder.append("[TIMELINE]\n").append(lines(row.getTimeline())).append("\n\n");
            builder.append("[RESULT]\nStatus=").append(value(row.getStatus())).append("\n\n");
        }
        return builder.toString();
    }

    public String report(String executionId) {
        ConsoleExecutionRecord record = historyStore.get(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown executionId: " + executionId));
        ConsoleExecutionResponse response = record.getResponse();
        StringBuilder builder = new StringBuilder();
        builder.append(csv("ExecutionId"));
        builder.append(',');
        builder.append(csv("Service"));
        builder.append(',');
        builder.append(csv("Flow"));
        builder.append(',');
        builder.append(csv("System"));
        builder.append(',');
        builder.append(csv("Env"));
        builder.append(',');
        builder.append(csv("Status"));
        builder.append(',');
        builder.append(csv("BookingID"));
        builder.append(',');
        builder.append(csv("CorrID"));
        builder.append(',');
        builder.append(csv("JobID"));
        builder.append(',');
        builder.append(csv("TimeMs"));
        builder.append(',');
        builder.append(csv("Message"));
        builder.append('\n');
        if (response == null || response.getRows() == null) {
            return builder.toString();
        }
        for (ConsoleResultRow row : response.getRows()) {
            builder.append(csv(executionId)).append(',');
            builder.append(csv(row.getService())).append(',');
            builder.append(csv(row.getFlow())).append(',');
            builder.append(csv(row.getSystem())).append(',');
            builder.append(csv(row.getEnv())).append(',');
            builder.append(csv(row.getStatus())).append(',');
            builder.append(csv(row.getBookingId())).append(',');
            builder.append(csv(row.getCorrId())).append(',');
            builder.append(csv(row.getJobId())).append(',');
            builder.append(csv(row.getTimeMs())).append(',');
            builder.append(csv(row.getMessage())).append('\n');
        }
        return builder.toString();
    }

    private List<ExecutionRequest> matrix(ConsoleExecutionRequest request) {
        String env = value(firstText(request == null ? null : request.getEnv(), "ST5"));
        String bookingId = firstText(request == null ? null : request.getBookingId(), generatedBookingId());
        List<String> systems = values(request == null ? null : request.getSystems(), DEFAULT_SYSTEMS);
        List<String> flows = values(request == null ? null : request.getFlowTypes(), DEFAULT_FLOWS);
        List<String> services = values(request == null ? null : request.getServices(), DEFAULT_SERVICES);

        List<ExecutionRequest> matrix = new ArrayList<>();
        for (String flow : flows) {
            FlowType flowType = FlowType.from(flow);
            for (String system : systems) {
                for (String service : services) {
                    matrix.add(executionRequest(request, flowType, env, system, service, bookingId));
                }
            }
        }
        return matrix;
    }

    private ExecutionRequest executionRequest(
            ConsoleExecutionRequest consoleRequest,
            FlowType flowType,
            String env,
            String system,
            String service,
            String bookingId) {
        ExecutionRequest request = new ExecutionRequest();
        request.setFlowType(flowType);
        request.setExecutionMode(defaultMode(flowType));
        request.setEnv(env);
        request.setSystem(system);
        request.setTrigger(trigger(flowType));
        request.setBookingId(flowType == FlowType.REST ? null : bookingId);
        request.putAttribute("service", service);
        request.putAttribute("payloadMode", value(consoleRequest == null ? null : consoleRequest.getPayloadMode()));
        request.putAttribute("traceEnabled", String.valueOf(consoleRequest == null || consoleRequest.isTraceEnabled()));
        request.setTestCase(testCase(consoleRequest, flowType, env, system, service, bookingId));
        return request;
    }

    private TestCase testCase(
            ConsoleExecutionRequest consoleRequest,
            FlowType flowType,
            String env,
            String system,
            String service,
            String bookingId) {
        TestCase testCase = new TestCase();
        testCase.setTestCaseId("TC_CONSOLE_" + flowType.name() + "_" + normalize(system) + "_" + normalize(service));
        testCase.setBookingId(flowType == FlowType.REST ? null : bookingId);
        testCase.setEnv(env);
        testCase.setDownstreamSystem(system);
        testCase.setExecutionMode(defaultMode(flowType).name());
        testCase.setFlow(normalize(system) + "_" + normalize(service));
        testCase.setScenario(normalize(service));
        if (flowType == FlowType.REST) {
            testCase.setCollection(collection(service));
            testCase.setBrand(firstText(consoleRequest == null ? null : consoleRequest.getBrand(), "TUI_UK"));
        }
        if (flowType == FlowType.RABBIT) {
            testCase.setRoutingKey(normalize(system) + ".CREATE");
        }
        if (flowType == FlowType.KAFKA) {
            testCase.setRoutingKey(bookingId);
        }
        return testCase;
    }

    private List<ConsoleResultRow> executeSequential(String executionId, List<ExecutionRequest> matrix) {
        List<ConsoleResultRow> rows = new ArrayList<>();
        for (ExecutionRequest request : matrix) {
            if (historyStore.isStopRequested(executionId)) {
                rows.add(stoppedRow(request));
                continue;
            }
            rows.add(row(request, executionEngine.execute(request)));
        }
        return rows;
    }

    private List<ConsoleResultRow> executeParallel(String executionId, List<ExecutionRequest> matrix) {
        int size = Math.max(1, Math.min(matrix.size(), 8));
        ExecutorService executorService = Executors.newFixedThreadPool(size);
        try {
            List<Future<ConsoleResultRow>> futures = new ArrayList<>();
            for (ExecutionRequest request : matrix) {
                Callable<ConsoleResultRow> task = () -> historyStore.isStopRequested(executionId)
                        ? stoppedRow(request)
                        : row(request, executionEngine.execute(request));
                futures.add(executorService.submit(task));
            }

            List<ConsoleResultRow> rows = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                Future<ConsoleResultRow> future = futures.get(index);
                ExecutionRequest request = matrix.get(index);
                try {
                    rows.add(get(future));
                } catch (TimeoutException e) {
                    future.cancel(true);
                    rows.add(failureRow(request, "Execution timed out after " + parallelTimeoutMs + "ms"));
                } catch (Exception e) {
                    rows.add(failureRow(request, e.getMessage()));
                }
            }
            return rows;
        } finally {
            executorService.shutdownNow();
        }
    }

    private ConsoleResultRow get(Future<ConsoleResultRow> future) throws Exception {
        if (parallelTimeoutMs <= 0) {
            return future.get();
        }
        return future.get(parallelTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private ConsoleExecutionResponse failedResponse(ConsoleExecutionRecord record, RuntimeException exception, long durationMs) {
        ConsoleResultRow row = new ConsoleResultRow();
        row.setStatus("FAIL");
        row.setMessage(value(exception.getMessage()));
        row.setTrace(Collections.singletonList("ExecutionId=" + value(record.getExecutionId())));
        row.setAssertions(Collections.singletonList("ERROR=YES"));
        row.setTimeline(Collections.singletonList("Execution failed before result matrix completed"));

        List<ConsoleResultRow> rows = Collections.singletonList(row);
        ConsoleExecutionResponse response = new ConsoleExecutionResponse();
        response.setExecutionId(record.getExecutionId());
        response.setExecutionStatus(ConsoleExecutionHistoryStore.FAILED);
        response.setRows(rows);
        response.setSummary(summary(rows, durationMs));
        return response;
    }

    private ConsoleResultRow failureRow(ExecutionRequest request, String message) {
        ConsoleResultRow row = new ConsoleResultRow();
        if (request != null) {
            row.setService(request.getAttributes().get("service"));
            row.setFlow(request.getFlowType() == null ? "NA" : request.getFlowType().name());
            row.setSystem(request.getSystem());
            row.setEnv(request.getEnv());
            row.setBookingId(request.getFlowType() == FlowType.REST ? "NA" : value(request.getBookingId()));
        }
        row.setStatus("FAIL");
        row.setCorrId("NA");
        row.setJobId("NA");
        row.setMessage(value(message));
        row.setTrace(traceLines(row));
        row.setAssertions(assertionLines(null, row));
        row.setTimeline(Collections.singletonList(value(message)));
        return row;
    }

    private ConsoleResultRow stoppedRow(ExecutionRequest request) {
        ConsoleResultRow row = new ConsoleResultRow();
        row.setService(request.getAttributes().get("service"));
        row.setFlow(request.getFlowType() == null ? "NA" : request.getFlowType().name());
        row.setSystem(request.getSystem());
        row.setEnv(request.getEnv());
        row.setStatus("FAIL");
        row.setBookingId(request.getFlowType() == FlowType.REST ? "NA" : value(request.getBookingId()));
        row.setCorrId("NA");
        row.setJobId("NA");
        row.setMessage("Execution stop requested before trigger");
        row.setTrace(traceLines(row));
        row.setAssertions(assertionLines(null, row));
        row.setTimeline(Collections.singletonList("Stop requested before trigger"));
        return row;
    }

    private ConsoleResultRow row(ExecutionRequest request, ExecutionReport report) {
        TriggerResult trigger = report == null ? null : report.getTriggerResult();
        TraceContext trace = report == null ? null : report.getTraceContext();

        ConsoleResultRow row = new ConsoleResultRow();
        row.setService(request.getAttributes().get("service"));
        row.setFlow(request.getFlowType() == null ? "NA" : request.getFlowType().name());
        row.setSystem(request.getSystem());
        row.setEnv(request.getEnv());
        row.setStatus(report != null && report.getStatus() == ExecutionStatus.PASS ? "PASS" : "FAIL");
        row.setBookingId(value(trace == null ? request.getBookingId() : trace.getBookingId()));
        row.setCorrId(value(trace == null ? null : trace.getCorrId()));
        row.setJobId(value(trace == null ? null : trace.getJobId()));
        row.setTimeMs(trigger == null ? 0 : trigger.getTimeMs());
        row.setMessage(report == null ? "No report returned" : report.getMessage());
        row.setTrace(traceLines(row));
        row.setAssertions(assertionLines(trigger, row));
        row.setTimeline(timelineLines(request, trigger, row));
        return row;
    }

    private List<String> traceLines(ConsoleResultRow row) {
        List<String> lines = new ArrayList<>();
        lines.add("BookingID=" + value(row.getBookingId()));
        lines.add("CorrID=" + value(row.getCorrId()));
        lines.add("JobID=" + value(row.getJobId()));
        return lines;
    }

    private List<String> assertionLines(TriggerResult trigger, ConsoleResultRow row) {
        List<String> lines = new ArrayList<>();
        lines.add("TRIGGER=" + ("PASS".equals(row.getStatus()) ? "SUCCESS" : "FAIL"));
        lines.add("PROCESS=" + assertionValue(trigger, "PROCESS", "PASS".equals(row.getStatus()) ? "PASS" : "FAIL"));
        lines.add("DOWNSTREAM=" + assertionValue(trigger, "DOWNSTREAM", "PASS".equals(row.getStatus()) ? "SUCCESS" : "FAIL"));
        lines.add("ERROR=" + assertionValue(trigger, "ERROR", "PASS".equals(row.getStatus()) ? "NO" : "YES"));
        if (trigger != null && trigger.getHttpStatus() != null) {
            lines.add("HTTP=" + trigger.getHttpStatus());
        }
        return lines;
    }

    private String assertionValue(TriggerResult trigger, String key, String fallback) {
        if (trigger == null || trigger.getMetadata() == null) {
            return fallback;
        }
        String value = trigger.getMetadata().get(key);
        return firstText(value, fallback);
    }

    private List<String> timelineLines(ExecutionRequest request, TriggerResult trigger, ConsoleResultRow row) {
        List<String> lines = new ArrayList<>();
        lines.add("Select " + value(request.getFlowType()));
        lines.add("Trigger " + ("PASS".equals(row.getStatus()) ? "SUCCESS" : "FAIL"));
        lines.add("Complete " + value(row.getTimeMs()) + "ms");
        if (trigger != null && trigger.getMessage() != null) {
            lines.add(trigger.getMessage());
        }
        return lines;
    }

    private ConsoleExecutionSummary summary(List<ConsoleResultRow> rows, long durationMs) {
        ConsoleExecutionSummary summary = new ConsoleExecutionSummary();
        int total = rows == null ? 0 : rows.size();
        int pass = 0;
        if (rows != null) {
            for (ConsoleResultRow row : rows) {
                if ("PASS".equalsIgnoreCase(row.getStatus())) {
                    pass++;
                }
            }
        }
        summary.setTotal(total);
        summary.setPass(pass);
        summary.setFail(total - pass);
        summary.setDurationMs(durationMs);
        summary.setSuccessRate(total == 0 ? 0 : Math.round((pass * 100f) / total));
        return summary;
    }

    private ExecutionMode defaultMode(FlowType flowType) {
        return flowType == FlowType.JMS || flowType == FlowType.RABBIT || flowType == FlowType.KAFKA
                ? ExecutionMode.ASYNC
                : ExecutionMode.SYNC;
    }

    private String trigger(FlowType flowType) {
        return flowType == null ? "NA" : flowType.name();
    }

    private String collection(String service) {
        String normalized = normalize(service);
        if (normalized.contains("ACCOM")) {
            return "ACCOMOFFERS";
        }
        if (normalized.contains("FLIGHT")) {
            return "FLIGHTOFFERS";
        }
        if (normalized.contains("CRUISE")) {
            return "CRUISEOFFERS";
        }
        return "PACKAGEOFFER";
    }

    private List<String> values(List<String> values, List<String> fallback) {
        List<String> resolved = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    resolved.add(value.trim());
                }
            }
        }
        return resolved.isEmpty() ? fallback : resolved;
    }

    private String generatedBookingId() {
        return defaultBookingId;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String normalize(String value) {
        return value(value).replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String value(Object value) {
        if (value == null) {
            return "NA";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "NA" : text;
    }

    private String lines(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "NA";
        }
        return String.join("\n", values);
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
