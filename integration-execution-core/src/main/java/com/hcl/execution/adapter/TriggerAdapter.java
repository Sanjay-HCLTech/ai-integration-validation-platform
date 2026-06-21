package com.hcl.execution.adapter;

import com.hcl.execution.core.ExecutionContext;
import com.hcl.execution.core.FlowType;

public interface TriggerAdapter {

    FlowType flowType();

    TriggerResult trigger(ExecutionContext context) throws Exception;
}
