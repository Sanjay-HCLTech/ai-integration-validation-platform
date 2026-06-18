package com.hcl.observability.correlation;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class LogCorrelationService {

    public List<String> readAllLogs(List<String> files) {
        List<String> allLines = new ArrayList<>();

        try {
            for (String file : files) {
                allLines.addAll(Files.readAllLines(Paths.get(file)));
            }
        } catch (Exception ignored) {
            return allLines;
        }

        return allLines;
    }

    public String extractCorrId(List<String> lines) {
        for (String line : lines) {
            if (line.contains("CorrID:")) {
                return line.split("CorrID:")[1].trim().split("\\s+")[0];
            }
        }

        return null;
    }

    public String extractJobId(List<String> lines) {
        for (String line : lines) {
            if (line.contains("JobID:")) {
                return line.split("JobID:")[1].trim().split("\\s+")[0];
            }
        }

        return null;
    }

    public boolean processCorrelation(List<String> lines) {
        String corrId = extractCorrId(lines);
        if (corrId == null) {
            return false;
        }

        TimelineValidator validator = new TimelineValidator();
        return validator.validateTimeline(lines, corrId);
    }
}
