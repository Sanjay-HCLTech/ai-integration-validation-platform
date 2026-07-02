package com.hcl.execution.rabbit;

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
public class RabbitFlowConfig {

    private final Environment environment;
    private final PlatformConfigResolver platformConfigResolver;

    public RabbitFlowConfig(Environment environment, PlatformConfigResolver platformConfigResolver) {
        this.environment = environment;
        this.platformConfigResolver = platformConfigResolver;
    }

    public String env(TestCase testCase) {
        String requestEnv = testCase == null ? null : testCase.getEnv();
        return platformConfigResolver.normalize(firstText(requestEnv, property("rabbit.env", platformConfigResolver.env(null))));
    }

    public String system(TestCase testCase) {
        String requestSystem = testCase == null ? null : testCase.getDownstreamSystem();
        return platformConfigResolver.system(
                requestSystem,
                testCase == null ? null : testCase.getFlow(),
                property("rabbit.system.default", "DMS"));
    }

    public Path payloadRoot() {
        return Paths.get(property("rabbit.payload.root", platformConfigResolver.payloadRoot().resolve("NORDICS").toString()))
                .normalize();
    }

    public String systemPayloadFolder(String system) {
        String normalizedSystem = platformConfigResolver.normalize(system);
        return firstText(
                property("rabbit.payload.system." + normalizedSystem, ""),
                property("rabbit.payload.system.folder." + normalizedSystem, ""),
                normalizedSystem);
    }

    public String payloadFile(TestCase testCase) {
        String normalizedEnv = env(testCase);
        String normalizedSystem = system(testCase);
        String normalizedScenario = platformConfigResolver.normalize(testCase == null ? null : testCase.getScenario());
        return firstText(
                property("rabbit.payload." + normalizedEnv + "." + normalizedSystem + "." + normalizedScenario, ""),
                property("rabbit.payload." + normalizedSystem + "." + normalizedScenario, ""),
                property("rabbit.payload." + normalizedScenario, ""));
    }

    public String exchangeType(String env, String system) {
        return exchangeType(env, system, null);
    }

    public String exchangeType(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("rabbit.exchange.type." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.exchange.type." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.exchange.type." + normalizedService, ""),
                property("rabbit.exchange.type." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.exchange.type." + normalizedEnv, ""),
                property("rabbit.exchange.type." + normalizedSystem, ""),
                property("rabbit.exchange.type.default", ""),
                property("rabbit.exchange.type", "direct"));
    }

    public String exchange(String env, String system) {
        return exchange(env, system, null);
    }

    public String exchange(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("rabbit.exchange." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.exchange." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.exchange." + normalizedService, ""),
                property("rabbit.exchange." + normalizedEnv + "." + normalizedSystem + ".default", ""),
                property("rabbit.exchange." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.exchange." + normalizedEnv + ".default", ""),
                property("rabbit.exchange." + normalizedEnv, ""),
                property("rabbit.exchange." + normalizedSystem, ""),
                property("rabbit.exchange.default", ""),
                property("rabbit.exchange", ""),
                property("rabbitmq.exchange", "NORDICS.BOOKING.EXCHANGE"));
    }

    public String routingKey(String env, String system, String requestedRoutingKey) {
        return routingKey(env, system, null, requestedRoutingKey);
    }

    public String routingKey(String env, String system, String service, String requestedRoutingKey) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                requestedRoutingKey,
                property("rabbit.routing." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.routing." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.routing." + normalizedService, ""),
                property("rabbit.routing." + normalizedEnv + "." + normalizedSystem + ".default", ""),
                property("rabbit.routing." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.routing." + normalizedEnv + ".default", ""),
                property("rabbit.routing." + normalizedEnv, ""),
                property("rabbit.routing." + normalizedSystem, ""),
                property("rabbit.routing.default", ""),
                property("rabbit.routing", ""),
                property("rabbitmq.routing.key", "nordics.booking.update"));
    }

    public String queue(String env, String system) {
        return queue(env, system, null);
    }

    public String queue(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("rabbit.queue." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.queue." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.queue." + normalizedService, ""),
                property("rabbit.queue." + normalizedEnv + "." + normalizedSystem + ".default", ""),
                property("rabbit.queue." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.queue." + normalizedEnv + ".default", ""),
                property("rabbit.queue." + normalizedEnv, ""),
                property("rabbit.queue." + normalizedSystem, ""),
                property("rabbit.queue.default", ""),
                property("rabbit.queue", ""),
                property("rabbitmq.queue.receiver", "Nordics.Booking.Queue"));
    }

    public String messageType(String env, String system) {
        return messageType(env, system, null);
    }

    public String messageType(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("rabbit.message.type." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.message.type." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.message.type." + normalizedService, ""),
                property("rabbit.message.type." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.message.type." + normalizedEnv, ""),
                property("rabbit.message.type." + normalizedSystem, ""),
                property("rabbit.message.type.default", ""),
                property("rabbit.message.type", "DATAHUB_EVENT"));
    }

    public Map<String, String> messageHeaders(String env, String system, String service) {
        return parseMap(configuredMap("rabbit.message.headers", env, system, service));
    }

    public Map<String, String> messageProperties(String env, String system, String service) {
        return parseMap(configuredMap("rabbit.message.properties", env, system, service));
    }

    public String trackingId(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("rabbit.tracking.id." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.tracking.id." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.tracking.id." + normalizedService, ""),
                property("rabbit.tracking.id." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.tracking.id." + normalizedEnv, ""),
                property("rabbit.tracking.id." + normalizedSystem, ""),
                property("rabbit.nordics.tracking.id", ""),
                property("rabbit.tracking.id", ""));
    }

    public String processContextInstanceId(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        return firstText(
                property("rabbit.process-context.instance-id." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.process-context.instance-id." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.process-context.instance-id." + normalizedService, ""),
                property("rabbit.process-context.instance-id." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.process-context.instance-id." + normalizedEnv, ""),
                property("rabbit.process-context.instance-id." + normalizedSystem, ""),
                property("rabbit.process-context.instance-id", ""));
    }

    public boolean logScanEnabled(String env, String system, String service) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedSystem = platformConfigResolver.normalize(system);
        String normalizedService = platformConfigResolver.normalize(service);
        String value = firstText(
                property("rabbit.nordics.log.scan.enabled." + normalizedEnv + "." + normalizedSystem + "." + normalizedService, ""),
                property("rabbit.nordics.log.scan.enabled." + normalizedEnv + "." + normalizedService, ""),
                property("rabbit.nordics.log.scan.enabled." + normalizedService, ""),
                property("rabbit.nordics.log.scan.enabled." + normalizedEnv + "." + normalizedSystem, ""),
                property("rabbit.nordics.log.scan.enabled." + normalizedEnv + ".default", ""),
                property("rabbit.nordics.log.scan.enabled." + normalizedEnv, ""),
                property("rabbit.nordics.log.scan.enabled.default", ""),
                property("rabbit.nordics.log.scan.enabled", "false"));
        return Boolean.parseBoolean(value);
    }

    private String configuredMap(String prefix, String env, String system, String service) {
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
                property(prefix, ""));
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
            int equals = pair.indexOf('=');
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
