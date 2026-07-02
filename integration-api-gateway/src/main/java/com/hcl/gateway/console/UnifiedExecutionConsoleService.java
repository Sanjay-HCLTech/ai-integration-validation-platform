package com.hcl.gateway.console;

import com.hcl.execution.adapter.TriggerResult;
import com.hcl.execution.core.ExecutionEngine;
import com.hcl.execution.core.ExecutionMode;
import com.hcl.execution.core.ExecutionReport;
import com.hcl.execution.core.ExecutionReportFormatter;
import com.hcl.execution.core.ExecutionRequest;
import com.hcl.execution.core.ExecutionStatus;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.core.TraceContext;
import com.hcl.execution.model.TestCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
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
    private final String localLogDir;
    private final ExecutionReportFormatter reportFormatter = new ExecutionReportFormatter();

    public UnifiedExecutionConsoleService(
            ExecutionEngine executionEngine,
            ConsoleExecutionHistoryStore historyStore,
            @Value("${console.execution.parallel.timeout.ms}") long parallelTimeoutMs,
            @Value("${console.execution.default.booking-id}") String defaultBookingId,
            @Value("${local.log.dir}") String localLogDir) {
        this.executionEngine = executionEngine;
        this.historyStore = historyStore;
        this.parallelTimeoutMs = Math.max(0, parallelTimeoutMs);
        this.defaultBookingId = firstText(defaultBookingId, "31835146");
        this.localLogDir = firstText(localLogDir, "C:/logs");
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
            if (row.getTerminalLog() != null && !row.getTerminalLog().isEmpty()) {
                builder.append(lines(row.getTerminalLog())).append("\n\n");
            } else {
                builder.append("[TRACE]\n").append(lines(row.getTrace())).append("\n\n");
                builder.append("[ASSERT]\n").append(lines(row.getAssertions())).append("\n\n");
                builder.append("[TIMELINE]\n").append(lines(row.getTimeline())).append("\n\n");
                builder.append("[RESULT]\nStatus=").append(value(row.getStatus())).append("\n\n");
            }
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
            builder.append(csv("TrackingID"));
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
            builder.append(csv(row.getTrackingId())).append(',');
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
        List<String> services = values(request == null ? null : request.getServices(), defaultServices(flows));
        if (request == null || !request.isRunAllServices()) {
            systems = firstOnly(systems);
            String scopedFlow = scopedFlow(flows, services);
            flows = Collections.singletonList(scopedFlow);
            services = Collections.singletonList(scopedService(scopedFlow, services));
        }

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

    private List<String> firstOnly(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(values.get(0));
    }

    private List<String> defaultServices(List<String> flows) {
        if (flows != null) {
            for (String flow : flows) {
                if ("RABBIT".equalsIgnoreCase(value(flow)) || "RABBITMQ".equalsIgnoreCase(value(flow))) {
                    return Collections.singletonList("ReservationEvent_v3");
                }
            }
        }
        return DEFAULT_SERVICES;
    }

    private String scopedFlow(List<String> flows, List<String> services) {
        if (hasReservationEvent(services)) {
            for (String flow : flows) {
                if (isRabbitFlow(flow)) {
                    return flow;
                }
            }
        }
        return firstOnly(flows).isEmpty() ? "JMS" : firstOnly(flows).get(0);
    }

    private String scopedService(String flow, List<String> services) {
        if (isRabbitFlow(flow)) {
            return "ReservationEvent_v3";
        }
        return firstOnly(services).isEmpty() ? "BookingDetails" : firstOnly(services).get(0);
    }

    private boolean hasReservationEvent(List<String> services) {
        if (services == null) {
            return false;
        }
        for (String service : services) {
            if (normalize(service).contains("RESERVATIONEVENT")) {
                return true;
            }
        }
        return false;
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
        String resolvedService = serviceForFlow(flowType, service);
        request.setBookingId(requestBookingId(consoleRequest, flowType, bookingId));
        request.putAttribute("service", resolvedService);
        request.putAttribute("payloadMode", value(consoleRequest == null ? null : consoleRequest.getPayloadMode()));
        request.putAttribute("traceEnabled", String.valueOf(consoleRequest == null || consoleRequest.isTraceEnabled()));
        request.setTestCase(testCase(consoleRequest, flowType, env, system, resolvedService, bookingId));
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
        testCase.setBookingId(requestBookingId(consoleRequest, flowType, bookingId));
        testCase.setEnv(env);
        testCase.setDownstreamSystem(system);
        testCase.setExecutionMode(defaultMode(flowType).name());
        testCase.setFlow(normalize(system) + "_" + normalize(service));
        testCase.setScenario(normalize(service));
        if (flowType == FlowType.REST) {
            testCase.setCollection(collection(service));
            testCase.setBrand(firstText(consoleRequest == null ? null : consoleRequest.getBrand(), "TUI_UK"));
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
        row.setTerminalLog(failureTerminalLog(record.getExecutionId(), row));

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
            row.setTrackingId("NA");
        }
        row.setStatus("FAIL");
        row.setCorrId("NA");
        row.setJobId("NA");
        row.setMessage(value(message));
        row.setTrace(traceLines(row));
        row.setAssertions(assertionLines(null, row));
        row.setTimeline(Collections.singletonList(value(message)));
        row.setTerminalLog(fallbackTerminalLog(request, row));
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
        row.setTrackingId("NA");
        row.setCorrId("NA");
        row.setJobId("NA");
        row.setMessage("Execution stop requested before trigger");
        row.setTrace(traceLines(row));
        row.setAssertions(assertionLines(null, row));
        row.setTimeline(Collections.singletonList("Stop requested before trigger"));
        row.setTerminalLog(fallbackTerminalLog(request, row));
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
        row.setBookingId(bookingId(request, trigger, trace));
        row.setTrackingId(trackingId(trigger));
        row.setCorrId(value(trace == null ? null : trace.getCorrId()));
        row.setJobId(value(trace == null ? null : trace.getJobId()));
        row.setTimeMs(trigger == null ? 0 : trigger.getTimeMs());
        row.setMessage(report == null ? "No report returned" : report.getMessage());
        row.setTrace(traceLines(row));
        row.setAssertions(assertionLines(trigger, row));
        row.setTimeline(timelineLines(request, trigger, row));
        row.setTerminalLog(terminalLog(request, report, row));
        return row;
    }

    private List<String> terminalLog(ExecutionRequest request, ExecutionReport report, ConsoleResultRow row) {
        TriggerResult trigger = report == null ? null : report.getTriggerResult();
        List<String> lines = new ArrayList<>();
        String testCaseId = testCaseId(request);
        String bookingId = row == null || "REST".equalsIgnoreCase(row.getFlow()) ? "NA" : value(row.getBookingId());
        String executionStatus = "PASS".equalsIgnoreCase(row == null ? null : row.getStatus()) ? "SUCCESS" : "FAIL";

        lines.add("==================== EXECUTION START ====================");
        lines.add("TestCase=" + value(testCaseId) + " | BookingID=" + bookingId);
        lines.add("[PROGRESS] Execution started TestCase=" + value(testCaseId)
                + " BookingID=" + bookingId + " Endpoint=/execute/executeAll");
        addRestSnapshot(lines, trigger);
        lines.add("[PROTOCOL_EXECUTION]");
        lines.add("Protocol=" + protocol(request)
                + " Mode=" + mode(request, trigger)
                + " Status=" + value(trigger == null ? row.getStatus() : trigger.getStatus())
                + " LatencyMs=" + (trigger == null ? row.getTimeMs() : trigger.getTimeMs()));
        lines.add("");
        lines.add("==================== UNIFIED TRACE REPORT ====================");
        addApiSection(lines, trigger);
        addFileHandling(lines, request, trigger, row);
        addRetry(lines, trigger, request, row);
        addProtocolSection(lines, request, trigger, row);
        addTimeline(lines, row);
        addValidation(lines, trigger, row);
        lines.add("");
        lines.add("Orchestrator: Execution completed");
        lines.add("");
        addSummary(lines, request, row);
        lines.add("==================== UNIFIED TRACE END =======================");

        String formatted = reportFormatter.format(report);
        if (formatted != null && !formatted.trim().isEmpty()) {
            Collections.addAll(lines, formatted.replace("\r", "").split("\n"));
        }
        lines.add("[PROGRESS] Execution completed TestCase=" + value(testCaseId)
                + " BookingID=" + bookingId
                + " Status=" + executionStatus
                + " TotalTimeMs=" + value(row == null ? null : row.getTimeMs()));
        lines.add("==================== EXECUTION END Status=" + executionStatus + " ======================");
        return lines;
    }

    private List<String> fallbackTerminalLog(ExecutionRequest request, ConsoleResultRow row) {
        List<String> lines = new ArrayList<>();
        String testCaseId = testCaseId(request);
        lines.add("==================== EXECUTION START ====================");
        lines.add("TestCase=" + value(testCaseId) + " | BookingID=" + value(row.getBookingId()));
        lines.add("[PROGRESS] Execution started TestCase=" + value(testCaseId)
                + " BookingID=" + value(row.getBookingId()) + " Endpoint=/execute/executeAll");
        lines.add("[RESULT]");
        lines.add("Status=" + value(row.getStatus()));
        lines.add("Reason=" + value(row.getMessage()));
        lines.add("[PROGRESS] Execution completed TestCase=" + value(testCaseId)
                + " BookingID=" + value(row.getBookingId())
                + " Status=" + value(row.getStatus())
                + " TotalTimeMs=" + row.getTimeMs());
        lines.add("==================== EXECUTION END Status=" + value(row.getStatus()) + " ======================");
        return lines;
    }

    private List<String> failureTerminalLog(String executionId, ConsoleResultRow row) {
        List<String> lines = new ArrayList<>();
        lines.add("==================== EXECUTION START ====================");
        lines.add("ExecutionId=" + value(executionId));
        lines.add("[RESULT]");
        lines.add("Status=" + value(row.getStatus()));
        lines.add("Reason=" + value(row.getMessage()));
        lines.add("==================== EXECUTION END Status=" + value(row.getStatus()) + " ======================");
        return lines;
    }

    private void addRestSnapshot(List<String> lines, TriggerResult trigger) {
        if (trigger == null
                || !hasMetadata(trigger, "REST_LOG_SNAPSHOT_STATUS")
                || !hasMetadataValue(trigger, "REST_LOG_SNAPSHOT_MESSAGE")) {
            return;
        }
        lines.add("[REST_LOG_SNAPSHOT]");
        lines.add("Status=" + metadata(trigger, "REST_LOG_SNAPSHOT_STATUS"));
        lines.add("RemotePath=" + metadata(trigger, "REST_LOG_SNAPSHOT_REMOTE_PATH"));
        lines.add("Message=" + metadata(trigger, "REST_LOG_SNAPSHOT_MESSAGE"));
    }

    private void addApiSection(List<String> lines, TriggerResult trigger) {
        lines.add("[API]");
        lines.add("Endpoint=" + metadata(trigger, "ENDPOINT_OR_DESTINATION"));
        lines.add("Status=" + value(trigger == null ? null : trigger.getHttpStatus()));
        lines.add("");
    }

    private void addFileHandling(List<String> lines, ExecutionRequest request, TriggerResult trigger, ConsoleResultRow row) {
        lines.add("-------------------- FILE HANDLING ---------------------");
        lines.add("[FILES]");
        if (hasMetadataValue(trigger, "TRACE_FILE_LINES")) {
            addMetadataLines(lines, metadata(trigger, "TRACE_FILE_LINES"));
            lines.add("");
            return;
        }
        List<String> localEvidence = localEvidenceFileLines(request, row);
        if (!localEvidence.isEmpty()) {
            for (int index = 0; index < localEvidence.size(); index++) {
                lines.add(localEvidence.get(index));
                if (index + 1 < localEvidence.size()) {
                    lines.add("");
                }
            }
            lines.add("");
            return;
        }
        if (!hasMetadata(trigger, "REST_LOG_SNAPSHOT_STATUS")
                || !hasMetadataValue(trigger, "REST_LOG_SNAPSHOT_MESSAGE")) {
            lines.add("Name=NO_REMOTE_FILES ExistsLocal=N Action=SKIP Reason=NO_FILE_ACTIVITY");
            lines.add("");
            return;
        }
        String status = metadata(trigger, "REST_LOG_SNAPSHOT_STATUS");
        String remotePath = metadata(trigger, "REST_LOG_SNAPSHOT_REMOTE_PATH");
        String localDir = metadata(trigger, "REST_LOG_SNAPSHOT_LOCAL_DIR");
        String files = metadata(trigger, "REST_LOG_SNAPSHOT_FILES");
        String message = metadata(trigger, "REST_LOG_SNAPSHOT_MESSAGE");
        if ("SUCCESS".equalsIgnoreCase(status)) {
            lines.add("Name=REST_REMOTE_SNAPSHOT ExistsLocal=Y Action=DOWNLOADED Files=" + value(files)
                    + " RemotePath=" + value(remotePath)
                    + " LocalDir=" + value(localDir));
        } else {
            lines.add("Name=REST_REMOTE_SNAPSHOT ExistsLocal=N Action=" + snapshotAction(status)
                    + " Status=" + value(status)
                    + " RemotePath=" + value(remotePath)
                    + " Reason=" + value(message));
        }
        lines.add("");
    }

    private String snapshotAction(String status) {
        if ("FAILED".equalsIgnoreCase(status)) {
            return "FAILED";
        }
        if ("NO_ACTIVITY".equalsIgnoreCase(status) || "SKIPPED".equalsIgnoreCase(status)) {
            return "SKIP";
        }
        return "CHECK";
    }

    private void addRetry(List<String> lines, TriggerResult trigger, ExecutionRequest request, ConsoleResultRow row) {
        lines.add("-------------------- RETRY LOGIC -----------------------");
        lines.add("[RETRY]");
        if (hasMetadataValue(trigger, "TRACE_RETRY_LINES")) {
            addMetadataLines(lines, metadata(trigger, "TRACE_RETRY_LINES"));
        } else if (!localEvidenceFileLines(request, row).isEmpty()) {
            lines.add("Stage=Trace      Attempt=1/1 Result=FOUND_LOCAL Action=STOP");
        } else {
            lines.add("Stage=Trace      Attempt=0/0 Result=SKIPPED Action=STOP");
        }
        lines.add("");
    }

    private List<String> localEvidenceFileLines(ExecutionRequest request, ConsoleResultRow row) {
        List<String> lines = new ArrayList<>();
        String scope = firstText(row == null ? null : row.getBookingId(),
                request == null ? null : request.getBookingId());
        if (!hasText(scope) || "NA".equalsIgnoreCase(scope)) {
            return lines;
        }

        File scopeDir = new File(localLogDir, safePathSegment(scope));
        if (!scopeDir.isDirectory()) {
            return lines;
        }

        File[] files = scopeDir.listFiles(file -> file != null && file.isFile());
        if (files == null || files.length == 0) {
            return lines;
        }
        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        for (File file : files) {
            if (isRabbitRequest(request) && !"audit.log".equalsIgnoreCase(file.getName())) {
                continue;
            }
            lines.add("File=" + padRight(file.getName(), 48)
                    + " Exists=Y Action=LOCAL_PRESENT LocalPath=" + file.getPath()
                    + " Reason=LOCAL_EVIDENCE_PRESENT");
        }
        return lines;
    }

    private void addMetadataLines(List<String> lines, String value) {
        if (!hasText(value)) {
            return;
        }
        String[] split = value.replace("\r", "").split("\n");
        for (int index = 0; index < split.length; index++) {
            String line = split[index];
            if (hasText(line)) {
                lines.add(line);
                if (isFileRecord(line) && hasNextFileRecord(split, index)) {
                    lines.add("");
                }
            }
        }
    }

    private boolean hasNextFileRecord(String[] lines, int index) {
        for (int next = index + 1; next < lines.length; next++) {
            if (!hasText(lines[next])) {
                continue;
            }
            return isFileRecord(lines[next]);
        }
        return false;
    }

    private boolean isFileRecord(String line) {
        String value = line == null ? "" : line.trim();
        return value.startsWith("File=") || value.startsWith("Name=");
    }

    private void addProtocolSection(List<String> lines, ExecutionRequest request, TriggerResult trigger, ConsoleResultRow row) {
        lines.add("-------------------- " + protocolSectionName(request) + " FLOW --------------------------");
        lines.add("[" + protocolSectionName(request) + "]");
        if (request != null && request.getFlowType() == FlowType.REST) {
            lines.add("Env=" + metadata(trigger, "REST_ENV"));
            lines.add("Collection=" + metadata(trigger, "REST_COLLECTION"));
            lines.add("Brand=" + metadata(trigger, "REST_BRAND"));
            lines.add("Protocol=REST");
            lines.add("Method=" + metadata(trigger, "REST_METHOD"));
            lines.add("Endpoint=" + metadata(trigger, "ENDPOINT_OR_DESTINATION"));
            lines.add("Payload=" + metadata(trigger, "PAYLOAD_SOURCE"));
            lines.add("QueryParam=" + metadata(trigger, "REST_QUERY_PARAM"));
            lines.add("BookingID=NA");
            lines.add("HttpStatus=" + value(trigger == null ? null : trigger.getHttpStatus()));
            lines.add("FlowStatus=" + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAILED"));
            lines.add("AcceptDefault=" + metadata(trigger, "REST_ACCEPT_DEFAULT"));
            lines.add("AcceptConfigured=" + metadata(trigger, "REST_ACCEPT_CONFIGURED"));
            lines.add("AcceptCombined=" + metadata(trigger, "REST_ACCEPT_COMBINED"));
            lines.add("QueryEncoding=" + metadata(trigger, "REST_QUERY_ENCODING"));
        } else {
            lines.add("Env=" + value(row.getEnv()));
            lines.add("System=" + value(row.getSystem()));
            lines.add("Service=" + value(row.getService()));
            lines.add("Protocol=" + protocol(request));
            lines.add("EndpointOrDestination=" + metadata(trigger, "ENDPOINT_OR_DESTINATION"));
            lines.add("BookingID=" + value(row.getBookingId()));
            lines.add("CorrID=" + value(row.getCorrId()));
            lines.add("JobID=" + value(row.getJobId()));
            lines.add("FlowStatus=" + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAILED"));
        }
        lines.add("");
    }

    private void addTimeline(List<String> lines, ConsoleResultRow row) {
        lines.add("-------------------- TIMELINE --------------------------");
        lines.add("[TIMELINE]");
        List<String> timeline = row == null ? Collections.emptyList() : row.getTimeline();
        if (timeline == null || timeline.isEmpty()) {
            lines.add("NA");
        } else {
            int index = 1;
            for (String item : timeline) {
                lines.add(index++ + ". " + value(item));
            }
        }
        lines.add("");
    }

    private void addValidation(List<String> lines, TriggerResult trigger, ConsoleResultRow row) {
        lines.add("-------------------- VALIDATION ------------------------");
        lines.add("[VALIDATION]");
        lines.add("BookingID=" + value(row.getBookingId())
                + " CorrID=" + value(row.getCorrId())
                + " JobID=" + value(row.getJobId()));
        lines.add("API=" + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAILED"));
        lines.add(protocol(row) + "=" + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAILED"));
        if (trigger != null && trigger.getHttpStatus() != null) {
            lines.add("HttpCode=" + trigger.getHttpStatus());
        }
        lines.add("ApiResponse=" + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAILED"));
        lines.add("ApiFlow=" + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAILED"));
        lines.add("EndToEnd=" + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAILED"));
    }

    private void addSummary(List<String> lines, ExecutionRequest request, ConsoleResultRow row) {
        lines.add("-------------------- SUMMARY ---------------------------");
        lines.add("[SUMMARY]");
        lines.add("TotalTimeMs=" + value(row.getTimeMs()) + " Status="
                + ("PASS".equalsIgnoreCase(row.getStatus()) ? "SUCCESS" : "FAIL"));
        lines.add("TriggeredService=" + protocolSectionName(request));
        lines.add("SkippedServices=" + skippedServices(request));
    }

    private String protocolSectionName(ExecutionRequest request) {
        if (request == null || request.getFlowType() == null) {
            return "EXECUTION";
        }
        if (request.getFlowType() == FlowType.REST) {
            return "APIGEE";
        }
        if (request.getFlowType() == FlowType.RABBIT) {
            return "RABBITMQ";
        }
        return request.getFlowType().name();
    }

    private String protocol(ExecutionRequest request) {
        if (request == null || request.getFlowType() == null) {
            return "NA";
        }
        return request.getFlowType() == FlowType.RABBIT ? "RABBITMQ" : request.getFlowType().name();
    }

    private String protocol(ConsoleResultRow row) {
        return row == null ? "NA" : ("RABBIT".equalsIgnoreCase(row.getFlow()) ? "RABBITMQ" : value(row.getFlow()));
    }

    private String mode(ExecutionRequest request, TriggerResult trigger) {
        if (trigger != null && trigger.getExecutionMode() != null) {
            return trigger.getExecutionMode().name();
        }
        return request == null || request.effectiveMode() == null ? "NA" : request.effectiveMode().name();
    }

    private String skippedServices(ExecutionRequest request) {
        List<String> services = new ArrayList<>(Arrays.asList("REST", "JMS", "SOAP", "RabbitMQ", "Kafka"));
        if (request != null && request.getFlowType() == FlowType.REST) {
            services.removeIf(item -> "REST".equalsIgnoreCase(item));
        }
        services.removeIf(item -> item.equalsIgnoreCase(protocolSectionName(request))
                || item.equalsIgnoreCase(protocol(request)));
        return String.join(",", services);
    }

    private String testCaseId(ExecutionRequest request) {
        return request == null || request.getTestCase() == null
                ? "TC_POST_EXECUTE"
                : value(request.getTestCase().getTestCaseId());
    }

    private boolean hasMetadata(TriggerResult trigger, String key) {
        return trigger != null
                && trigger.getMetadata() != null
                && trigger.getMetadata().containsKey(key);
    }

    private boolean hasMetadataValue(TriggerResult trigger, String key) {
        return hasMetadata(trigger, key) && hasText(trigger.getMetadata().get(key));
    }

    private String metadata(TriggerResult trigger, String key) {
        if (trigger == null || trigger.getMetadata() == null || key == null) {
            return "NA";
        }
        return value(trigger.getMetadata().get(key));
    }

    private List<String> traceLines(ConsoleResultRow row) {
        List<String> lines = new ArrayList<>();
        if ("REST".equalsIgnoreCase(row.getFlow())) {
            lines.add("TrackingID=" + value(row.getTrackingId()));
            lines.add("BookingID=NA");
            lines.add("CorrID=NA");
            lines.add("JobID=NA");
            return lines;
        }
        lines.add("BookingID=" + value(row.getBookingId()));
        lines.add("CorrID=" + value(row.getCorrId()));
        lines.add("JobID=" + value(row.getJobId()));
        return lines;
    }

    private String trackingId(TriggerResult trigger) {
        if (trigger == null || trigger.getMetadata() == null) {
            return "NA";
        }
        return value(trigger.getMetadata().get("TRACKING_ID"));
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
        return "PACKAGEOFFERS";
    }

    private String serviceForFlow(FlowType flowType, String service) {
        if (flowType == FlowType.RABBIT) {
            return "ReservationEvent_v3";
        }
        return service;
    }

    private String requestBookingId(ConsoleExecutionRequest consoleRequest, FlowType flowType, String bookingId) {
        if (flowType == FlowType.REST) {
            return null;
        }
        if (flowType == FlowType.RABBIT && !isExplicitBookingId(consoleRequest, bookingId)) {
            return null;
        }
        return bookingId;
    }

    private boolean isExplicitBookingId(ConsoleExecutionRequest consoleRequest, String bookingId) {
        if (!hasText(bookingId)) {
            return false;
        }
        return (consoleRequest != null && consoleRequest.isBookingIdExplicit())
                || !sameText(bookingId, defaultBookingId);
    }

    private String bookingId(ExecutionRequest request, TriggerResult trigger, TraceContext trace) {
        if (request != null && request.getFlowType() == FlowType.RABBIT) {
            return firstText(metadata(trigger, "RABBIT_BOOKING_ID"), "NA");
        }
        return value(trace == null ? request.getBookingId() : trace.getBookingId());
    }

    private boolean isRabbitFlow(String flow) {
        return "RABBIT".equalsIgnoreCase(value(flow)) || "RABBITMQ".equalsIgnoreCase(value(flow));
    }

    private boolean isRabbitRequest(ExecutionRequest request) {
        return request != null && request.getFlowType() == FlowType.RABBIT;
    }

    private boolean sameText(String first, String second) {
        return hasText(first) && hasText(second) && first.trim().equalsIgnoreCase(second.trim());
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

    private String safePathSegment(String value) {
        String text = hasText(value) ? value.trim() : "NA";
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String padRight(String value, int width) {
        String text = value(value);
        if (text.length() >= width) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
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
