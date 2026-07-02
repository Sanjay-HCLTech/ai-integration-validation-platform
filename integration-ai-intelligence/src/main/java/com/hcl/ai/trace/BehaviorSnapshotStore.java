package com.hcl.ai.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class BehaviorSnapshotStore {

    private final ObjectMapper objectMapper;
    private final Path baselineDir;
    private final ConcurrentMap<String, BehaviorSnapshot> cache = new ConcurrentHashMap<>();

    public BehaviorSnapshotStore(
            ObjectMapper objectMapper,
            @Value("${intelligence.drift.baseline.dir}") String baselineDir) {
        this.objectMapper = objectMapper;
        this.baselineDir = Paths.get(baselineDir).toAbsolutePath().normalize();
    }

    public Optional<BehaviorSnapshot> get(String baselineId) {
        if (!hasText(baselineId)) {
            return Optional.empty();
        }
        BehaviorSnapshot cached = cache.get(baselineId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = file(baselineId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            BehaviorSnapshot snapshot = objectMapper.readValue(file.toFile(), BehaviorSnapshot.class);
            if (snapshot != null && hasText(snapshot.getBaselineId())) {
                cache.put(snapshot.getBaselineId(), snapshot);
            }
            return Optional.ofNullable(snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read behavior baseline " + baselineId, e);
        }
    }

    public void save(BehaviorSnapshot snapshot) {
        if (snapshot == null || !hasText(snapshot.getBaselineId())) {
            return;
        }
        try {
            Files.createDirectories(baselineDir);
            Path target = file(snapshot.getBaselineId());
            Path temp = baselineDir.resolve(target.getFileName().toString() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), snapshot);
            move(temp, target);
            cache.put(snapshot.getBaselineId(), snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save behavior baseline " + snapshot.getBaselineId(), e);
        }
    }

    public Path baselineDir() {
        return baselineDir;
    }

    private Path file(String baselineId) {
        return baselineDir.resolve(sanitize(baselineId) + ".json");
    }

    private void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
