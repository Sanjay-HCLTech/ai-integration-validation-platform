package com.hcl.ai.trace;

import com.hcl.ai.index.IndexedLogLine;
import com.hcl.ai.index.ObservabilityIndexResult;
import com.hcl.ai.report.IntelligenceExecutionRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrphanLogDetectionService {

    public List<String> detect(List<IntelligenceExecutionRow> rows) {
        List<String> orphans = new ArrayList<>();
        if (rows == null) {
            return orphans;
        }
        for (IntelligenceExecutionRow row : rows) {
            if (missing(row.getCorrId()) && missing(row.getJobId())) {
                orphans.add(value(row.getSystem()) + "/" + value(row.getService()) + " has no CorrID or JobID");
            }
        }
        return orphans;
    }

    public List<String> detectEvidence(ObservabilityIndexResult indexResult) {
        Set<String> orphans = new LinkedHashSet<>();
        if (indexResult == null || indexResult.getEntries() == null) {
            return new ArrayList<>();
        }
        for (IndexedLogLine entry : indexResult.getEntries()) {
            if (entry == null) {
                continue;
            }
            boolean hasBooking = !missing(entry.getBookingId());
            boolean hasCorr = !missing(entry.getCorrId());
            boolean hasJob = !missing(entry.getJobId());
            if (hasBooking && !hasCorr && !hasJob) {
                orphans.add(value(entry.getFile()) + ":" + entry.getLineNumber()
                        + " has BookingID without CorrID/JobID");
            }
            if (!hasBooking && (hasCorr || hasJob)) {
                orphans.add(value(entry.getFile()) + ":" + entry.getLineNumber()
                        + " has correlation token without BookingID");
            }
        }
        return new ArrayList<>(orphans);
    }

    private boolean missing(String value) {
        return value == null || value.trim().isEmpty() || "NA".equalsIgnoreCase(value.trim());
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value.trim();
    }
}
