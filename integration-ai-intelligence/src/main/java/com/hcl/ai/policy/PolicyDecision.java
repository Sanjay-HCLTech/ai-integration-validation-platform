package com.hcl.ai.policy;

import java.util.ArrayList;
import java.util.List;

public class PolicyDecision {

    private boolean retryAllowed;
    private String reason;
    private List<String> controls = new ArrayList<>();

    public boolean isRetryAllowed() {
        return retryAllowed;
    }

    public void setRetryAllowed(boolean retryAllowed) {
        this.retryAllowed = retryAllowed;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<String> getControls() {
        return controls;
    }

    public void setControls(List<String> controls) {
        this.controls = controls;
    }
}
