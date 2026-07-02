package com.hcl.execution.kafka;

import com.hcl.execution.config.PlatformConfigResolver;
import com.hcl.execution.model.TestCase;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class KafkaFlowConfig {

    private final Environment environment;
    private final PlatformConfigResolver platformConfigResolver;

    public KafkaFlowConfig(Environment environment, PlatformConfigResolver platformConfigResolver) {
        this.environment = environment;
        this.platformConfigResolver = platformConfigResolver;
    }

    public String env(TestCase testCase) {
        String requestEnv = testCase == null ? null : testCase.getEnv();
        return platformConfigResolver.normalize(firstText(requestEnv, property("kafka.env", platformConfigResolver.env(null))));
    }

    public String system(TestCase testCase) {
        String requestSystem = testCase == null ? null : testCase.getDownstreamSystem();
        return platformConfigResolver.system(
                requestSystem,
                testCase == null ? null : testCase.getFlow(),
                property("kafka.system.default", "DMS"));
    }

    public Path payloadRoot() {
        return Paths.get(property("kafka.payload.root", platformConfigResolver.payloadRoot().resolve("KAFKA").toString()))
                .normalize();
    }

    public String systemPayloadFolder(String system) {
        String normalizedSystem = platformConfigResolver.normalize(system);
        return firstText(
                property("kafka.payload.system." + normalizedSystem, ""),
                property("kafka.payload.system.folder." + normalizedSystem, ""),
                normalizedSystem);
    }

    public String payloadFile(TestCase testCase) {
        String normalizedEnv = env(testCase);
        String normalizedSystem = system(testCase);
        String normalizedScenario = platformConfigResolver.normalize(testCase == null ? null : testCase.getScenario());
        return firstText(
                property("kafka.payload." + normalizedEnv + "." + normalizedSystem + "." + normalizedScenario, ""),
                property("kafka.payload." + normalizedSystem + "." + normalizedScenario, ""),
                property("kafka.payload." + normalizedScenario, ""));
    }

    public String topic(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("kafka.topic." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("kafka.topic." + normalizedEnv + "." + normalizedService, ""),
                property("kafka.topic." + normalizedService, ""),
                property("kafka.topic." + normalizedEnv + "." + normalizedSystem, ""),
                property("kafka.topic." + normalizedEnv, ""),
                property("kafka.topic." + normalizedSystem, ""),
                property("kafka.topic.default", ""),
                property("kafka.topic", "integration.events"));
    }

    public String key(String env, String system, String service, String requestedKey, String bookingId) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                requestedKey,
                property("kafka.key." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("kafka.key." + normalizedEnv + "." + normalizedService, ""),
                property("kafka.key." + normalizedService, ""),
                property("kafka.key." + normalizedEnv + "." + normalizedSystem, ""),
                property("kafka.key." + normalizedEnv, ""),
                property("kafka.key." + normalizedSystem, ""),
                property("kafka.key", ""),
                bookingId,
                "NA");
    }

    public String consumerGroup(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("kafka.consumer.group." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("kafka.consumer.group." + normalizedEnv + "." + normalizedService, ""),
                property("kafka.consumer.group." + normalizedService, ""),
                property("kafka.consumer.group." + normalizedEnv + "." + normalizedSystem, ""),
                property("kafka.consumer.group." + normalizedEnv, ""),
                property("kafka.consumer.group." + normalizedSystem, ""),
                property("kafka.consumer.group.default", "ivp-simulation-consumer"));
    }

    public String messageType(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("kafka.message.type." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("kafka.message.type." + normalizedEnv + "." + normalizedService, ""),
                property("kafka.message.type." + normalizedService, ""),
                property("kafka.message.type." + normalizedEnv + "." + normalizedSystem, ""),
                property("kafka.message.type." + normalizedEnv, ""),
                property("kafka.message.type." + normalizedSystem, ""),
                property("kafka.message.type.default", ""),
                property("kafka.message.type", "DATAHUB_EVENT"));
    }

    public Map<String, String> messageHeaders(String env, String system, String service) {
        String configured = firstText(
                configuredMap("kafka.message.headers", env, system, service),
                configuredMap("kafka.headers", env, system, service));
        return parseMap(configured);
    }

    public Map<String, String> headers(String env, String system, String service) {
        return messageHeaders(env, system, service);
    }

    public String trackingId(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("kafka.tracking.id." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("kafka.tracking.id." + normalizedEnv + "." + normalizedService, ""),
                property("kafka.tracking.id." + normalizedService, ""),
                property("kafka.tracking.id." + normalizedEnv + "." + normalizedSystem, ""),
                property("kafka.tracking.id." + normalizedEnv, ""),
                property("kafka.tracking.id." + normalizedSystem, ""),
                property("kafka.tracking.id", ""));
    }

    public int partition(String env, String system, String service) {
        String value = configuredValue("kafka.partition", env, system, service, "0");
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String configuredMap(String prefix, String env, String system, String service) {
        return configuredValue(prefix, env, system, service, "");
    }

    private String configuredValue(String prefix, String env, String system, String service, String fallback) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property(prefix + "." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property(prefix + "." + normalizedEnv + "." + normalizedService, ""),
                property(prefix + "." + normalizedService, ""),
                property(prefix + "." + normalizedEnv + "." + normalizedSystem, ""),
                property(prefix + "." + normalizedEnv, ""),
                property(prefix + "." + normalizedSystem, ""),
                property(prefix, fallback));
    }

    private Map<String, String> parseMap(String value) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (!platformConfigResolver.hasText(value)) {
            return parsed;
        }
        String[] pairs = value.split(";");
        for (String pair : pairs) {
            if (!platformConfigResolver.hasText(pair)) {
                continue;
            }
            int equals = pair == null ? -1 : pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = pair.substring(0, equals).trim();
            String entryValue = pair.substring(equals + 1).trim();
            if (platformConfigResolver.hasText(key)) {
                parsed.put(key, entryValue);
            }
        }
        return parsed;
    }

    private String property(String key, String fallback) {
        String value = environment.getProperty(configKey(key));
        return platformConfigResolver.hasText(value) ? value.trim() : fallback;
    }

    private String configKey(String key) {
        return key == null ? null : key.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private String firstText(String... values) {
        return platformConfigResolver.firstText(values);
    }
}
