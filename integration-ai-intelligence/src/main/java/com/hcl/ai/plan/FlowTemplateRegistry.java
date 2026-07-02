package com.hcl.ai.plan;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FlowTemplateRegistry {

    private final Map<String, FlowTemplate> templates = new LinkedHashMap<>();

    public FlowTemplateRegistry() {
        register(template("DMS_JMS_MONGO", "DMS JMS to Mongo persistence",
                list("DMS", "TDA"), list("JMS"), list("BookingDetails", "SubscribeBookingDetails"),
                list("MongoTDA"), true));
        register(template("DMS_RABBIT_NORDICS", "DMS Rabbit Nordics reservation event",
                list("DMS"), list("RABBIT"), list("ReservationEvent_v3"),
                list("NordicsAudit"), true));
        register(template("DMS_KAFKA_DATAHUB", "DMS Kafka to DataHub",
                list("DMS", "TDA"), list("KAFKA"), list("BookingDetails", "ReservationEvent_v3"),
                list("DataHub", "MongoTDA"), true));
        register(template("SAP_JMS_DMS", "SAP JMS booking propagation",
                list("SAP", "DMS"), list("JMS"), list("BookingDetails", "SubscribeBookingDetails"),
                list("DMS"), true));
        register(template("SAP_SOAP_BOOKING", "SAP SOAP booking service",
                list("SAP"), list("SOAP"), list("DisplayBooking_v3", "InfoBooking_v3", "CreateBooking_v3"),
                list("SAP"), false));
        register(template("AO_REST_OFFERS", "AO REST offer validation",
                list("AO"), list("REST"), list("AccomOffers", "FlightOffers", "PackageOffers", "CruiseOffers"),
                list("APIGEE"), false));
        register(template("TDA_JMS_MONGO", "TDA JMS Mongo persistence",
                list("TDA"), list("JMS"), list("BookingDetails", "SubscribeBookingDetails"),
                list("MongoTDA"), true));
        register(template("GIP_SOAP_BOOKING", "GIP SOAP booking service",
                list("GIP"), list("SOAP"), list("DisplayBooking_v3", "InfoBooking_v3"),
                list("GIP"), false));
        register(template("C4C_REST_CUSTOMER", "C4C REST customer integration",
                list("C4C"), list("REST"), list("BookingDetails"),
                list("C4C"), false));
    }

    public FlowTemplate resolve(String flow, String triggerMode, String target) {
        String normalizedFlow = normalize(flow);
        String normalizedTrigger = normalize(triggerMode);
        String normalizedTarget = normalize(target);
        FlowTemplate exact = findExact(normalizedFlow, normalizedTrigger, normalizedTarget);
        if (exact != null) {
            return copy(exact);
        }
        FlowTemplate byTarget = findByTarget(normalizedTarget, normalizedTrigger);
        if (byTarget != null) {
            return copy(byTarget);
        }
        FlowTemplate byFlow = findByFlow(normalizedFlow, normalizedTrigger);
        if (byFlow != null) {
            return copy(byFlow);
        }
        return copy(templates.get("DMS_JMS_MONGO"));
    }

    public List<String> servicesFor(String flow, String target) {
        return resolve(flow, null, target).getServices();
    }

    public List<String> systemsFor(String flow, String target) {
        return resolve(flow, null, target).getSystems();
    }

    public List<String> supportedFlowTypes() {
        return Arrays.asList("JMS", "RABBIT", "KAFKA", "REST", "SOAP");
    }

    public List<FlowTemplate> templates() {
        List<FlowTemplate> values = new ArrayList<>();
        for (FlowTemplate template : templates.values()) {
            values.add(copy(template));
        }
        return values;
    }

    private FlowTemplate findExact(String flow, String triggerMode, String target) {
        for (FlowTemplate template : templates.values()) {
            if (matchesFlow(template, flow) && matchesTrigger(template, triggerMode) && matchesTarget(template, target)) {
                return template;
            }
        }
        return null;
    }

    private FlowTemplate findByTarget(String target, String triggerMode) {
        if (!hasText(target)) {
            return null;
        }
        for (FlowTemplate template : templates.values()) {
            if (matchesTarget(template, target) && matchesTrigger(template, triggerMode)) {
                return template;
            }
        }
        return null;
    }

    private FlowTemplate findByFlow(String flow, String triggerMode) {
        if (!hasText(flow)) {
            return null;
        }
        for (FlowTemplate template : templates.values()) {
            if (matchesFlow(template, flow) && matchesTrigger(template, triggerMode)) {
                return template;
            }
        }
        return null;
    }

    private boolean matchesFlow(FlowTemplate template, String flow) {
        return !hasText(flow) || contains(template.getSystems(), flow) || contains(template.getTemplateId(), flow);
    }

    private boolean matchesTrigger(FlowTemplate template, String triggerMode) {
        return !hasText(triggerMode) || contains(template.getFlowTypes(), triggerMode);
    }

    private boolean matchesTarget(FlowTemplate template, String target) {
        return !hasText(target)
                || contains(template.getDownstreamTargets(), target)
                || contains(template.getSystems(), target)
                || mongoAlias(template, target);
    }

    private boolean mongoAlias(FlowTemplate template, String target) {
        return (target.contains("MONGO") || target.contains("TDA"))
                && (contains(template.getDownstreamTargets(), "MONGOTDA") || contains(template.getSystems(), "TDA"));
    }

    private void register(FlowTemplate template) {
        templates.put(template.getTemplateId(), template);
    }

    private FlowTemplate template(
            String id,
            String name,
            List<String> systems,
            List<String> flowTypes,
            List<String> services,
            List<String> downstreamTargets,
            boolean replaySafe) {
        FlowTemplate template = new FlowTemplate();
        template.setTemplateId(id);
        template.setName(name);
        template.setSystems(systems);
        template.setFlowTypes(flowTypes);
        template.setServices(services);
        template.setDownstreamTargets(downstreamTargets);
        template.setReplaySafe(replaySafe);
        return template;
    }

    private FlowTemplate copy(FlowTemplate source) {
        FlowTemplate copy = new FlowTemplate();
        if (source == null) {
            return copy;
        }
        copy.setTemplateId(source.getTemplateId());
        copy.setName(source.getName());
        copy.setSystems(new ArrayList<>(source.getSystems()));
        copy.setFlowTypes(new ArrayList<>(source.getFlowTypes()));
        copy.setServices(new ArrayList<>(source.getServices()));
        copy.setDownstreamTargets(new ArrayList<>(source.getDownstreamTargets()));
        copy.setReplaySafe(source.isReplaySafe());
        return copy;
    }

    private List<String> list(String... values) {
        return values == null ? Collections.emptyList() : new ArrayList<>(Arrays.asList(values));
    }

    private boolean contains(List<String> values, String expected) {
        if (values == null || !hasText(expected)) {
            return false;
        }
        String normalizedExpected = normalize(expected);
        for (String value : values) {
            if (normalize(value).equals(normalizedExpected)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(String value, String expected) {
        return hasText(value) && hasText(expected) && normalize(value).contains(normalize(expected));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
