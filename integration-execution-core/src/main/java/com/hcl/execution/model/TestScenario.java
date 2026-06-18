package com.hcl.execution.model;

import java.util.Locale;

public class TestScenario {

    private ScenarioCategory category;
    private ScenarioFlow flow;
    private String scenario;
    private ScenarioMode mode = ScenarioMode.SYNC;

    public TestScenario() {
    }

    public TestScenario(
            ScenarioCategory category,
            ScenarioFlow flow,
            String scenario,
            ScenarioMode mode) {
        this.category = category;
        this.flow = flow;
        setScenario(scenario);
        this.mode = mode == null ? ScenarioMode.SYNC : mode;
    }

    public static TestScenario of(String category, String flow, String scenario, String mode) {
        return new TestScenario(
                ScenarioCategory.from(category),
                ScenarioFlow.from(flow),
                normalizeScenario(scenario),
                ScenarioMode.from(mode));
    }

    public ScenarioCategory getCategory() {
        return category;
    }

    public void setCategory(ScenarioCategory category) {
        this.category = category;
    }

    public ScenarioFlow getFlow() {
        return flow;
    }

    public void setFlow(ScenarioFlow flow) {
        this.flow = flow;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = normalizeScenario(scenario);
    }

    public ScenarioMode getMode() {
        return mode;
    }

    public void setMode(ScenarioMode mode) {
        this.mode = mode == null ? ScenarioMode.SYNC : mode;
    }

    public boolean isAsync() {
        return ScenarioMode.ASYNC.equals(mode);
    }

    private static String normalizeScenario(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Scenario is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
