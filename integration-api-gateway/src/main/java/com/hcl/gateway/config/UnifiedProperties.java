package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "unified")
public class UnifiedProperties {

    private final Trace trace = new Trace();

    public Trace getTrace() {
        return trace;
    }

    public static class Trace {
        private final Report report = new Report();

        public Report getReport() {
            return report;
        }
    }

    public static class Report {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
