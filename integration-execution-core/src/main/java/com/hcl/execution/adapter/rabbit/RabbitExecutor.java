package com.hcl.execution.adapter.rabbit;

import com.hcl.execution.adapter.ProtocolResultMapper;
import com.hcl.execution.adapter.TriggerAdapter;
import com.hcl.execution.adapter.TriggerResult;
import com.hcl.execution.core.ExecutionContext;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.protocol.RabbitExecutionService;
import org.springframework.stereotype.Service;

@Service
public class RabbitExecutor implements TriggerAdapter {

    private final RabbitExecutionService rabbitExecutionService;

    public RabbitExecutor(RabbitExecutionService rabbitExecutionService) {
        this.rabbitExecutionService = rabbitExecutionService;
    }

    @Override
    public FlowType flowType() {
        return FlowType.RABBIT;
    }

    @Override
    public TriggerResult trigger(ExecutionContext context) {
        return ProtocolResultMapper.from(flowType(), context.getExecutionMode(),
                rabbitExecutionService.execute(context.getTestCase()));
    }
}
