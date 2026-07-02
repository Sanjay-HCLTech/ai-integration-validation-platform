package com.hcl.ai.plan;

import java.util.ArrayList;
import java.util.List;

public class ExecutionPlan {

    private String env;
    private List<String> systems = new ArrayList<>();
    private List<String> flowTypes = new ArrayList<>();
    private List<String> services = new ArrayList<>();
    private String payloadMode;
    private String bookingId;
    private boolean parallel = true;
    private boolean traceEnabled = true;
    private boolean replaySafe;
    private String summary;
    private String templateId;
    private String templateName;
    private List<String> downstreamTargets = new ArrayList<>();

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

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
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

    public boolean isReplaySafe() {
        return replaySafe;
    }

    public void setReplaySafe(boolean replaySafe) {
        this.replaySafe = replaySafe;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public List<String> getDownstreamTargets() {
        return downstreamTargets;
    }

    public void setDownstreamTargets(List<String> downstreamTargets) {
        this.downstreamTargets = downstreamTargets;
    }
}
