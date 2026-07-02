package com.hcl.ai.validation;

import com.hcl.ai.index.IndexedLogLine;
import com.hcl.ai.index.ObservabilityIndexResult;
import com.hcl.ai.report.IntelligenceExecutionRow;
import com.hcl.ai.report.IntelligenceExecutionSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class IntelligenceValidationService {

    private final ValidationRuleRegistry ruleRegistry;

    public IntelligenceValidationService(ValidationRuleRegistry ruleRegistry) {
        this.ruleRegistry = ruleRegistry;
    }

    public List<RuleEvaluation> evaluate(IntelligenceExecutionSnapshot snapshot) {
        return evaluate(snapshot, null);
    }

    public List<RuleEvaluation> evaluate(IntelligenceExecutionSnapshot snapshot, ObservabilityIndexResult indexResult) {
        List<RuleEvaluation> evaluations = new ArrayList<>();
        IntelligenceExecutionSnapshot safeSnapshot = snapshot == null ? new IntelligenceExecutionSnapshot() : snapshot;
        List<IntelligenceExecutionRow> rows = safeSnapshot.getRows() == null
                ? new ArrayList<>()
                : safeSnapshot.getRows();
        for (RuleDefinition rule : ruleRegistry.rules()) {
            if (rule.getScope() == RuleScope.EVIDENCE) {
                evaluations.add(evidenceRule(rule, rows, indexResult));
            } else {
                for (IntelligenceExecutionRow row : rows) {
                    if (applies(rule, row)) {
                        evaluations.add(rowRule(rule, row));
                    }
                }
                if (rows.isEmpty()) {
                    evaluations.add(noRowsRule(rule));
                }
            }
        }
        return evaluations;
    }

    private RuleEvaluation rowRule(RuleDefinition rule, IntelligenceExecutionRow row) {
        RuleEvaluation evaluation = new RuleEvaluation();
        applyRule(evaluation, rule);
        String matcher = value(rule.getMatcher()).toUpperCase(Locale.ROOT);
        if ("STATUS_PASS".equals(matcher)) {
            evaluation.setActual(pass(row) ? "Successful execution row" : value(row.getMessage()));
            evaluation.setStatus(pass(row) ? "PASS" : "FAIL");
        } else if ("TIMELINE_PRESENT".equals(matcher)) {
            evaluation.setActual(hasTimeline(row) ? "Timeline evidence present" : "Timeline evidence missing");
            evaluation.setStatus(hasTimeline(row) ? "PASS" : "FAIL");
        } else if (matcher.startsWith("ASSERTION_CONTAINS:")) {
            String expected = rule.getMatcher().substring("ASSERTION_CONTAINS:".length());
            boolean found = contains(row.getAssertions(), expected);
            evaluation.setActual(found ? "Assertion found: " + expected : "Assertion not found: " + expected);
            evaluation.setStatus(found ? "PASS" : "FAIL");
        } else {
            evaluation.setActual("Unsupported row matcher: " + value(rule.getMatcher()));
            evaluation.setStatus("FAIL");
        }
        return evaluation;
    }

    private RuleEvaluation evidenceRule(
            RuleDefinition rule,
            List<IntelligenceExecutionRow> rows,
            ObservabilityIndexResult indexResult) {
        RuleEvaluation evaluation = new RuleEvaluation();
        applyRule(evaluation, rule);
        String matcher = value(rule.getMatcher()).toUpperCase(Locale.ROOT);
        if (indexResult == null || indexResult.getEntries() == null || indexResult.getEntries().isEmpty()) {
            evaluation.setActual("No local indexed evidence available");
            evaluation.setStatus("FAIL");
            return evaluation;
        }
        if (matcher.startsWith("EVIDENCE_CONTAINS:")) {
            String expected = rule.getMatcher().substring("EVIDENCE_CONTAINS:".length());
            boolean found = evidenceContains(indexResult, expected);
            evaluation.setActual(found ? "Evidence contains: " + expected : "Evidence missing: " + expected);
            evaluation.setStatus(found ? "PASS" : "FAIL");
        } else if ("EVIDENCE_TOKEN_LINKED".equals(matcher)) {
            boolean linked = linkedEvidence(indexResult);
            evaluation.setActual(linked
                    ? "BookingID linked with CorrID or JobID in local evidence"
                    : "No local evidence line links BookingID with CorrID or JobID");
            evaluation.setStatus(linked ? "PASS" : "FAIL");
        } else {
            evaluation.setActual("Unsupported evidence matcher: " + value(rule.getMatcher()));
            evaluation.setStatus("FAIL");
        }
        return evaluation;
    }

    private RuleEvaluation noRowsRule(RuleDefinition rule) {
        RuleEvaluation evaluation = new RuleEvaluation();
        applyRule(evaluation, rule);
        evaluation.setActual("No execution rows available");
        evaluation.setStatus("FAIL");
        return evaluation;
    }

    private void applyRule(RuleEvaluation evaluation, RuleDefinition rule) {
        evaluation.setRuleId(rule.getId());
        evaluation.setType(rule.getType());
        evaluation.setScope(rule.getScope());
        evaluation.setRule(rule.getName());
        evaluation.setExpected(rule.getExpected());
    }

    private boolean applies(RuleDefinition rule, IntelligenceExecutionRow row) {
        if (row == null) {
            return false;
        }
        return matches(rule.getSystem(), row.getSystem())
                && matches(rule.getFlow(), row.getFlow())
                && matches(rule.getService(), row.getService());
    }

    private boolean matches(String expected, String actual) {
        return !hasText(expected) || (actual != null && expected.trim().equalsIgnoreCase(actual.trim()));
    }

    private boolean pass(IntelligenceExecutionRow row) {
        return row != null && "PASS".equalsIgnoreCase(row.getStatus());
    }

    private boolean hasTimeline(IntelligenceExecutionRow row) {
        return row != null && row.getTimeline() != null && !row.getTimeline().isEmpty();
    }

    private boolean contains(List<String> values, String expected) {
        if (values == null || !hasText(expected)) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean evidenceContains(ObservabilityIndexResult indexResult, String expected) {
        if (!hasText(expected)) {
            return false;
        }
        for (IndexedLogLine line : indexResult.getEntries()) {
            if (line != null && line.getLine() != null
                    && line.getLine().toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean linkedEvidence(ObservabilityIndexResult indexResult) {
        for (IndexedLogLine line : indexResult.getEntries()) {
            if (line == null) {
                continue;
            }
            if (hasText(line.getBookingId()) && (hasText(line.getCorrId()) || hasText(line.getJobId()))) {
                return true;
            }
        }
        return false;
    }

    private String value(String value) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? "NA" : text.toUpperCase(Locale.ROOT).equals("NA") ? "NA" : text;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
