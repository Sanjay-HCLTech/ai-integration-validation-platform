package com.hcl.execution.soap;

import com.hcl.execution.config.PlatformConfigResolver;
import com.hcl.execution.model.TestCase;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@Service
public class SoapFlowConfig {

    private final Environment environment;
    private final PlatformConfigResolver platformConfigResolver;

    public SoapFlowConfig(Environment environment, PlatformConfigResolver platformConfigResolver) {
        this.environment = environment;
        this.platformConfigResolver = platformConfigResolver;
    }

    public String env(TestCase testCase) {
        String requestEnv = testCase == null ? null : testCase.getEnv();
        return platformConfigResolver.normalize(firstText(requestEnv, property("soap.env", platformConfigResolver.env(null))));
    }

    public String downstreamSystem(TestCase testCase) {
        String requestSystem = testCase == null ? null : testCase.getDownstreamSystem();
        return platformConfigResolver.system(
                requestSystem,
                testCase == null ? null : testCase.getFlow(),
                property("soap.system.default", "DMS"));
    }

    public String endpoint(String env, String system) {
        return endpoint(env, system, null);
    }

    public String endpoint(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("soap.endpoint." + normalizedEnv + "." + normalizedService, ""),
                property("soap.endpoint." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("soap.endpoint." + normalizedService, ""),
                property("soap.endpoint." + normalizedEnv + "." + normalizedSystem, ""),
                property("soap.endpoint." + normalizedEnv, ""),
                property("soap.endpoint." + normalizedSystem, ""),
                property("soap.endpoint.default", ""),
                property("soap.endpoint", ""));
    }

    public String transport(String env, String system, String service, String fallback) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("soap.transport." + normalizedEnv + "." + normalizedService, ""),
                property("soap.transport." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("soap.transport." + normalizedService, ""),
                property("soap.transport." + normalizedEnv + "." + normalizedSystem, ""),
                property("soap.transport." + normalizedEnv, ""),
                property("soap.transport." + normalizedSystem, ""),
                property("soap.transport.default", ""),
                property("soap.transport", ""),
                fallback);
    }

    public String soapAction(String env, String system, String fallback) {
        return soapAction(env, system, null, fallback);
    }

    public String soapAction(String env, String system, String service, String fallback) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("soap.action." + normalizedEnv + "." + normalizedService, ""),
                property("soap.action." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("soap.action." + normalizedService, ""),
                property("soap.action." + normalizedEnv + "." + normalizedSystem, ""),
                property("soap.action." + normalizedEnv, ""),
                property("soap.action." + normalizedSystem, ""),
                fallback);
    }

    public String systemPayloadFolder(String system) {
        String normalizedSystem = platformConfigResolver.normalize(system);
        return firstText(
                property("soap.payload.system." + normalizedSystem, ""),
                property("soap.payload.system.folder." + normalizedSystem, ""),
                normalizedSystem);
    }

    public Path payloadRoot() {
        return Paths.get(property("soap.payload.root", platformConfigResolver.payloadRoot().resolve("SOAP").toString()))
                .normalize();
    }

    private String property(String key, String fallback) {
        String value = environment.getProperty(configKey(key));
        return hasText(value) ? value.trim() : fallback;
    }

    private String configKey(String key) {
        return key == null ? null : key.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private String firstText(String... values) {
        return platformConfigResolver.firstText(values);
    }

    private boolean hasText(String value) {
        return platformConfigResolver.hasText(value);
    }
}
