package com.hcl.observability.validation;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LogValidationService {

    public boolean validateBasic(List<String> logs) {

        if (logs == null || logs.isEmpty()) return false;

        boolean hasRequest = logs.stream().anyMatch(l -> l.contains("REQUEST"));
        boolean hasReply = logs.stream().anyMatch(l -> l.contains("REPLY"));

        return hasRequest && hasReply;
    }
}
