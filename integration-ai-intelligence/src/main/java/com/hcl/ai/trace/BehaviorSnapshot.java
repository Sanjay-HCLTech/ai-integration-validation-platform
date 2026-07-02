package com.hcl.ai.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BehaviorSnapshot {

    private String baselineId;
    private String templateId;
    private String templateName;
    private Instant createdAt;
    private List<String> systems = new ArrayList<>();
    private List<String> flowTypes = new ArrayList<>();
    private List<String> services = new ArrayList<>();
    private List<String> downstreamTargets = new ArrayList<>();
    private List<String> tracePath = new ArrayList<>();
    private List<String> evidenceFiles = new ArrayList<>();
    private int evidenceLineCount;
    private int pass;
    private int fail;
    private long durationMs;

    public String getBaselineId() {
        return baselineId;
    }

    public void setBaselineId(String baselineId) {
        this.baselineId = baselineId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
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

    public List<String> getDownstreamTargets() {
        return downstreamTargets;
    }

    public void setDownstreamTargets(List<String> downstreamTargets) {
        this.downstreamTargets = downstreamTargets;
    }

    public List<String> getTracePath() {
        return tracePath;
    }

    public void setTracePath(List<String> tracePath) {
        this.tracePath = tracePath;
    }

    public List<String> getEvidenceFiles() {
        return evidenceFiles;
    }

    public void setEvidenceFiles(List<String> evidenceFiles) {
        this.evidenceFiles = evidenceFiles;
    }

    public int getEvidenceLineCount() {
        return evidenceLineCount;
    }

    public void setEvidenceLineCount(int evidenceLineCount) {
        this.evidenceLineCount = evidenceLineCount;
    }

    public int getPass() {
        return pass;
    }

    public void setPass(int pass) {
        this.pass = pass;
    }

    public int getFail() {
        return fail;
    }

    public void setFail(int fail) {
        this.fail = fail;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
