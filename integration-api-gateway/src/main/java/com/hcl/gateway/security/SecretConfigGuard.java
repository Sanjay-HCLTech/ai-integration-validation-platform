package com.hcl.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class SecretConfigGuard implements ApplicationRunner {

    private final boolean enabled;

    public SecretConfigGuard(@Value("${security.secrets.config-guard.enabled}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            return;
        }
        ClassPathResource resource = new ClassPathResource("application.properties");
        if (!resource.exists()) {
            return;
        }
        List<String> violations = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                inspect(lineNumber, line, violations);
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Secret configuration guard failed. Use env/vault placeholders only: "
                    + String.join("; ", violations));
        }
    }

    private void inspect(int lineNumber, String line, List<String> violations) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
            return;
        }
        String key = trimmed.substring(0, trimmed.indexOf('=')).trim().toLowerCase();
        String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
        if (!isSecretKey(key)) {
            return;
        }
        if (value.isEmpty()) {
            return;
        }
        if (!value.startsWith("${") || !value.endsWith("}")) {
            violations.add("line " + lineNumber + " " + key + " must use ${ENV_VAR:} style placeholder");
            return;
        }
        int colon = value.indexOf(':');
        String fallback = colon < 0 ? "" : value.substring(colon + 1, value.length() - 1).trim();
        if (!fallback.isEmpty() && !fallback.startsWith("${")) {
            violations.add("line " + lineNumber + " " + key + " has a literal fallback");
        }
    }

    private boolean isSecretKey(String key) {
        return key.contains("api.key")
                || key.contains("api-key")
                || key.endsWith(".password")
                || key.endsWith(".secret")
                || key.contains(".secret.")
                || key.endsWith(".token");
    }
}
