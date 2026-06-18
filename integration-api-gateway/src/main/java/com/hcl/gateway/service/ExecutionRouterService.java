package com.hcl.gateway.service;

import com.hcl.execution.model.ExecutionResult;
import com.hcl.execution.model.ScenarioCategory;
import com.hcl.execution.model.TestCase;
import com.hcl.execution.model.TestScenario;
import com.hcl.execution.model.TestStep;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExecutionRouterService {

    private final OrchestratorService orchestratorService;

    public ExecutionRouterService(OrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    public ExecutionResult execute(
            TestCase testCase,
            String category,
            String flow,
            String scenario,
            String mode) {
        if (isBlank(category)) {
            return orchestratorService.execute(testCase);
        }

        TestScenario testScenario = TestScenario.of(category, flow, scenario, mode);
        testCase.setExecutionMode(testScenario.getMode().name());
        if (testCase.getSteps() == null || testCase.getSteps().isEmpty()) {
            testCase.setSteps(singleStep(testScenario));
        }

        System.out.println("[ROUTER]");
        System.out.println("Category=" + testScenario.getCategory()
                + " Flow=" + testScenario.getFlow()
                + " Scenario=" + testScenario.getScenario()
                + " Mode=" + testScenario.getMode()
                + " Protocol=" + protocol(testScenario.getCategory()));

        return orchestratorService.execute(testCase);
    }

    private List<TestStep> singleStep(TestScenario scenario) {
        TestStep step = new TestStep();
        step.setStepName(stepName(scenario.getCategory()));
        step.setSystem(system(scenario.getCategory()));
        step.setEvent("REQUEST");

        List<TestStep> steps = new ArrayList<>();
        steps.add(step);
        return steps;
    }

    private String stepName(ScenarioCategory category) {
        switch (category) {
            case DATAHUB:
                return "DATAHUB JMS";
            case VRP:
                return "VRP SOAP";
            case NORDICS:
                return "Nordics RabbitMQ";
            case APIGEE:
                return "APIGEE REST JSON";
            default:
                throw new IllegalArgumentException("Unsupported category: " + category);
        }
    }

    private String system(ScenarioCategory category) {
        switch (category) {
            case DATAHUB:
                return "DATAHUB";
            case VRP:
                return "VRP";
            case NORDICS:
                return "NORDICS";
            case APIGEE:
                return "APIGEE";
            default:
                throw new IllegalArgumentException("Unsupported category: " + category);
        }
    }

    private String protocol(ScenarioCategory category) {
        switch (category) {
            case DATAHUB:
                return "JMS";
            case VRP:
                return "SOAP";
            case NORDICS:
                return "RABBITMQ";
            case APIGEE:
                return "REST";
            default:
                throw new IllegalArgumentException("Unsupported category: " + category);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
