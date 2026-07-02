package com.hcl.ai.plan;

import com.hcl.ai.intent.IntelligenceIntent;
import com.hcl.ai.intent.IntelligenceIntentRequest;
import com.hcl.ai.intent.IntelligenceMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class ExecutionPlanBuilder {

    private final FlowTemplateRegistry templateRegistry;

    public ExecutionPlanBuilder(FlowTemplateRegistry templateRegistry) {
        this.templateRegistry = templateRegistry;
    }

    public ExecutionPlan build(IntelligenceIntent intent, IntelligenceIntentRequest request) {
        IntelligenceIntent safeIntent = intent == null ? new IntelligenceIntent() : intent;
        IntelligenceIntentRequest safeRequest = request == null ? new IntelligenceIntentRequest() : request;
        FlowTemplate template = templateRegistry.resolve(
                safeIntent.getFlow(), safeIntent.getTriggerMode(), safeIntent.getTarget());

        ExecutionPlan plan = new ExecutionPlan();
        plan.setEnv(firstText(safeRequest.getEnv(), "ST5"));
        plan.setFlowTypes(singleWhenScoped(values(safeRequest.getFlowTypes(), values(template.getFlowTypes(), singleton(firstText(safeIntent.getTriggerMode(), "JMS")))), safeRequest));
        plan.setSystems(singleWhenScoped(values(safeRequest.getSystems(), values(template.getSystems(), templateRegistry.systemsFor(safeIntent.getFlow(), safeIntent.getTarget()))), safeRequest));
        plan.setServices(services(safeRequest, template, safeIntent));
        plan.setPayloadMode(firstText(safeRequest.getPayloadMode(), "AUTO"));
        plan.setBookingId(bookingId(safeRequest, safeIntent));
        plan.setParallel(safeRequest.isParallel());
        plan.setTraceEnabled(safeRequest.isTraceEnabled());
        plan.setReplaySafe(template.isReplaySafe() && isReplaySafe(plan));
        plan.setTemplateId(template.getTemplateId());
        plan.setTemplateName(template.getName());
        plan.setDownstreamTargets(values(template.getDownstreamTargets(), Collections.emptyList()));
        plan.setSummary("Execute " + String.join(",", plan.getFlowTypes())
                + " for " + String.join(",", plan.getSystems())
                + " using " + String.join(",", plan.getServices())
                + " via template " + firstText(plan.getTemplateName(), plan.getTemplateId()));
        return plan;
    }

    private List<String> singleWhenScoped(List<String> values, IntelligenceIntentRequest request) {
        if (request.isRunAllServices() || values == null || values.size() <= 1) {
            return values;
        }
        return singleton(values.get(0));
    }

    private List<String> services(IntelligenceIntentRequest request, FlowTemplate template, IntelligenceIntent intent) {
        List<String> requestedServices = values(request.getServices(), Collections.emptyList());
        if (!requestedServices.isEmpty()) {
            return requestedServices;
        }
        String promptService = serviceFromPrompt(request.getPrompt());
        if (hasText(promptService)) {
            return singleton(promptService);
        }
        List<String> templateServices = values(template.getServices(), templateRegistry.servicesFor(intent.getFlow(), intent.getTarget()));
        if (request.isRunAllServices() || templateServices.size() <= 1) {
            return templateServices;
        }
        return singleton(templateServices.get(0));
    }

    private String serviceFromPrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("FLIGHTOFFERS") || normalized.contains("FLIGHT OFFERS")) {
            return "FlightOffers";
        }
        if (normalized.contains("PACKAGEOFFERS") || normalized.contains("PACKAGE OFFERS") || normalized.contains("PACKAGEOFFER")) {
            return "PackageOffers";
        }
        if (normalized.contains("ACCOMOFFERS") || normalized.contains("ACCOM OFFERS")) {
            return "AccomOffers";
        }
        if (normalized.contains("CRUISEOFFERS") || normalized.contains("CRUISE OFFERS")) {
            return "CruiseOffers";
        }
        if (normalized.contains("RESERVATIONEVENT") || normalized.contains("RESERVATION EVENT") || normalized.contains("NORDICS")) {
            return "ReservationEvent_v3";
        }
        return null;
    }

    private boolean isReplaySafe(ExecutionPlan plan) {
        if (plan == null || plan.getFlowTypes() == null) {
            return false;
        }
        for (String flowType : plan.getFlowTypes()) {
            String value = flowType == null ? "" : flowType.trim().toUpperCase();
            if ("REST".equals(value) || "SOAP".equals(value)) {
                return false;
            }
        }
        return true;
    }

    private String bookingId(IntelligenceIntentRequest request, IntelligenceIntent intent) {
        String intentBookingId = intent == null ? null : intent.getBookingId();
        if (request != null && request.getMode() == IntelligenceMode.SMART_PROMPT && hasText(intentBookingId)) {
            return intentBookingId;
        }
        return firstText(request == null ? null : request.getBookingId(), intentBookingId);
    }

    private List<String> values(List<String> values, List<String> fallback) {
        List<String> resolved = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (hasText(value)) {
                    resolved.add(value.trim());
                }
            }
        }
        return resolved.isEmpty() ? fallback : resolved;
    }

    private List<String> singleton(String value) {
        return Collections.singletonList(firstText(value, "JMS"));
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
