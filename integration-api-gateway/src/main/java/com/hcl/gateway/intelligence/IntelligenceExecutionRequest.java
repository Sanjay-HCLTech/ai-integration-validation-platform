package com.hcl.gateway.intelligence;

import com.hcl.ai.intent.IntelligenceMode;

import java.util.ArrayList;
import java.util.List;

public class IntelligenceExecutionRequest {

    private String executionId;
    private IntelligenceMode mode = IntelligenceMode.SMART_PROMPT;
    private String prompt;
    private String bookingId;
    private String env;
    private List<String> systems = new ArrayList<>();
    private List<String> flowTypes = new ArrayList<>();
    private List<String> services = new ArrayList<>();
    private String payloadMode;
    private boolean runAllServices;
    private boolean parallel = true;
    private boolean traceEnabled = true;
    private boolean bookingIdExplicit;

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public IntelligenceMode getMode() {
        return mode;
    }

    public void setMode(IntelligenceMode mode) {
        this.mode = mode;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public List<String> getSystems() {
        return systems;
    }

    public void setSystems(List<String> systems) {
        this.systems = systems;
    }

    public List<String> getFlowTypes() {
        return flowTypes;
    }

    public void setFlowTypes(List<String> flowTypes) {
        this.flowTypes = flowTypes;
    }

    public List<String> getServices() {
        return services;
    }

    public void setServices(List<String> services) {
        this.services = services;
    }

    public String getPayloadMode() {
        return payloadMode;
    }

    public void setPayloadMode(String payloadMode) {
        this.payloadMode = payloadMode;
    }

    public boolean isRunAllServices() {
        return runAllServices;
    }

    public void setRunAllServices(boolean runAllServices) {
        this.runAllServices = runAllServices;
    }

    public boolean isParallel() {
        return parallel;
    }

    public void setParallel(boolean parallel) {
        this.parallel = parallel;
    }

    public boolean isTraceEnabled() {
        return traceEnabled;
    }

    public void setTraceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
    }

    public boolean isBookingIdExplicit() {
        return bookingIdExplicit;
    }

    public void setBookingIdExplicit(boolean bookingIdExplicit) {
        this.bookingIdExplicit = bookingIdExplicit;
    }
}
