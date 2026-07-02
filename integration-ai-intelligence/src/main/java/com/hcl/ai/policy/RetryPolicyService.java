package com.hcl.ai.policy;

import com.hcl.ai.plan.ExecutionPlan;
import com.hcl.ai.report.IntelligenceExecutionSnapshot;
import org.springframework.stereotype.Service;

@Service
public class RetryPolicyService {

    public PolicyDecision evaluate(ExecutionPlan plan, IntelligenceExecutionSnapshot snapshot) {
        PolicyDecision decision = new PolicyDecision();
        if (snapshot == null || snapshot.getFail() <= 0) {
            decision.setRetryAllowed(false);
            decision.setReason("No failed execution rows require replay");
            decision.getControls().add("NO_RETRY_WHEN_PASS");
            return decision;
        }
        if (plan != null && plan.isReplaySafe()) {
            decision.setRetryAllowed(true);
            decision.setReason("Replay is allowed for failed asynchronous execution rows only");
            decision.getControls().add("FAILED_ROWS_ONLY");
            decision.getControls().add("ASYNC_FLOWS_ONLY");
            decision.getControls().add("TRACE_ENABLED");
            return decision;
        }
        decision.setRetryAllowed(false);
        decision.setReason("Replay blocked because one or more selected flows may not be idempotent");
        decision.getControls().add("MANUAL_REVIEW_REQUIRED");
        decision.getControls().add("NO_SYNC_REPLAY");
        return decision;
    }
}
