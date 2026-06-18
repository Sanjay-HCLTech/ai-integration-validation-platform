package com.hcl.observability.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class UnifiedTraceValidationResult {

    private final Map<TraceSystem, TraceStatus> systemStatuses;
    private final boolean endToEndSuccess;

    public UnifiedTraceValidationResult(Map<TraceSystem, TraceStatus> systemStatuses, boolean endToEndSuccess) {
        this.systemStatuses = Collections.unmodifiableMap(new LinkedHashMap<>(systemStatuses));
        this.endToEndSuccess = endToEndSuccess;
    }

    public Map<TraceSystem, TraceStatus> getSystemStatuses() {
        return systemStatuses;
    }

    public boolean isEndToEndSuccess() {
        return endToEndSuccess;
    }
}
