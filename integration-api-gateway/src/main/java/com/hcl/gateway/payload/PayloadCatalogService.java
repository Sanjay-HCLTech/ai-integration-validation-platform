package com.hcl.gateway.payload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class PayloadCatalogService {

    private final String payloadBaseDir;
    private final String defaultPayloadFile;
    private final String manifestFile;

    public PayloadCatalogService(
            @Value("${payload.base.dir}") String payloadBaseDir,
            @Value("${payload.default.file}") String defaultPayloadFile,
            @Value("${payload.manifest.file:manifest.csv}") String manifestFile) {
        this.payloadBaseDir = payloadBaseDir;
        this.defaultPayloadFile = defaultPayloadFile;
        this.manifestFile = manifestFile;
    }

    public PayloadResolution resolve(
            String category,
            String flow,
            String scenario,
            String mode,
            String payloadFile,
            String bookingId,
            String requestPayload) {
        if (!isBlank(requestPayload)) {
            logPayload(category, flow, scenario, "REQUEST_BODY", "LOADED", true);
            return new PayloadResolution(interpolate(requestPayload, bookingId), "REQUEST_BODY", true, "REQUEST_BODY");
        }

        if (!isBlank(payloadFile)) {
            String payload = loadPayloadFile(payloadFile, bookingId);
            logPayload(category, flow, scenario, payloadFile, "LOADED", true);
            return new PayloadResolution(payload, payloadFile, true, "PAYLOAD_PARAM");
        }

        String catalogFile = resolveCatalogFile(category, flow, scenario, mode);
        if (!isBlank(catalogFile)) {
            String payload = loadPayloadFile(catalogFile, bookingId);
            logPayload(category, flow, scenario, catalogFile, "LOADED", false);
            return new PayloadResolution(payload, catalogFile, false, "CATALOG");
        }
        if (hasCatalogRequest(category, flow, scenario)) {
            throw new IllegalArgumentException("No payload catalog entry found for Category=" + category
                    + " Flow=" + flow
                    + " Scenario=" + scenario
                    + " Mode=" + value(mode));
        }

        String payload = loadPayloadFile(defaultPayloadFile, bookingId);
        logPayload(category, flow, scenario, defaultPayloadFile, "LOADED", false);
        return new PayloadResolution(payload, defaultPayloadFile, false, "DEFAULT");
    }

    private String resolveCatalogFile(String category, String flow, String scenario, String mode) {
        if (isBlank(category) || isBlank(flow) || isBlank(scenario)) {
            return "";
        }

        List<PayloadCatalogEntry> entries = loadManifest();
        String normalizedCategory = normalize(category);
        String normalizedFlow = normalize(flow);
        String normalizedScenario = normalize(scenario);
        String normalizedMode = isBlank(mode) ? "" : normalize(mode);
        PayloadCatalogEntry defaultMatch = null;

        for (PayloadCatalogEntry entry : entries) {
            if (!normalizedCategory.equals(entry.category)
                    || !normalizedFlow.equals(entry.flow)
                    || !normalizedScenario.equals(entry.scenario)) {
                continue;
            }
            if (!isBlank(normalizedMode) && !normalizedMode.equals(entry.mode)) {
                continue;
            }
            if (entry.defaultEntry) {
                defaultMatch = entry;
            }
            if (isBlank(normalizedMode) || normalizedMode.equals(entry.mode)) {
                return entry.file;
            }
        }

        return defaultMatch == null ? "" : defaultMatch.file;
    }

    private List<PayloadCatalogEntry> loadManifest() {
        String raw = loadRawFile(manifestFile);
        List<PayloadCatalogEntry> entries = new ArrayList<>();
        String[] lines = raw.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || i == 0 && line.toLowerCase(Locale.ROOT).startsWith("category,")) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 7) {
                throw new IllegalArgumentException("Invalid payload manifest row " + (i + 1) + ": " + line);
            }
            if (isBlank(parts[4]) || isBlank(parts[5])) {
                throw new IllegalArgumentException("Invalid payload manifest row " + (i + 1)
                        + ": file and contentType are required");
            }
            entries.add(new PayloadCatalogEntry(
                    normalize(parts[0]),
                    normalize(parts[1]),
                    normalize(parts[2]),
                    normalize(parts[3]),
                    parts[4].trim(),
                    "Y".equalsIgnoreCase(parts[6].trim())));
        }
        return entries;
    }

    private String loadPayloadFile(String payloadFile, String bookingId) {
        validateExtension(payloadFile);
        return interpolate(loadRawFile(payloadFile), bookingId);
    }

    private String loadRawFile(String file) {
        if (isBlank(file)) {
            throw new IllegalArgumentException("Payload file is required");
        }

        if (payloadBaseDir != null && payloadBaseDir.trim().startsWith("classpath:")) {
            String classpathBase = payloadBaseDir.trim().replace("classpath:", "");
            if (!classpathBase.endsWith("/")) {
                classpathBase = classpathBase + "/";
            }
            String classpathFile = classpathBase + file;
            validateRelativePath(Paths.get(file));
            try (InputStream inputStream = new ClassPathResource(classpathFile).getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalArgumentException("Payload file not found on classpath: " + classpathFile, e);
            }
        }

        Path basePath = resolveFilesystemBasePath();
        Path requestedPath = basePath.resolve(file).normalize();
        if (!requestedPath.startsWith(basePath)) {
            throw new IllegalArgumentException("Payload path escapes configured payload.base.dir: " + file);
        }

        try {
            return Files.readString(requestedPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload file not found/readable: " + requestedPath, e);
        }
    }

    private Path resolveFilesystemBasePath() {
        Path configuredBase = Paths.get(isBlank(payloadBaseDir) ? "regression-payloads" : payloadBaseDir.trim());
        if (configuredBase.isAbsolute()) {
            return configuredBase.normalize();
        }

        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path cwdBase = cwd.resolve(configuredBase).normalize();
        if (Files.exists(cwdBase) && Files.isDirectory(cwdBase)) {
            return cwdBase;
        }

        Path parent = cwd.getParent();
        if (parent != null) {
            Path parentBase = parent.resolve(configuredBase).normalize();
            if (Files.exists(parentBase) && Files.isDirectory(parentBase)) {
                return parentBase;
            }
        }

        return cwdBase;
    }

    private void validateRelativePath(Path path) {
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IllegalArgumentException("Payload path escapes configured payload.base.dir: " + path);
        }
    }

    private void validateExtension(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        String name = Paths.get(file).getFileName() == null ? lower : Paths.get(file).getFileName().toString();
        if (!name.contains(".")) {
            return;
        }
        if (!(lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".txt"))) {
            throw new IllegalArgumentException("Unsupported payload file extension: " + file);
        }
    }

    private String interpolate(String payload, String bookingId) {
        if (payload == null) {
            return "";
        }
        String now = OffsetDateTime.now().toString();
        return payload
                .replace("${bookingId}", value(bookingId))
                .replace("${jobId}", "")
                .replace("${corrId}", "")
                .replace("${timestamp}", now);
    }

    private void logPayload(String category, String flow, String scenario, String file, String status, boolean override) {
        System.out.println("[PAYLOAD]");
        System.out.println("Category=" + value(category)
                + " Flow=" + value(flow)
                + " Scenario=" + value(scenario)
                + " File=" + value(file)
                + " Status=" + status
                + " Override=" + (override ? "Y" : "N"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasCatalogRequest(String category, String flow, String scenario) {
        return !isBlank(category) && !isBlank(flow) && !isBlank(scenario);
    }

    private String value(String value) {
        return isBlank(value) ? "NA" : value.trim();
    }

    private static class PayloadCatalogEntry {
        private final String category;
        private final String flow;
        private final String scenario;
        private final String mode;
        private final String file;
        private final boolean defaultEntry;

        private PayloadCatalogEntry(
                String category,
                String flow,
                String scenario,
                String mode,
                String file,
                boolean defaultEntry) {
            this.category = category;
            this.flow = flow;
            this.scenario = scenario;
            this.mode = mode;
            this.file = file;
            this.defaultEntry = defaultEntry;
        }
    }
}
