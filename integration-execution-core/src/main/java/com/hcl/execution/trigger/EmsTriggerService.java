package com.hcl.execution.trigger;

import com.hcl.execution.jms.JmsProcessingResult;
import com.hcl.execution.jms.JmsProducerService;
import com.hcl.execution.model.TestCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service("emsTriggerService")
public class EmsTriggerService implements TriggerService {

    private final JmsProducerService jmsProducerService;

    @Value("${system.jms.enabled:true}")
    private boolean enabled;

    @Value("${jms.default.async:false}")
    private boolean defaultAsync;

    public EmsTriggerService(JmsProducerService jmsProducerService) {
        this.jmsProducerService = jmsProducerService;
    }

    @Override
    public void trigger(TestCase testCase) {
        if (!enabled) {
            throw new RuntimeException("JMS trigger is disabled by system.jms.enabled=false");
        }

        String payload = testCase.getPayload() == null ? "{}" : testCase.getPayload();
        boolean async = requestedAsync(testCase);
        JmsProcessingResult result = jmsProducerService.send(
                testCase.getBookingId(),
                payload,
                "DATAHUB",
                async);
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
}
