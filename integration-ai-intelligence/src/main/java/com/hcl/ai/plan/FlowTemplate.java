package com.hcl.ai.plan;

import java.util.ArrayList;
import java.util.List;

public class FlowTemplate {

    private String templateId;
    private String name;
    private List<String> systems = new ArrayList<>();
    private List<String> flowTypes = new ArrayList<>();
    private List<String> services = new ArrayList<>();
    private List<String> downstreamTargets = new ArrayList<>();
    private boolean replaySafe;

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public boolean isReplaySafe() {
        return replaySafe;
    }

    public void setReplaySafe(boolean replaySafe) {
        this.replaySafe = replaySafe;
    }
}
