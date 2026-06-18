package com.hcl.observability.trace;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class UnifiedTimelineBuilder {

    public List<NormalizedTraceEvent> build(List<NormalizedTraceEvent> events) {
        List<NormalizedTraceEvent> timeline = new ArrayList<>();
        if (events != null) {
            timeline.addAll(events);
        }

        timeline.sort(Comparator
                .comparing(NormalizedTraceEvent::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(event -> value(event.getSystem()))
                .thenComparing(event -> value(event.getPhase()))
                .thenComparing(event -> value(event.getOperation())));

        return timeline;
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
