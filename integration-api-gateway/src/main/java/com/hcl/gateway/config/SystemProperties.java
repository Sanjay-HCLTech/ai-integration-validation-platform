package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "system")
public class SystemProperties {

    private final Toggle rest = new Toggle();
    private final Toggle soap = new Toggle();
    private final Toggle jms = new Toggle();
    private final Toggle rabbitmq = new Toggle();
    private final Toggle kafka = new Toggle();

    public Toggle getRest() {
        return rest;
    }

    public Toggle getSoap() {
        return soap;
    }

    public Toggle getJms() {
        return jms;
    }

    public Toggle getRabbitmq() {
        return rabbitmq;
    }

    public Toggle getKafka() {
        return kafka;
    }

    public static class Toggle {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
