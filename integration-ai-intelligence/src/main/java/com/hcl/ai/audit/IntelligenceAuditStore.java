package com.hcl.ai.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.ai.intent.IntelligenceIntent;
import com.hcl.ai.intent.IntelligenceIntentRequest;
import com.hcl.ai.plan.ExecutionPlan;
import com.hcl.ai.report.IntelligenceReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class IntelligenceAuditStore {

    private final ConcurrentMap<String, IntelligenceAuditRecord> records = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path auditDir;

    public IntelligenceAuditStore(
            ObjectMapper objectMapper,
            @Value("${intelligence.audit.dir}") String auditDir) {
        this.objectMapper = objectMapper;
        this.auditDir = Paths.get(auditDir).toAbsolutePath().normalize();
    }

    public void save(
            String executionId,
            IntelligenceIntentRequest request,
            IntelligenceIntent intent,
            ExecutionPlan plan,
            Object executionResult,
            IntelligenceReport report) {
        if (!hasText(executionId)) {
            return;
        }
        IntelligenceAuditRecord record = new IntelligenceAuditRecord();
        record.setExecutionId(executionId);
        record.setCreatedAt(Instant.now());
        record.setPrompt(request == null ? null : request.getPrompt());
        record.setRequest(request);
        record.setIntent(intent);
        record.setPlan(plan);
        record.setExecutionResult(executionResult);
        record.setResult(report);
        records.put(executionId, record);
        persist(record);
    }

    public Optional<IntelligenceAuditRecord> get(String executionId) {
        if (!hasText(executionId)) {
            return Optional.empty();
        }
        IntelligenceAuditRecord cached = records.get(executionId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return read(executionId);
    }

    public List<IntelligenceAuditSummary> recent(int limit) {
        List<IntelligenceAuditRecord> values = new ArrayList<>(records.values());
        values.addAll(readPersisted());
        values.sort(Comparator.comparing(IntelligenceAuditRecord::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        List<IntelligenceAuditSummary> summaries = new ArrayList<>();
        int max = Math.max(1, limit);
        for (IntelligenceAuditRecord record : values) {
            if (record == null || !hasText(record.getExecutionId())) {
                continue;
            }
            if (containsExecutionId(summaries, record.getExecutionId())) {
                continue;
            }
            summaries.add(summary(record));
            if (summaries.size() >= max) {
                break;
            }
        }
        return summaries;
    }

    public Path auditDir() {
        return auditDir;
    }

    private void persist(IntelligenceAuditRecord record) {
        try {
            Files.createDirectories(auditDir);
            Path target = auditFile(record.getExecutionId());
            Path temp = auditDir.resolve(target.getFileName().toString() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), record);
            move(temp, target);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist intelligence audit for executionId="
                    + record.getExecutionId(), e);
        }
    }

    private Optional<IntelligenceAuditRecord> read(String executionId) {
        Path file = auditFile(executionId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            IntelligenceAuditRecord record = objectMapper.readValue(file.toFile(), IntelligenceAuditRecord.class);
            if (record != null && hasText(record.getExecutionId())) {
                records.put(record.getExecutionId(), record);
            }
            return Optional.ofNullable(record);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read intelligence audit for executionId=" + executionId, e);
        }
    }

    private List<IntelligenceAuditRecord> readPersisted() {
        List<IntelligenceAuditRecord> values = new ArrayList<>();
        if (!Files.isDirectory(auditDir)) {
            return values;
        }
        try (Stream<Path> files = Files.list(auditDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> readPath(path).ifPresent(values::add));
            return values;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list intelligence audit records", e);
        }
    }

    private Optional<IntelligenceAuditRecord> readPath(Path file) {
        try {
            IntelligenceAuditRecord record = objectMapper.readValue(file.toFile(), IntelligenceAuditRecord.class);
            if (record != null && hasText(record.getExecutionId())) {
                records.put(record.getExecutionId(), record);
            }
            return Optional.ofNullable(record);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private IntelligenceAuditSummary summary(IntelligenceAuditRecord record) {
        IntelligenceAuditSummary summary = new IntelligenceAuditSummary();
        summary.setExecutionId(record.getExecutionId());
        summary.setCreatedAt(record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
        summary.setPrompt(record.getPrompt());
        if (record.getResult() != null && record.getResult().getExecutionSummary() != null) {
            summary.setStatus(record.getResult().getExecutionSummary().getStatus());
            summary.setFlow(record.getResult().getExecutionSummary().getFlow());
            summary.setTriggerMode(record.getResult().getExecutionSummary().getTriggerMode());
            summary.setBookingId(record.getResult().getExecutionSummary().getBookingId());
        }
        if (record.getPlan() != null) {
            summary.setTemplateId(record.getPlan().getTemplateId());
            summary.setTemplateName(record.getPlan().getTemplateName());
        }
        return summary;
    }

    private boolean containsExecutionId(List<IntelligenceAuditSummary> summaries, String executionId) {
        for (IntelligenceAuditSummary summary : summaries) {
            if (summary != null && executionId.equals(summary.getExecutionId())) {
                return true;
            }
        }
        return false;
    }

    private void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path auditFile(String executionId) {
        return auditDir.resolve(sanitize(executionId) + ".json");
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
