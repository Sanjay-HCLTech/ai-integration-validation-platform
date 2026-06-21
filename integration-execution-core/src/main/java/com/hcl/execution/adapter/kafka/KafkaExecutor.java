package com.hcl.execution.adapter.kafka;

import com.hcl.execution.adapter.ProtocolResultMapper;
import com.hcl.execution.adapter.TriggerAdapter;
import com.hcl.execution.adapter.TriggerResult;
import com.hcl.execution.core.ExecutionContext;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.protocol.KafkaExecutionService;
import org.springframework.stereotype.Service;

@Service
public class KafkaExecutor implements TriggerAdapter {

    private final KafkaExecutionService kafkaExecutionService;

    public KafkaExecutor(KafkaExecutionService kafkaExecutionService) {
        this.kafkaExecutionService = kafkaExecutionService;
    }

    @Override
    public FlowType flowType() {
        return FlowType.KAFKA;
    }

    @Override
    public TriggerResult trigger(ExecutionContext context) {
        return ProtocolResultMapper.from(flowType(), context.getExecutionMode(),
                kafkaExecutionService.execute(context.getTestCase()));
    }
}
