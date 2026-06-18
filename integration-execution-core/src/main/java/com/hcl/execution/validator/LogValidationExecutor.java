package com.hcl.execution.validator;

import com.hcl.execution.model.StepResult;
import com.hcl.execution.model.TestStep;

import java.util.Map;

public class LogValidationExecutor {

    public StepResult validate(TestStep step,
            Map<String, Map<String, Map<String, Object>>> logs) {

        StepResult result = new StepResult();
        result.setStepName(step.getStepName());

        try {

            // Simple match validation
            if (logs.containsKey(step.getSystem())
                    && logs.get(step.getSystem()).containsKey(step.getEvent())) {

                result.setStatus("PASS");
                result.setMessage("Validation successful");

            } else {
                result.setStatus("FAIL");
                result.setMessage("Log not found");
            }

        } catch (Exception e) {
            result.setStatus("FAIL");
            result.setMessage("Error during validation");
        }

        return result;
    }
}