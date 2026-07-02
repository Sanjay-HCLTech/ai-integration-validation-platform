package com.hcl.gateway.intelligence;

import com.hcl.ai.report.IntelligenceReport;
import com.hcl.ai.audit.IntelligenceAuditRecord;
import com.hcl.ai.audit.IntelligenceAuditSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/intelligence")
public class IntelligenceController {

    private final IntelligenceExecutionService intelligenceExecutionService;
    private final BusinessReportExportService businessReportExportService;
    private final boolean enabled;

    public IntelligenceController(
            IntelligenceExecutionService intelligenceExecutionService,
            BusinessReportExportService businessReportExportService,
            @Value("${console.intelligence.enabled}") boolean enabled) {
        this.intelligenceExecutionService = intelligenceExecutionService;
        this.businessReportExportService = businessReportExportService;
        this.enabled = enabled;
    }

    @PostMapping("/execute")
    public IntelligenceExecutionResponse execute(@RequestBody(required = false) IntelligenceExecutionRequest request) {
        requireEnabled();
        return intelligenceExecutionService.execute(request == null ? new IntelligenceExecutionRequest() : request);
    }

    @PostMapping("/intent")
    public IntelligenceExecutionResponse intent(@RequestBody(required = false) IntelligenceExecutionRequest request) {
        requireEnabled();
        return intelligenceExecutionService.intent(request == null ? new IntelligenceExecutionRequest() : request);
    }

    @PostMapping("/replay/{executionId}")
    public IntelligenceExecutionResponse replay(@PathVariable String executionId) {
        requireEnabled();
        return intelligenceExecutionService.replay(executionId);
    }

    @GetMapping("/{executionId}/report")
    public ResponseEntity<IntelligenceReport> report(@PathVariable String executionId) {
        requireEnabled();
        return intelligenceExecutionService.report(executionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{executionId}/audit")
    public ResponseEntity<IntelligenceAuditRecord> audit(@PathVariable String executionId) {
        requireEnabled();
        return intelligenceExecutionService.audit(executionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/audits")
    public List<IntelligenceAuditSummary> audits(@RequestParam(defaultValue = "50") int limit) {
        requireEnabled();
        return intelligenceExecutionService.audits(limit);
    }

    @GetMapping(value = "/{executionId}/report/download/{format}")
    public ResponseEntity<String> downloadReport(@PathVariable String executionId, @PathVariable String format) {
        requireEnabled();
        return intelligenceExecutionService.report(executionId)
                .map(report -> exportResponse(executionId, format, report))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{executionId}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> logs(@PathVariable String executionId) {
        requireEnabled();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ivp-intelligence-" + executionId + "-logs.txt")
                .body(intelligenceExecutionService.logs(executionId));
    }

    @GetMapping(value = "/{executionId}/evidence/{fileName}")
    public ResponseEntity<Resource> evidence(
            @PathVariable String executionId,
            @PathVariable String fileName,
            @RequestParam(defaultValue = "false") boolean inline) {
        requireEnabled();
        return intelligenceExecutionService.evidenceFile(executionId, fileName)
                .map(path -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                (inline ? "inline" : "attachment")
                                        + "; filename=" + sanitize(path.getFileName().toString()))
                        .body((Resource) new FileSystemResource(path)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Intelligence endpoints are disabled");
        }
    }

    private ResponseEntity<String> exportResponse(String executionId, String format, IntelligenceReport report) {
        String safeFormat = format == null ? "txt" : format.trim().toLowerCase();
        if ("json".equals(safeFormat)) {
            return attachment(executionId, "json", MediaType.APPLICATION_JSON, businessReportExportService.json(report));
        }
        if ("html".equals(safeFormat)) {
            return attachment(executionId, "html", MediaType.TEXT_HTML, businessReportExportService.html(report, executionId));
        }
        if ("csv".equals(safeFormat)) {
            return attachment(executionId, "csv", MediaType.valueOf("text/csv"), businessReportExportService.csv(report));
        }
        return attachment(executionId, "txt", MediaType.TEXT_PLAIN, businessReportExportService.text(report));
    }

    private ResponseEntity<String> attachment(String executionId, String extension, MediaType mediaType, String body) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=ivp-intelligence-" + sanitize(executionId) + "-business-report." + extension)
                .body(body);
    }

    private String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "report";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
