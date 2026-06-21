package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "jms")
public class JmsProperties {

    private final Payload payload = new Payload();
    private final Map<String, Object> destination = new LinkedHashMap<>();
    private final Map<String, Object> message = new LinkedHashMap<>();
    private final Correlation correlation = new Correlation();
    private final Expected expected = new Expected();
    private final Simulation simulation = new Simulation();
    private final SystemConfig system = new SystemConfig();
    private String env = "ST5";
    private String provider;
    private final Default defaultValue = new Default();

    public Payload getPayload() {
        return payload;
    }

    public Map<String, Object> getDestination() {
        return destination;
    }

    public Map<String, Object> getMessage() {
        return message;
    }

    public Correlation getCorrelation() {
        return correlation;
    }

    public Expected getExpected() {
        return expected;
    }

    public Simulation getSimulation() {
        return simulation;
    }

    public SystemConfig getSystem() {
        return system;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Default getDefault() {
        return defaultValue;
    }

    public static class Default {
        private boolean async;

        public boolean isAsync() {
            return async;
        }

        public void setAsync(boolean async) {
            this.async = async;
        }
    }

    public static class SystemConfig {
        private String defaultValue;

        public String getDefault() {
            return defaultValue;
        }

        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    public static class Payload {
        private final Map<String, String> system = new LinkedHashMap<>();
        private final EnvPayload st5 = new EnvPayload();
        private final EnvPayload sit = new EnvPayload();
        private final EnvPayload st3 = new EnvPayload();

        public Map<String, String> getSystem() {
            return system;
        }

        public EnvPayload getSt5() {
            return st5;
        }

        public EnvPayload getSit() {
            return sit;
        }

        public EnvPayload getSt3() {
            return st3;
        }
    }

    public static class EnvPayload {
        private final Map<String, String> dms = new LinkedHashMap<>();
        private final Map<String, String> sap = new LinkedHashMap<>();
        private final Map<String, String> ao = new LinkedHashMap<>();
        private final Map<String, String> tda = new LinkedHashMap<>();
        private final Map<String, String> gip = new LinkedHashMap<>();
        private final Map<String, String> c4c = new LinkedHashMap<>();

        public Map<String, String> getDms() {
            return dms;
        }

        public Map<String, String> getSap() {
            return sap;
        }

        public Map<String, String> getAo() {
            return ao;
        }

        public Map<String, String> getTda() {
            return tda;
        }

        public Map<String, String> getGip() {
            return gip;
        }

        public Map<String, String> getC4c() {
            return c4c;
        }
    }

    public static class Correlation {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Expected {
        private final Publish publish = new Publish();

        public Publish getPublish() {
            return publish;
        }
    }

    public static class Publish {
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    public static class Simulation {
        private final Consumer consumer = new Consumer();
        private final Retry retry = new Retry();
        private final Processing processing = new Processing();
        private final LogAnalyzer logAnalyzer = new LogAnalyzer();

        public Consumer getConsumer() {
            return consumer;
        }

        public Retry getRetry() {
            return retry;
        }

        public Processing getProcessing() {
            return processing;
        }

        public LogAnalyzer getLogAnalyzer() {
            return logAnalyzer;
        }
    }

    public static class Consumer {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Retry {
        private int count;
        private final Delay delay = new Delay();

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public Delay getDelay() {
            return delay;
        }
    }

    public static class Processing {
        private final Delay delay = new Delay();

        public Delay getDelay() {
            return delay;
        }
    }

    public static class Delay {
        private long ms;

        public long getMs() {
            return ms;
        }

        public void setMs(long ms) {
            this.ms = ms;
        }
    }

    public static class LogAnalyzer {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
