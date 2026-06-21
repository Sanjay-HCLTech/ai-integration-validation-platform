package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "soap")
public class SoapProperties {

    private final Payload payload = new Payload();
    private final Map<String, Object> endpoint = new LinkedHashMap<>();
    private final SystemConfig system = new SystemConfig();
    private final Expected expected = new Expected();
    private final Status status = new Status();
    private final Connect connect = new Connect();
    private final Read read = new Read();
    private final Jms jms = new Jms();
    private final Allow allow = new Allow();
    private final Map<String, Object> transport = new LinkedHashMap<>();
    private String env = "ST5";
    private String action;

    public Payload getPayload() {
        return payload;
    }

    public Map<String, Object> getTransport() {
        return transport;
    }

    public Map<String, Object> getEndpoint() {
        return endpoint;
    }

    public SystemConfig getSystem() {
        return system;
    }

    public Expected getExpected() {
        return expected;
    }

    public Status getStatus() {
        return status;
    }

    public Connect getConnect() {
        return connect;
    }

    public Read getRead() {
        return read;
    }

    public Jms getJms() {
        return jms;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Allow getAllow() {
        return allow;
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

        public Map<String, String> getSystem() {
            return system;
        }
    }

    public static class Expected {
        private final Http http = new Http();
        private final StatusValues status = new StatusValues();

        public Http getHttp() {
            return http;
        }

        public StatusValues getStatus() {
            return status;
        }
    }

    public static class Http {
        private int status;

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }
    }

    public static class StatusValues {
        private String values;

        public String getValues() {
            return values;
        }

        public void setValues(String values) {
            this.values = values;
        }
    }

    public static class Status {
        private String xpath;

        public String getXpath() {
            return xpath;
        }

        public void setXpath(String xpath) {
            this.xpath = xpath;
        }
    }

    public static class Connect {
        private final Timeout timeout = new Timeout();

        public Timeout getTimeout() {
            return timeout;
        }
    }

    public static class Read {
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

    public static class Jms {
        private String sender;
        private String receiver;
        private boolean async;
        private final Validation validation = new Validation();
        private final Simulation simulation = new Simulation();

        public String getSender() {
            return sender;
        }

        public void setSender(String sender) {
            this.sender = sender;
        }

        public String getReceiver() {
            return receiver;
        }

        public void setReceiver(String receiver) {
            this.receiver = receiver;
        }

        public boolean isAsync() {
            return async;
        }

        public void setAsync(boolean async) {
            this.async = async;
        }

        public Validation getValidation() {
            return validation;
        }

        public Simulation getSimulation() {
            return simulation;
        }
    }

    public static class Validation {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Simulation {
        private final Validation validation = new Validation();

        public Validation getValidation() {
            return validation;
        }
    }

    public static class Allow {
        private final Error error = new Error();

        public Error getError() {
            return error;
        }
    }

    public static class Error {
        private boolean status;

        public boolean isStatus() {
            return status;
        }

        public void setStatus(boolean status) {
            this.status = status;
        }
    }
}
