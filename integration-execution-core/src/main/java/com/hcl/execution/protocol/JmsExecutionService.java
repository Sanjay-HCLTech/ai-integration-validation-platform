package com.hcl.execution.protocol;

import com.hcl.execution.jms.JmsFlowConfig;
import com.hcl.execution.jms.JmsPayloadResolver;
import com.hcl.execution.jms.JmsPublishRequest;
import com.hcl.execution.jms.JmsProcessingResult;
import com.hcl.execution.jms.JmsProducerService;
import com.hcl.execution.model.TestCase;
import org.springframework.stereotype.Service;

@Service
public class JmsExecutionService {

    private final JmsProducerService jmsProducerService;
    private final JmsFlowConfig flowConfig;
    private final JmsPayloadResolver payloadResolver;

    public JmsExecutionService(
            JmsProducerService jmsProducerService,
            JmsFlowConfig flowConfig,
            JmsPayloadResolver payloadResolver) {
        this.jmsProducerService = jmsProducerService;
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    public ProtocolExecutionResult execute(TestCase testCase, boolean async) {
        long startNanos = System.nanoTime();
        String env = flowConfig.env(testCase);
        String requestedSystem = flowConfig.system(testCase);
        String scenario = testCase == null ? null : testCase.getScenario();
        String destinationType = flowConfig.destinationType(env, requestedSystem, scenario);
        String destinationName = flowConfig.destinationName(env, requestedSystem, scenario);
        String configuredPayload = flowConfig.payloadFile(testCase);
        String payloadSource = value(configuredPayload);

        try {
            JmsPayloadResolver.ResolvedJmsPayload resolvedPayload = payloadResolver.resolve(testCase);
            String system = resolvedPayload.getSystem();
            destinationType = flowConfig.destinationType(env, system, scenario);
            destinationName = flowConfig.destinationName(env, system, scenario);
            String messageType = flowConfig.messageType(env, system, scenario);
            payloadSource = resolvedPayload.getSource();

            JmsPublishRequest request = new JmsPublishRequest();
            request.setBookingId(testCase == null ? null : testCase.getBookingId());
            request.setPayload(resolvedPayload.getContent());
            request.setSourceSystem(system);
            request.setAsync(async);
            request.setEnv(env);
            request.setDestinationType(destinationType);
            request.setDestinationName(destinationName);
            request.setMessageType(messageType);
            request.setPayloadSource(payloadSource);

            JmsProcessingResult jmsResult = jmsProducerService.send(request);

            ProtocolExecutionResult result = base("JMS", async ? "ASYNC" : "SYNC", elapsedMs(startNanos));
            if (jmsResult != null) {
                result.setStatus(jmsResult.getStatus());
                result.setJobId(jmsResult.getJobId());
                result.setCorrId(jmsResult.getCorrId());
                result.setMessage(jmsResult.getMessage());
                result.setPayloadSource(payloadSource);
                result.setEndpointOrDestination(destinationLabel(destinationType) + "[" + destinationName + "]");
                if (isSuccess(jmsResult.getStatus())) {
                    result.setProcessStatus(async ? "ASYNC_VALIDATION_PENDING" : "PASS");
                    result.setDownstreamStatus(async ? "ASYNC_VALIDATION_PENDING" : "SYNC_RESPONSE_VALIDATED");
                    result.setErrorFound("NO");
                    result.setValidationComplete(!async);
                } else {
                    result.setProcessStatus("FAIL");
                    result.setDownstreamStatus("FAILED");
                    result.setErrorFound("YES");
                    result.setValidationComplete(true);
                }
            } else {
                result.setStatus("ERROR");
                result.setMessage("JMS execution returned no result");
                result.setPayloadSource(payloadSource);
                result.setEndpointOrDestination(destinationLabel(destinationType) + "[" + destinationName + "]");
                result.setProcessStatus("FAIL");
                result.setDownstreamStatus("FAILED");
                result.setErrorFound("YES");
                result.setValidationComplete(true);
            }
            printJmsExecution(testCase, env, system, destinationType, destinationName, payloadSource, result);
            return result;
        } catch (RuntimeException e) {
            ProtocolExecutionResult result = base("JMS", async ? "ASYNC" : "SYNC", elapsedMs(startNanos));
            result.setStatus("ERROR");
            result.setMessage(e.getMessage());
            result.setPayloadSource(payloadSource);
            result.setEndpointOrDestination(destinationLabel(destinationType) + "[" + destinationName + "]");
            result.setProcessStatus("FAIL");
            result.setDownstreamStatus("FAILED");
            result.setErrorFound("YES");
            result.setValidationComplete(true);
            printJmsExecution(testCase, env, requestedSystem, destinationType, destinationName, payloadSource, result);
            return result;
        }
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

    private void printJmsExecution(
            TestCase testCase,
            String env,
            String system,
            String destinationType,
            String destinationName,
            String payloadSource,
            ProtocolExecutionResult result) {
        System.out.println();
        System.out.println("[JMS_EXEC]");
        System.out.println("Env=" + value(env));
        System.out.println("System=" + value(system));
        System.out.println("Protocol=JMS");
        System.out.println(destinationLabel(destinationType) + "=" + value(destinationName));
        System.out.println("PayloadFile=" + value(payloadSource));
        System.out.println("CorrID=" + value(result == null ? null : result.getCorrId()));
        System.out.println("TimeMs=" + (result == null ? 0 : result.getLatencyMs()));
        System.out.println();
        System.out.println("[TRACE]");
        System.out.println("BookingID=" + value(testCase == null ? null : testCase.getBookingId()));
        System.out.println("JobID=" + value(result == null ? null : result.getJobId()));
        System.out.println("CorrID=" + value(result == null ? null : result.getCorrId()));
        System.out.println();
        System.out.println("[ASSERT]");
        boolean success = result != null && isSuccess(result.getStatus());
        boolean async = result != null && "ASYNC".equalsIgnoreCase(result.getMode());
        System.out.println("JMS_PUBLISH=" + (success ? "SUCCESS" : "FAIL"));
        System.out.println("GEMS_TRIGGER=" + (success ? async ? "ASYNC_VALIDATION_PENDING" : "PASS" : "FAIL"));
        System.out.println("DATAHUB_PROCESS=" + (success ? async ? "ASYNC_VALIDATION_PENDING" : "PASS" : "FAIL"));
        System.out.println("DOWNSTREAM_STATUS=" + (success ? async ? "ASYNC_VALIDATION_PENDING" : "SUCCESS" : "FAILED"));
        System.out.println("ERROR_FOUND=" + (success ? "NO" : "YES"));
        System.out.println();
        System.out.println("[RESULT]");
        System.out.println("Status=" + (success ? async ? "TRIGGER_SUCCESS_ASYNC_VALIDATION_PENDING" : "PASS" : "FAIL"));
    }

    private boolean isSuccess(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "QUEUED".equalsIgnoreCase(status);
    }

    private String destinationLabel(String destinationType) {
        return "TOPIC".equalsIgnoreCase(destinationType) ? "Topic" : "Queue";
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value.trim();
    }
}
