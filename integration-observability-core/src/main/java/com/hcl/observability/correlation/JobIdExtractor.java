package com.hcl.observability.correlation;

import java.util.List;

public class JobIdExtractor {

    public String extractJobId(List<String> lines) {

        for (String line : lines) {

            if (line.contains("JobID:")) {

                return line.split("JobID:")[1]
                        .trim()
                        .split("\\s+")[0];
            }
        }
        return null;
    }
}