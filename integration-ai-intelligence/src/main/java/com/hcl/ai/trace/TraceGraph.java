package com.hcl.ai.trace;

import java.util.ArrayList;
import java.util.List;

public class TraceGraph {

    private List<TraceGraphNode> nodes = new ArrayList<>();
    private List<TraceGraphEdge> edges = new ArrayList<>();
    private List<String> path = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public List<TraceGraphNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<TraceGraphNode> nodes) {
        this.nodes = nodes;
    }

    public List<TraceGraphEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<TraceGraphEdge> edges) {
        this.edges = edges;
    }

    public List<String> getPath() {
        return path;
    }

    public void setPath(List<String> path) {
        this.path = path;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
