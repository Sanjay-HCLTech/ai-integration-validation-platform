package com.hcl.observability.trace;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UnifiedTraceValidationService {

    public UnifiedTraceValidationResult validate(List<NormalizedTraceEvent> events) {
        Map<TraceSystem, TraceStatus> statuses = new LinkedHashMap<>();
        if (events != null) {
            for (NormalizedTraceEvent event : events) {
                TraceSystem system = event.getSystem() == null ? TraceSystem.UNKNOWN : event.getSystem();
                TraceStatus status = event.getStatus() == null ? TraceStatus.UNKNOWN : event.getStatus();
                statuses.put(system, stronger(statuses.get(system), status));
            }
        }

        boolean success = !statuses.isEmpty()
                && statuses.values().stream().noneMatch(status ->
                status == TraceStatus.FAILED || status == TraceStatus.ERROR || status == TraceStatus.UNKNOWN);

        return new UnifiedTraceValidationResult(statuses, success);
    }

    private TraceStatus stronger(TraceStatus existing, TraceStatus next) {
        if (existing == null) {
            return next;
        }
        if (existing == TraceStatus.ERROR || next == TraceStatus.ERROR) {
            return TraceStatus.ERROR;
        }
        if (existing == TraceStatus.FAILED || next == TraceStatus.FAILED) {
            return TraceStatus.FAILED;
        }
        if (existing == TraceStatus.DELIVERED || next == TraceStatus.DELIVERED) {
            return TraceStatus.DELIVERED;
        }
        if (existing == TraceStatus.PROCESSED || next == TraceStatus.PROCESSED) {
            return TraceStatus.PROCESSED;
        }
        if (existing == TraceStatus.CONSUMED || next == TraceStatus.CONSUMED) {
            return TraceStatus.CONSUMED;
        }
        if (existing == TraceStatus.SUCCESS || next == TraceStatus.SUCCESS) {
            return TraceStatus.SUCCESS;
        }
        return TraceStatus.UNKNOWN;
    }
}
