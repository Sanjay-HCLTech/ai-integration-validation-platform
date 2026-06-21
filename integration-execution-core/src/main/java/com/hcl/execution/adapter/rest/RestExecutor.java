package com.hcl.execution.adapter.rest;

import com.hcl.execution.adapter.ProtocolResultMapper;
import com.hcl.execution.adapter.TriggerAdapter;
import com.hcl.execution.adapter.TriggerResult;
import com.hcl.execution.core.ExecutionContext;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.protocol.RestExecutionService;
import org.springframework.stereotype.Service;

@Service
public class RestExecutor implements TriggerAdapter {

    private final RestExecutionService restExecutionService;

    public RestExecutor(RestExecutionService restExecutionService) {
        this.restExecutionService = restExecutionService;
    }

    @Override
    public FlowType flowType() {
        return FlowType.REST;
    }

    @Override
    public TriggerResult trigger(ExecutionContext context) throws Exception {
        return ProtocolResultMapper.from(flowType(), context.getExecutionMode(),
                restExecutionService.execute(context.getTestCase()));
    }
}
