package com.hcl.execution.jms;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class JmsConsumerService implements InitializingBean, DisposableBean {

    private final JmsQueueService queueService;
    private final JmsProcessingService processingService;
    private final boolean consumerEnabled;
    private final boolean unifiedTraceReportEnabled;
    private final long retryDelayMs;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    public JmsConsumerService(
            JmsQueueService queueService,
            JmsProcessingService processingService,
            @Value("${jms.simulation.consumer.enabled:true}") boolean consumerEnabled,
            @Value("${unified.trace.report.enabled:false}") boolean unifiedTraceReportEnabled,
            @Value("${jms.simulation.retry.delay.ms:2000}") long retryDelayMs) {
        this.queueService = queueService;
        this.processingService = processingService;
        this.consumerEnabled = consumerEnabled;
        this.unifiedTraceReportEnabled = unifiedTraceReportEnabled;
        this.retryDelayMs = Math.max(0, retryDelayMs);
    }

    @Override
    public void afterPropertiesSet() {
        if (!consumerEnabled) {
            return;
        }

        running = true;
        executorService.submit(this::consume);
    }

    private void consume() {
        while (running) {
            try {
                JmsMessage message = queueService.receive();
                processWithRetry(message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private void processWithRetry(JmsMessage message) {
        long startNanos = System.nanoTime();
        int attempts = Math.max(1, Math.min(message.getRetryCount(), 3));
        JmsProcessingResult lastResult = null;

        System.out.println("==================== EXECUTION START ====================");
        System.out.println("TestCase=TC_JMS_SIMULATION | BookingID=" + message.getBookingId());
        System.out.println("Orchestrator: Starting execution...");
        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("[API]");
            System.out.println("Endpoint=/execute/jms");
            System.out.println("Status=200");
        }

        for (int attempt = 1; attempt <= attempts; attempt++) {
            lastResult = processingService.processAsync(message);
            if ("SUCCESS".equalsIgnoreCase(lastResult.getStatus())) {
                if (!unifiedTraceReportEnabled) {
                    System.out.println("Orchestrator: Execution completed");
                    printSummary(startNanos, lastResult);
                } else {
                    System.out.println("==================== EXECUTION END ======================");
                }
                return;
            }

            if (attempt < attempts) {
                waitBeforeRetry();
            }
        }

        if (!unifiedTraceReportEnabled) {
            System.out.println("Orchestrator: Execution completed");
            printSummary(startNanos, lastResult);
        } else {
            System.out.println("==================== EXECUTION END ======================");
        }
    }

    private void waitBeforeRetry() {
        if (retryDelayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printSummary(long startNanos, JmsProcessingResult result) {
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
        String status = result == null ? "UNKNOWN" : result.getStatus();

        System.out.println();
        System.out.println("-------------------- SUMMARY ---------------------------");
        System.out.println("[SUMMARY]");
        System.out.println("TotalTimeMs=" + totalMs + " Status=" + status);
        System.out.println("==================== EXECUTION END ======================");
    }

    @Override
    public void destroy() {
        running = false;
        executorService.shutdownNow();
    }
}
