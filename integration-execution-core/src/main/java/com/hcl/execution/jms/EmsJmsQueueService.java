package com.hcl.execution.jms;

/*
 * Phase-2 adapter seam.
 * Replace SimulationJmsQueueService with this implementation when TIBCO EMS
 * libraries are introduced. Producer and consumer code should not change.
 */
public class EmsJmsQueueService implements JmsQueueService {

    private final EmsQueueProperties properties;

    public EmsJmsQueueService(EmsQueueProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(JmsMessage message) {
        throw new UnsupportedOperationException("TIBCO EMS sender is not enabled in Phase-1 simulation");
    }

    @Override
    public JmsMessage receive() {
        throw new UnsupportedOperationException("TIBCO EMS receiver is not enabled in Phase-1 simulation");
    }

    @Override
    public String providerName() {
        return "EMS:" + properties.getHost() + ":" + properties.getPort();
    }
}
