package com.hcl.gateway.controller;

import com.hcl.execution.model.ExecutionResult;
import com.hcl.execution.model.StepResult;
import com.hcl.execution.model.TestCase;
import com.hcl.execution.model.TestStep;
import com.hcl.execution.jms.JmsFlowConfig;
import com.hcl.execution.jms.JmsPayloadResolver;
import com.hcl.execution.jms.JmsProcessingResult;
import com.hcl.execution.jms.JmsPublishRequest;
import com.hcl.execution.jms.JmsProducerService;
import com.hcl.gateway.service.ExecutionRouterService;
import com.hcl.gateway.service.OrchestratorService;
import com.hcl.gateway.payload.PayloadCatalogService;
import com.hcl.observability.autotest.TestCaseGenerator;
import com.hcl.observability.log.LogAnalyzerService;
import com.hcl.observability.log.LogSearchResult;
import com.hcl.observability.log.LogTransferException;
import com.hcl.observability.report.ReportService;
import com.hcl.observability.trace.TimelineEvent;
import com.hcl.observability.trace.TimelineService;
import com.hcl.observability.validation.BusinessValidationResult;
import com.hcl.observability.validation.BusinessValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/execute")
public class ExecutionController {

    private static final Pattern JOB_ID_PATTERN =
            Pattern.compile("\\bJobI[Dd]\\s*[:=]\\s*([A-Za-z0-9._:-]{2,})");
    private static final Pattern CORR_ID_PATTERN =
            Pattern.compile("\\b(?:CorrI[Dd]|JMSCorrelationID)\\s*[:=]\\s*([A-Za-z0-9._:-]{20,})");
    private static final Pattern BIZ_KEY_PATTERN =
            Pattern.compile("\\bBizKey:\\s*([^\\t\\r\\n ]{3,})");
    private static final String EVIDENCE_SEPARATOR =
            "-------------------------------------------------------------------------------------------------------------------------------------------";
    private static final int LOCAL_EVIDENCE_EXPANSION_PASSES = 2;

    private final OrchestratorService orchestratorService;
    private final ExecutionRouterService executionRouterService;
    private final JmsProducerService jmsProducerService;
    private final JmsFlowConfig jmsFlowConfig;
    private final JmsPayloadResolver jmsPayloadResolver;
    private final LogAnalyzerService logAnalyzerService;
    private final TestCaseGenerator testCaseGenerator;
    private final PayloadCatalogService payloadCatalogService;
    private final TimelineService timelineService;
    private final BusinessValidationService businessValidationService;
    private final ReportService reportService;
    private final String localLogDir;
    private final boolean unifiedTraceReportEnabled;
    private final boolean platformReportEnabled;
    private final boolean soapPayloadCatalogEnabled;
    private final boolean restPayloadCatalogEnabled;

    public ExecutionController(
            OrchestratorService orchestratorService,
            ExecutionRouterService executionRouterService,
            JmsProducerService jmsProducerService,
            JmsFlowConfig jmsFlowConfig,
            JmsPayloadResolver jmsPayloadResolver,
            LogAnalyzerService logAnalyzerService,
            TestCaseGenerator testCaseGenerator,
            PayloadCatalogService payloadCatalogService,
            TimelineService timelineService,
            BusinessValidationService businessValidationService,
            ReportService reportService,
            @Value("${local.log.dir:C:/logs}") String localLogDir,
            @Value("${unified.trace.report.enabled:false}") boolean unifiedTraceReportEnabled,
            @Value("${platform.report.enabled:false}") boolean platformReportEnabled,
            @Value("${soap.payload.catalog.enabled:true}") boolean soapPayloadCatalogEnabled,
            @Value("${rest.payload.catalog.enabled:true}") boolean restPayloadCatalogEnabled) {
        this.orchestratorService = orchestratorService;
        this.executionRouterService = executionRouterService;
        this.jmsProducerService = jmsProducerService;
        this.jmsFlowConfig = jmsFlowConfig;
        this.jmsPayloadResolver = jmsPayloadResolver;
        this.logAnalyzerService = logAnalyzerService;
        this.testCaseGenerator = testCaseGenerator;
        this.payloadCatalogService = payloadCatalogService;
        this.timelineService = timelineService;
        this.businessValidationService = businessValidationService;
        this.reportService = reportService;
        this.localLogDir = localLogDir;
        this.unifiedTraceReportEnabled = unifiedTraceReportEnabled;
        this.platformReportEnabled = platformReportEnabled;
        this.soapPayloadCatalogEnabled = soapPayloadCatalogEnabled;
        this.restPayloadCatalogEnabled = restPayloadCatalogEnabled;
    }

