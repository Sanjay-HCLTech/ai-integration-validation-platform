package com.hcl.execution.protocol;

import com.hcl.execution.jms.JmsProcessingResult;
import com.hcl.execution.jms.JmsProducerService;
import com.hcl.execution.model.TestCase;
import org.springframework.stereotype.Service;

@Service
public class JmsExecutionService {

    private final JmsProducerService jmsProducerService;

    public JmsExecutionService(JmsProducerService jmsProducerService) {
        this.jmsProducerService = jmsProducerService;
    }

    public ProtocolExecutionResult execute(TestCase testCase, boolean async) {
        long startNanos = System.nanoTime();
        JmsProcessingResult jmsResult = jmsProducerService.send(
                testCase.getBookingId(),
                testCase.getPayload() == null ? "{}" : testCase.getPayload(),
                "DATAHUB",
                async);

        ProtocolExecutionResult result = base("JMS", async ? "ASYNC" : "SYNC", elapsedMs(startNanos));
        if (jmsResult != null) {
            result.setStatus(jmsResult.getStatus());
            result.setJobId(jmsResult.getJobId());
            result.setCorrId(jmsResult.getCorrId());
            result.setMessage(jmsResult.getMessage());
        } else {
            result.setStatus("ERROR");
            result.setMessage("JMS execution returned no result");
        }
        log(result);
        return result;
    }

    private ProtocolExecutionResult base(String protocol, String mode, long latencyMs) {
        ProtocolExecutionResult result = new ProtocolExecutionResult();
        result.setProtocol(protocol);
        result.setMode(mode);
        result.setLatencyMs(latencyMs);
        return result;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void log(ProtocolExecutionResult result) {
        System.out.println("[EXECUTION]");
        System.out.println("Protocol=" + result.getProtocol()
                + " Mode=" + result.getMode()
                + " Status=" + result.getStatus()
                + " LatencyMs=" + result.getLatencyMs());
    }
}
