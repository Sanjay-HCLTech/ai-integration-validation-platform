package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "rabbit")
public class RabbitProperties {

    private final Map<String, Object> queue = new LinkedHashMap<>();
    private final Map<String, Object> routing = new LinkedHashMap<>();
    private final Map<String, Object> exchange = new LinkedHashMap<>();
    private final Map<String, Object> tracking = new LinkedHashMap<>();
    private final Payload payload = new Payload();
    private final Map<String, Object> message = new LinkedHashMap<>();
    private final Nordics nordics = new Nordics();
    private final Correlation correlation = new Correlation();
    private final SystemConfig system = new SystemConfig();
    private final Broker broker = new Broker();
    private final Management management = new Management();
    private final Expected expected = new Expected();
    private String env;
    private String vhost;
    private String username;
    private String password;

    public Map<String, Object> getQueue() {
        return queue;
    }

    public Map<String, Object> getRouting() {
        return routing;
    }

    public Map<String, Object> getExchange() {
        return exchange;
    }

    public Map<String, Object> getTracking() {
        return tracking;
    }

    public Payload getPayload() {
        return payload;
    }

    public Map<String, Object> getMessage() {
        return message;
    }

    public Nordics getNordics() {
        return nordics;
    }

    public Correlation getCorrelation() {
        return correlation;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public SystemConfig getSystem() {
        return system;
    }

    public Broker getBroker() {
        return broker;
    }

    public Management getManagement() {
        return management;
    }

    public Expected getExpected() {
        return expected;
    }

    public String getVhost() {
        return vhost;
    }

    public void setVhost(String vhost) {
        this.vhost = vhost;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public static class Nordics {
        private String trackingId;
        private final Log log = new Log();
        private final Sftp sftp = new Sftp();

        public String getTrackingId() {
            return trackingId;
        }

        public void setTrackingId(String trackingId) {
            this.trackingId = trackingId;
        }

        public Log getLog() {
            return log;
        }

        public Sftp getSftp() {
            return sftp;
        }
    }

    public static class Payload {
        private final Map<String, String> system = new LinkedHashMap<>();

        public Map<String, String> getSystem() {
            return system;
        }
    }

    public static class Log {
        private final Scan scan = new Scan();
        private final Remote remote = new Remote();

        public Scan getScan() {
            return scan;
        }

        public Remote getRemote() {
            return remote;
        }
    }

    public static class Scan {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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

    public static class SystemConfig {
        private String defaultValue;

        public String getDefault() {
            return defaultValue;
        }

        public void setDefault(String defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    public static class Broker {
        private String host;
        private final Amqp amqp = new Amqp();

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Amqp getAmqp() {
            return amqp;
        }
    }

    public static class Amqp {
        private int port;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    public static class Management {
        private String url;
        private final Exchange exchange = new Exchange();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Exchange getExchange() {
            return exchange;
        }
    }

    public static class Exchange {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
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

    public static class Remote {
        private String path;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class Sftp {
        private String host;
        private int port;
        private String username;
        private String password;
        private final Map<String, Object> privateProperties = new LinkedHashMap<>();
        private final RemoteRun remote = new RemoteRun();
        private final PayloadLog payload = new PayloadLog();

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Map<String, Object> getPrivate() {
            return privateProperties;
        }

        public RemoteRun getRemote() {
            return remote;
        }

        public PayloadLog getPayload() {
            return payload;
        }
    }

    public static class RemoteRun {
        private final Run run = new Run();

        public Run getRun() {
            return run;
        }
    }

    public static class Run {
        private String as;

        public String getAs() {
            return as;
        }

        public void setAs(String as) {
            this.as = as;
        }
    }

    public static class PayloadLog {
        private final LogDir log = new LogDir();

        public LogDir getLog() {
            return log;
        }
    }

    public static class LogDir {
        private String dir;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }
}
