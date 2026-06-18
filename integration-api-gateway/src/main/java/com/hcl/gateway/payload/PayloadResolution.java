package com.hcl.gateway.payload;

public class PayloadResolution {

    private final String content;
    private final String file;
    private final boolean override;
    private final String source;

    public PayloadResolution(String content, String file, boolean override, String source) {
        this.content = content;
        this.file = file;
        this.override = override;
        this.source = source;
    }

    public String getContent() {
        return content;
    }

    public String getFile() {
        return file;
    }

    public boolean isOverride() {
        return override;
    }

    public String getSource() {
        return source;
    }
}
