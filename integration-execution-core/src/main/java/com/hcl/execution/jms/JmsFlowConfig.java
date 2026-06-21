package com.hcl.execution.jms;

import com.hcl.execution.config.PlatformConfigResolver;
import com.hcl.execution.model.TestCase;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class JmsFlowConfig {

    private final Environment environment;
    private final PlatformConfigResolver platformConfigResolver;

    public JmsFlowConfig(Environment environment, PlatformConfigResolver platformConfigResolver) {
        this.environment = environment;
        this.platformConfigResolver = platformConfigResolver;
    }

    public String env(TestCase testCase) {
        String requestEnv = testCase == null ? null : testCase.getEnv();
        return platformConfigResolver.normalize(firstText(requestEnv, property("jms.env", platformConfigResolver.env(null))));
    }

    public String system(TestCase testCase) {
        String requestSystem = testCase == null ? null : testCase.getDownstreamSystem();
        return platformConfigResolver.system(
                requestSystem,
                testCase == null ? null : testCase.getFlow(),
                property("jms.system.default", "DMS"));
    }

    public Path payloadRoot() {
        return Paths.get(property("jms.payload.root", platformConfigResolver.payloadRoot().resolve("DATAHUB").toString()))
                .normalize();
    }

    public String systemPayloadFolder(String system) {
        String normalizedSystem = platformConfigResolver.normalize(system);
        return firstText(
                property("jms.payload.system." + normalizedSystem, ""),
                property("jms.payload.system.folder." + normalizedSystem, ""),
                normalizedSystem);
    }

    public String payloadFile(TestCase testCase) {
        String normalizedEnv = env(testCase);
        String normalizedSystem = system(testCase);
        String normalizedScenario = platformConfigResolver.normalize(testCase == null ? null : testCase.getScenario());
        return firstText(
                property("jms.payload." + normalizedEnv + "." + normalizedSystem + "." + normalizedScenario, ""),
                property("jms.payload." + normalizedSystem + "." + normalizedScenario, ""),
                property("jms.payload." + normalizedScenario, ""));
    }

    public String destinationType(String env, String system) {
        return destinationType(env, system, null);
    }

    public String destinationType(String env, String system, String scenario) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedScenario = platformConfigResolver.normalize(scenario);
        return platformConfigResolver.normalize(firstText(
                property("jms.destination.type." + normalizedEnv + "." + normalizedSystem + "." + normalizedScenario, ""),
                property("jms.destination.type." + normalizedEnv + "." + normalizedSystem, ""),
                property("jms.destination.type." + normalizedEnv, ""),
                property("jms.destination.type." + normalizedSystem, ""),
                property("jms.destination.type.default", ""),
                property("jms.destination.type", "QUEUE")));
    }

    public String destinationName(String env, String system) {
        return destinationName(env, system, null);
    }

    public String destinationName(String env, String system, String scenario) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedScenario = platformConfigResolver.normalize(scenario);
        return firstText(
                property("jms.destination." + normalizedEnv + "." + normalizedSystem + "." + normalizedScenario, ""),
                property("jms.destination." + normalizedEnv + "." + normalizedSystem + ".default", ""),
                property("jms.destination." + normalizedEnv + "." + normalizedSystem, ""),
                property("jms.destination." + normalizedEnv, ""),
                property("jms.destination." + normalizedSystem, ""),
                property("jms.destination", ""),
                property("ems.queue.sender", ""));
    }

    public String messageType(String env, String system) {
        return messageType(env, system, null);
    }

    public String messageType(String env, String system, String scenario) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedScenario = platformConfigResolver.normalize(scenario);
        return firstText(
                property("jms.message.type." + normalizedEnv + "." + normalizedSystem + "." + normalizedScenario, ""),
                property("jms.message.type." + normalizedEnv + "." + normalizedSystem, ""),
                property("jms.message.type." + normalizedEnv, ""),
                property("jms.message.type." + normalizedSystem, ""),
                property("jms.message.type.default", ""),
                property("jms.message.type", "DATAHUB_EVENT"));
    }

    private String property(String key, String fallback) {
        String value = environment.getProperty(key);
        return platformConfigResolver.hasText(value) ? value.trim() : fallback;
    }

    private String firstText(String... values) {
        return platformConfigResolver.firstText(values);
    }
}