    @PostMapping
    public ExecutionResult execute(
            @RequestBody(required = false) TestCase request,
            @RequestParam(name = "bookingId", required = false) String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "flow", required = false) String flow,
            @RequestParam(name = "scenario", required = false) String scenario,
            @RequestParam(name = "mode", required = false) String mode) {

        long executionStartNanos = System.nanoTime();
        TestCase testCase = request == null ? new TestCase() : request;
        String executionStatus = "UNKNOWN";
        try {
            if (isBlank(testCase.getTestCaseId())) {
                testCase.setTestCaseId("TC_POST_EXECUTE");
            }

            if (!isBlank(bookingId)) {
                testCase.setBookingId(bookingId);
            }

            if (isBlank(testCase.getBookingId())) {
                testCase.setBookingId("42007233");
            }

            if (isBlank(testCase.getPayload())) {
                testCase.setPayload(resolvePayload(category, flow, scenario, mode,
                        payloadFile, testCase.getBookingId(), null));
            }

            if (isBlank(category) && (testCase.getSteps() == null || testCase.getSteps().isEmpty())) {
                testCase.setSteps(defaultSteps());
            }

            System.out.println("==================== EXECUTION START ====================");
            System.out.println("TestCase=" + testCase.getTestCaseId() + " | BookingID=" + testCase.getBookingId());
            progress("Execution started TestCase=" + testCase.getTestCaseId()
                    + " BookingID=" + testCase.getBookingId()
                    + " Endpoint=/execute");

            ExecutionResult result = isBlank(category)
                    ? orchestratorService.execute(testCase)
                    : executionRouterService.execute(testCase, category, flow, scenario, mode);
            long totalMs = elapsedMs(executionStartNanos);
            executionStatus = executionStatus(result);
            progress("Execution completed TestCase=" + testCase.getTestCaseId()
                    + " BookingID=" + testCase.getBookingId()
                    + " Status=" + executionStatus
                    + " TotalTimeMs=" + totalMs);
            if (!unifiedTraceReportEnabled) {
                printSummary(totalMs, result);
            }
            return result;
        } catch (RuntimeException e) {
            executionStatus = "ERROR";
            progress("Execution failed TestCase=" + defaultValue(testCase.getTestCaseId())
                    + " BookingID=" + defaultValue(testCase.getBookingId())
                    + " Status=ERROR"
                    + " Error=" + oneLine(e.getMessage())
                    + " TotalTimeMs=" + elapsedMs(executionStartNanos));
            throw e;
        } finally {
            System.out.println("==================== EXECUTION END Status=" + executionStatus + " ======================");
        }
    }

    @GetMapping("/run")
    public ResponseEntity<?> runFromBrowser(
            @RequestParam(name = "bookingId", required = false) String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "flow", required = false) String flow,
            @RequestParam(name = "scenario", required = false) String scenario,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "format", required = false) String format,
            @RequestHeader(name = "Accept", required = false) String acceptHeader) {
        ExecutionResult result = execute(null, bookingId, payloadFile, category, flow, scenario, mode);
        if (wantsPrettyOutput(format, acceptHeader)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(prettyExecutionResult(result, bookingId, category, flow, scenario, mode));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/run/pretty", produces = MediaType.TEXT_PLAIN_VALUE)
    public String runPrettyFromBrowser(
            @RequestParam(name = "bookingId", required = false) String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "flow", required = false) String flow,
            @RequestParam(name = "scenario", required = false) String scenario,
            @RequestParam(name = "mode", required = false) String mode) {
        ExecutionResult result = execute(null, bookingId, payloadFile, category, flow, scenario, mode);
        return prettyExecutionResult(result, bookingId, category, flow, scenario, mode);
    }

    @PostMapping("/jms")
    public JmsProcessingResult executeJms(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "source", defaultValue = "DATAHUB") String sourceSystem,
            @RequestParam(name = "async", defaultValue = "false") boolean async,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", required = false) String flow,
            @RequestParam(name = "scenario", required = false) String scenario,
            @RequestBody(required = false) String requestPayload) {
        return executeJmsInternal("POST", bookingId, sourceSystem, async, payloadFile, flow, scenario, requestPayload);
    }

    @GetMapping("/jms")
    public JmsProcessingResult executeJmsFromBrowser(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "source", defaultValue = "DATAHUB") String sourceSystem,
            @RequestParam(name = "async", defaultValue = "false") boolean async,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", required = false) String flow,
            @RequestParam(name = "scenario", required = false) String scenario) {
        return executeJmsInternal("GET", bookingId, sourceSystem, async, payloadFile, flow, scenario, null);
    }

    @PostMapping("/datahub")
    public JmsProcessingResult executeDataHubJms(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "system", defaultValue = "DMS") String system,
            @RequestParam(name = "async", defaultValue = "false") boolean async,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "BOOKING_UPDATE") String scenario,
            @RequestBody(required = false) String requestPayload) {
        return executeJmsInternal("POST", bookingId, system, async, payloadFile, flow, scenario, requestPayload);
    }

    @GetMapping("/datahub")
    public JmsProcessingResult executeDataHubJmsFromBrowser(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "system", defaultValue = "DMS") String system,
            @RequestParam(name = "async", defaultValue = "false") boolean async,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "BOOKING_UPDATE") String scenario) {
        return executeJmsInternal("GET", bookingId, system, async, payloadFile, flow, scenario, null);
    }

    @PostMapping("/apigee")
    public ExecutionResult executeApigeeRest(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "PACKAGEOFFER") String scenario,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "collection", required = false) String collection,
            @RequestParam(name = "brand", required = false) String brand,
            @RequestBody(required = false) String requestPayload) {
        return executeModule("TC_APIGEE_REST_JSON", "APIGEE", "APIGEE REST JSON", bookingId,
                payloadFile, flow, scenario, "SYNC", requestPayload, env, null, collection, brand);
    }

    @GetMapping("/apigee")
    public ExecutionResult executeApigeeRestFromBrowser(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "PACKAGEOFFER") String scenario,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "collection", required = false) String collection,
            @RequestParam(name = "brand", required = false) String brand) {
        return executeModule("TC_APIGEE_REST_JSON", "APIGEE", "APIGEE REST JSON", bookingId,
                payloadFile, flow, scenario, "SYNC", null, env, null, collection, brand);
    }

    @PostMapping("/vrp")
    public ExecutionResult executeVrpSoap(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "BOOKING_REQUEST") String scenario,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "system", required = false) String downstreamSystem,
            @RequestBody(required = false) String requestPayload) {
        return executeModule("TC_VRP_SOAP", "VRP", "VRP SOAP", bookingId, payloadFile,
                flow, scenario, "SYNC", requestPayload, env, downstreamSystem);
    }

    @GetMapping("/vrp")
    public ExecutionResult executeVrpSoapFromBrowser(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "BOOKING_REQUEST") String scenario,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "system", required = false) String downstreamSystem) {
        return executeModule("TC_VRP_SOAP", "VRP", "VRP SOAP", bookingId, payloadFile,
                flow, scenario, "SYNC", null, env, downstreamSystem);
    }

    @PostMapping("/nordics")
    public ExecutionResult executeNordicsRabbitMq(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "BOOKING_EVENT") String scenario,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "system", required = false) String downstreamSystem,
            @RequestParam(name = "routingKey", required = false) String routingKey,
            @RequestBody(required = false) String requestPayload) {
        return executeModule("TC_NORDICS_RABBITMQ", "NORDICS", "Nordics RabbitMQ", bookingId,
                payloadFile, flow, scenario, "ASYNC", requestPayload, env, downstreamSystem, null, null, routingKey);
    }

    @GetMapping("/nordics")
    public ExecutionResult executeNordicsRabbitMqFromBrowser(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "payload", required = false) String payloadFile,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "BOOKING_EVENT") String scenario,
            @RequestParam(name = "env", required = false) String env,
            @RequestParam(name = "system", required = false) String downstreamSystem,
            @RequestParam(name = "routingKey", required = false) String routingKey) {
        return executeModule("TC_NORDICS_RABBITMQ", "NORDICS", "Nordics RabbitMQ", bookingId,
                payloadFile, flow, scenario, "ASYNC", null, env, downstreamSystem, null, null, routingKey);
    }

    @GetMapping(value = "/generate-testcase", produces = MediaType.APPLICATION_JSON_VALUE)
    public String generateTestCaseFromLogs(
            @RequestParam(name = "bookingId", required = false) String bookingId,
            @RequestParam(name = "jobId", required = false) String jobId,
            @RequestParam(name = "corrId", required = false) String corrId,
            @RequestParam(name = "sftpProfile", required = false) String sftpProfile) throws Exception {
        long executionStartNanos = System.nanoTime();
        String executionStatus = "UNKNOWN";
        LogSearchResult logSearchResult = null;
        String reportBookingId = usableBookingId(bookingId) ? bookingId : "NA";
        System.out.println("==================== EXECUTION START ====================");
        System.out.println("TestCase=TC_GENERATE_TESTCASE | BookingID=" + reportBookingId
                + " | JobID=" + pendingValue(jobId)
                + " | CorrID=" + pendingValue(corrId));
        System.out.println("Orchestrator: Starting execution...");
        try {
            try (AutoCloseable ignored = useSftpProfile(sftpProfileForRequest(sftpProfile))) {
                logSearchResult = usableBookingId(bookingId)
                        ? logAnalyzerService.analyzeRecursive(bookingId)
                        : logAnalyzerService.searchFinalTraceDetailed(null, corrId, jobId);
            }
            validateLogExecutionResult(reportBookingId, logSearchResult);
            CorrelationIds discoveredIds = discoveredCorrelationIds(logSearchResult, jobId, corrId);
            progress("Correlation resolved TestCase=TC_GENERATE_TESTCASE"
                    + " BookingID=" + reportBookingId
                    + " JobID=" + defaultValue(discoveredIds.jobId)
                    + " CorrID=" + defaultValue(discoveredIds.corrId)
                    + " JobIDs=" + discoveredIds.jobIdsCount
                    + " CorrIDs=" + discoveredIds.corrIdsCount);
            String generatedJson = testCaseGenerator.generateJsonFromAllEvents(reportBookingId, logSearchResult.getLines());
            String businessStatus = resultStatusFromJson(generatedJson);
            String businessReason = resultReasonFromJson(generatedJson);
            executionStatus = "SUCCESS";
            progress("Execution completed TestCase=TC_GENERATE_TESTCASE"
                    + " BookingID=" + reportBookingId
                    + " JobID=" + defaultValue(discoveredIds.jobId)
                    + " CorrID=" + defaultValue(discoveredIds.corrId)
                    + " Status=COMPLETED"
                    + " BusinessStatus=" + businessStatus
                    + " TotalTimeMs=" + elapsedMs(executionStartNanos));
            printLogExecutionSummary(reportBookingId, businessStatus, businessReason,
                    elapsedMs(executionStartNanos), logSearchResult,
                    businessIssues(businessStatus, businessReason, logSearchResult));
            printPlatformReportIfEnabled(reportBookingId, logSearchResult, discoveredIds,
                    elapsedMs(executionStartNanos), businessStatus);
            return generatedJson;
        } catch (Exception e) {
            if (e instanceof LogTransferException) {
                logSearchResult = ((LogTransferException) e).getPartialResult();
            }
            executionStatus = "FAIL";
            CorrelationIds discoveredIds = discoveredCorrelationIds(logSearchResult, jobId, corrId);
            progress("Execution failed TestCase=TC_GENERATE_TESTCASE"
                    + " BookingID=" + reportBookingId
                    + " JobID=" + defaultValue(discoveredIds.jobId)
                    + " CorrID=" + defaultValue(discoveredIds.corrId)
                    + " Status=FAIL"
                    + " Error=" + oneLine(e.getMessage())
                    + " TotalTimeMs=" + elapsedMs(executionStartNanos));
            printLogExecutionSummary(reportBookingId, "FAIL", oneLine(e.getMessage()),
                    elapsedMs(executionStartNanos), logSearchResult, Arrays.asList(oneLine(e.getMessage())));
            throw e;
        } finally {
            printExecutionEnd(executionStatus);
        }
    }

    @PostMapping(value = "/generate-testcase", produces = MediaType.APPLICATION_JSON_VALUE)
    public String generateTestCaseFromRawLines(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestBody String rawLogLines) {
        List<String> lines = isBlank(rawLogLines)
                ? new ArrayList<>()
                : Arrays.asList(rawLogLines.split("\\R"));
        return testCaseGenerator.generateJsonFromAllEvents(bookingId, lines);
    }

    @GetMapping(value = "/report-preview", produces = MediaType.TEXT_PLAIN_VALUE)
    public String previewPlatformReportFromLocalLogs(
            @RequestParam(name = "bookingId") String bookingId,
            @RequestParam(name = "category", defaultValue = "DATAHUB") String category,
            @RequestParam(name = "flow", defaultValue = "DMS_BOOKING") String flow,
            @RequestParam(name = "scenario", defaultValue = "LOCAL_PREVIEW") String scenario) throws Exception {
        if (isBlank(bookingId)) {
            throw new RuntimeException("bookingId is required for local report preview");
        }

        long startNanos = System.nanoTime();
        String scope = safeScope(bookingId);
        List<TimelineEvent> timeline = timelineService.buildTimeline(scope);
        BusinessValidationResult validation = businessValidationService.validate(category, timeline);

        ReportService.ReportContext context = new ReportService.ReportContext();
        context.setTestCaseName("TC_LOCAL_REPORT_PREVIEW");
        context.setBookingId(bookingId);
        context.setCategory(category);
        context.setFlow(flow);
        context.setScenario(scenario);
        context.setProtocol("LOCAL_LOGS");
        context.setMode("PREVIEW");
        context.setFilesFound(countLocalLogFiles(scope));
        context.setFilesProcessed(countLocalLogFiles(scope));
        context.setFilesMerged(0);
        context.setLines(countTimelineSourceLines(scope));
        context.setJobIds(0);
        context.setCorrIds(0);
        context.setDepth(0);
        context.setExecutionTimeMs(0);
        context.setLogTimeMs(0);
        context.setMergeTimeMs(0);
        context.setTotalTimeMs(elapsedMs(startNanos));
        context.setTimeline(timeline);
        context.setValidation(validation);
        return reportService.buildReport(context);
    }

    private JmsProcessingResult executeJmsInternal(
            String method,
            String bookingId,
            String sourceSystem,
            boolean async,
            String payloadFile,
            String flow,
            String scenario,
            String requestPayload) {
        JmsPublishRequest publishRequest = buildJmsPublishRequest(
                bookingId, sourceSystem, async, payloadFile, flow, scenario, requestPayload);

        if (async) {
            long asyncStartNanos = System.nanoTime();
            String executionStatus = "UNKNOWN";
            System.out.println("==================== ASYNC ENQUEUE START ====================");
            progress("Execution started TestCase=TC_JMS_SIMULATION"
                    + " BookingID=" + bookingId
                    + " SourceSystem=" + sourceSystem
                    + " Endpoint=/execute/jms"
                    + " Method=" + method
                    + " Mode=ASYNC");
            try {
                JmsProcessingResult result = jmsProducerService.send(publishRequest);
                executionStatus = jmsExecutionStatus(result);
                progress("Execution completed TestCase=TC_JMS_SIMULATION"
                        + " BookingID=" + bookingId
                        + " SourceSystem=" + sourceSystem
                        + " Status=" + executionStatus
                        + " Mode=ASYNC_ENQUEUE"
                        + " TotalTimeMs=" + elapsedMs(asyncStartNanos));
                return result;
            } catch (RuntimeException e) {
                executionStatus = "ERROR";
                progress("Execution failed TestCase=TC_JMS_SIMULATION"
                        + " BookingID=" + bookingId
                        + " SourceSystem=" + sourceSystem
                        + " Status=ERROR"
                        + " Mode=ASYNC_ENQUEUE"
                        + " Error=" + oneLine(e.getMessage())
                        + " TotalTimeMs=" + elapsedMs(asyncStartNanos));
                throw e;
            } finally {
                System.out.println("==================== ASYNC ENQUEUE END Status=" + executionStatus + " ======================");
            }
        }

        long executionStartNanos = System.nanoTime();
        String executionStatus = "UNKNOWN";
        System.out.println("==================== EXECUTION START ====================");
        System.out.println("TestCase=TC_JMS_SIMULATION | BookingID=" + bookingId);
        System.out.println("Orchestrator: Starting execution...");
        if (!unifiedTraceReportEnabled) {
            progress("Execution started TestCase=TC_JMS_SIMULATION"
                    + " BookingID=" + bookingId
                    + " SourceSystem=" + sourceSystem
                    + " Endpoint=/execute/jms"
                    + " Method=" + method);
        }
        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("[API]");
            System.out.println("Endpoint=/execute/jms");
            System.out.println("Status=200");
        }

        try {
            JmsProcessingResult result = jmsProducerService.send(publishRequest);
            executionStatus = jmsExecutionStatus(result);
            if (!unifiedTraceReportEnabled) {
                progress("Execution completed TestCase=TC_JMS_SIMULATION"
                        + " BookingID=" + bookingId
                        + " SourceSystem=" + sourceSystem
                        + " Status=" + executionStatus
                        + " TotalTimeMs=" + elapsedMs(executionStartNanos));
                System.out.println("Orchestrator: Execution completed");
                printJmsSummary(elapsedMs(executionStartNanos), result);
            }
            return result;
        } catch (RuntimeException e) {
            executionStatus = "ERROR";
            progress("Execution failed TestCase=TC_JMS_SIMULATION"
                    + " BookingID=" + bookingId
                    + " SourceSystem=" + sourceSystem
                    + " Status=ERROR"
                    + " Error=" + oneLine(e.getMessage())
                    + " TotalTimeMs=" + elapsedMs(executionStartNanos));
            throw e;
        } finally {
            printExecutionEnd(executionStatus);
        }
    }

    private JmsPublishRequest buildJmsPublishRequest(
            String bookingId,
            String sourceSystem,
            boolean async,
            String payloadFile,
            String flow,
            String scenario,
            String requestPayload) {
        TestCase testCase = new TestCase();
        testCase.setTestCaseId("TC_JMS_SIMULATION");
        testCase.setBookingId(bookingId);
        testCase.setFlow(flow);
        testCase.setScenario(scenario);
        testCase.setExecutionMode(async ? "ASYNC" : "SYNC");
        testCase.setDownstreamSystem(sourceSystem);
        if (!isBlank(requestPayload)) {
            testCase.setPayload(requestPayload);
        } else if (!isBlank(payloadFile)) {
            testCase.setPayload(payloadFile);
        }

        JmsPayloadResolver.ResolvedJmsPayload resolvedPayload = jmsPayloadResolver.resolve(testCase);
        String env = jmsFlowConfig.env(testCase);
        String system = resolvedPayload.getSystem();

        JmsPublishRequest request = new JmsPublishRequest();
        request.setBookingId(bookingId);
        request.setPayload(resolvedPayload.getContent());
        request.setSourceSystem(system);
        request.setAsync(async);
        request.setEnv(env);
        request.setDestinationType(jmsDestinationType(env, system, scenario));
        request.setDestinationName(jmsDestinationName(env, system, scenario));
        request.setMessageType(jmsMessageType(env, system, scenario));
        request.setPayloadSource(resolvedPayload.getSource());
        return request;
    }

    private String jmsDestinationType(String env, String system, String scenario) {
        try {
            return (String) jmsFlowConfig.getClass()
                    .getMethod("destinationType", String.class, String.class, String.class)
                    .invoke(jmsFlowConfig, env, system, scenario);
        } catch (ReflectiveOperationException ignored) {
            return jmsFlowConfig.destinationType(env, system);
        }
    }

    private String jmsDestinationName(String env, String system, String scenario) {
        try {
            return (String) jmsFlowConfig.getClass()
                    .getMethod("destinationName", String.class, String.class, String.class)
                    .invoke(jmsFlowConfig, env, system, scenario);
        } catch (ReflectiveOperationException ignored) {
            return jmsFlowConfig.destinationName(env, system);
        }
    }

    private String jmsMessageType(String env, String system, String scenario) {
        try {
            return (String) jmsFlowConfig.getClass()
                    .getMethod("messageType", String.class, String.class, String.class)
                    .invoke(jmsFlowConfig, env, system, scenario);
        } catch (ReflectiveOperationException ignored) {
            return jmsFlowConfig.messageType(env, system);
        }
    }

    private ExecutionResult executeModule(
            String testCaseName,
            String system,
            String stepName,
            String bookingId,
            String payloadFile,
            String flow,
            String scenario,
            String mode,
            String requestPayload,
            String env,
            String downstreamSystem) {
        return executeModule(testCaseName, system, stepName, bookingId, payloadFile,
                flow, scenario, mode, requestPayload, env, downstreamSystem, null, null);
    }

    private ExecutionResult executeModule(
            String testCaseName,
            String system,
            String stepName,
            String bookingId,
            String payloadFile,
            String flow,
            String scenario,
            String mode,
            String requestPayload,
            String env,
            String downstreamSystem,
            String collection,
            String brand) {
        return executeModule(testCaseName, system, stepName, bookingId, payloadFile,
                flow, scenario, mode, requestPayload, env, downstreamSystem, collection, brand, null);
    }

    private ExecutionResult executeModule(
            String testCaseName,
            String system,
            String stepName,
            String bookingId,
            String payloadFile,
            String flow,
            String scenario,
            String mode,
            String requestPayload,
            String env,
            String downstreamSystem,
            String collection,
            String brand,
            String routingKey) {
        TestCase testCase = new TestCase();
        testCase.setTestCaseId(testCaseName);
        testCase.setBookingId(bookingId);
        testCase.setEnv(env);
        testCase.setFlow(flow);
        testCase.setScenario(scenario);
        testCase.setDownstreamSystem(downstreamSystem);
        testCase.setCollection(collection);
        testCase.setBrand(brand);
        testCase.setRoutingKey(routingKey);
        if (isRabbitSystem(system)) {
            if (!isBlank(requestPayload)) {
                testCase.setPayload(requestPayload);
            } else if (!isBlank(payloadFile)) {
                testCase.setPayload(payloadFile);
            } else {
                testCase.setPayload(resolvePayload(system, flow, scenario, mode, payloadFile, bookingId, null));
            }
        } else if (shouldResolvePayloadInGateway(system, payloadFile, requestPayload)) {
            testCase.setPayload(resolvePayload(system, flow, scenario, mode, payloadFile, bookingId, requestPayload));
        }
        testCase.setSteps(singleStep(stepName, system));
        return execute(testCase, bookingId, payloadFile, system, flow, scenario, mode);
    }

    private boolean shouldResolvePayloadInGateway(String system, String payloadFile, String requestPayload) {
        if (!"VRP".equalsIgnoreCase(defaultValue(system))) {
            if ("APIGEE".equalsIgnoreCase(defaultValue(system))) {
                return restPayloadCatalogEnabled || !isBlank(payloadFile) || !isBlank(requestPayload);
            }
            return true;
        }
        return soapPayloadCatalogEnabled || !isBlank(payloadFile) || !isBlank(requestPayload);
    }

    private boolean isRabbitSystem(String system) {
        String normalized = defaultValue(system).toUpperCase();
        return "NORDICS".equals(normalized) || "RABBIT".equals(normalized) || "RABBITMQ".equals(normalized);
    }

    private String sftpProfileForRequest(String sftpProfile) {
        String normalized = defaultValue(sftpProfile).trim().toLowerCase();
        if ("rabbit".equals(normalized)
                || "rabbitmq".equals(normalized)
                || "nordics".equals(normalized)
                || "rabbit-nordics".equals(normalized)) {
            return "rabbit-nordics";
        }
        if ("rest".equals(normalized)
                || "api".equals(normalized)
                || "apigee".equals(normalized)
                || "apigee-rest".equals(normalized)) {
            return "apigee-rest";
        }
        return "default";
    }

    private AutoCloseable useSftpProfile(String profile) {
        try {
            Class<?> context = Class.forName("com.hcl.observability.sftp.SftpProfileContext");
            Object scope = context.getMethod("use", String.class).invoke(null, profile);
            if (scope instanceof AutoCloseable) {
                return (AutoCloseable) scope;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return () -> {
        };
    }

    private String resolvePayload(
            String category,
            String flow,
            String scenario,
            String mode,
            String payloadFile,
            String bookingId,
            String requestPayload) {
        return payloadCatalogService.resolve(category, flow, scenario, mode, payloadFile, bookingId, requestPayload)
                .getContent();
    }

    private List<TestStep> defaultSteps() {
        List<TestStep> steps = new ArrayList<>();

        TestStep step1 = new TestStep();
        step1.setStepName("Booking API");
        step1.setSystem("REST");

        TestStep step2 = new TestStep();
        step2.setStepName("VRP Processing");
        step2.setSystem("VRP");

        steps.add(step1);
        steps.add(step2);

        return steps;
    }

    private List<TestStep> singleStep(String stepName, String system) {
        TestStep step = new TestStep();
        step.setStepName(stepName);
        step.setSystem(system);
        step.setEvent("REQUEST");
        List<TestStep> steps = new ArrayList<>();
        steps.add(step);
        return steps;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void progress(String message) {
        System.out.println("[PROGRESS] " + message);
    }

    private void printExecutionEnd(String executionStatus) {
        if (unifiedTraceReportEnabled && !"ERROR".equalsIgnoreCase(executionStatus)) {
            System.out.println("==================== EXECUTION END ======================");
            return;
        }
        System.out.println("==================== EXECUTION END Status=" + executionStatus + " ======================");
    }

    private String executionStatus(ExecutionResult result) {
        String status = result == null || isBlank(result.getFinalStatus()) ? "UNKNOWN" : result.getFinalStatus();
        return "PASS".equalsIgnoreCase(status) ? "SUCCESS" : status;
    }

    private String jmsExecutionStatus(JmsProcessingResult result) {
        return result == null || isBlank(result.getStatus()) ? "UNKNOWN" : result.getStatus();
    }

    private String defaultValue(String value) {
        return isBlank(value) ? "NA" : value;
    }

    private String pendingValue(String value) {
        return isBlank(value) ? "PENDING_DISCOVERY" : value;
    }

    private boolean usableBookingId(String value) {
        return !isBlank(value) && !"NA".equalsIgnoreCase(value.trim());
    }

    private CorrelationIds discoveredCorrelationIds(
            LogSearchResult result,
            String requestedJobId,
            String requestedCorrId) {
        Set<String> jobIds = new LinkedHashSet<>();
        Set<String> corrIds = new LinkedHashSet<>();
        addIfPresent(jobIds, requestedJobId);
        addIfPresent(corrIds, requestedCorrId);

        if (result != null && result.getLines() != null) {
            for (String line : result.getLines()) {
                addMatches(jobIds, JOB_ID_PATTERN.matcher(line));
                addMatches(corrIds, CORR_ID_PATTERN.matcher(line));
            }
        }

        return new CorrelationIds(firstValue(jobIds), firstValue(corrIds), jobIds.size(), corrIds.size());
    }

    private void addMatches(Set<String> values, Matcher matcher) {
        while (matcher.find()) {
            addIfPresent(values, matcher.group(1));
        }
    }

    private void addIfPresent(Set<String> values, String value) {
        if (!isBlank(value) && !"NA".equalsIgnoreCase(value.trim())) {
            values.add(value.trim().replaceAll("[,;\\])}]+$", ""));
        }
    }

    private String firstValue(Set<String> values) {
        return values == null || values.isEmpty() ? "" : values.iterator().next();
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private void printSummary(long totalMs, ExecutionResult result) {
        String status = executionStatus(result);

        System.out.println();
        System.out.println("-------------------- SUMMARY ---------------------------");
        System.out.println("[SUMMARY]");
        System.out.println("TotalTimeMs=" + totalMs + " Status=" + status);
    }

    private void printJmsSummary(long totalMs, JmsProcessingResult result) {
        String status = result == null || isBlank(result.getStatus()) ? "UNKNOWN" : result.getStatus();

        System.out.println();
        System.out.println("-------------------- SUMMARY ---------------------------");
        System.out.println("[SUMMARY]");
        System.out.println("TotalTimeMs=" + totalMs + " Status=" + status);
    }

    private void validateLogExecutionResult(String bookingId, LogSearchResult result) {
        if (result == null) {
            throw new RuntimeException("No LogAnalyzer result returned for BookingID=" + bookingId);
        }

        if (result.getBlockLines() > 0
                && result.getFilesTransferred() == 0
                && result.getCompleteLocalFiles() == 0) {
            throw new RuntimeException("ZERO_FILE_TRANSFER: BlockLines=" + result.getBlockLines()
                    + " but no local evidence file was transferred");
        }

        if (result.getFilesTransferred() == 0
                && result.getCompleteLocalFiles() == 0
                && (result.getLines() == null || result.getLines().isEmpty())) {
            throw new RuntimeException("NO_MEANINGFUL_LOG_DATA: no new local evidence files transferred");
        }

        if (!hasMinimumTraceEvidence(result.getLines())) {
            throw new RuntimeException("NO_FLOW_EVIDENCE_DETECTED: expected REQUEST plus REPLY/RESPONSE, PUBLISH, CONFIRM, or ERROR evidence not found");
        }
    }

    private boolean hasMinimumTraceEvidence(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }

        boolean requestFound = false;
        boolean responseFound = false;
        boolean publishFound = false;
        boolean confirmFound = false;
        boolean errorFound = false;
        for (String line : lines) {
            String upper = line == null ? "" : line.toUpperCase();
            if (upper.contains("REQUEST")) {
                requestFound = true;
            }
            if (upper.contains("REPLY") || upper.contains("RESPONSE")) {
                responseFound = true;
            }
            if (upper.contains("PUBLISH")) {
                publishFound = true;
            }
            if (upper.contains("CONFIRM")) {
                confirmFound = true;
            }
            if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("STACKTRACE")) {
                errorFound = true;
            }
            if (requestFound && (responseFound || publishFound || confirmFound || errorFound)) {
                return true;
            }
        }

        return false;
    }

    private List<String> businessIssues(String status, String reason, LogSearchResult result) {
        List<String> issues = new ArrayList<>();
        if (!isSuccessStatus(status)) {
            issues.add(isBlank(reason) ? "Business validation failed" : reason);
        }
        String traceMessage = result == null ? "" : result.getMessage();
        if (!isBlank(traceMessage) && traceMessage.contains("searchedTokens=")) {
            String searchedTokens = metricValue(traceMessage, "searchedTokens");
            String tokens = metricValue(traceMessage, "Tokens");
            if (!"NA".equals(searchedTokens) && !"NA".equals(tokens) && !searchedTokens.equals(tokens)) {
                issues.add("Trace expansion searched " + searchedTokens + " of " + tokens
                        + " discovered tokens; remaining IDs were bounded by safety limits");
            }
        }
        return issues;
    }

    private void printPlatformReportIfEnabled(
            String bookingId,
            LogSearchResult logSearchResult,
            CorrelationIds discoveredIds,
            long totalMs,
            String businessStatus) throws Exception {
        if (!platformReportEnabled) {
            return;
        }

        String scope = platformReportScope(bookingId, discoveredIds);
        List<TimelineEvent> timeline = timelineService.buildTimeline(scope);
        BusinessValidationResult validation = businessValidationService.validate("DATAHUB", timeline);

        ReportService.ReportContext context = new ReportService.ReportContext();
        context.setTestCaseName("TC_GENERATE_TESTCASE");
        context.setBookingId(bookingId);
        context.setCategory("DATAHUB");
        context.setFlow("DMS_BOOKING");
        context.setScenario("GENERATE_TESTCASE");
        context.setProtocol("SFTP_LOG");
        context.setMode("ANALYZE");
        context.setFilesFound(logSearchResult == null ? 0 : logSearchResult.getUniqueFilesFound());
        context.setFilesProcessed(logSearchResult == null ? 0 : logSearchResult.getFilesTransferred());
        context.setFilesMerged(0);
        context.setLines(logSearchResult == null ? 0 : logSearchResult.getLines().size());
        context.setDepth(parseMetric(logSearchResult == null ? "" : logSearchResult.getMessage(), "Depth"));
        context.setJobIds(discoveredIds == null ? 0 : discoveredIds.jobIdsCount);
        context.setCorrIds(discoveredIds == null ? 0 : discoveredIds.corrIdsCount);
        context.setExecutionTimeMs(0);
        context.setLogTimeMs(totalMs);
        context.setMergeTimeMs(0);
        context.setTotalTimeMs(totalMs);
        context.setTimeline(timeline);
        context.setValidation(validation);

        System.out.println();
        System.out.println("==================== PLATFORM REPORT ====================");
        System.out.print(reportService.buildReport(context));
        System.out.println("==================== PLATFORM REPORT END ================");
        if (!isSuccessStatus(businessStatus) && validation.isSuccess()) {
            progress("Platform report validation differs from generated JSON BusinessStatus="
                    + businessStatus + " PlatformStatus=" + validation.getStatus());
        }
    }

    private String platformReportScope(String bookingId, CorrelationIds discoveredIds) {
        if (usableBookingId(bookingId)) {
            return bookingId;
        }
        if (discoveredIds != null && !isBlank(discoveredIds.jobId)) {
            return "JOB_" + safeScope(discoveredIds.jobId);
        }
        if (discoveredIds != null && !isBlank(discoveredIds.corrId)) {
            return "CORR_" + safeScope(discoveredIds.corrId);
        }
        return "UNSCOPED";
    }

    private int countLocalLogFiles(String scope) {
        File scopeDir = new File(localLogDir, safeScope(scope));
        File[] files = scopeDir.listFiles(file -> file.isFile()
                && file.getName().contains(".log")
                && !file.getName().startsWith("processed_files_"));
        return files == null ? 0 : files.length;
    }

    private int countTimelineSourceLines(String scope) throws Exception {
        File scopeDir = new File(localLogDir, safeScope(scope));
        File[] files = scopeDir.listFiles(file -> file.isFile()
                && file.getName().contains(".log")
                && !file.getName().startsWith("processed_files_"));
        if (files == null) {
            return 0;
        }

        int lines = 0;
        for (File file : files) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                while (reader.readLine() != null) {
                    lines++;
                }
            }
        }
        return lines;
    }

    private String safeScope(String value) {
        return value == null ? "NA" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private int parseMetric(String message, String key) {
        String value = metricValue(message, key);
        if ("NA".equals(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private boolean isSuccessStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "PASS".equalsIgnoreCase(status);
    }

    private void printLogExecutionSummary(
            String bookingId,
            String status,
            String reason,
            long totalMs,
            LogSearchResult result,
            List<String> issues) {
        int grepCalls = result == null ? 0 : result.getAttempts();
        int retries = Math.max(0, grepCalls - 1);
        int filesFound = result == null ? 0 : result.getUniqueFilesFound();
        int filesTransferred = result == null ? 0 : result.getFilesTransferred();
        int completeLocalFiles = result == null ? 0 : result.getCompleteLocalFiles();
        int blockLines = result == null ? 0 : result.getBlockLines();
        String traceMessage = result == null ? "" : result.getMessage();

        System.out.println();
        System.out.println("[SUMMARY]");
        System.out.println("BookingID=" + bookingId);
        System.out.println("Status=" + status);
        System.out.println("Reason=" + reason);
        System.out.println();
        System.out.println("[PERF]");
        System.out.println("TotalTime=" + totalMs);
        System.out.println("GrepCalls=" + grepCalls);
        System.out.println("Retries=" + retries);
        System.out.println();
        System.out.println("[DATA]");
        System.out.println("FilesFound=" + filesFound);
        System.out.println("FilesTransferred=" + filesTransferred);
        System.out.println("CompleteLocalFiles=" + completeLocalFiles);
        System.out.println("BlockLines=" + blockLines);
        System.out.println();
        System.out.println("[TRACE]");
        System.out.println("Depth=" + metricValue(traceMessage, "Depth"));
        System.out.println("JobIDs=" + metricValue(traceMessage, "JobIDs"));
        System.out.println("CorrIDs=" + metricValue(traceMessage, "CorrIDs"));
        System.out.println("Tokens=" + metricValue(traceMessage, "Tokens"));
        System.out.println();
        System.out.println("[ISSUE]");
        if (issues == null || issues.isEmpty()) {
            System.out.println("- None");
            return;
        }
        for (String issue : issues) {
            System.out.println("- " + issue);
        }
    }

    private String metricValue(String message, String key) {
        if (isBlank(message) || isBlank(key)) {
            return "NA";
        }

        String marker = key + "=";
        int start = message.indexOf(marker);
        if (start < 0) {
            return "NA";
        }

        int valueStart = start + marker.length();
        int valueEnd = valueStart;
        while (valueEnd < message.length() && Character.isDigit(message.charAt(valueEnd))) {
            valueEnd++;
        }

        return valueEnd > valueStart ? message.substring(valueStart, valueEnd) : "NA";
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String resultStatusFromJson(String json) {
        String status = jsonFieldAfter(json, "result", "status");
        return "FAIL".equalsIgnoreCase(status) ? "FAIL" : "PASS";
    }

    private String resultReasonFromJson(String json) {
        String reason = jsonFieldAfter(json, "result", "reason");
        return isBlank(reason) ? "Generated business validation result" : reason;
    }

    private String jsonFieldAfter(String json, String section, String field) {
        if (isBlank(json) || isBlank(section) || isBlank(field)) {
            return "";
        }

        int sectionIndex = json.indexOf("\"" + section + "\"");
        if (sectionIndex < 0) {
            return "";
        }

        int fieldIndex = json.indexOf("\"" + field + "\"", sectionIndex);
        if (fieldIndex < 0) {
            return "";
        }

        int colonIndex = json.indexOf(':', fieldIndex);
        if (colonIndex < 0) {
            return "";
        }

        int openQuote = json.indexOf('"', colonIndex + 1);
        if (openQuote < 0) {
            return "";
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = openQuote + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaped) {
                value.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                return value.toString();
            } else {
                value.append(ch);
            }
        }
        return "";
    }

    private String prettyExecutionResult(
            ExecutionResult result,
            String bookingId,
            String category,
            String flow,
            String scenario,
            String mode) {
        ExecutionResult safeResult = result == null ? new ExecutionResult() : result;
        List<StepResult> steps = safeResult.getSteps() == null ? new ArrayList<>() : safeResult.getSteps();
        String status = defaultValue(safeResult.getFinalStatus());
        String reason = firstFailureMessage(steps);
        if (isBlank(reason)) {
            reason = "All critical execution steps completed successfully";
        }
        FailureSummary failureSummary = summarizeFailure(steps, reason);
        LocalEvidenceReport evidenceReport = buildLocalEvidenceReport(bookingId, steps);

        StringBuilder report = new StringBuilder();
        report.append("==================== EXECUTION RESULT ====================")
                .append(System.lineSeparator());
        report.append("TestCase     : ").append(defaultValue(safeResult.getTestCaseId())).append(System.lineSeparator());
        report.append("BookingID    : ").append(defaultValue(bookingId)).append(System.lineSeparator());
        report.append("Category     : ").append(defaultValue(category)).append(System.lineSeparator());
        report.append("Flow         : ").append(defaultValue(flow)).append(System.lineSeparator());
        report.append("Scenario     : ").append(defaultValue(scenario)).append(System.lineSeparator());
        report.append("Mode         : ").append(defaultValue(mode)).append(System.lineSeparator());
        report.append("Service Flow : ")
                .append("SourceFiles=")
                .append(evidenceReport.sourceFileCount)
                .append("/")
                .append(evidenceReport.availableFileCount)
                .append(" | ")
                .append(evidenceReport.serviceFlow)
                .append(System.lineSeparator());
        report.append(EVIDENCE_SEPARATOR).append(System.lineSeparator());
        report.append("Service Flow Evidents:").append(System.lineSeparator());
        report.append(EVIDENCE_SEPARATOR).append(System.lineSeparator());
        appendServiceFlowEvidence(report, evidenceReport);
        report.append(EVIDENCE_SEPARATOR).append(System.lineSeparator());
        report.append(System.lineSeparator());

        report.append("[STATUS]").append(System.lineSeparator());
        report.append("Result       : ").append(status).append(System.lineSeparator());
        report.append("Reason       : ").append(failureSummary.reason).append(System.lineSeparator());
        report.append("FailurePoint : ").append(failureSummary.failurePoint).append(System.lineSeparator());
        report.append("Action       : ").append(failureSummary.action).append(System.lineSeparator());
        report.append(System.lineSeparator());

        report.append("[COUNTS]").append(System.lineSeparator());
        report.append("TotalSteps   : ").append(steps.size()).append(System.lineSeparator());
        report.append("Passed       : ").append(countSteps(steps, "PASS")).append(System.lineSeparator());
        report.append("Warnings     : ").append(countSteps(steps, "WARN")).append(System.lineSeparator());
        report.append("Failed       : ").append(countFailures(steps)).append(System.lineSeparator());
        report.append(System.lineSeparator());

        appendKeyEvidence(report, steps, bookingId, evidenceReport);
        appendStepTable(report, steps);

        report.append("===================== EXECUTION END =====================")
                .append(System.lineSeparator());
        return report.toString();
    }

    private boolean wantsPrettyOutput(String format, String acceptHeader) {
        if ("json".equalsIgnoreCase(format)) {
            return false;
        }
        if ("pretty".equalsIgnoreCase(format) || "text".equalsIgnoreCase(format)) {
            return true;
        }
        return !isBlank(acceptHeader) && acceptHeader.toLowerCase().contains("text/html");
    }

    private void appendKeyEvidence(
            StringBuilder report,
            List<StepResult> steps,
            String bookingId,
            LocalEvidenceReport evidenceReport) {
        report.append("[KEY EVIDENCE]").append(System.lineSeparator());
        report.append("Local Evidence:").append(System.lineSeparator());
        report.append("  Folder     = ").append(localEvidencePath(bookingId)).append(System.lineSeparator());
        report.append("  Files      = ").append(evidenceReport.fileSummary).append(System.lineSeparator());
        report.append("  Skipped    = ").append(evidenceReport.skippedFileCount).append(" unrelated local files")
                .append(System.lineSeparator());
        report.append(System.lineSeparator());
        appendSearchEvidence(report, "Booking Search", stepMessage(steps, "BookingID Log Search"));
        appendSearchEvidence(report, "Trace Search", stepMessage(steps, "Final Trace Search"));
        report.append("Correlation:").append(System.lineSeparator());
        report.append("  CorrID     = ")
                .append(afterPrefix(stepMessage(steps, "CorrID Extraction"), "CorrID:"))
                .append(System.lineSeparator());
        report.append("  JobID      = ")
                .append(afterPrefix(stepMessage(steps, "JobID Extraction"), "JobID:"))
                .append(System.lineSeparator());
        report.append(System.lineSeparator());
    }

    private void appendServiceFlowEvidence(StringBuilder report, LocalEvidenceReport evidenceReport) {
        if (evidenceReport.files.isEmpty()) {
            report.append("No relevant local evidence lines found").append(System.lineSeparator());
            return;
        }

        for (EvidenceFile evidenceFile : evidenceReport.files) {
            report.append(evidenceFile.fileName).append(System.lineSeparator());
            for (String line : evidenceFile.lines) {
                report.append(line).append(System.lineSeparator());
                report.append(System.lineSeparator());
            }
            report.append(EVIDENCE_SEPARATOR).append(System.lineSeparator());
            report.append(System.lineSeparator());
        }
    }

    private LocalEvidenceReport buildLocalEvidenceReport(String bookingId, List<StepResult> steps) {
        LocalEvidenceReport report = new LocalEvidenceReport();
        Set<String> tokens = evidenceTokens(bookingId, steps);
        if (isBlank(bookingId)) {
            report.serviceFlow = "NA";
            report.fileSummary = "NA";
            return report;
        }

        File[] files = localLogFiles(bookingId);
        report.availableFileCount = files.length;
        if (files.length == 0) {
            report.serviceFlow = "NA";
            report.fileSummary = "NA";
            return report;
        }

        for (int pass = 0; pass < LOCAL_EVIDENCE_EXPANSION_PASSES; pass++) {
            boolean tokensExpanded = false;
            for (File file : files) {
                EvidenceFile evidenceFile = relevantEvidence(file, tokens);
                if (evidenceFile.lines.isEmpty()) {
                    continue;
                }
                EvidenceFile existing = report.fileByName(file.getName());
                if (existing == null) {
                    report.files.add(evidenceFile);
                    existing = evidenceFile;
                } else {
                    existing.addLines(evidenceFile.lines);
                }
                tokensExpanded = extractEvidenceTokens(existing.lines, tokens) || tokensExpanded;
            }
            if (!tokensExpanded) {
                break;
            }
        }

        report.sourceFileCount = report.files.size();
        report.skippedFileCount = Math.max(0, report.availableFileCount - report.sourceFileCount);
        report.fileSummary = evidenceFileSummary(report.files);
        report.serviceFlow = abstractServiceFlow(report.files);
        return report;
    }

    private Set<String> evidenceTokens(String bookingId, List<StepResult> steps) {
        Set<String> tokens = new LinkedHashSet<>();
        addEvidenceToken(tokens, bookingId);
        addEvidenceToken(tokens, afterPrefix(stepMessage(steps, "CorrID Extraction"), "CorrID:"));
        addEvidenceToken(tokens, afterPrefix(stepMessage(steps, "JobID Extraction"), "JobID:"));
        return tokens;
    }

    private void addEvidenceToken(Set<String> tokens, String value) {
        if (!isBlank(value) && !"NA".equalsIgnoreCase(value.trim())) {
            tokens.add(value.trim());
        }
    }

    private File[] localLogFiles(String bookingId) {
        File scopeDir = new File(localLogDir, safeScope(bookingId));
        File[] files = scopeDir.listFiles(file -> file.isFile()
                && file.getName().contains(".log")
                && !file.getName().equals(safeScope(bookingId) + ".log")
                && !file.getName().startsWith("processed_files_"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (left, right) -> {
            int modified = Long.compare(left.lastModified(), right.lastModified());
            return modified != 0 ? modified : left.getName().compareToIgnoreCase(right.getName());
        });
        return files;
    }

    private EvidenceFile relevantEvidence(File file, Set<String> tokens) {
        EvidenceFile evidenceFile = new EvidenceFile(file.getName());
        boolean capturingBlock = false;
        boolean includeContinuation = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                boolean timestamped = isTimestampedLogLine(line);
                boolean relevant = containsAnyEvidenceToken(line, tokens);
                if (timestamped) {
                    if (relevant) {
                        capturingBlock = true;
                        includeContinuation = true;
                    } else if (capturingBlock && hasCorrelationMarker(line)) {
                        capturingBlock = false;
                        includeContinuation = false;
                    }
                }

                if (relevant || capturingBlock || (includeContinuation && !timestamped)) {
                    evidenceFile.lines.add(line);
                    includeContinuation = capturingBlock || !timestamped;
                }
            }
        } catch (Exception ignored) {
            return evidenceFile;
        }
        return evidenceFile;
    }

    private boolean extractEvidenceTokens(List<String> lines, Set<String> tokens) {
        boolean added = false;
        for (String line : lines) {
            added = addMatchesAsEvidenceTokens(tokens, JOB_ID_PATTERN.matcher(line)) || added;
            added = addMatchesAsEvidenceTokens(tokens, CORR_ID_PATTERN.matcher(line)) || added;
            Matcher bizKeyMatcher = BIZ_KEY_PATTERN.matcher(line == null ? "" : line);
            while (bizKeyMatcher.find()) {
                String bizKey = bizKeyMatcher.group(1);
                if (!isBlank(bizKey) && !"NA".equalsIgnoreCase(bizKey.trim())) {
                    added = tokens.add(bizKey.trim()) || added;
                }
            }
        }
        return added;
    }

    private boolean addMatchesAsEvidenceTokens(Set<String> tokens, Matcher matcher) {
        boolean added = false;
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!isBlank(token) && !"NA".equalsIgnoreCase(token.trim())) {
                added = tokens.add(token.trim().replaceAll("[,;\\])}]+$", "")) || added;
            }
        }
        return added;
    }

    private boolean hasCorrelationMarker(String line) {
        if (line == null) {
            return false;
        }
        return line.contains("JobID:") || line.contains("JobID=")
                || line.contains("CorrID:") || line.contains("CorrID=")
                || line.contains("JMSCorrelationID");
    }

    private boolean containsAnyEvidenceToken(String line, Set<String> tokens) {
        if (line == null || tokens == null || tokens.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            if (!isBlank(token) && line.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTimestampedLogLine(String line) {
        return line != null && (line.matches("^\\d{4}\\s+[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}:\\d{1,3}.*")
                || line.matches("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*"));
    }

    private String evidenceFileSummary(List<EvidenceFile> files) {
        if (files == null || files.isEmpty()) {
            return "NA";
        }
        List<String> names = new ArrayList<>();
        for (EvidenceFile file : files) {
            names.add(file.fileName);
            if (names.size() >= 8) {
                break;
            }
        }
        if (files.size() > names.size()) {
            names.add("+" + (files.size() - names.size()) + " more");
        }
        return String.join(", ", names);
    }

    private String abstractServiceFlow(List<EvidenceFile> files) {
        List<String> services = new ArrayList<>();
        String previous = "";
        for (EvidenceFile file : files) {
            for (String line : file.lines) {
                String service = serviceFromEvidenceLine(line, file.fileName);
                if (isBlank(service) || service.equals(previous)) {
                    continue;
                }
                services.add(service);
                previous = service;
                if (services.size() >= 20) {
                    break;
                }
            }
            if (services.size() >= 20) {
                break;
            }
        }
        return services.isEmpty() ? "NA" : String.join(" ---> ", services);
    }

    private String serviceFromEvidenceLine(String line, String fileName) {
        String[] fields = line == null ? new String[0] : line.split("\\t");
        if (fields.length >= 6 && !isBlank(fields[5])) {
            return normalizeServiceName(fields[5].trim(), line);
        }
        return normalizeServiceName(fileName.replaceAll("\\.log(?:\\.\\d+)?$", ""), line);
    }

    private String normalizeServiceName(String service, String line) {
        String upperLine = line == null ? "" : line.toUpperCase();
        if (upperLine.contains("ATCORE") || "ATCORE_DH".equalsIgnoreCase(service) || "ANITEVRP".equalsIgnoreCase(service)) {
            return "ATCORE";
        }
        if (upperLine.contains("MONGODB")) {
            return "MONGODB";
        }
        return defaultValue(service);
    }

    private String localEvidencePath(String bookingId) {
        if (isBlank(bookingId)) {
            return defaultValue(localLogDir);
        }
        return new File(localLogDir, safeScope(bookingId)).getPath();
    }

    private void appendSearchEvidence(StringBuilder report, String title, String message) {
        report.append(title).append(":").append(System.lineSeparator());
        report.append("  Source     = ").append(searchSource(message)).append(System.lineSeparator());
        report.append("  Lines      = ").append(metricValue(message, "lines")).append(System.lineSeparator());
        report.append("  Files      = ").append(metricValue(message, "files")).append(System.lineSeparator());
        report.append("  Remote     = ").append(remoteStatus(message)).append(System.lineSeparator());
        report.append(System.lineSeparator());
    }

    private String searchSource(String message) {
        if (containsIgnoreCase(message, "Local complete evidence reused")) {
            return "Local (reused)";
        }
        if (containsIgnoreCase(message, "remote skipped=true")) {
            return "Local (reused)";
        }
        if (containsIgnoreCase(message, "Logs found")) {
            return "Remote scan";
        }
        return "NA";
    }

    private String remoteStatus(String message) {
        if (containsIgnoreCase(message, "remote skipped=true")) {
            return "Skipped";
        }
        if (containsIgnoreCase(message, "remote skipped=false")) {
            return "Used";
        }
        if (containsIgnoreCase(message, "Remote scan")) {
            return "Used";
        }
        return "NA";
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null
                && expected != null
                && value.toLowerCase().contains(expected.toLowerCase());
    }

    private void appendStepTable(StringBuilder report, List<StepResult> steps) {
        report.append("[STEPS]").append(System.lineSeparator());
        report.append(String.format("%-3s %-8s %-34s %s%n", "#", "Status", "Step", "Message"));
        report.append(String.format("%-3s %-8s %-34s %s%n", "---", "--------", "----------------------------------",
                "------------------------------------------------------------"));

        for (int i = 0; i < steps.size(); i++) {
            StepResult step = steps.get(i);
            report.append(String.format("%-3d %-8s %-34s %s%n",
                    i + 1,
                    defaultValue(step.getStatus()),
                    abbreviate(defaultValue(step.getStepName()), 34),
                    abbreviate(defaultValue(step.getMessage()), 120)));
        }
        report.append(System.lineSeparator());
    }

    private String firstFailureMessage(List<StepResult> steps) {
        for (StepResult step : steps) {
            if (step != null && ("FAIL".equalsIgnoreCase(step.getStatus())
                    || "ERROR".equalsIgnoreCase(step.getStatus()))) {
                return defaultValue(step.getStepName()) + ": " + defaultValue(step.getMessage());
            }
        }
        return "";
    }

    private FailureSummary summarizeFailure(List<StepResult> steps, String fallbackReason) {
        if (countFailures(steps) == 0) {
            return new FailureSummary(
                    "All critical execution steps completed successfully",
                    "None",
                    "No action required");
        }

        StepResult timelineFailure = findStep(steps, "Timeline Validation");
        if (timelineFailure != null && "FAIL".equalsIgnoreCase(timelineFailure.getStatus())) {
            String message = defaultValue(timelineFailure.getMessage());
            String missingEvent = extractBetween(message, "but ", " is missing");
            String operation = extractBetween(message, "operation ", " and CorrID");
            String ids = extractBetween(message, "CorrID: [", "]");
            String action = "Check local evidence for " + defaultValue(operation)
                    + " using " + correlationSearch(ids);
            String reason = "Functional flow completed, but timeline validation failed because "
                    + defaultValue(missingEvent)
                    + " is missing after REQUEST.";
            return new FailureSummary(
                    abbreviate(reason, 180),
                    defaultValue(operation),
                    abbreviate(action, 180));
        }

        if ("PASS".equalsIgnoreCase(fallbackReason) || isBlank(fallbackReason)) {
            return new FailureSummary(
                    "All critical execution steps completed successfully",
                    "None",
                    "No action required");
        }

        return new FailureSummary(
                abbreviate(fallbackReason, 180),
                firstFailedStepName(steps),
                "Review failed step details below");
    }

    private StepResult findStep(List<StepResult> steps, String stepName) {
        for (StepResult step : steps) {
            if (step != null && stepName.equals(step.getStepName())) {
                return step;
            }
        }
        return null;
    }

    private String firstFailedStepName(List<StepResult> steps) {
        for (StepResult step : steps) {
            if (step != null && ("FAIL".equalsIgnoreCase(step.getStatus())
                    || "ERROR".equalsIgnoreCase(step.getStatus()))) {
                return defaultValue(step.getStepName());
            }
        }
        return "None";
    }

    private String correlationSearch(String ids) {
        if (isBlank(ids)) {
            return "the CorrID/JobID from the failed timeline step";
        }
        String[] parts = ids.split(",");
        String corrId = parts.length > 0 ? parts[0].trim() : "";
        String bookingId = parts.length > 1 ? parts[1].trim() : "";
        String jobId = parts.length > 2 ? parts[2].trim() : "";
        List<String> searchParts = new ArrayList<>();
        if (!isBlank(jobId)) {
            searchParts.add("JobID=" + jobId);
        }
        if (!isBlank(corrId)) {
            searchParts.add("CorrID=" + corrId);
        }
        if (!isBlank(bookingId)) {
            searchParts.add("BookingID=" + bookingId);
        }
        return searchParts.isEmpty() ? "the failed timeline correlation IDs" : String.join(", ", searchParts);
    }

    private String extractBetween(String value, String startMarker, String endMarker) {
        if (isBlank(value) || isBlank(startMarker) || isBlank(endMarker)) {
            return "";
        }
        int start = value.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        start += startMarker.length();
        int end = value.indexOf(endMarker, start);
        if (end < 0 || end <= start) {
            return "";
        }
        return value.substring(start, end).trim();
    }

    private String stepMessage(List<StepResult> steps, String stepName) {
        for (StepResult step : steps) {
            if (step != null && stepName.equals(step.getStepName())) {
                return abbreviate(defaultValue(step.getMessage()), 140);
            }
        }
        return "NA";
    }

    private String afterPrefix(String value, String prefix) {
        if (isBlank(value) || isBlank(prefix)) {
            return "NA";
        }
        return value.startsWith(prefix) ? value.substring(prefix.length()).trim() : value;
    }

    private int countSteps(List<StepResult> steps, String status) {
        int count = 0;
        for (StepResult step : steps) {
            if (step != null && status.equalsIgnoreCase(step.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private int countFailures(List<StepResult> steps) {
        int count = 0;
        for (StepResult step : steps) {
            if (step != null && ("FAIL".equalsIgnoreCase(step.getStatus())
                    || "ERROR".equalsIgnoreCase(step.getStatus()))) {
                count++;
            }
        }
        return count;
    }

    private String abbreviate(String value, int maxLength) {
        String safeValue = defaultValue(value).replace('\r', ' ').replace('\n', ' ').trim();
        if (safeValue.length() <= maxLength) {
            return safeValue;
        }
        return safeValue.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static class CorrelationIds {
        private final String jobId;
        private final String corrId;
        private final int jobIdsCount;
        private final int corrIdsCount;

        private CorrelationIds(String jobId, String corrId, int jobIdsCount, int corrIdsCount) {
            this.jobId = jobId;
            this.corrId = corrId;
            this.jobIdsCount = jobIdsCount;
            this.corrIdsCount = corrIdsCount;
        }
    }

    private static class FailureSummary {
        private final String reason;
        private final String failurePoint;
        private final String action;

        private FailureSummary(String reason, String failurePoint, String action) {
            this.reason = reason;
            this.failurePoint = failurePoint;
            this.action = action;
        }
    }

    private static class LocalEvidenceReport {
        private final List<EvidenceFile> files = new ArrayList<>();
        private String serviceFlow = "NA";
        private String fileSummary = "NA";
        private int sourceFileCount;
        private int availableFileCount;
        private int skippedFileCount;

        private EvidenceFile fileByName(String fileName) {
            for (EvidenceFile file : files) {
                if (file.fileName.equals(fileName)) {
                    return file;
                }
            }
            return null;
        }
    }

    private static class EvidenceFile {
        private final String fileName;
        private final List<String> lines = new ArrayList<>();

        private EvidenceFile(String fileName) {
            this.fileName = fileName;
        }

        private void addLines(List<String> newLines) {
            for (String line : newLines) {
                if (!lines.contains(line)) {
                    lines.add(line);
                }
            }
        }
    }
}
