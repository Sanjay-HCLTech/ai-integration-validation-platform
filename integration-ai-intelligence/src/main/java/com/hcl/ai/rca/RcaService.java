package com.hcl.ai.rca;

import org.springframework.stereotype.Service;

@Service
public class RcaService {

    public String analyze(Object result) {

        if (result == null) {
            return "No execution data available";
        }

        return "Execution data received for RCA analysis";
    }
}
