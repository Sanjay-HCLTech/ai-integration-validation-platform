package com.hcl.execution.jms;

public interface JmsQueueService {

    void send(JmsMessage message);

    JmsMessage receive() throws InterruptedException;

    String providerName();
}
