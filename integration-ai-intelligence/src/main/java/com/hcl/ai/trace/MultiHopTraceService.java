package com.hcl.ai.trace;

import com.hcl.ai.index.IndexedLogLine;
import com.hcl.ai.index.ObservabilityIndexResult;
import com.hcl.ai.report.IntelligenceExecutionRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MultiHopTraceService {

    public List<String> detect(List<IntelligenceExecutionRow> rows) {
        return detect(rows, null);
    }

    public List<String> detect(List<IntelligenceExecutionRow> rows, ObservabilityIndexResult indexResult) {
        TraceGraph graph = graph(rows, indexResult);
        return graph.getPath();
    }

    public TraceGraph graph(List<IntelligenceExecutionRow> rows, ObservabilityIndexResult indexResult) {
        TraceGraph graph = evidenceGraph(indexResult);
        if (!graph.getNodes().isEmpty()) {
            return graph;
        }
        graph.getWarnings().add("No local indexed evidence graph available; using execution row fallback");
        for (String hop : rowHops(rows)) {
            TraceGraphNode node = new TraceGraphNode();
            node.setId(hop);
            node.setService(hop);
            node.setFile("NA");
            node.setEvidenceCount(0);
            node.setFirstTimestamp("NA");
            node.setLastTimestamp("NA");
            graph.getNodes().add(node);
            graph.getPath().add(hop);
        }
        linkSequential(graph, "EXECUTION_ROW_SEQUENCE");
        return graph;
    }

    private TraceGraph evidenceGraph(ObservabilityIndexResult indexResult) {
        TraceGraph graph = new TraceGraph();
        if (indexResult == null || indexResult.getEntries() == null || indexResult.getEntries().isEmpty()) {
            return graph;
        }

        Map<String, TraceGraphNode> nodes = new LinkedHashMap<>();
        Map<String, String> firstNodeByToken = new LinkedHashMap<>();
        String previousNodeId = "";
        for (IndexedLogLine entry : indexResult.getEntries()) {
            String nodeId = nodeId(entry);
            TraceGraphNode node = nodes.computeIfAbsent(nodeId, id -> node(entry, id));
            node.setEvidenceCount(node.getEvidenceCount() + 1);
            node.setLastTimestamp(timestamp(entry));
            if (!hasText(node.getFirstTimestamp()) || "NA".equals(node.getFirstTimestamp())) {
                node.setFirstTimestamp(timestamp(entry));
            }

            String token = token(entry);
            if (hasText(token)) {
                String firstNode = firstNodeByToken.get(token);
                if (hasText(firstNode) && !firstNode.equals(nodeId)) {
                    addEdge(graph, firstNode, nodeId, token, "SHARED_CORRELATION_TOKEN");
                } else {
                    firstNodeByToken.put(token, nodeId);
                }
            }
            if (hasText(previousNodeId) && !previousNodeId.equals(nodeId)) {
                addEdge(graph, previousNodeId, nodeId, token, "LOCAL_EVIDENCE_ORDER");
            }
            previousNodeId = nodeId;
        }

        graph.getNodes().addAll(nodes.values());
        for (TraceGraphNode node : graph.getNodes()) {
            graph.getPath().add(node.getService());
        }
        return graph;
    }

    private List<String> rowHops(List<IntelligenceExecutionRow> rows) {
        List<String> hops = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return hops;
        }
        String previous = "";
        for (IntelligenceExecutionRow row : rows) {
            String current = value(row.getSystem()) + ":" + value(row.getService());
            if (!current.equals(previous)) {
                hops.add(current);
                previous = current;
            }
        }
        return hops;
    }

    private TraceGraphNode node(IndexedLogLine entry, String nodeId) {
        TraceGraphNode node = new TraceGraphNode();
        node.setId(nodeId);
        node.setService(service(entry));
        node.setFile(value(entry == null ? null : entry.getFile()));
        node.setFirstTimestamp(timestamp(entry));
        node.setLastTimestamp(timestamp(entry));
        return node;
    }

    private String nodeId(IndexedLogLine entry) {
        return service(entry).toUpperCase(Locale.ROOT);
    }

    private String service(IndexedLogLine entry) {
        String file = value(entry == null ? null : entry.getFile());
        String upperLine = value(entry == null ? null : entry.getLine()).toUpperCase(Locale.ROOT);
        if (upperLine.contains("MONGODB") || upperLine.contains("MONGO")) {
            return "MongoTDA";
        }
        if (upperLine.contains("POSTBOOKFLOW")) {
            return "PostBookflowSubscriber";
        }
        if (upperLine.contains("DATAHUB")) {
            return "DataHub";
        }
        if (upperLine.contains("JMS")) {
            return "JMS";
        }
        String baseName = file.replaceAll("\\.log(?:\\.\\d+)?$", "");
        return baseName.isEmpty() || "NA".equals(baseName) ? "UnknownService" : baseName;
    }

    private String token(IndexedLogLine entry) {
        if (entry == null) {
            return "";
        }
        if (hasText(entry.getCorrId())) {
            return "CorrID=" + entry.getCorrId();
        }
        if (hasText(entry.getJobId())) {
            return "JobID=" + entry.getJobId();
        }
        if (hasText(entry.getBookingId())) {
            return "BookingID=" + entry.getBookingId();
        }
        return "";
    }

    private String timestamp(IndexedLogLine entry) {
        return value(entry == null ? null : entry.getTimestamp());
    }

    private void linkSequential(TraceGraph graph, String reason) {
        for (int index = 1; index < graph.getNodes().size(); index++) {
            addEdge(graph, graph.getNodes().get(index - 1).getId(), graph.getNodes().get(index).getId(), "NA", reason);
        }
    }

    private void addEdge(TraceGraph graph, String from, String to, String token, String reason) {
        if (!hasText(from) || !hasText(to) || from.equals(to) || edgeExists(graph, from, to, reason)) {
            return;
        }
        TraceGraphEdge edge = new TraceGraphEdge();
        edge.setFrom(from);
        edge.setTo(to);
        edge.setToken(value(token));
        edge.setReason(reason);
        graph.getEdges().add(edge);
    }

    private boolean edgeExists(TraceGraph graph, String from, String to, String reason) {
        for (TraceGraphEdge edge : graph.getEdges()) {
            if (edge != null && from.equals(edge.getFrom()) && to.equals(edge.getTo())
                    && value(reason).equals(edge.getReason())) {
                return true;
            }
        }
        return false;
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty() && !"NA".equalsIgnoreCase(value.trim());
    }
}
