package com.hcl.observability.log;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;

@Service
public class LogCleanupService {

    private final Path localLogDir;
    private final int retentionDays;

    public LogCleanupService(
            @Value("${local.log.dir}") String localLogDir,
            @Value("${local.log.retention.days:7}") int retentionDays) {
        this.localLogDir = Path.of(localLogDir).toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
    }

    public void cleanupExpiredLogs() {
        if (retentionDays <= 0 || !Files.isDirectory(localLogDir)) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));

        try {
            Files.list(localLogDir)
                    .filter(Files::isDirectory)
                    .forEach(path -> deleteIfExpired(path, cutoff));
        } catch (IOException e) {
            return;
        }
    }

    private void deleteIfExpired(Path bookingDir, Instant cutoff) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(bookingDir, BasicFileAttributes.class);
            if (attrs.lastModifiedTime().toInstant().isAfter(cutoff)) {
                return;
            }

            deleteRecursively(bookingDir);
        } catch (IOException e) {
            return;
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!normalizedRoot.startsWith(localLogDir)) {
            throw new IOException("Refusing to delete path outside local log directory: " + normalizedRoot);
        }

        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
