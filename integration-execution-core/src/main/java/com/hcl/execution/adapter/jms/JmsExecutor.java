package com.hcl.execution.adapter.jms;

import com.hcl.execution.adapter.ProtocolResultMapper;
import com.hcl.execution.adapter.TriggerAdapter;
import com.hcl.execution.adapter.TriggerResult;
import com.hcl.execution.core.ExecutionContext;
import com.hcl.execution.core.ExecutionMode;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.protocol.JmsExecutionService;
import org.springframework.stereotype.Service;

@Service
public class JmsExecutor implements TriggerAdapter {

    private final JmsExecutionService jmsExecutionService;

    public JmsExecutor(JmsExecutionService jmsExecutionService) {
        this.jmsExecutionService = jmsExecutionService;
    }

    @Override
    public FlowType flowType() {
        return FlowType.JMS;
    }

    @Override
    public TriggerResult trigger(ExecutionContext context) {
        boolean async = context.getExecutionMode() == ExecutionMode.ASYNC;
        return ProtocolResultMapper.from(flowType(), context.getExecutionMode(),
                jmsExecutionService.execute(context.getTestCase(), async));
    }
}
