package com.hcl.execution.trigger;

import com.hcl.execution.jms.JmsFlowConfig;
import com.hcl.execution.jms.JmsPayloadResolver;
import com.hcl.execution.jms.JmsProcessingResult;
import com.hcl.execution.jms.JmsPublishRequest;
import com.hcl.execution.jms.JmsProducerService;
import com.hcl.execution.model.TestCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service("emsTriggerService")
public class EmsTriggerService implements TriggerService {

    private final JmsProducerService jmsProducerService;
    private final JmsFlowConfig flowConfig;
    private final JmsPayloadResolver payloadResolver;

    @Value("${system.jms.enabled}")
    private boolean enabled;

    @Value("${jms.default.async}")
    private boolean defaultAsync;

    public EmsTriggerService(
            JmsProducerService jmsProducerService,
            JmsFlowConfig flowConfig,
            JmsPayloadResolver payloadResolver) {
        this.jmsProducerService = jmsProducerService;
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    @Override
    public void trigger(TestCase testCase) {
        if (!enabled) {
            throw new RuntimeException("JMS trigger is disabled by system.jms.enabled=false");
        }

        boolean async = requestedAsync(testCase);
        JmsPayloadResolver.ResolvedJmsPayload resolvedPayload = payloadResolver.resolve(testCase);
        String env = flowConfig.env(testCase);
        String system = resolvedPayload.getSystem();

        JmsPublishRequest request = new JmsPublishRequest();
        request.setBookingId(testCase == null ? null : testCase.getBookingId());
        request.setPayload(resolvedPayload.getContent());
        request.setSourceSystem(system);
        request.setAsync(async);
        request.setEnv(env);
        request.setDestinationType(flowConfig.destinationType(env, system, scenario(testCase)));
        request.setDestinationName(flowConfig.destinationName(env, system, scenario(testCase)));
        request.setMessageType(flowConfig.messageType(env, system, scenario(testCase)));
        request.setPayloadSource(resolvedPayload.getSource());

        JmsProcessingResult result = jmsProducerService.send(request);
        if (result == null || (!"SUCCESS".equalsIgnoreCase(result.getStatus())
                && !"QUEUED".equalsIgnoreCase(result.getStatus()))) {
            throw new RuntimeException("JMS trigger failed: "
                    + (result == null ? "No result" : result.getMessage()));
        }
    }

    private boolean requestedAsync(TestCase testCase) {
        if (testCase == null || testCase.getExecutionMode() == null
                || testCase.getExecutionMode().trim().isEmpty()) {
            return defaultAsync;
        }
        return "ASYNC".equalsIgnoreCase(testCase.getExecutionMode().trim());
    }

    private String scenario(TestCase testCase) {
        return testCase == null ? null : testCase.getScenario();
    }
}
