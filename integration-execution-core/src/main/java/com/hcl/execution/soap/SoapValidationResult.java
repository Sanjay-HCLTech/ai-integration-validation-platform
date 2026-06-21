package com.hcl.execution.soap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SoapValidationResult {

    private final List<SoapAssertionResult> assertions = new ArrayList<>();

    public void add(String name, boolean passed, String message) {
        assertions.add(new SoapAssertionResult(name, passed, message));
    }

    public List<SoapAssertionResult> getAssertions() {
        return Collections.unmodifiableList(assertions);
    }

    public boolean isPassed() {
        if (assertions.isEmpty()) {
            return false;
        }
        for (SoapAssertionResult assertion : assertions) {
            if (!assertion.isPassed()) {
                return false;
            }
        }
        return true;
    }

    public String summary() {
        List<String> failed = new ArrayList<>();
        for (SoapAssertionResult assertion : assertions) {
            if (!assertion.isPassed()) {
                failed.add(assertion.getName() + ": " + assertion.getMessage());
            }
        }
        return failed.isEmpty() ? "SOAP assertions passed" : String.join("; ", failed);
    }
}
