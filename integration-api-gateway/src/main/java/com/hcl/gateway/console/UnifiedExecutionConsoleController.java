package com.hcl.gateway.console;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/execute")
public class UnifiedExecutionConsoleController {

    private final UnifiedExecutionConsoleService consoleService;

    public UnifiedExecutionConsoleController(UnifiedExecutionConsoleService consoleService) {
        this.consoleService = consoleService;
    }

    @PostMapping("/executeAll")
    public ConsoleExecutionResponse executeAll(@RequestBody(required = false) ConsoleExecutionRequest request) {
        return consoleService.executeAll(request == null ? new ConsoleExecutionRequest() : request);
    }

    @GetMapping("/history")
    public List<ConsoleExecutionHistoryItem> history() {
        return consoleService.history();
    }

    @GetMapping("/{executionId}")
    public ConsoleExecutionResponse execution(@PathVariable String executionId) {
        return consoleService.execution(executionId);
    }

    @PostMapping("/{executionId}/stop")
    public ResponseEntity<String> stop(@PathVariable String executionId) {
        boolean stopped = consoleService.stop(executionId);
        if (stopped) {
            return ResponseEntity.ok("STOP_REQUESTED");
        }
        return consoleService.exists(executionId)
                ? ResponseEntity.status(409).body("NOT_RUNNING")
                : ResponseEntity.notFound().build();
    }

    @GetMapping(value = "/{executionId}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> logs(@PathVariable String executionId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ivp-execution-" + executionId + "-logs.txt")
                .body(consoleService.logs(executionId));
    }

    @GetMapping(value = "/{executionId}/report", produces = "text/csv")
    public ResponseEntity<String> report(@PathVariable String executionId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ivp-execution-" + executionId + "-report.csv")
                .body(consoleService.report(executionId));
    }
}
