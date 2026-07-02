package com.hcl.ai.validation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ValidationRuleRegistry {

    private final String configuredRules;

    public ValidationRuleRegistry(@Value("${intelligence.validation.rules}") String configuredRules) {
        this.configuredRules = configuredRules;
    }

    public List<RuleDefinition> rules() {
        List<RuleDefinition> parsed = parse(configuredRules);
        return parsed.isEmpty() ? defaults() : parsed;
    }

    private List<RuleDefinition> parse(String value) {
        List<RuleDefinition> rules = new ArrayList<>();
        if (!hasText(value)) {
            return rules;
        }
        String[] definitions = value.split(";;");
        for (String definition : definitions) {
            RuleDefinition rule = parseDefinition(definition);
            if (rule != null && hasText(rule.getId()) && hasText(rule.getName())) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private RuleDefinition parseDefinition(String definition) {
        if (!hasText(definition)) {
            return null;
        }
        RuleDefinition rule = new RuleDefinition();
        String[] parts = definition.split(",");
        for (String part : parts) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(separator + 1).trim();
            apply(rule, key, value);
        }
        return rule;
    }

    private void apply(RuleDefinition rule, String key, String value) {
        if ("id".equals(key)) {
            rule.setId(value);
        } else if ("type".equals(key)) {
            rule.setType(ruleType(value));
        } else if ("scope".equals(key)) {
            rule.setScope(ruleScope(value));
        } else if ("name".equals(key)) {
            rule.setName(value);
        } else if ("expected".equals(key)) {
            rule.setExpected(value);
        } else if ("matcher".equals(key)) {
            rule.setMatcher(value);
        } else if ("system".equals(key)) {
            rule.setSystem(value);
        } else if ("flow".equals(key)) {
            rule.setFlow(value);
        } else if ("service".equals(key)) {
            rule.setService(value);
        }
    }

    private List<RuleDefinition> defaults() {
        List<RuleDefinition> rules = new ArrayList<>();
        rules.add(rule("TRIGGER_SUCCESS", RuleType.TECHNICAL, RuleScope.ROW,
                "Trigger acknowledgement", "Trigger result should be successful", "STATUS_PASS"));
        rules.add(rule("TIMELINE_PRESENT", RuleType.LOG, RuleScope.ROW,
                "Timeline evidence", "Trace and timeline should contain correlated evidence", "TIMELINE_PRESENT"));
        rules.add(rule("CORRELATION_LINKED", RuleType.LOG, RuleScope.EVIDENCE,
                "Correlation linkage", "Local evidence should link BookingID with CorrID or JobID", "EVIDENCE_TOKEN_LINKED"));
        RuleDefinition mongo = rule("MONGO_PERSISTENCE", RuleType.FUNCTIONAL, RuleScope.EVIDENCE,
                "Mongo persistence", "Mongo persistence confirmation should be present", "EVIDENCE_CONTAINS:Mongo");
        mongo.setSystem("TDA");
        rules.add(mongo);
        return rules;
    }

    private RuleDefinition rule(String id, RuleType type, RuleScope scope, String name, String expected, String matcher) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(type);
        rule.setScope(scope);
        rule.setName(name);
        rule.setExpected(expected);
        rule.setMatcher(matcher);
        return rule;
    }

    private RuleType ruleType(String value) {
        try {
            return RuleType.valueOf(value(value).toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return RuleType.FUNCTIONAL;
        }
    }

    private RuleScope ruleScope(String value) {
        try {
            return RuleScope.valueOf(value(value).toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return RuleScope.ROW;
        }
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
