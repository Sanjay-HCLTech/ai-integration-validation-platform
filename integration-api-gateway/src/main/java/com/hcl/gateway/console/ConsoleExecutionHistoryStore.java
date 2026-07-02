package com.hcl.gateway.console;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ConsoleExecutionHistoryStore {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String STOP_REQUESTED = "STOP_REQUESTED";
    public static final String FAILED = "FAILED";

    private final ConcurrentMap<String, ConsoleExecutionRecord> records = new ConcurrentHashMap<>();
    private final int recentLimit;

    public ConsoleExecutionHistoryStore(@Value("${console.execution.history.limit}") int recentLimit) {
        this.recentLimit = Math.max(1, recentLimit);
    }

    public ConsoleExecutionRecord create(ConsoleExecutionRequest request) {
        ConsoleExecutionRequest safeRequest = request == null ? new ConsoleExecutionRequest() : request;
        String executionId = hasText(safeRequest.getExecutionId()) ? safeRequest.getExecutionId().trim() : UUID.randomUUID().toString();
        safeRequest.setExecutionId(executionId);

        ConsoleExecutionRecord record = new ConsoleExecutionRecord();
        record.setExecutionId(executionId);
        record.setCreatedAt(Instant.now());
        record.setStatus(RUNNING);
        record.setRequest(safeRequest);
        records.put(executionId, record);
        prune();
        return record;
    }

    public Optional<ConsoleExecutionRecord> get(String executionId) {
        return Optional.ofNullable(records.get(executionId));
    }

    public boolean exists(String executionId) {
        return records.containsKey(executionId);
    }

    public List<ConsoleExecutionHistoryItem> recent() {
        List<ConsoleExecutionRecord> sortedRecords = new ArrayList<>(records.values());
        sortedRecords.sort(Comparator.comparing(ConsoleExecutionRecord::getCreatedAt).reversed());

        List<ConsoleExecutionHistoryItem> items = new ArrayList<>();
        int limit = Math.min(sortedRecords.size(), recentLimit);
        for (int index = 0; index < limit; index++) {
            items.add(item(sortedRecords.get(index)));
        }
        return items;
    }

    public boolean stop(String executionId) {
        ConsoleExecutionRecord record = records.get(executionId);
        if (record == null) {
            return false;
        }
        if (STOP_REQUESTED.equals(record.getStatus())) {
            return true;
        }
        if (!RUNNING.equals(record.getStatus())) {
            return false;
        }
        record.setStatus(STOP_REQUESTED);
        return true;
    }

    public boolean isStopRequested(String executionId) {
        ConsoleExecutionRecord record = records.get(executionId);
        return record != null && STOP_REQUESTED.equals(record.getStatus());
    }

    public void complete(String executionId, ConsoleExecutionResponse response) {
        ConsoleExecutionRecord record = records.get(executionId);
        if (record == null) {
            return;
        }
        record.setResponse(response);
        if (!STOP_REQUESTED.equals(record.getStatus())) {
            record.setStatus(COMPLETED);
        }
        response.setExecutionStatus(record.getStatus());
    }

    public void fail(String executionId, ConsoleExecutionResponse response) {
        ConsoleExecutionRecord record = records.get(executionId);
        if (record == null) {
            return;
        }
        record.setResponse(response);
        record.setStatus(FAILED);
        if (response != null) {
            response.setExecutionStatus(FAILED);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void prune() {
        List<ConsoleExecutionRecord> sortedRecords = new ArrayList<>(records.values());
        sortedRecords.sort(Comparator.comparing(ConsoleExecutionRecord::getCreatedAt).reversed());
        for (int index = recentLimit; index < sortedRecords.size(); index++) {
            records.remove(sortedRecords.get(index).getExecutionId());
        }
    }

    private ConsoleExecutionHistoryItem item(ConsoleExecutionRecord record) {
        ConsoleExecutionHistoryItem item = new ConsoleExecutionHistoryItem();
        item.setExecutionId(record.getExecutionId());
        item.setCreatedAt(record.getCreatedAt());
        item.setStatus(record.getStatus());

        ConsoleExecutionSummary summary = record.getResponse() == null ? null : record.getResponse().getSummary();
        if (summary != null) {
            item.setTotal(summary.getTotal());
            item.setPass(summary.getPass());
            item.setFail(summary.getFail());
            item.setDurationMs(summary.getDurationMs());
            item.setSuccessRate(summary.getSuccessRate());
        }
        return item;
    }
}
