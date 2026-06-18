package com.hcl.observability.autotest;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class FlowBuilder {

    public FlowPattern build(List<FlowEvent> rawEvents) {
        List<FlowEvent> events = sortAndDeduplicate(rawEvents);
        List<String> systems = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        Set<String> systemSet = new LinkedHashSet<>();
        Set<String> stepSet = new LinkedHashSet<>();

        for (FlowEvent event : events) {
            if (hasText(event.getSystem()) && systemSet.add(event.getSystem())) {
                systems.add(event.getSystem());
            }

            String step = stepName(event);
            if (hasText(step) && stepSet.add(step)) {
                steps.add(step);
            }

        }

        String source = systems.isEmpty() ? "" : systems.get(0);
        String finalSystem = systems.isEmpty() ? "" : systems.get(systems.size() - 1);
        List<String> downstream = systems.size() <= 1
                ? new ArrayList<>()
                : new ArrayList<>(systems.subList(1, systems.size()));

        return new FlowPattern(events, systems, steps, source, downstream, finalSystem);
    }

    public List<FlowEvent> sortAndDeduplicate(List<FlowEvent> rawEvents) {
        if (rawEvents == null || rawEvents.isEmpty()) {
            return new ArrayList<>();
        }

        List<FlowEvent> sorted = new ArrayList<>(rawEvents);
        sorted.sort(Comparator.comparingLong(this::timelineSortTimestamp));

        Set<String> seen = new LinkedHashSet<>();
        List<FlowEvent> result = new ArrayList<>();
        for (FlowEvent event : sorted) {
            if (event == null) {
                continue;
            }
            if (seen.add(event.dedupKey())) {
                result.add(event);
            }
        }
        return result;
    }

    private long timelineSortTimestamp(FlowEvent event) {
        if (event == null || event.getTimestamp() <= 0) {
            return Long.MAX_VALUE;
        }
        return event.getTimestamp();
    }

    private String stepName(FlowEvent event) {
        if (event == null || !hasText(event.getSystem()) || !hasText(event.getPhase())) {
            return "";
        }
        return event.getSystem() + "_" + event.getPhase();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static class FlowPattern {
        private final List<FlowEvent> timeline;
        private final List<String> systems;
        private final List<String> steps;
        private final String sourceSystem;
        private final List<String> downstreamSystems;
        private final String finalSystem;

        private FlowPattern(
                List<FlowEvent> timeline,
                List<String> systems,
                List<String> steps,
                String sourceSystem,
                List<String> downstreamSystems,
                String finalSystem) {
            this.timeline = Collections.unmodifiableList(new ArrayList<>(timeline));
            this.systems = Collections.unmodifiableList(new ArrayList<>(systems));
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.sourceSystem = sourceSystem;
            this.downstreamSystems = Collections.unmodifiableList(new ArrayList<>(downstreamSystems));
            this.finalSystem = finalSystem;
        }

        public List<FlowEvent> getTimeline() {
            return timeline;
        }

        public List<String> getSystems() {
            return systems;
        }

        public List<String> getSteps() {
            return steps;
        }

        public String getSourceSystem() {
            return sourceSystem;
        }

        public List<String> getDownstreamSystems() {
            return downstreamSystems;
        }

        public String getFinalSystem() {
            return finalSystem;
        }

    }
}
