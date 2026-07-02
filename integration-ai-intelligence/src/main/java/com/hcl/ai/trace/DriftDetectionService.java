package com.hcl.ai.trace;

import com.hcl.ai.index.IndexedLogLine;
import com.hcl.ai.index.ObservabilityIndexResult;
import com.hcl.ai.plan.ExecutionPlan;
import com.hcl.ai.report.IntelligenceExecutionRow;
import com.hcl.ai.report.IntelligenceExecutionSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class DriftDetectionService {

    private final BehaviorSnapshotStore snapshotStore;
    private final boolean autoCreateBaseline;

    public DriftDetectionService(
            BehaviorSnapshotStore snapshotStore,
            @Value("${intelligence.drift.baseline.auto-create}") boolean autoCreateBaseline) {
        this.snapshotStore = snapshotStore;
        this.autoCreateBaseline = autoCreateBaseline;
    }

    public List<String> detect(ExecutionPlan plan, List<IntelligenceExecutionRow> rows) {
        return detect(plan, snapshot(rows), null, null);
    }

    public List<String> detect(
            ExecutionPlan plan,
            IntelligenceExecutionSnapshot execution,
            ObservabilityIndexResult indexResult,
            TraceGraph traceGraph) {
        List<String> signals = routeSignals(plan, rows(execution));
        if (plan == null) {
            return signals;
        }

        BehaviorSnapshot current = snapshot(plan, execution, indexResult, traceGraph);
        Optional<BehaviorSnapshot> baseline = snapshotStore.get(current.getBaselineId());
        if (baseline.isPresent()) {
            compare(baseline.get(), current, signals);
        } else if (autoCreateBaseline && current.getFail() == 0 && current.getEvidenceLineCount() > 0) {
            snapshotStore.save(current);
            signals.add("Baseline snapshot created for " + current.getBaselineId());
        } else {
            signals.add("No stored baseline snapshot found for " + current.getBaselineId());
        }
        if (signals.isEmpty()) {
            signals.add("No drift detected against planned route and stored baseline");
        }
        return signals;
    }

    private List<String> routeSignals(ExecutionPlan plan, List<IntelligenceExecutionRow> rows) {
        List<String> signals = new ArrayList<>();
        if (plan == null || rows == null || rows.isEmpty()) {
            return signals;
        }
        for (IntelligenceExecutionRow row : rows) {
            if (!contains(plan.getFlowTypes(), row.getFlow())) {
                signals.add("Unexpected trigger mode observed: " + value(row.getFlow()));
            }
            if (!contains(plan.getSystems(), row.getSystem())) {
                signals.add("Unexpected system observed: " + value(row.getSystem()));
            }
            if (!contains(plan.getServices(), row.getService())) {
                signals.add("Unexpected service observed: " + value(row.getService()));
            }
        }
        return signals;
    }

    private BehaviorSnapshot snapshot(
            ExecutionPlan plan,
            IntelligenceExecutionSnapshot execution,
            ObservabilityIndexResult indexResult,
            TraceGraph traceGraph) {
        BehaviorSnapshot snapshot = new BehaviorSnapshot();
        snapshot.setBaselineId(baselineId(plan));
        snapshot.setTemplateId(value(plan.getTemplateId()));
        snapshot.setTemplateName(value(plan.getTemplateName()));
        snapshot.setCreatedAt(Instant.now());
        snapshot.setSystems(unique(plan.getSystems()));
        snapshot.setFlowTypes(unique(plan.getFlowTypes()));
        snapshot.setServices(unique(plan.getServices()));
        snapshot.setDownstreamTargets(unique(plan.getDownstreamTargets()));
        snapshot.setTracePath(traceGraph == null
                ? new ArrayList<>()
                : scopedTracePath(plan, traceGraph.getPath()));
        snapshot.setEvidenceFiles(evidenceFiles(plan, indexResult));
        snapshot.setEvidenceLineCount(indexResult == null ? 0 : indexResult.getLinesIndexed());
        snapshot.setPass(execution == null ? 0 : execution.getPass());
        snapshot.setFail(execution == null ? 0 : execution.getFail());
        snapshot.setDurationMs(execution == null ? 0 : execution.getDurationMs());
        return snapshot;
    }

    private void compare(BehaviorSnapshot baseline, BehaviorSnapshot current, List<String> signals) {
        compareList("system route", baseline.getSystems(), current.getSystems(), signals);
        compareList("trigger route", baseline.getFlowTypes(), current.getFlowTypes(), signals);
        compareList("service route", baseline.getServices(), current.getServices(), signals);
        compareList("downstream target", baseline.getDownstreamTargets(), current.getDownstreamTargets(), signals);
        compareList("trace path", baseline.getTracePath(), current.getTracePath(), signals);
        compareList("evidence files", baseline.getEvidenceFiles(), current.getEvidenceFiles(), signals);
        if (baseline.getEvidenceLineCount() > 0 && current.getEvidenceLineCount() == 0) {
            signals.add("Drift: current run has no indexed evidence lines; baseline had "
                    + baseline.getEvidenceLineCount());
        }
        if (baseline.getFail() == 0 && current.getFail() > 0) {
            signals.add("Drift: current run failed while baseline passed");
        }
        if (baseline.getDurationMs() > 0 && current.getDurationMs() > baseline.getDurationMs() * 2) {
            signals.add("Drift: duration increased from " + baseline.getDurationMs()
                    + "ms to " + current.getDurationMs() + "ms");
        }
        if (signals.isEmpty()) {
            signals.add("No drift detected against baseline " + baseline.getBaselineId());
        }
    }

    private void compareList(String label, List<String> baseline, List<String> current, List<String> signals) {
        List<String> missing = missing(baseline, current);
        List<String> added = missing(current, baseline);
        if (!missing.isEmpty()) {
            signals.add("Drift: missing " + label + " item(s): " + String.join(", ", missing));
        }
        if (!added.isEmpty()) {
            signals.add("Drift: new " + label + " item(s): " + String.join(", ", added));
        }
    }

    private String baselineId(ExecutionPlan plan) {
        List<String> systems = unique(plan == null ? null : plan.getSystems());
        List<String> flowTypes = unique(plan == null ? null : plan.getFlowTypes());
        List<String> services = unique(plan == null ? null : plan.getServices());
        String templateId = hasText(plan == null ? null : plan.getTemplateId())
                ? plan.getTemplateId().trim()
                : "PLAN";
        return normalizeId(templateId)
                + "__" + normalizeId(String.join("_", systems))
                + "__" + normalizeId(String.join("_", flowTypes))
                + "__" + normalizeId(String.join("_", services));
    }

    private List<String> scopedTracePath(ExecutionPlan plan, List<String> tracePath) {
        List<String> values = unique(tracePath);
        if (!isRabbitPlan(plan)) {
            return values;
        }
        List<String> rabbitValues = new ArrayList<>();
        for (String value : values) {
            String normalized = normalizeId(value);
            if (normalized.contains("AUDIT")
                    || normalized.contains("RABBIT")
                    || normalized.contains("NORDICS")
                    || normalized.contains("RESERVATIONEVENT")) {
                rabbitValues.add(value);
            }
        }
        if (rabbitValues.isEmpty() && !values.isEmpty()) {
            rabbitValues.add("audit");
        }
        return unique(rabbitValues);
    }

    private List<String> evidenceFiles(ExecutionPlan plan, ObservabilityIndexResult indexResult) {
        Set<String> values = new LinkedHashSet<>();
        if (indexResult != null && indexResult.getEntries() != null) {
            for (IndexedLogLine entry : indexResult.getEntries()) {
                if (entry != null && hasText(entry.getFile())) {
                    String file = entry.getFile().trim();
                    if (!isRabbitPlan(plan) || isRabbitEvidenceFile(file)) {
                        values.add(file);
                    }
                }
            }
        }
        return new ArrayList<>(values);
    }

    private boolean isRabbitEvidenceFile(String file) {
        return hasText(file) && "audit.log".equalsIgnoreCase(file.trim());
    }

    private boolean isRabbitPlan(ExecutionPlan plan) {
        return contains(plan == null ? null : plan.getFlowTypes(), "RABBIT")
                || contains(plan == null ? null : plan.getFlowTypes(), "RABBITMQ")
                || contains(plan == null ? null : plan.getServices(), "ReservationEvent_v3");
    }

    private String normalizeId(String value) {
        if (!hasText(value)) {
            return "NA";
        }
        return value.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private List<String> unique(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    unique.add(value.trim());
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private List<String> missing(List<String> expected, List<String> actual) {
        List<String> missing = new ArrayList<>();
        for (String value : unique(expected)) {
            if (!contains(actual, value)) {
                missing.add(value);
            }
        }
        return missing;
    }

    private IntelligenceExecutionSnapshot snapshot(List<IntelligenceExecutionRow> rows) {
        IntelligenceExecutionSnapshot snapshot = new IntelligenceExecutionSnapshot();
        snapshot.setRows(rows == null ? new ArrayList<>() : new ArrayList<>(rows));
        return snapshot;
    }

    private List<IntelligenceExecutionRow> rows(IntelligenceExecutionSnapshot execution) {
        return execution == null || execution.getRows() == null ? new ArrayList<>() : execution.getRows();
    }

    private boolean contains(List<String> values, String expected) {
        if (values == null || values.isEmpty() || !hasText(expected)) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.trim().equalsIgnoreCase(expected.trim())) {
                return true;
            }
        }
        return false;
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
