package com.hcl.ai.rca;

import com.hcl.execution.model.ExecutionResult;
import com.hcl.execution.model.StepResult;
import org.springframework.stereotype.Service;

@Service
public class RcaService {

    public String analyze(ExecutionResult result) {

        if (result == null || result.getSteps() == null) {
            return "No execution data available";
        }

        for (StepResult step : result.getSteps()) {

            if ("FAIL".equalsIgnoreCase(step.getStatus())) {

                return "Failure at step: " + step.getStepName()
                        + " | Reason: " + step.getMessage();
            }
        }

        return "No failures detected";
    }
}
