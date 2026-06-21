package com.hcl.execution.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RestValidationResult {

    private final List<RestAssertionResult> assertions = new ArrayList<>();

    public void add(String name, boolean passed, String message) {
        assertions.add(new RestAssertionResult(name, passed, message));
    }

    public List<RestAssertionResult> getAssertions() {
        return Collections.unmodifiableList(assertions);
    }

    public boolean isPassed() {
        if (assertions.isEmpty()) {
            return false;
        }
        for (RestAssertionResult assertion : assertions) {
            if (!assertion.isPassed()) {
                return false;
            }
        }
        return true;
    }

    public String summary() {
        List<String> failed = new ArrayList<>();
        for (RestAssertionResult assertion : assertions) {
            if (!assertion.isPassed()) {
                failed.add(assertion.getName() + ": " + assertion.getMessage());
            }
        }
        return failed.isEmpty() ? "REST assertions passed" : String.join("; ", failed);
    }
}
