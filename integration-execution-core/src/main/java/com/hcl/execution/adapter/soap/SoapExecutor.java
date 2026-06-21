package com.hcl.execution.adapter.soap;

import com.hcl.execution.adapter.ProtocolResultMapper;
import com.hcl.execution.adapter.TriggerAdapter;
import com.hcl.execution.adapter.TriggerResult;
import com.hcl.execution.core.ExecutionContext;
import com.hcl.execution.core.ExecutionMode;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.protocol.SoapExecutionService;
import org.springframework.stereotype.Service;

@Service
public class SoapExecutor implements TriggerAdapter {

    private final SoapExecutionService soapExecutionService;

    public SoapExecutor(SoapExecutionService soapExecutionService) {
        this.soapExecutionService = soapExecutionService;
    }

    @Override
    public FlowType flowType() {
        return FlowType.SOAP;
    }

    @Override
    public TriggerResult trigger(ExecutionContext context) throws Exception {
        ExecutionMode mode = context.getExecutionMode();
        return ProtocolResultMapper.from(flowType(), mode,
                soapExecutionService.execute(context.getTestCase(), mode.name()));
    }
}
