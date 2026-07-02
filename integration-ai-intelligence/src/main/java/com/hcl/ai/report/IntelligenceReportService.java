package com.hcl.ai.report;

import com.hcl.ai.index.IndexedLogLine;
import com.hcl.ai.index.ObservabilityIndexResult;
import com.hcl.ai.index.ObservabilityIndexService;
import com.hcl.ai.intent.IntelligenceIntent;
import com.hcl.ai.policy.RetryPolicyService;
import com.hcl.ai.plan.ExecutionPlan;
import com.hcl.ai.trace.DriftDetectionService;
import com.hcl.ai.trace.MultiHopTraceService;
import com.hcl.ai.trace.OrphanLogDetectionService;
import com.hcl.ai.trace.TraceGraph;
import com.hcl.ai.validation.IntelligenceValidationService;
import com.hcl.ai.validation.RuleEvaluation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class IntelligenceReportService {

    private final IntelligenceValidationService validationService;
    private final MultiHopTraceService multiHopTraceService;
    private final OrphanLogDetectionService orphanLogDetectionService;
    private final DriftDetectionService driftDetectionService;
    private final RetryPolicyService retryPolicyService;
    private final ObservabilityIndexService observabilityIndexService;
    private final int evidenceMaxLines;

    public IntelligenceReportService(
            IntelligenceValidationService validationService,
            MultiHopTraceService multiHopTraceService,
            OrphanLogDetectionService orphanLogDetectionService,
            DriftDetectionService driftDetectionService,
            RetryPolicyService retryPolicyService,
            ObservabilityIndexService observabilityIndexService,
            @Value("${intelligence.evidence.max-lines}") int evidenceMaxLines) {
        this.validationService = validationService;
        this.multiHopTraceService = multiHopTraceService;
        this.orphanLogDetectionService = orphanLogDetectionService;
        this.driftDetectionService = driftDetectionService;
        this.retryPolicyService = retryPolicyService;
        this.observabilityIndexService = observabilityIndexService;
        this.evidenceMaxLines = Math.max(1, evidenceMaxLines);
    }

    public IntelligenceReport build(IntelligenceIntent intent, ExecutionPlan plan, IntelligenceExecutionSnapshot snapshot) {
        IntelligenceIntent safeIntent = intent == null ? new IntelligenceIntent() : intent;
        ExecutionPlan safePlan = plan == null ? new ExecutionPlan() : plan;
        IntelligenceExecutionSnapshot safeSnapshot = snapshot == null ? new IntelligenceExecutionSnapshot() : snapshot;
        List<IntelligenceExecutionRow> rows = safeSnapshot.getRows() == null
                ? Collections.emptyList()
                : safeSnapshot.getRows();
        String bookingId = bookingId(safeIntent, safePlan, rows);
        ObservabilityIndexResult indexResult = scopedIndexResult(
                safePlan,
                rows,
                observabilityIndexService.index(bookingId));
        boolean rabbitReport = isRabbitReport(safePlan, rows);

        IntelligenceReport report = new IntelligenceReport();
        report.setExecutionSummary(summary(safeIntent, safePlan, safeSnapshot, rows));
        report.setTimeline(timeline(rows));
        report.setRules(validationService.evaluate(safeSnapshot, indexResult));
        TraceGraph traceGraph = multiHopTraceService.graph(rows, indexResult);
        report.setTraceGraph(traceGraph);
        report.setMultiHopTrace(traceGraph.getPath());
        report.setOrphanLogs(orphanLogDetectionService.detect(rows));
        report.setOrphanEvidence(rabbitReport
                ? Collections.emptyList()
                : orphanLogDetectionService.detectEvidence(indexResult));
        report.setEvidenceLines(evidenceLines(indexResult));
        report.setDriftSignals(driftDetectionService.detect(safePlan, safeSnapshot, indexResult, traceGraph));
        report.setObservabilityIndexSummary(indexSummary(indexResult));
        report.setPolicyDecision(retryPolicyService.evaluate(safePlan, safeSnapshot));
        report.setFailurePoint(failurePoint(rows));
        report.setExpectedVsActual(expectedVsActual(rows));
        report.setKeyInsights(keyInsights(rows, report.getRules(), report.getOrphanLogs(),
                report.getOrphanEvidence(), report.getDriftSignals(), indexResult, traceGraph, rabbitReport));
        report.setExecutiveSummary(executiveSummary(report));
        report.setActions(actions(rows));
        report.setSeverity(severity(safeSnapshot, report.getOrphanLogs()));
        report.setRetrySuggestion(retrySuggestion(safePlan, safeSnapshot));
        return report;
    }

    private ExecutionSummary summary(
            IntelligenceIntent intent,
            ExecutionPlan plan,
            IntelligenceExecutionSnapshot snapshot,
            List<IntelligenceExecutionRow> rows) {
        ExecutionSummary summary = new ExecutionSummary();
        IntelligenceExecutionRow first = rows.isEmpty() ? null : rows.get(0);
        summary.setStatus(snapshot.getFail() > 0 ? "FAIL" : "PASS");
        summary.setFlow(firstText(intent.getFlow(), firstValue(plan.getSystems())));
        summary.setTriggerMode(firstText(intent.getTriggerMode(), firstValue(plan.getFlowTypes())));
        summary.setBookingId(firstText(intent.getBookingId(), firstText(plan.getBookingId(), first == null ? null : first.getBookingId())));
        summary.setConfidence(Math.max(0, intent.getConfidence()) + "%");
        return summary;
    }

    private FailurePoint failurePoint(List<IntelligenceExecutionRow> rows) {
        FailurePoint failurePoint = new FailurePoint();
        IntelligenceExecutionRow failed = firstFailed(rows);
        failurePoint.setSystem(failed == null ? "None" : value(failed.getSystem()));
        failurePoint.setTimestamp("NA");
        return failurePoint;
    }

    private ExpectedVsActual expectedVsActual(List<IntelligenceExecutionRow> rows) {
        ExpectedVsActual expectedVsActual = new ExpectedVsActual();
        IntelligenceExecutionRow failed = firstFailed(rows);
        expectedVsActual.setExpected("Flow should trigger, correlate logs, and complete downstream validation");
        expectedVsActual.setActual(failed == null ? "All configured execution rows passed" : value(failed.getMessage()));
        return expectedVsActual;
    }

    private List<String> keyInsights(
            List<IntelligenceExecutionRow> rows,
            List<RuleEvaluation> rules,
            List<String> orphanLogs,
            List<String> orphanEvidence,
            List<String> driftSignals,
            ObservabilityIndexResult indexResult,
            TraceGraph traceGraph,
            boolean rabbitReport) {
        List<String> insights = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        for (IntelligenceExecutionRow row : rows) {
            if ("PASS".equalsIgnoreCase(row.getStatus())) {
                pass++;
            } else {
                fail++;
            }
        }
        insights.add(pass + " execution row(s) passed and " + fail + " failed");
        if (rules != null) {
            int failedRules = 0;
            for (RuleEvaluation rule : rules) {
                if (rule != null && "FAIL".equalsIgnoreCase(rule.getStatus())) {
                    failedRules++;
                }
            }
            insights.add(failedRules + " validation rule(s) require attention");
        }
        if (orphanLogs != null && !orphanLogs.isEmpty()) {
            insights.add("Orphan log candidates detected: " + orphanLogs.size());
        }
        if (!rabbitReport && orphanEvidence != null && !orphanEvidence.isEmpty()) {
            insights.add("Local evidence orphan candidates detected: " + orphanEvidence.size());
        }
        if (driftSignals != null && !driftSignals.isEmpty()) {
            String driftSignal = firstRelevantDriftSignal(driftSignals, rabbitReport);
            if (hasText(driftSignal)) {
                insights.add(driftSignal);
            }
        }
        if (indexResult != null) {
            insights.add((rabbitReport ? "Rabbit audit evidence indexed " : "Indexed ")
                    + indexResult.getLinesIndexed()
                    + (rabbitReport ? " line(s) across " : " local evidence line(s) across ")
                    + indexResult.getFilesScanned() + " file(s)");
        }
        if (traceGraph != null) {
            insights.add("Trace graph has " + traceGraph.getNodes().size()
                    + " node(s) and " + traceGraph.getEdges().size() + " edge(s)");
        }
        return insights;
    }

    private ObservabilityIndexResult scopedIndexResult(
            ExecutionPlan plan,
            List<IntelligenceExecutionRow> rows,
            ObservabilityIndexResult indexResult) {
        if (!isRabbitReport(plan, rows) || indexResult == null || indexResult.getEntries() == null) {
            return indexResult;
        }

        ObservabilityIndexResult scoped = new ObservabilityIndexResult();
        scoped.setBookingId(indexResult.getBookingId());
        scoped.setLocalPath(indexResult.getLocalPath());
        scoped.setWarnings(indexResult.getWarnings());
        List<IndexedLogLine> entries = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        for (IndexedLogLine entry : indexResult.getEntries()) {
            if (entry != null && "audit.log".equalsIgnoreCase(value(entry.getFile()))) {
                entries.add(entry);
                files.add(value(entry.getFile()));
            }
        }
        scoped.setEntries(entries);
        scoped.setLinesIndexed(entries.size());
        scoped.setFilesScanned(files.size());
        return scoped;
    }

    private String firstRelevantDriftSignal(List<String> driftSignals, boolean rabbitReport) {
        if (driftSignals == null) {
            return null;
        }
        for (String signal : driftSignals) {
            if (!rabbitReport || !isMixedRabbitDrift(signal)) {
                return signal;
            }
        }
        return rabbitReport ? "Rabbit drift evaluated against audit.log evidence only" : null;
    }

    private boolean isMixedRabbitDrift(String signal) {
        if (!hasText(signal)) {
            return false;
        }
        String upper = signal.toUpperCase();
        return upper.contains("BOOKINGDETAILS")
                || upper.contains("MANAGEBOOKING")
                || upper.contains("MONGOTDA")
                || upper.contains("PACKAGEOFFERS")
                || upper.contains("PACKAGESEARCH")
                || upper.contains("POSTBOOKFLOW");
    }

    private String executiveSummary(IntelligenceReport report) {
        if (report == null || report.getExecutionSummary() == null) {
            return "No execution data available for intelligence summary.";
        }
        if ("PASS".equalsIgnoreCase(report.getExecutionSummary().getStatus())) {
            return "Execution completed successfully across the configured trigger and validation path.";
        }
        return "Execution requires attention at "
                + value(report.getFailurePoint() == null ? null : report.getFailurePoint().getSystem())
                + " after deterministic trigger and validation processing.";
    }

    private List<RecommendedAction> actions(List<IntelligenceExecutionRow> rows) {
        IntelligenceExecutionRow failed = firstFailed(rows);
        if (failed == null) {
            return Collections.emptyList();
        }
        RecommendedAction action = new RecommendedAction();
        action.setFile(value(failed.getSystem()) + ".log");
        action.setSearch("BookingID=" + value(failed.getBookingId()));
        action.setTime("Use local evidence window for this execution");
        return Collections.singletonList(action);
    }

    private String severity(IntelligenceExecutionSnapshot snapshot, List<String> orphanLogs) {
        if (snapshot != null && snapshot.getFail() > 0) {
            return orphanLogs != null && !orphanLogs.isEmpty() ? "CRITICAL" : "HIGH";
        }
        return "LOW";
    }

    private String retrySuggestion(ExecutionPlan plan, IntelligenceExecutionSnapshot snapshot) {
        if (snapshot == null || snapshot.getFail() <= 0) {
            return "No retry required";
        }
        if (plan != null && plan.isReplaySafe()) {
            return "Replay failed asynchronous execution row(s) only";
        }
        return "Review failure before retry because this flow may not be safely replayable";
    }

    private String bookingId(IntelligenceIntent intent, ExecutionPlan plan, List<IntelligenceExecutionRow> rows) {
        String value = firstText(intent.getBookingId(), plan.getBookingId());
        if (hasText(value)) {
            return value;
        }
        if (rows != null) {
            for (IntelligenceExecutionRow row : rows) {
                if (row != null && hasText(row.getBookingId()) && !"NA".equalsIgnoreCase(row.getBookingId())) {
                    return row.getBookingId();
                }
            }
        }
        return null;
    }

    private String indexSummary(ObservabilityIndexResult indexResult) {
        if (indexResult == null) {
            return "Index not available";
        }
        String summary = "LocalPath=" + value(indexResult.getLocalPath())
                + " Files=" + indexResult.getFilesScanned()
                + " Lines=" + indexResult.getLinesIndexed();
        if (indexResult.getWarnings() != null && !indexResult.getWarnings().isEmpty()) {
            summary += " Warnings=" + String.join(" | ", indexResult.getWarnings());
        }
        return summary;
    }

    private boolean isRabbitReport(ExecutionPlan plan, List<IntelligenceExecutionRow> rows) {
        if (contains(plan == null ? null : plan.getFlowTypes(), "RABBIT")
                || contains(plan == null ? null : plan.getFlowTypes(), "RABBITMQ")
                || contains(plan == null ? null : plan.getServices(), "ReservationEvent_v3")) {
            return true;
        }
        if (rows != null) {
            for (IntelligenceExecutionRow row : rows) {
                if (row != null
                        && ("RABBIT".equalsIgnoreCase(row.getFlow())
                        || "RABBITMQ".equalsIgnoreCase(row.getFlow())
                        || "ReservationEvent_v3".equalsIgnoreCase(row.getService()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean contains(List<String> values, String expected) {
        if (values == null || !hasText(expected)) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.trim().equalsIgnoreCase(expected.trim())) {
                return true;
            }
        }
        return false;
    }

    private List<IndexedLogLine> evidenceLines(ObservabilityIndexResult indexResult) {
        if (indexResult == null || indexResult.getEntries() == null || indexResult.getEntries().isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.min(evidenceMaxLines, indexResult.getEntries().size());
        return new ArrayList<>(indexResult.getEntries().subList(0, limit));
    }

    private List<String> timeline(List<IntelligenceExecutionRow> rows) {
        List<String> timeline = new ArrayList<>();
        if (rows == null) {
            return timeline;
        }
        for (IntelligenceExecutionRow row : rows) {
            if (row.getTimeline() == null || row.getTimeline().isEmpty()) {
                timeline.add(value(row.getSystem()) + " -> " + value(row.getService()) + " -> " + value(row.getStatus()));
            } else {
                for (String line : row.getTimeline()) {
                    timeline.add(value(row.getSystem()) + "/" + value(row.getService()) + ": " + value(line));
                }
            }
        }
        return timeline;
    }

    private IntelligenceExecutionRow firstFailed(List<IntelligenceExecutionRow> rows) {
        if (rows == null) {
            return null;
        }
        for (IntelligenceExecutionRow row : rows) {
            if (row != null && !"PASS".equalsIgnoreCase(row.getStatus())) {
                return row;
            }
        }
        return null;
    }

    private String firstValue(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : fallback;
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
