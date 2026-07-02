package com.hcl.ai.validation;

public class RuleEvaluation {

    private String ruleId;
    private String rule;
    private RuleType type;
    private RuleScope scope;
    private String expected;
    private String actual;
    private String status;

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public RuleType getType() {
        return type;
    }

    public void setType(RuleType type) {
        this.type = type;
    }

    public RuleScope getScope() {
        return scope;
    }

    public void setScope(RuleScope scope) {
        this.scope = scope;
    }

    public String getExpected() {
        return expected;
    }

    public void setExpected(String expected) {
        this.expected = expected;
    }

    public String getActual() {
        return actual;
    }

    public void setActual(String actual) {
        this.actual = actual;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
