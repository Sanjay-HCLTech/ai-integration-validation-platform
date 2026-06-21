package com.hcl.execution.adapter;

import com.hcl.execution.core.FlowType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class TriggerAdapterRegistry {

    private final Map<FlowType, TriggerAdapter> adapters = new EnumMap<>(FlowType.class);

    public TriggerAdapterRegistry(List<TriggerAdapter> triggerAdapters) {
        if (triggerAdapters == null) {
            return;
        }
        for (TriggerAdapter adapter : triggerAdapters) {
            if (adapter != null) {
                adapters.put(adapter.flowType(), adapter);
            }
        }
    }

    public TriggerAdapter get(FlowType flowType) {
        TriggerAdapter adapter = adapters.get(flowType);
        if (adapter == null) {
            throw new IllegalArgumentException("No TriggerAdapter registered for FlowType=" + flowType);
        }
        return adapter;
    }
}
