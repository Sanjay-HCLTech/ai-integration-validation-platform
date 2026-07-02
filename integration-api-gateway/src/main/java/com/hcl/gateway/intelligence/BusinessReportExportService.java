package com.hcl.gateway.intelligence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcl.ai.report.ExecutionSummary;
import com.hcl.ai.report.ExpectedVsActual;
import com.hcl.ai.report.FailurePoint;
import com.hcl.ai.report.IntelligenceReport;
import com.hcl.ai.report.RecommendedAction;
import com.hcl.ai.trace.TraceGraph;
import com.hcl.ai.trace.TraceGraphEdge;
import com.hcl.ai.validation.RuleEvaluation;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BusinessReportExportService {

    private final ObjectMapper objectMapper;

    public BusinessReportExportService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String json(IntelligenceReport report) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to export intelligence report as JSON", e);
        }
    }

    public String text(IntelligenceReport report) {
        IntelligenceReport safeReport = safe(report);
        ExecutionSummary summary = safeReport.getExecutionSummary();
        FailurePoint failurePoint = safeReport.getFailurePoint();
        ExpectedVsActual expectedVsActual = safeReport.getExpectedVsActual();

        StringBuilder text = new StringBuilder();
        line(text, "============================================================");
        line(text, "INTEGRATION INTELLIGENCE BUSINESS REPORT");
        line(text, "============================================================");
        line(text, "");
        line(text, "EXECUTION SUMMARY");
        field(text, "Status", value(summary == null ? null : summary.getStatus()));
        field(text, "Flow", value(summary == null ? null : summary.getFlow()));
        field(text, "Trigger Mode", value(summary == null ? null : summary.getTriggerMode()));
        field(text, "Booking ID", value(summary == null ? null : summary.getBookingId()));
        field(text, "Confidence", value(summary == null ? null : summary.getConfidence()));
        field(text, "Severity", value(safeReport.getSeverity()));
        line(text, "");
        line(text, "EXECUTIVE SUMMARY");
        line(text, value(safeReport.getExecutiveSummary()));
        line(text, "");
        section(text, "KEY INSIGHTS", safeReport.getKeyInsights());
        line(text, "FAILURE POINT");
        field(text, "System", value(failurePoint == null ? null : failurePoint.getSystem()));
        field(text, "Timestamp", value(failurePoint == null ? null : failurePoint.getTimestamp()));
        line(text, "");
        line(text, "EXPECTED VS ACTUAL");
        field(text, "Expected", value(expectedVsActual == null ? null : expectedVsActual.getExpected()));
        field(text, "Actual", value(expectedVsActual == null ? null : expectedVsActual.getActual()));
        line(text, "");
        line(text, "REPLAY SUGGESTION");
        line(text, value(safeReport.getRetrySuggestion()));
        line(text, "");
        section(text, "TIMELINE", safeReport.getTimeline());
        section(text, "MULTI-HOP TRACE", safeReport.getMultiHopTrace());
        traceEdges(text, safeReport.getTraceGraph());
        section(text, "DRIFT SIGNALS", safeReport.getDriftSignals());
        section(text, "ORPHAN LOGS", safeReport.getOrphanLogs());
        section(text, "ORPHAN EVIDENCE", safeReport.getOrphanEvidence());
        line(text, "OBSERVABILITY INDEX");
        line(text, value(safeReport.getObservabilityIndexSummary()));
        line(text, "");
        policy(text, safeReport);
        actions(text, safeReport.getActions());
        rules(text, safeReport.getRules());
        line(text, "============================================================");
        line(text, "END OF REPORT");
        line(text, "============================================================");
        return text.toString();
    }

    public String csv(IntelligenceReport report) {
        IntelligenceReport safeReport = safe(report);
        StringBuilder csv = new StringBuilder();
        csv.append("section,key,value").append(System.lineSeparator());
        ExecutionSummary summary = safeReport.getExecutionSummary();
        row(csv, "Execution", "status", summary == null ? null : summary.getStatus());
        row(csv, "Execution", "flow", summary == null ? null : summary.getFlow());
        row(csv, "Execution", "triggerMode", summary == null ? null : summary.getTriggerMode());
        row(csv, "Execution", "bookingId", summary == null ? null : summary.getBookingId());
        row(csv, "Execution", "confidence", summary == null ? null : summary.getConfidence());
        row(csv, "Execution", "severity", safeReport.getSeverity());
        row(csv, "Summary", "executiveSummary", safeReport.getExecutiveSummary());
        row(csv, "ExpectedVsActual", "expected",
                safeReport.getExpectedVsActual() == null ? null : safeReport.getExpectedVsActual().getExpected());
        row(csv, "ExpectedVsActual", "actual",
                safeReport.getExpectedVsActual() == null ? null : safeReport.getExpectedVsActual().getActual());
        row(csv, "FailurePoint", "system",
                safeReport.getFailurePoint() == null ? null : safeReport.getFailurePoint().getSystem());
        row(csv, "FailurePoint", "timestamp",
                safeReport.getFailurePoint() == null ? null : safeReport.getFailurePoint().getTimestamp());
        row(csv, "Replay", "suggestion", safeReport.getRetrySuggestion());
        numberedRows(csv, "Insight", "insight", safeReport.getKeyInsights());
        numberedRows(csv, "Timeline", "event", safeReport.getTimeline());
        numberedRows(csv, "Trace", "hop", safeReport.getMultiHopTrace());
        numberedRows(csv, "Drift", "signal", safeReport.getDriftSignals());
        numberedRows(csv, "Orphan", "log", safeReport.getOrphanLogs());
        numberedRows(csv, "OrphanEvidence", "evidence", safeReport.getOrphanEvidence());
        ruleRows(csv, safeReport.getRules());
        actionRows(csv, safeReport.getActions());
        return csv.toString();
    }

    public String html(IntelligenceReport report, String executionId) {
        IntelligenceReport safeReport = safe(report);
        ExecutionSummary summary = safeReport.getExecutionSummary();
        String status = value(summary == null ? null : summary.getStatus());
        String statusClass = "PASS".equalsIgnoreCase(status) ? "pass" : "fail";
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<title>Integration Intelligence Report</title>")
                .append("<style>")
                .append("body{font-family:Arial,Helvetica,sans-serif;margin:0;color:#17202a;background:#f4f6f8;}")
                .append("main{max-width:1100px;margin:0 auto;padding:28px;}")
                .append(".hero{background:#202a33;color:#fff;padding:24px;border-radius:6px;}")
                .append("h1{margin:0 0 8px;font-size:26px;} h2{font-size:16px;margin:0 0 10px;}")
                .append(".muted{color:#5d6b78}.hero .muted{color:#d7dde3}")
                .append(".grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:16px 0;}")
                .append(".card{background:#fff;border:1px solid #d7dde3;border-radius:6px;padding:14px;}")
                .append(".metric span{display:block;font-size:12px;color:#5d6b78;margin-bottom:7px;text-transform:uppercase;}")
                .append(".metric strong{font-size:20px;}")
                .append(".badge{display:inline-block;padding:5px 10px;border-radius:999px;font-weight:700;}")
                .append(".pass{background:#daf5e4;color:#167a3a}.fail{background:#ffe1dc;color:#b42318}")
                .append("ul{margin:0;padding-left:18px;} li{margin:5px 0;}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #d7dde3;}")
                .append("th,td{border-bottom:1px solid #d7dde3;padding:8px 10px;text-align:left;vertical-align:top;}")
                .append("th{background:#eef2f6;font-size:12px;text-transform:uppercase;}")
                .append(".section{margin-top:16px;}.mono{font-family:Consolas,'Courier New',monospace;font-size:12px;}")
                .append(".print{float:right;background:#fff;border:1px solid #d7dde3;border-radius:4px;padding:6px 10px;}")
                .append("@media print{body{background:#fff}.print{display:none}main{padding:0}.card,.hero{break-inside:avoid}}")
                .append("</style></head><body><main>");
        html.append("<button class=\"print\" onclick=\"window.print()\">Print / Save PDF</button>");
        html.append("<section class=\"hero\"><h1>Integration Intelligence Report</h1>")
                .append("<div class=\"muted\">Execution ")
                .append(escape(value(executionId)))
                .append("</div></section>");
        html.append("<section class=\"grid\">")
                .append(metric("Status", "<span class=\"badge " + statusClass + "\">" + escape(status) + "</span>"))
                .append(metric("Flow", escape(value(summary == null ? null : summary.getFlow()))))
                .append(metric("Trigger", escape(value(summary == null ? null : summary.getTriggerMode()))))
                .append(metric("Booking", escape(value(summary == null ? null : summary.getBookingId()))))
                .append(metric("Confidence", escape(value(summary == null ? null : summary.getConfidence()))))
                .append(metric("Severity", escape(value(safeReport.getSeverity()))))
                .append(metric("Retry", escape(value(safeReport.getRetrySuggestion()))))
                .append(metric("Evidence", escape(value(safeReport.getObservabilityIndexSummary()))))
                .append("</section>");
        html.append(section("Executive Summary", "<p>" + escape(value(safeReport.getExecutiveSummary())) + "</p>"));
        html.append(section("Key Insights", listHtml(safeReport.getKeyInsights())));
        html.append(section("Expected vs Actual", expectedHtml(safeReport.getExpectedVsActual())));
        html.append(section("Failure Point", failureHtml(safeReport.getFailurePoint())));
        html.append(section("Drift Detection", listHtml(safeReport.getDriftSignals())));
        html.append(section("Validation Rules", rulesHtml(safeReport.getRules())));
        html.append(section("Recommended Actions", actionsHtml(safeReport.getActions())));
        html.append(section("Trace Path", listHtml(safeReport.getMultiHopTrace())));
        html.append("</main></body></html>");
        return html.toString();
    }

    private void policy(StringBuilder text, IntelligenceReport report) {
        line(text, "REPLAY POLICY");
        if (report.getPolicyDecision() == null) {
            line(text, "NA");
            line(text, "");
            return;
        }
        field(text, "Retry Allowed", report.getPolicyDecision().isRetryAllowed() ? "YES" : "NO");
        field(text, "Reason", value(report.getPolicyDecision().getReason()));
        field(text, "Controls", listValue(report.getPolicyDecision().getControls()));
        line(text, "");
    }

    private void actions(StringBuilder text, List<RecommendedAction> actions) {
        line(text, "RECOMMENDED ACTIONS");
        if (actions == null || actions.isEmpty()) {
            line(text, "NA");
            line(text, "");
            return;
        }
        int index = 1;
        for (RecommendedAction action : actions) {
            line(text, index + ". File=" + value(action.getFile())
                    + " | Search=" + value(action.getSearch())
                    + " | Time=" + value(action.getTime()));
            index++;
        }
        line(text, "");
    }

    private void rules(StringBuilder text, List<RuleEvaluation> rules) {
        line(text, "VALIDATION RULES");
        if (rules == null || rules.isEmpty()) {
            line(text, "NA");
            line(text, "");
            return;
        }
        int index = 1;
        for (RuleEvaluation rule : rules) {
            line(text, index + ". " + value(rule.getStatus()) + " | " + value(rule.getRule()));
            field(text, "   Expected", value(rule.getExpected()));
            field(text, "   Actual", value(rule.getActual()));
            index++;
        }
        line(text, "");
    }

    private void traceEdges(StringBuilder text, TraceGraph graph) {
        line(text, "TRACE GRAPH EDGES");
        if (graph == null || graph.getEdges() == null || graph.getEdges().isEmpty()) {
            line(text, "NA");
            line(text, "");
            return;
        }
        int index = 1;
        for (TraceGraphEdge edge : graph.getEdges()) {
            line(text, index + ". " + value(edge.getFrom()) + " -> " + value(edge.getTo())
                    + " | Token=" + value(edge.getToken())
                    + " | Reason=" + value(edge.getReason()));
            index++;
        }
        line(text, "");
    }

    private void section(StringBuilder text, String title, List<String> values) {
        line(text, title);
        if (values == null || values.isEmpty()) {
            line(text, "NA");
        } else {
            int index = 1;
            for (String value : values) {
                line(text, index + ". " + value(value));
                index++;
            }
        }
        line(text, "");
    }

    private void field(StringBuilder text, String name, String value) {
        line(text, String.format("%-14s : %s", name, value));
    }

    private void line(StringBuilder text, String value) {
        text.append(value).append(System.lineSeparator());
    }

    private void numberedRows(StringBuilder csv, String section, String keyPrefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        int index = 1;
        for (String value : values) {
            row(csv, section, keyPrefix + index, value);
            index++;
        }
    }

    private void ruleRows(StringBuilder csv, List<RuleEvaluation> rules) {
        if (rules == null || rules.isEmpty()) {
            return;
        }
        int index = 1;
        for (RuleEvaluation rule : rules) {
            row(csv, "Rule", "rule" + index + ".status", rule.getStatus());
            row(csv, "Rule", "rule" + index + ".name", rule.getRule());
            row(csv, "Rule", "rule" + index + ".expected", rule.getExpected());
            row(csv, "Rule", "rule" + index + ".actual", rule.getActual());
            index++;
        }
    }

    private void actionRows(StringBuilder csv, List<RecommendedAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        int index = 1;
        for (RecommendedAction action : actions) {
            row(csv, "Action", "action" + index + ".file", action.getFile());
            row(csv, "Action", "action" + index + ".search", action.getSearch());
            row(csv, "Action", "action" + index + ".time", action.getTime());
            index++;
        }
    }

    private void row(StringBuilder csv, String section, String key, String value) {
        csv.append(csv(section)).append(',')
                .append(csv(key)).append(',')
                .append(csv(value(value)))
                .append(System.lineSeparator());
    }

    private String csv(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\"", "\"\"") + "\"";
    }

    private String metric(String label, String value) {
        return "<div class=\"card metric\"><span>" + escape(label) + "</span><strong>" + value + "</strong></div>";
    }

    private String section(String title, String body) {
        return "<section class=\"card section\"><h2>" + escape(title) + "</h2>" + body + "</section>";
    }

    private String listHtml(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "<p class=\"muted\">NA</p>";
        }
        StringBuilder html = new StringBuilder("<ul>");
        for (String value : values) {
            html.append("<li>").append(escape(value(value))).append("</li>");
        }
        html.append("</ul>");
        return html.toString();
    }

    private String expectedHtml(ExpectedVsActual expectedVsActual) {
        return "<table><tr><th>Expected</th><th>Actual</th></tr><tr><td>"
                + escape(value(expectedVsActual == null ? null : expectedVsActual.getExpected()))
                + "</td><td>"
                + escape(value(expectedVsActual == null ? null : expectedVsActual.getActual()))
                + "</td></tr></table>";
    }

    private String failureHtml(FailurePoint failurePoint) {
        return "<table><tr><th>System</th><th>Timestamp</th></tr><tr><td>"
                + escape(value(failurePoint == null ? null : failurePoint.getSystem()))
                + "</td><td>"
                + escape(value(failurePoint == null ? null : failurePoint.getTimestamp()))
                + "</td></tr></table>";
    }

    private String rulesHtml(List<RuleEvaluation> rules) {
        if (rules == null || rules.isEmpty()) {
            return "<p class=\"muted\">NA</p>";
        }
        StringBuilder html = new StringBuilder("<table><tr><th>Status</th><th>Rule</th><th>Expected</th><th>Actual</th></tr>");
        for (RuleEvaluation rule : rules) {
            html.append("<tr><td>").append(escape(value(rule.getStatus()))).append("</td><td>")
                    .append(escape(value(rule.getRule()))).append("</td><td>")
                    .append(escape(value(rule.getExpected()))).append("</td><td>")
                    .append(escape(value(rule.getActual()))).append("</td></tr>");
        }
        html.append("</table>");
        return html.toString();
    }

    private String actionsHtml(List<RecommendedAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return "<p class=\"muted\">NA</p>";
        }
        StringBuilder html = new StringBuilder("<table><tr><th>File</th><th>Search</th><th>Time</th></tr>");
        for (RecommendedAction action : actions) {
            html.append("<tr><td>").append(escape(value(action.getFile()))).append("</td><td class=\"mono\">")
                    .append(escape(value(action.getSearch()))).append("</td><td>")
                    .append(escape(value(action.getTime()))).append("</td></tr>");
        }
        html.append("</table>");
        return html.toString();
    }

    private String escape(String value) {
        return value(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String listValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "NA";
        }
        return String.join(", ", values);
    }

    private IntelligenceReport safe(IntelligenceReport report) {
        return report == null ? new IntelligenceReport() : report;
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value.trim();
    }
}
