package com.hcl.execution.jms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmsQueueProperties {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String senderQueue;
    private final String receiverQueue;

    public EmsQueueProperties(
            @Value("${ems.host}") String host,
            @Value("${ems.port}") int port,
            @Value("${ems.user}") String user,
            @Value("${ems.password}") String password,
            @Value("${ems.queue.sender}") String senderQueue,
            @Value("${ems.queue.receiver}") String receiverQueue) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.senderQueue = senderQueue;
        this.receiverQueue = receiverQueue;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getSenderQueue() {
        return senderQueue;
    }

    public String getReceiverQueue() {
        return receiverQueue;
    }
}
