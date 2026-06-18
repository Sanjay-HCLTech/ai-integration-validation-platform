package com.hcl.observability.trace;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UnifiedTraceContextHolder {

    private final ThreadLocal<UnifiedTraceContext> current = new ThreadLocal<>();

    public UnifiedTraceContext begin(String testCaseId, String bookingId) {
        UnifiedTraceContext context = new UnifiedTraceContext();
        context.setTestCaseId(testCaseId);
        context.setBookingId(bookingId);
        current.set(context);
        return context;
    }

    public UnifiedTraceContext currentOrCreate() {
        UnifiedTraceContext context = current.get();
        if (context == null) {
            context = new UnifiedTraceContext();
            current.set(context);
        }
        return context;
    }

    public UnifiedTraceContext current() {
        return current.get();
    }

    public void addEvents(List<NormalizedTraceEvent> events) {
        currentOrCreate().addEvents(events);
    }

    public void addFileLine(String line) {
        currentOrCreate().addFileLine(line);
    }

    public void addRetryLine(String line) {
        currentOrCreate().addRetryLine(line);
    }

    public void addProtocolLine(String protocol, String line) {
        currentOrCreate().addProtocolLine(protocol, line);
    }

    public void addValidationLine(String line) {
        currentOrCreate().addValidationLine(line);
    }

    public void addSummaryLine(String line) {
        currentOrCreate().addSummaryLine(line);
    }

    public void clear() {
        current.remove();
    }
}
