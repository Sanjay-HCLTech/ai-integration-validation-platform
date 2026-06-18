package com.hcl.observability.correlation;

import java.util.List;

public class CorrIdExtractor {

    public static String extractCorrId(List<String> logs) {
        for (String line : logs) {
            String corrId = valueAfter(line, "CorrID=", "CorrID:", "JMSCorrelationID=", "JMSCorrelationID:");
            if (corrId != null) {
                return corrId;
            }
        }

        return null;
    }

    public static String extractJobId(List<String> logs) {
        for (String line : logs) {
            String jobId = valueAfter(line, "JobID=", "JobID:", "JobId=", "JobId:");
            if (jobId != null) {
                return jobId;
            }
        }

        return null;
    }

    private static String valueAfter(String line, String... markers) {
        if (line == null) {
            return null;
        }

        for (String marker : markers) {
            int start = line.indexOf(marker);
            if (start < 0) {
                continue;
            }

            String value = line.substring(start + marker.length()).trim();
            if (value.isEmpty()) {
                continue;
            }

            return value.split("[\\s,;|]+")[0];
        }

        return null;
    }
}
