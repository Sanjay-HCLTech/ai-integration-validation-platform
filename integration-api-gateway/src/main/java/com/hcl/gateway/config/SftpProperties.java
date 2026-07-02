package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "sftp")
public class SftpProperties {

    private String host;
    private int port;
    private String username;
    private String password;
    private final Map<String, Object> privateProperties = new LinkedHashMap<>();
    private final Remote remote = new Remote();
    private final Connect connect = new Connect();
    private final Command command = new Command();
    private final Keepalive keepalive = new Keepalive();
    private final Search search = new Search();
    private final Correlation correlation = new Correlation();
    private final Block block = new Block();
    private final Local local = new Local();
    private final LogName log = new LogName();
    private final Payload payload = new Payload();
    private final Grep grep = new Grep();

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

    public Remote getRemote() {
        return remote;
    }

    public Connect getConnect() {
        return connect;
    }

    public Command getCommand() {
        return command;
    }

    public Keepalive getKeepalive() {
        return keepalive;
    }

    public Search getSearch() {
        return search;
    }

    public Correlation getCorrelation() {
        return correlation;
    }

    public Block getBlock() {
        return block;
    }

    public Local getLocal() {
        return local;
    }

    public LogName getLog() {
        return log;
    }

    public Payload getPayload() {
        return payload;
    }

    public Grep getGrep() {
        return grep;
    }

    public static class Payload {
        private final Log log = new Log();

        public Log getLog() {
            return log;
        }
    }

    public static class Log {
        private String dir;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class Grep {
        private final Retry retry = new Retry();

        public Retry getRetry() {
            return retry;
        }
    }

    public static class Retry {
        private int count;
        private final Wait wait = new Wait();

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public Wait getWait() {
            return wait;
        }
    }

    public static class Wait {
        private long ms;

        public long getMs() {
            return ms;
        }

        public void setMs(long ms) {
            this.ms = ms;
        }
    }

    public static class Remote {
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

    public static class Connect {
        private final Timeout timeout = new Timeout();

        public Timeout getTimeout() {
            return timeout;
        }
    }

    public static class Command {
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

    public static class Keepalive {
        private final Interval interval = new Interval();

        public Interval getInterval() {
            return interval;
        }
    }

    public static class Interval {
        private long ms;

        public long getMs() {
            return ms;
        }

        public void setMs(long ms) {
            this.ms = ms;
        }
    }

    public static class Search {
        private final Modified modified = new Modified();

        public Modified getModified() {
            return modified;
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

    public static class Correlation {
        private final Max max = new Max();
        private int tokenLimit;
        private int tokensPerSearch;

        public Max getMax() {
            return max;
        }

        public int getTokenLimit() {
            return tokenLimit;
        }

        public void setTokenLimit(int tokenLimit) {
            this.tokenLimit = tokenLimit;
        }

        public int getTokensPerSearch() {
            return tokensPerSearch;
        }

        public void setTokensPerSearch(int tokensPerSearch) {
            this.tokensPerSearch = tokensPerSearch;
        }
    }

    public static class Max {
        private int depth;
        private int jobids;

        public int getDepth() {
            return depth;
        }

        public void setDepth(int depth) {
            this.depth = depth;
        }

        public int getJobids() {
            return jobids;
        }

        public void setJobids(int jobids) {
            this.jobids = jobids;
        }

    }

    public static class Block {
        private final MaxMatches max = new MaxMatches();

        public MaxMatches getMax() {
            return max;
        }
    }

    public static class MaxMatches {
        private final Matches matches = new Matches();

        public Matches getMatches() {
            return matches;
        }
    }

    public static class Matches {
        private final Per per = new Per();

        public Per getPer() {
            return per;
        }
    }

    public static class Per {
        private int file;

        public int getFile() {
            return file;
        }

        public void setFile(int file) {
            this.file = file;
        }
    }

    public static class Local {
        private final Min min = new Min();

        public Min getMin() {
            return min;
        }
    }

    public static class Min {
        private final BlockLines block = new BlockLines();

        public BlockLines getBlock() {
            return block;
        }
    }

    public static class BlockLines {
        private int lines;

        public int getLines() {
            return lines;
        }

        public void setLines(int lines) {
            this.lines = lines;
        }
    }

    public static class LogName {
        private final Name name = new Name();

        public Name getName() {
            return name;
        }
    }

    public static class Name {
        private final Filter filter = new Filter();

        public Filter getFilter() {
            return filter;
        }
    }

    public static class Filter {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
