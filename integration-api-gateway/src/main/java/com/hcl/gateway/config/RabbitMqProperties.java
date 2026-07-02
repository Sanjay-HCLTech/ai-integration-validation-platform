package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rabbitmq")
public class RabbitMqProperties {

    private final Simulation simulation = new Simulation();
    private final Routing routing = new Routing();
    private final Queue queue = new Queue();
    private String exchange;

    public Simulation getSimulation() {
        return simulation;
    }

    public Routing getRouting() {
        return routing;
    }

    public Queue getQueue() {
        return queue;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
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

    public static class Routing {
        private String key;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class Queue {
        private String receiver;

        public String getReceiver() {
            return receiver;
        }

        public void setReceiver(String receiver) {
            this.receiver = receiver;
        }
    }
}
