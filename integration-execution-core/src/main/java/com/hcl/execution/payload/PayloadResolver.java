package com.hcl.execution.payload;

import com.hcl.execution.config.PlatformConfigResolver;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class PayloadResolver {

    private static final Set<String> DEFAULT_EXTENSIONS = new HashSet<>(Arrays.asList("xml", "json", "txt"));

    private final PlatformConfigResolver configResolver;

    public PayloadResolver(PlatformConfigResolver configResolver) {
        this.configResolver = configResolver;
    }

    public PayloadResolution resolve(
            String requestedPayload,
            String system,
            String bookingId,
            Set<String> allowedExtensions) {
        return resolve(requestedPayload, system, bookingId, allowedExtensions, null, null);
    }

    public PayloadResolution resolve(
            String requestedPayload,
            String folderKey,
            String bookingId,
            Set<String> allowedExtensions,
            Path payloadRoot,
            String payloadFolder) {
        Set<String> extensions = allowedExtensions == null || allowedExtensions.isEmpty()
                ? DEFAULT_EXTENSIONS
                : normalizeExtensions(allowedExtensions);

        if (!hasText(requestedPayload)) {
            Path latest = latestSystemPayload(folderKey, extensions, payloadRoot, payloadFolder);
            return latest == null ? null : read(latest, folderKey, bookingId);
        }

        String trimmed = requestedPayload.trim();
        Path explicitPath = resolvePath(trimmed, folderKey, extensions, payloadRoot, payloadFolder);
        if (explicitPath != null) {
            return read(explicitPath, folderKey, bookingId);
        }
        return null;
    }

    public Path systemFolder(String system) {
        return configResolver.payloadRoot()
                .resolve(configResolver.systemPayloadFolder(system))
                .normalize();
    }

    public Path payloadFolder(String folderKey, Path payloadRoot, String payloadFolder) {
        Path root = payloadRoot == null ? configResolver.payloadRoot() : payloadRoot;
        String folder = hasText(payloadFolder) ? payloadFolder : configResolver.systemPayloadFolder(folderKey);
        return root.resolve(folder).normalize();
    }

    private Path resolvePath(
            String requestedPayload,
            String folderKey,
            Set<String> extensions,
            Path payloadRoot,
            String payloadFolder) {
        Path path = Paths.get(requestedPayload);
        if (path.isAbsolute() && Files.isRegularFile(path) && supported(path, extensions)) {
            return path.normalize();
        }

        Path fromPayloadFolder = existingSupportedPath(payloadFolder(folderKey, payloadRoot, payloadFolder).resolve(path), extensions);
        if (fromPayloadFolder != null) {
            return fromPayloadFolder;
        }

        Path root = payloadRoot == null ? configResolver.payloadRoot() : payloadRoot;
        Path fromRoot = existingSupportedPath(root.resolve(path), extensions);
        if (fromRoot != null) {
            return fromRoot;
        }

        Path fromCwd = existingSupportedPath(Paths.get("").toAbsolutePath().normalize().resolve(path), extensions);
        if (fromCwd != null) {
            return fromCwd;
        }

        Path regressionRoot = findAncestorPath("regression-payloads");
        if (regressionRoot != null) {
            Path fromRegressionPayloads = existingSupportedPath(regressionRoot.resolve(path), extensions);
            if (fromRegressionPayloads != null) {
                return fromRegressionPayloads;
            }
        }
        return null;
    }

    private Path existingSupportedPath(Path candidate, Set<String> extensions) {
        if (candidate == null) {
            return null;
        }
        Path normalized = candidate.normalize();
        if (Files.isRegularFile(normalized) && supported(normalized, extensions)) {
            return normalized;
        }
        if (hasExtension(normalized)) {
            return null;
        }
        for (String extension : extensions) {
            if (!hasText(extension)) {
                continue;
            }
            Path withExtension = Paths.get(normalized.toString() + "." + extension).normalize();
            if (Files.isRegularFile(withExtension) && supported(withExtension, extensions)) {
                return withExtension;
            }
        }
        return null;
    }

    private boolean hasExtension(Path path) {
        return hasText(extension(path));
    }

    private Path findAncestorPath(String childName) {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(childName).normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    private Path latestSystemPayload(String folderKey, Set<String> extensions, Path payloadRoot, String payloadFolder) {
        Path folder = payloadFolder(folderKey, payloadRoot, payloadFolder);
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> supported(path, extensions))
                    .max(this::compareLastModified)
                    .orElse(null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to scan payload folder: " + folder, e);
        }
    }

    private PayloadResolution read(Path path, String system, String bookingId) {
        try {
            String content = readPayloadText(path);
            return new PayloadResolution(interpolate(content, bookingId), path.toString(), system, path);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload file is not readable: " + path, e);
        }
    }

    private String readPayloadText(Path path) throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException ignored) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private boolean supported(Path path, Set<String> extensions) {
        String extension = extension(path);
        return extensions.contains(extension);
    }

    private String extension(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Set<String> normalizeExtensions(Set<String> allowedExtensions) {
        Set<String> normalized = new HashSet<>();
        for (String extension : allowedExtensions) {
            if (extension != null && extension.trim().isEmpty()) {
                normalized.add("");
            } else if (hasText(extension)) {
                normalized.add(extension.trim().replace(".", "").toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private int compareLastModified(Path left, Path right) {
        try {
            int modified = Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
            return modified != 0 ? modified : left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
        } catch (Exception ignored) {
            return left.toString().compareToIgnoreCase(right.toString());
        }
    }

    private String interpolate(String payload, String bookingId) {
        if (payload == null) {
            return "";
        }
        return payload
                .replace("${bookingId}", value(bookingId))
                .replace("${jobId}", "")
                .replace("${corrId}", "")
                .replace("${timestamp}", OffsetDateTime.now().toString());
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
