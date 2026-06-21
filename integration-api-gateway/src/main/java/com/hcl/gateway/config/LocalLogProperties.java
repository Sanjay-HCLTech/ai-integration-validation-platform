package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "local.log")
public class LocalLogProperties {

    private String dir = "C:/logs";
    private final Retention retention = new Retention();

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public Retention getRetention() {
        return retention;
    }

    public static class Retention {
        private int days = 7;

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }
    }
}
