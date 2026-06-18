package com.hcl.observability.correlation;

public class TimelineValidationResult {

    private final boolean valid;
    private final String message;

    private TimelineValidationResult(boolean valid, String message) {
        this.valid = valid;
        this.message = message;
    }

    public static TimelineValidationResult pass(String message) {
        return new TimelineValidationResult(true, message);
    }

    public static TimelineValidationResult fail(String message) {
        return new TimelineValidationResult(false, message);
    }

    public boolean isValid() {
        return valid;
    }

    public String getMessage() {
        return message;
    }
}
