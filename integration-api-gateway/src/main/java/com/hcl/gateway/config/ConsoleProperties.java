package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "console")
public class ConsoleProperties {

    private final Execution execution = new Execution();
    private final Intelligence intelligence = new Intelligence();

    public Execution getExecution() {
        return execution;
    }

    public Intelligence getIntelligence() {
        return intelligence;
    }

    public static class Execution {
        private final History history = new History();
        private final Parallel parallel = new Parallel();
        private final Default defaultValue = new Default();

        public History getHistory() {
            return history;
        }

        public Parallel getParallel() {
            return parallel;
        }

        public Default getDefault() {
            return defaultValue;
        }
    }

    public static class History {
        private int limit;

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class Parallel {
        private final Timeout timeout = new Timeout();

        public Timeout getTimeout() {
            return timeout;
        }
    }

    public static class Timeout {
        private long ms;

        public long getMs() {
            return ms;
        }

        public void setMs(long ms) {
            this.ms = ms;
        }
    }

    public static class Default {
        private String bookingId;

        public String getBookingId() {
            return bookingId;
        }

        public void setBookingId(String bookingId) {
            this.bookingId = bookingId;
        }
    }

    public static class Intelligence {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
