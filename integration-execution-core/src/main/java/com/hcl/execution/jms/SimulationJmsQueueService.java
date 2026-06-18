package com.hcl.execution.jms;

import org.springframework.stereotype.Service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class SimulationJmsQueueService implements JmsQueueService {

    private final BlockingQueue<JmsMessage> queue = new LinkedBlockingQueue<>();

    @Override
    public void send(JmsMessage message) {
        queue.offer(message);
    }

    @Override
    public JmsMessage receive() throws InterruptedException {
        return queue.take();
    }

    @Override
    public String providerName() {
        return "SIMULATION";
    }
}
