package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "kafka")
public class KafkaProperties {

    private final Payload payload = new Payload();
    private final Map<String, Object> message = new LinkedHashMap<>();
    private final Map<String, Object> topic = new LinkedHashMap<>();
    private final SystemConfig system = new SystemConfig();
    private final Simulation simulation = new Simulation();
    private final Bootstrap bootstrap = new Bootstrap();
    private final Consumer consumer = new Consumer();
    private String env;
    private int partition;

    public Payload getPayload() {
        return payload;
    }

    public Map<String, Object> getTopic() {
        return topic;
    }

    public Map<String, Object> getMessage() {
        return message;
    }

    public SystemConfig getSystem() {
        return system;
    }

    public Simulation getSimulation() {
        return simulation;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public int getPartition() {
        return partition;
    }

    public void setPartition(int partition) {
        this.partition = partition;
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

    public static class Simulation {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Bootstrap {
        private String servers;

        public String getServers() {
            return servers;
        }

        public void setServers(String servers) {
            this.servers = servers;
        }
    }

    public static class Consumer {
        private final Map<String, Object> group = new LinkedHashMap<>();

        public Map<String, Object> getGroup() {
            return group;
        }
    }
}
