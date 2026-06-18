package com.hcl.execution.trigger;

import com.hcl.execution.model.TestCase;

public interface TriggerService {
    void trigger(TestCase testCase) throws Exception;
}
