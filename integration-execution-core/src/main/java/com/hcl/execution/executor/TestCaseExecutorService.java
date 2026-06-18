package com.hcl.execution.executor;

import com.hcl.execution.model.ExecutionResult;
import com.hcl.execution.model.StepResult;
import com.hcl.execution.model.TestCase;
import com.hcl.execution.model.TestStep;
import com.hcl.execution.trigger.TriggerService;
import com.hcl.observability.correlation.CorrIdExtractor;
import com.hcl.observability.correlation.TimelineValidationResult;
import com.hcl.observability.correlation.TimelineValidator;
import com.hcl.observability.log.LogAnalyzerService;
import com.hcl.observability.log.LogSearchResult;
import com.hcl.observability.report.ReportService;
import com.hcl.observability.trace.NormalizedTraceEvent;
import com.hcl.observability.trace.TimelineEvent;
import com.hcl.observability.trace.TimelineService;
import com.hcl.observability.trace.TraceStatus;
import com.hcl.observability.trace.TraceSystem;
import com.hcl.observability.trace.UnifiedTraceContext;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import com.hcl.observability.trace.UnifiedTraceReportService;
import com.hcl.observability.validation.BusinessValidationResult;
import com.hcl.observability.validation.BusinessValidationService;
import com.hcl.observability.validation.LogValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TestCaseExecutorService {

    private static final Pattern BOOKING_RESERVATION_ID_FIELD_PATTERN = Pattern.compile(
            "(?i)(?:\"(?:bookingId|bookingID|bookingIds|reservationId|reservationID|reservationIds|bookingNumber|reservationNumber)\""
                    + "|\\b(?:BookingID|BookingId|BookingIDs|ReservationID|ReservationId|ReservationIDs|BookingNumber|ReservationNumber)\\b"
                    + "|<(?:BookingID|BookingId|ReservationID|ReservationId)>)[^0-9]{0,80}(\\d{5,})");

    private final LogAnalyzerService logAnalyzerService;
    private final LogValidationService logValidationService;
    private final TimelineValidator timelineValidator;
    private final TimelineService timelineService;
    private final BusinessValidationService businessValidationService;
    private final ReportService reportService;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final UnifiedTraceReportService traceReportService;
    private final Map<String, TriggerService> triggerServices;
    private final int retryCount;
    private final long waitMs;
    private final boolean unifiedTraceReportEnabled;
    private final boolean platformReportEnabled;

    public TestCaseExecutorService(
            LogAnalyzerService logAnalyzerService,
            LogValidationService logValidationService,
            TimelineValidator timelineValidator,
            TimelineService timelineService,
            BusinessValidationService businessValidationService,
            ReportService reportService,
            UnifiedTraceContextHolder traceContextHolder,
            UnifiedTraceReportService traceReportService,
            Map<String, TriggerService> triggerServices,
            @Value("${execution.retry.count:3}") int retryCount,
            @Value("${execution.wait.ms:3000}") long waitMs,
            @Value("${unified.trace.report.enabled:false}") boolean unifiedTraceReportEnabled,
            @Value("${platform.report.enabled:false}") boolean platformReportEnabled) {
        this.logAnalyzerService = logAnalyzerService;
        this.logValidationService = logValidationService;
        this.timelineValidator = timelineValidator;
        this.timelineService = timelineService;
        this.businessValidationService = businessValidationService;
        this.reportService = reportService;
        this.traceContextHolder = traceContextHolder;
        this.traceReportService = traceReportService;
        this.triggerServices = triggerServices;
        this.retryCount = retryCount;
        this.waitMs = waitMs;
        this.unifiedTraceReportEnabled = unifiedTraceReportEnabled;
        this.platformReportEnabled = platformReportEnabled;
    }

    public ExecutionResult execute(TestCase testCase) {
        ExecutionResult result = new ExecutionResult();
        result.setTestCaseId(testCase.getTestCaseId());

        List<StepResult> steps = new ArrayList<>();
        long apiMs = -1;
        long logFetchMs = -1;
        long correlationMs = -1;

        try {
            String bookingId = testCase.getBookingId();
            UnifiedTraceContext traceContext = traceContextHolder.begin(testCase.getTestCaseId(), bookingId);

            long apiStartNanos = System.nanoTime();
            StepResult triggerResult = triggerWithRetry(testCase);
            apiMs = elapsedMs(apiStartNanos);
            steps.add(triggerResult);
            List<String> partialObservabilityMessages = new ArrayList<>();

            if (isFailure(triggerResult)) {
                finish(result, steps);
                return result;
            }

            if (hasTriggerManagedObservability(traceContext)) {
                TraceStatus observabilityStatus = triggerObservabilityStatus(traceContext);
                boolean observabilityOk = observabilityStatus != TraceStatus.ERROR
                        && observabilityStatus != TraceStatus.FAILED
                        && observabilityStatus != TraceStatus.UNKNOWN;
                steps.add(observabilityOk
                        ? step("BookingID Log Search", "PASS",
                                "LogAnalyzer already executed by trigger; duplicate remote scan skipped")
                        : step("BookingID Log Search", "FAIL",
                                "LogAnalyzer already executed by trigger and returned " + observabilityStatus
                                        + "; duplicate remote scan skipped"));
                finish(result, steps);
                traceContext.setTotalTimeMs(elapsedMs(apiStartNanos));
                return result;
            }

            long logFetchStartNanos = System.nanoTime();
            LogSearchResult bookingSearch = logAnalyzerService.fetchLogsDetailed(bookingId);
            logFetchMs = elapsedMs(logFetchStartNanos);
            List<String> bookingLogs = bookingSearch.getLines();

            if (bookingLogs.isEmpty()) {
                steps.add(step("BookingID Log Search", "FAIL", "No logs found for BookingID: " + bookingId));
                finish(result, steps);
                return result;
            }

            addSearchStep(steps, partialObservabilityMessages, "BookingID Log Search", bookingSearch);

            long correlationStartNanos = System.nanoTime();
            String corrId = CorrIdExtractor.extractCorrId(bookingLogs);
            traceContext.setCorrId(corrId);

            steps.add(corrId != null
                    ? step("CorrID Extraction", "PASS", "CorrID: " + corrId)
                    : step("CorrID Extraction", "FAIL", "CorrID not found"));

            List<String> traceLogs = new ArrayList<>(bookingLogs);

            String jobId = CorrIdExtractor.extractJobId(bookingLogs);
            traceContext.setJobId(jobId);
            steps.add(jobId != null
                    ? step("JobID Extraction", "PASS", "JobID: " + jobId)
                    : step("JobID Extraction", "FAIL", "JobID not found"));

            traceLogs.addAll(expandFinalTrace(bookingId, corrId, jobId, steps, partialObservabilityMessages));
            traceLogs = new ArrayList<>(new LinkedHashSet<>(traceLogs));

            FlowValidationSummary flowSummary = validateEndToEndFlow(bookingId, corrId, jobId, traceLogs);
            steps.add(flowSummary.isBookingIdValid()
                    ? step("BookingID Reservation Validation", "PASS",
                            "BookingID " + bookingId + " FOUND in ReservationQueue response")
                    : step("BookingID Reservation Validation", "FAIL",
                            "BookingID " + bookingId + " NOT FOUND in ReservationQueue response"));
            steps.add(flowSummary.isAtcoreResponseFound()
                    ? step("Atcore Response Validation", "PASS", "Atcore response found")
                    : step("Atcore Response Validation", "FAIL", "Atcore response not found"));
            steps.add(flowSummary.isQueuePublishFound()
                    ? step("Queue Publish Validation", "PASS", "Queue publish/request found")
                    : step("Queue Publish Validation", "FAIL", "Queue publish/request not found"));
            steps.add(flowSummary.isEndToEndSuccess()
                    ? step("End-to-End Flow", "PASS", "End-to-End Flow: SUCCESS")
                    : step("End-to-End Flow", "FAIL", "End-to-End Flow: FAILED"));
            printValidationSummary(flowSummary);
            correlationMs = elapsedMs(correlationStartNanos);

            if (!partialObservabilityMessages.isEmpty()) {
                steps.add(step("Observability Coverage", "WARN",
                        "Partial distributed trace. " + String.join(" || ", partialObservabilityMessages)));
            } else {
                steps.add(step("Observability Coverage", "PASS", "No partial grep or coverage warnings detected"));
            }

            boolean basicValidation = logValidationService.validateBasic(traceLogs);
            steps.add(basicValidation
                    ? step("Analyzer Validation", "PASS", "REQUEST and REPLY events found")
                    : step("Analyzer Validation", "FAIL", "REQUEST/REPLY events missing"));

            TimelineValidationResult timelineResult = timelineValidator.validateTimelineDetailed(
                    traceLogs, corrId, bookingId, jobId);
            steps.add(timelineResult.isValid()
                    ? step("Timeline Validation", "PASS", timelineResult.getMessage())
                    : step("Timeline Validation", "FAIL", timelineResult.getMessage()));

            finish(result, steps);
            traceContext.setTotalTimeMs(elapsedMs(apiStartNanos) + Math.max(0, logFetchMs) + Math.max(0, correlationMs));
            printPlatformReportIfEnabled(testCase, bookingSearch, apiMs, logFetchMs, correlationMs);
            printUnifiedTraceReportIfEnabled(traceContext);
        } catch (Exception e) {
            steps.add(step("Execution Error", "ERROR", e.getMessage()));

            result.setSteps(steps);
            result.setFinalStatus("ERROR");

            e.printStackTrace();
            UnifiedTraceContext traceContext = traceContextHolder.current();
            if (traceContext != null) {
                traceContext.setTotalTimeMs(Math.max(0, apiMs) + Math.max(0, logFetchMs) + Math.max(0, correlationMs));
                printUnifiedTraceReportIfEnabled(traceContext);
            }
        } finally {
            traceContextHolder.clear();
        }

        return result;
    }

    private void printUnifiedTraceReportIfEnabled(UnifiedTraceContext traceContext) {
        if (!unifiedTraceReportEnabled || traceContext == null) {
            return;
        }

        System.out.println();
        System.out.println("==================== UNIFIED TRACE REPORT ====================");
        System.out.print(traceReportService.buildReport(traceContext));
        System.out.println("==================== UNIFIED TRACE END =======================");
    }

    private boolean hasTriggerManagedObservability(UnifiedTraceContext traceContext) {
        return triggerObservabilityStatus(traceContext) != null;
    }

    private TraceStatus triggerObservabilityStatus(UnifiedTraceContext traceContext) {
        if (traceContext == null) {
            return null;
        }

        TraceStatus status = null;
        for (NormalizedTraceEvent event : traceContext.getEvents()) {
            if (event == null || event.getSystem() != TraceSystem.OBSERVABILITY) {
                continue;
            }
            status = event.getStatus() == null ? TraceStatus.UNKNOWN : event.getStatus();
        }
        return status;
    }

    private void printPlatformReportIfEnabled(
            TestCase testCase,
            LogSearchResult logSearchResult,
            long apiMs,
            long logFetchMs,
            long correlationMs) throws Exception {
        if (!platformReportEnabled || testCase == null) {
            return;
        }

        String bookingId = testCase.getBookingId();
        String category = categoryForReport(testCase);
        List<TimelineEvent> timeline = timelineService.buildTimeline(bookingId);
        BusinessValidationResult validation = businessValidationService.validate(category, timeline);

        ReportService.ReportContext context = new ReportService.ReportContext();
        context.setTestCaseName(testCase.getTestCaseId());
        context.setBookingId(bookingId);
        context.setCategory(category);
        context.setFlow("NA");
        context.setScenario("NA");
        context.setProtocol(protocolForReport(testCase));
        context.setMode("NA");
        context.setFilesFound(logSearchResult == null ? 0 : logSearchResult.getUniqueFilesFound());
        context.setFilesProcessed(logSearchResult == null ? 0 : logSearchResult.getFilesTransferred());
        context.setFilesMerged(0);
        context.setLines(logSearchResult == null ? 0 : logSearchResult.getLines().size());
        context.setDepth(metricValue(logSearchResult == null ? "" : logSearchResult.getMessage(), "Depth"));
        context.setJobIds(metricValue(logSearchResult == null ? "" : logSearchResult.getMessage(), "JobIDs"));
        context.setCorrIds(metricValue(logSearchResult == null ? "" : logSearchResult.getMessage(), "CorrIDs"));
        context.setExecutionTimeMs(Math.max(0, apiMs));
        context.setLogTimeMs(Math.max(0, logFetchMs));
        context.setMergeTimeMs(0);
        context.setTotalTimeMs(Math.max(0, apiMs) + Math.max(0, logFetchMs) + Math.max(0, correlationMs));
        context.setTimeline(timeline);
        context.setValidation(validation);

        System.out.println();
        System.out.println("==================== PLATFORM REPORT ====================");
        System.out.print(reportService.buildReport(context));
        System.out.println("==================== PLATFORM REPORT END ================");
    }

    private String categoryForReport(TestCase testCase) {
        String trigger = firstTriggerSystem(testCase);
        if ("EMS".equals(trigger)) {
            return "DATAHUB";
        }
        if ("SOAP".equals(trigger)) {
            return "VRP";
        }
        if ("RABBITMQ".equals(trigger) || "RABBIT".equals(trigger) || "NORDICS".equals(trigger)) {
            return "NORDICS";
        }
        return "APIGEE";
    }

    private String protocolForReport(TestCase testCase) {
        String trigger = firstTriggerSystem(testCase);
        if ("EMS".equals(trigger)) {
            return "JMS";
        }
        if ("RABBITMQ".equals(trigger) || "RABBIT".equals(trigger) || "NORDICS".equals(trigger)) {
            return "RabbitMQ";
        }
        return trigger;
    }

    private int metricValue(String message, String key) {
        if (!hasText(message) || !hasText(key)) {
            return 0;
        }

        String marker = key + "=";
        int start = message.indexOf(marker);
        if (start < 0) {
            return 0;
        }
        start += marker.length();
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private StepResult triggerWithRetry(TestCase testCase) {
        TriggerService triggerService = selectTrigger(testCase);
        String triggerName = triggerName(testCase);
        Exception lastError = null;

        int attempts = Math.max(1, retryCount);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                triggerService.trigger(testCase);
                return step("Trigger - " + triggerName, "PASS", "Triggered successfully on attempt " + attempt);
            } catch (Exception e) {
                lastError = e;
                System.out.println("Trigger attempt " + attempt + " failed: " + e.getMessage());

                if (attempt < attempts) {
                    sleepBeforeRetry();
                }
            }
        }

        String message = lastError == null ? "Trigger failed" : lastError.getMessage();
        return step("Trigger - " + triggerName, "FAIL", message);
    }

    private TriggerService selectTrigger(TestCase testCase) {
        String beanName = triggerBeanName(testCase);
        TriggerService triggerService = triggerServices.get(beanName);
        if (triggerService == null) {
            throw new RuntimeException("No trigger service found: " + beanName);
        }
        return triggerService;
    }

    private String triggerBeanName(TestCase testCase) {
        String system = firstTriggerSystem(testCase);
        if ("EMS".equals(system)) {
            return "emsTriggerService";
        }
        if ("RABBITMQ".equals(system) || "RABBIT".equals(system) || "NORDICS".equals(system)) {
            return "rabbitMqTriggerService";
        }
        if ("SOAP".equals(system)) {
            return "soapTriggerService";
        }
        return "restTriggerService";
    }

    private String triggerName(TestCase testCase) {
        return firstTriggerSystem(testCase);
    }

    private String firstTriggerSystem(TestCase testCase) {
        if (testCase.getSteps() == null) {
            return "REST";
        }

        for (TestStep step : testCase.getSteps()) {
            if (step.getSystem() == null) {
                continue;
            }

            String system = step.getSystem().trim().toUpperCase(Locale.ROOT);
            if ("API".equals(system) || "REST".equals(system) || "APIGEE".equals(system)) {
                return "REST";
            }
            if ("DATAHUB".equals(system) || "JMS".equals(system)) {
                return "EMS";
            }
            if ("EMS".equals(system) || "SOAP".equals(system) || "VRP".equals(system)) {
                return "VRP".equals(system) ? "SOAP" : system;
            }
            if ("RABBITMQ".equals(system) || "RABBIT".equals(system) || "NORDICS".equals(system)) {
                return system;
            }
        }

        return "REST";
    }

    private List<String> expandFinalTrace(
            String bookingId,
            String corrId,
            String jobId,
            List<StepResult> steps,
            List<String> partialObservabilityMessages) throws Exception {
        List<String> traceLogs = new ArrayList<>();

        if (corrId != null || jobId != null) {
            LogSearchResult traceSearch = logAnalyzerService.searchFinalTraceDetailed(bookingId, corrId, jobId);
            traceLogs.addAll(traceSearch.getLines());
            addSearchStep(steps, partialObservabilityMessages, "Final Trace Search", traceSearch);
        }

        return traceLogs;
    }

    private void addSearchStep(
            List<StepResult> steps,
            List<String> partialObservabilityMessages,
            String stepName,
            LogSearchResult searchResult) {
        String status;
        if (searchResult.getLines().isEmpty()) {
            status = "WARN";
        } else if (searchResult.isPartialCoverage()) {
            status = "WARN";
            partialObservabilityMessages.add(stepName + ": " + searchResult.getMessage());
        } else {
            status = "PASS";
        }

        steps.add(step(stepName, status, searchResult.getMessage()));
    }

    private FlowValidationSummary validateEndToEndFlow(
            String bookingId,
            String corrId,
            String jobId,
            List<String> traceLogs) {
        List<String> reservationIds = extractReservationIds(traceLogs, bookingId);
        boolean bookingIdPresent = bookingId != null && reservationIds.contains(bookingId);
        boolean atcoreResponseFound = containsAtcoreResponse(traceLogs);
        boolean queuePublishFound = containsQueuePublish(traceLogs);
        boolean endToEndSuccess = bookingIdPresent
                && hasText(corrId)
                && hasText(jobId)
                && atcoreResponseFound
                && queuePublishFound;

        return new FlowValidationSummary(
                bookingId,
                corrId,
                jobId,
                bookingIdPresent,
                atcoreResponseFound,
                queuePublishFound,
                endToEndSuccess);
    }

    private List<String> extractReservationIds(List<String> traceLogs, String bookingId) {
        LinkedHashSet<String> reservationIds = new LinkedHashSet<>();
        if (traceLogs == null) {
            return new ArrayList<>(reservationIds);
        }

        for (String line : traceLogs) {
            collectExplicitReservationIds(line, reservationIds);

            if (isReservationQueueResponseForBooking(line, bookingId)) {
                reservationIds.add(bookingId);
            }
        }

        return new ArrayList<>(reservationIds);
    }

    private void collectExplicitReservationIds(String line, LinkedHashSet<String> reservationIds) {
        if (line == null) {
            return;
        }

        Matcher matcher = BOOKING_RESERVATION_ID_FIELD_PATTERN.matcher(line);
        while (matcher.find()) {
            reservationIds.add(matcher.group(1));
        }
    }

    private boolean containsAtcoreResponse(List<String> traceLogs) {
        if (traceLogs == null) {
            return false;
        }

        return traceLogs.stream().anyMatch(line ->
                containsIgnoreCase(line, "Atcore")
                        && containsAnyIgnoreCase(line, "REPLY", "RESPONSE", "response received"));
    }

    private boolean containsQueuePublish(List<String> traceLogs) {
        if (traceLogs == null) {
            return false;
        }

        return traceLogs.stream().anyMatch(line ->
                containsAnyIgnoreCase(line, "ReservationQueue", "Reservation Queue", "Queue")
                        && containsAnyIgnoreCase(line, "REQUEST", "PUBLISH", "sending", "sent"));
    }

    private boolean isReservationQueueResponseForBooking(String line, String bookingId) {
        return hasText(bookingId)
                && containsIgnoreCase(line, bookingId)
                && containsAnyIgnoreCase(line, "ReservationQueue", "Reservation Queue")
                && containsAnyIgnoreCase(line, "REPLY", "RESPONSE", "response received", "response sent");
    }

    private void printValidationSummary(FlowValidationSummary summary) {
        if (unifiedTraceReportEnabled) {
            return;
        }

        System.out.println();
        System.out.println("-------------------- VALIDATION ------------------------");
        System.out.println("[VALIDATION]");
        System.out.println("BookingID=" + defaultValue(summary.getBookingId())
                + " CorrID=" + defaultValue(summary.getCorrId())
                + " JobID=" + defaultValue(summary.getJobId()));
        System.out.println("Atcore=" + yesNo(summary.isAtcoreResponseFound())
                + " Queue=" + yesNo(summary.isQueuePublishFound())
                + " ReservationMatch=" + yesNo(summary.isBookingIdValid()));
        System.out.println("EndToEnd=" + (summary.isEndToEndSuccess() ? "SUCCESS" : "FAILED"));
    }

    private String defaultValue(String value) {
        return hasText(value) ? value : "NOT FOUND";
    }

    private String yesNo(boolean value) {
        return value ? "Y" : "N";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null
                && expected != null
                && value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private boolean containsAnyIgnoreCase(String value, String... expectedValues) {
        for (String expected : expectedValues) {
            if (containsIgnoreCase(value, expected)) {
                return true;
            }
        }

        return false;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Math.max(0, waitMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void finish(ExecutionResult result, List<StepResult> steps) {
        result.setSteps(steps);
        result.setFinalStatus(steps.stream().anyMatch(this::isBusinessCriticalFailure) ? "FAIL" : "PASS");
    }

    private boolean isFailure(StepResult step) {
        return "FAIL".equalsIgnoreCase(step.getStatus()) || "ERROR".equalsIgnoreCase(step.getStatus());
    }

    private boolean isBusinessCriticalFailure(StepResult step) {
        if (!isFailure(step)) {
            return false;
        }

        String stepName = step.getStepName();
        if (stepName == null) {
            return false;
        }

        return stepName.startsWith("Trigger - ")
                || "BookingID Log Search".equals(stepName)
                || "CorrID Extraction".equals(stepName)
                || "JobID Extraction".equals(stepName)
                || "End-to-End Flow".equals(stepName)
                || "Analyzer Validation".equals(stepName)
                || "Timeline Validation".equals(stepName)
                || "Execution Error".equals(stepName);
    }

    private StepResult step(String name, String status, String message) {
        StepResult result = new StepResult();
        result.setStepName(name);
        result.setStatus(status);
        result.setMessage(message);
        return result;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static class FlowValidationSummary {
        private final String bookingId;
        private final String corrId;
        private final String jobId;
        private final boolean bookingIdValid;
        private final boolean atcoreResponseFound;
        private final boolean queuePublishFound;
        private final boolean endToEndSuccess;

        private FlowValidationSummary(
                String bookingId,
                String corrId,
                String jobId,
                boolean bookingIdValid,
                boolean atcoreResponseFound,
                boolean queuePublishFound,
                boolean endToEndSuccess) {
            this.bookingId = bookingId;
            this.corrId = corrId;
            this.jobId = jobId;
            this.bookingIdValid = bookingIdValid;
            this.atcoreResponseFound = atcoreResponseFound;
            this.queuePublishFound = queuePublishFound;
            this.endToEndSuccess = endToEndSuccess;
        }

        private String getBookingId() {
            return bookingId;
        }

        private String getCorrId() {
            return corrId;
        }

        private String getJobId() {
            return jobId;
        }

        private boolean isBookingIdValid() {
            return bookingIdValid;
        }

        private boolean isAtcoreResponseFound() {
            return atcoreResponseFound;
        }

        private boolean isQueuePublishFound() {
            return queuePublishFound;
        }

        private boolean isEndToEndSuccess() {
            return endToEndSuccess;
        }
    }
}
