package com.hcl.ai.index;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ObservabilityIndexService {

    private static final Pattern BOOKING_PATTERN =
            Pattern.compile("\\b(?:BookingID|bookingId|booking-id)\\s*[:=]\\s*([A-Za-z0-9._:-]{3,})");
    private static final Pattern CORR_PATTERN =
            Pattern.compile("\\b(?:CorrID|CorrId|JMSCorrelationID)\\s*[:=]\\s*([A-Za-z0-9._:-]{8,})");
    private static final Pattern JOB_PATTERN =
            Pattern.compile("\\b(?:JobID|JobId)\\s*[:=]\\s*([A-Za-z0-9._:-]{2,})");
    private static final Pattern LEGACY_TIMESTAMP =
            Pattern.compile("^(\\d{4}\\s+[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}:\\d{1,3})");
    private static final Pattern ISO_TIMESTAMP =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?)");

    private final String localLogDir;

    public ObservabilityIndexService(@Value("${local.log.dir}") String localLogDir) {
        this.localLogDir = localLogDir == null ? "" : localLogDir.trim();
    }

    public ObservabilityIndexResult index(String bookingId) {
        ObservabilityIndexResult result = new ObservabilityIndexResult();
        result.setBookingId(value(bookingId));
        File scopeDir = hasText(bookingId) ? new File(localLogDir, safeScope(bookingId)) : new File(localLogDir);
        result.setLocalPath(scopeDir.getPath());
        if (!scopeDir.isDirectory()) {
            result.getWarnings().add("Local evidence folder not found: " + scopeDir.getPath());
            return result;
        }

        File[] files = scopeDir.listFiles(file -> file.isFile()
                && file.getName().contains(".log")
                && !file.getName().startsWith("processed_files_"));
        if (files == null || files.length == 0) {
            result.getWarnings().add("No local log files found under: " + scopeDir.getPath());
            return result;
        }
        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        result.setFilesScanned(files.length);
        for (File file : files) {
            indexFile(file, bookingId, result);
        }
        result.setLinesIndexed(result.getEntries().size());
        return result;
    }

    public Optional<Path> evidenceFile(String bookingId, String fileName) {
        if (!hasText(fileName) || fileName.contains("/") || fileName.contains("\\")) {
            return Optional.empty();
        }
        File scopeDir = hasText(bookingId) ? new File(localLogDir, safeScope(bookingId)) : new File(localLogDir);
        Path scopePath = scopeDir.toPath().toAbsolutePath().normalize();
        Path filePath = scopePath.resolve(fileName).toAbsolutePath().normalize();
        if (!filePath.startsWith(scopePath) || !filePath.toFile().isFile()) {
            return Optional.empty();
        }
        return Optional.of(filePath);
    }

    private void indexFile(File file, String bookingId, ObservabilityIndexResult result) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!isRelevant(line, bookingId)) {
                    continue;
                }
                IndexedLogLine indexed = new IndexedLogLine();
                indexed.setFile(file.getName());
                indexed.setLineNumber(lineNumber);
                indexed.setTimestamp(timestamp(line));
                indexed.setBookingId(firstMatch(BOOKING_PATTERN, line, bookingId));
                indexed.setCorrId(firstMatch(CORR_PATTERN, line, null));
                indexed.setJobId(firstMatch(JOB_PATTERN, line, null));
                indexed.setLine(line);
                result.getEntries().add(indexed);
            }
        } catch (Exception e) {
            result.getWarnings().add("Unable to index " + file.getName() + ": " + value(e.getMessage()));
        }
    }

    private boolean isRelevant(String line, String bookingId) {
        if (line == null) {
            return false;
        }
        return (hasText(bookingId) && line.contains(bookingId))
                || BOOKING_PATTERN.matcher(line).find()
                || CORR_PATTERN.matcher(line).find()
                || JOB_PATTERN.matcher(line).find();
    }

    private String firstMatch(Pattern pattern, String line, String fallback) {
        Matcher matcher = pattern.matcher(line == null ? "" : line);
        if (matcher.find()) {
            return stripTail(matcher.group(1));
        }
        return fallback;
    }

    private String timestamp(String line) {
        String safeLine = line == null ? "" : line;
        Matcher iso = ISO_TIMESTAMP.matcher(safeLine);
        if (iso.find()) {
            return iso.group(1);
        }
        Matcher legacy = LEGACY_TIMESTAMP.matcher(safeLine);
        return legacy.find() ? legacy.group(1) : "NA";
    }

    private String stripTail(String value) {
        return value == null ? null : value.trim().replaceAll("[,;\\])}]+$", "");
    }

    private String safeScope(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
