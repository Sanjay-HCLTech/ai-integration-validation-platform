package com.hcl.execution.payload;

import java.nio.file.Path;

public class PayloadResolution {

    private final String content;
    private final String source;
    private final String system;
    private final Path path;

    public PayloadResolution(String content, String source, String system, Path path) {
        this.content = content;
        this.source = source;
        this.system = system;
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public String getSource() {
        return source;
    }

    public String getSystem() {
        return system;
    }

    public Path getPath() {
        return path;
    }
}
