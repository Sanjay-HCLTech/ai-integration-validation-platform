package com.hcl.ai.report;

import com.hcl.ai.policy.PolicyDecision;
import com.hcl.ai.index.IndexedLogLine;
import com.hcl.ai.trace.TraceGraph;
import com.hcl.ai.validation.RuleEvaluation;

import java.util.ArrayList;
import java.util.List;

public class IntelligenceReport {

    private ExecutionSummary executionSummary = new ExecutionSummary();
    private String executiveSummary;
    private List<String> keyInsights = new ArrayList<>();
    private FailurePoint failurePoint = new FailurePoint();
    private ExpectedVsActual expectedVsActual = new ExpectedVsActual();
    private List<RecommendedAction> actions = new ArrayList<>();
    private List<String> timeline = new ArrayList<>();
    private List<String> multiHopTrace = new ArrayList<>();
    private TraceGraph traceGraph = new TraceGraph();
    private List<String> orphanLogs = new ArrayList<>();
    private List<String> orphanEvidence = new ArrayList<>();
    private List<IndexedLogLine> evidenceLines = new ArrayList<>();
    private List<String> driftSignals = new ArrayList<>();
    private String observabilityIndexSummary;
    private List<RuleEvaluation> rules = new ArrayList<>();
    private PolicyDecision policyDecision = new PolicyDecision();
    private String severity;
    private String retrySuggestion;

    public ExecutionSummary getExecutionSummary() {
        return executionSummary;
    }

    public void setExecutionSummary(ExecutionSummary executionSummary) {
        this.executionSummary = executionSummary;
    }

    public String getExecutiveSummary() {
        return executiveSummary;
    }

    public void setExecutiveSummary(String executiveSummary) {
        this.executiveSummary = executiveSummary;
    }

    public List<String> getKeyInsights() {
        return keyInsights;
    }

    public void setKeyInsights(List<String> keyInsights) {
        this.keyInsights = keyInsights;
    }

    public FailurePoint getFailurePoint() {
        return failurePoint;
    }

    public void setFailurePoint(FailurePoint failurePoint) {
        this.failurePoint = failurePoint;
    }

    public ExpectedVsActual getExpectedVsActual() {
        return expectedVsActual;
    }

    public void setExpectedVsActual(ExpectedVsActual expectedVsActual) {
        this.expectedVsActual = expectedVsActual;
    }

    public List<RecommendedAction> getActions() {
        return actions;
    }

    public void setActions(List<RecommendedAction> actions) {
        this.actions = actions;
    }

    public List<String> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<String> timeline) {
        this.timeline = timeline;
    }

    public List<String> getMultiHopTrace() {
        return multiHopTrace;
    }

    public void setMultiHopTrace(List<String> multiHopTrace) {
        this.multiHopTrace = multiHopTrace;
    }

    public TraceGraph getTraceGraph() {
        return traceGraph;
    }

    public void setTraceGraph(TraceGraph traceGraph) {
        this.traceGraph = traceGraph;
    }

    public List<String> getOrphanLogs() {
        return orphanLogs;
    }

    public void setOrphanLogs(List<String> orphanLogs) {
        this.orphanLogs = orphanLogs;
    }

    public List<String> getOrphanEvidence() {
        return orphanEvidence;
    }

    public void setOrphanEvidence(List<String> orphanEvidence) {
        this.orphanEvidence = orphanEvidence;
    }

    public List<IndexedLogLine> getEvidenceLines() {
        return evidenceLines;
    }

    public void setEvidenceLines(List<IndexedLogLine> evidenceLines) {
        this.evidenceLines = evidenceLines;
    }

    public List<String> getDriftSignals() {
        return driftSignals;
    }

    public void setDriftSignals(List<String> driftSignals) {
        this.driftSignals = driftSignals;
    }

    public String getObservabilityIndexSummary() {
        return observabilityIndexSummary;
    }

    public void setObservabilityIndexSummary(String observabilityIndexSummary) {
        this.observabilityIndexSummary = observabilityIndexSummary;
    }

    public List<RuleEvaluation> getRules() {
        return rules;
    }

    public void setRules(List<RuleEvaluation> rules) {
        this.rules = rules;
    }

    public PolicyDecision getPolicyDecision() {
        return policyDecision;
    }

    public void setPolicyDecision(PolicyDecision policyDecision) {
        this.policyDecision = policyDecision;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getRetrySuggestion() {
        return retrySuggestion;
    }

    public void setRetrySuggestion(String retrySuggestion) {
        this.retrySuggestion = retrySuggestion;
    }
}
