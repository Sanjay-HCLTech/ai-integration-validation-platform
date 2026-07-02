package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "rest")
public class RestProperties {

    private final Payload payload = new Payload();
    private final Map<String, Object> endpoint = new LinkedHashMap<>();
    private final Map<String, Object> api = new LinkedHashMap<>();
    private final Map<String, Object> content = new LinkedHashMap<>();
    private final Map<String, Object> expected = new LinkedHashMap<>();
    private final Map<String, Object> status = new LinkedHashMap<>();
    private final Map<String, Object> success = new LinkedHashMap<>();
    private final Map<String, Object> get = new LinkedHashMap<>();
    private final Map<String, Object> method = new LinkedHashMap<>();
    private final Map<String, Object> accept = new LinkedHashMap<>();
    private final Log log = new Log();
    private final Collection collection = new Collection();
    private final Brand brand = new Brand();
    private final Connect connect = new Connect();
    private final Read read = new Read();
    private String env;
    private String connection;

    public Payload getPayload() {
        return payload;
    }

    public Map<String, Object> getEndpoint() {
        return endpoint;
    }

    public Map<String, Object> getApi() {
        return api;
    }

    public Map<String, Object> getMethod() {
        return method;
    }

    public Map<String, Object> getAccept() {
        return accept;
    }

    public Map<String, Object> getContent() {
        return content;
    }

    public Map<String, Object> getExpected() {
        return expected;
    }

    public Map<String, Object> getStatus() {
        return status;
    }

    public Map<String, Object> getSuccess() {
        return success;
    }

    public Map<String, Object> getGet() {
        return get;
    }

    public Log getLog() {
        return log;
    }

    public Collection getCollection() {
        return collection;
    }

    public Brand getBrand() {
        return brand;
    }

    public Connect getConnect() {
        return connect;
    }

    public Read getRead() {
        return read;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getConnection() {
        return connection;
    }

    public void setConnection(String connection) {
        this.connection = connection;
    }

    public static class Collection {
        private String defaultValue;

        public String getDefault() {
            return defaultValue;
        }

        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    public static class Payload {
        private final Map<String, String> collection = new LinkedHashMap<>();
        private final Map<String, String> st5 = new LinkedHashMap<>();
        private final Map<String, String> sit = new LinkedHashMap<>();

        public Map<String, String> getCollection() {
            return collection;
        }

        public Map<String, String> getSt5() {
            return st5;
        }

        public Map<String, String> getSit() {
            return sit;
        }
    }

    public static class Brand {
        private String defaultValue;

        public String getDefault() {
            return defaultValue;
        }

        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
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

    public static class Log {
        private final Remote remote = new Remote();
        private final Snapshot snapshot = new Snapshot();

        public Remote getRemote() {
            return remote;
        }

        public Snapshot getSnapshot() {
            return snapshot;
        }
    }

    public static class Remote {
        private String path;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Snapshot {
        private boolean enabled;
        private String envs;
        private final Max max = new Max();
        private final Modified modified = new Modified();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEnvs() {
            return envs;
        }

        public void setEnvs(String envs) {
            this.envs = envs;
        }

        public Max getMax() {
            return max;
        }

        public Modified getModified() {
            return modified;
        }
    }

    public static class Max {
        private int files;

        public int getFiles() {
            return files;
        }

        public void setFiles(int files) {
            this.files = files;
        }
    }

    public static class Modified {
        private final Within within = new Within();

        public Within getWithin() {
            return within;
        }
    }

    public static class Within {
        private int days;

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }
    }
}
