package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "execution")
public class ExecutionProperties {

    private final Retry retry = new Retry();
    private final Wait wait = new Wait();

    public Retry getRetry() {
        return retry;
    }

    public Wait getWait() {
        return wait;
    }

    public static class Retry {
        private int count = 3;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    public static class Wait {
        private long ms = 3000;

        public long getMs() {
            return ms;
        }

        public void setMs(long ms) {
            this.ms = ms;
        }
    }
}
