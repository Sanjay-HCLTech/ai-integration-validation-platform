package com.hcl.execution.rest;

public class RestAssertionResult {

    private final String name;
    private final boolean passed;
    private final String message;

    public RestAssertionResult(String name, boolean passed, String message) {
        this.name = name;
        this.passed = passed;
        this.message = message;
    }

    public String getName() {
        return name;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getMessage() {
        return message;
    }
}
