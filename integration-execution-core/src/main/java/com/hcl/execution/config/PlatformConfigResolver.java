package com.hcl.execution.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@Service
public class PlatformConfigResolver {

    private final Environment environment;

    public PlatformConfigResolver(Environment environment) {
        this.environment = environment;
    }

    public String env(String requestedEnv) {
        return normalize(firstText(requestedEnv, property("platform.env", "")));
    }

    public String system(String requestedSystem, String flow, String fallbackSystem) {
        return normalize(firstText(requestedSystem, systemFromFlow(flow), fallbackSystem, property("payload.system.default", "")));
    }

    public Path payloadRoot() {
        return Paths.get(property("payload.root", "")).normalize();
    }

    public String systemPayloadFolder(String system) {
        String normalizedSystem = normalize(system);
        return firstText(
                property("payload.system." + normalizedSystem, ""),
                property("payload.system.folder." + normalizedSystem, ""),
                normalizedSystem);
    }

    public String property(String key, String fallback) {
        String value = environment.getProperty(configKey(key));
        return hasText(value) ? value.trim() : fallback;
    }

    private String configKey(String key) {
        return key == null ? null : key.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    public String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    public boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String systemFromFlow(String flow) {
        if (!hasText(flow)) {
            return "";
        }
        String normalized = normalize(flow);
        int separator = normalized.indexOf('_');
        return separator > 0 ? normalized.substring(0, separator) : normalized;
    }
}
