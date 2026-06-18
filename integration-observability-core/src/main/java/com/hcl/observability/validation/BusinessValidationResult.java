package com.hcl.observability.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BusinessValidationResult {

    private final String category;
    private final String status;
    private final String expected;
    private final String actual;
    private final String result;
    private final String failurePoint;
    private final List<String> gaps;
    private final List<String> supportActions;

    public BusinessValidationResult(
            String category,
            String status,
            String expected,
            String actual,
            String result,
            String failurePoint,
            List<String> gaps,
            List<String> supportActions) {
        this.category = category;
        this.status = status;
        this.expected = expected;
        this.actual = actual;
        this.result = result;
        this.failurePoint = failurePoint;
        this.gaps = Collections.unmodifiableList(new ArrayList<>(gaps));
        this.supportActions = Collections.unmodifiableList(new ArrayList<>(supportActions));
    }

    public String getCategory() {
        return category;
    }

    public String getStatus() {
        return status;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }

    public String getResult() {
        return result;
    }

    public String getFailurePoint() {
        return failurePoint;
    }

    public List<String> getGaps() {
        return gaps;
    }

    public List<String> getSupportActions() {
        return supportActions;
    }

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }
}
