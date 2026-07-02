package com.hcl.ai.intent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeterministicIntentParser implements IntentExtractionService {

    private static final Pattern BOOKING_PATTERN =
            Pattern.compile("\\b(?:BK)?\\d{6,12}\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public IntelligenceIntent extract(IntelligenceIntentRequest request) {
        IntelligenceIntent intent = new IntelligenceIntent();
        IntelligenceIntentRequest safeRequest = request == null ? new IntelligenceIntentRequest() : request;
        String prompt = value(safeRequest.getPrompt());
        String upperPrompt = prompt.toUpperCase(Locale.ROOT);

        intent.setIntents(intents(upperPrompt, safeRequest.getMode()));
        intent.setTriggerMode(firstText(firstValue(safeRequest.getFlowTypes()), triggerMode(upperPrompt)));
        intent.setFlow(firstText(firstValue(safeRequest.getSystems()), flow(upperPrompt)));
        intent.setTarget(target(upperPrompt));
        intent.setBookingId(bookingId(safeRequest, prompt));
        intent.setConfidence(confidence(intent, prompt, safeRequest));
        intent.getAttributes().put("mode", safeRequest.getMode() == null ? "SMART_PROMPT" : safeRequest.getMode().name());
        intent.getAttributes().put("source", "DETERMINISTIC_INTENT_PARSER");
        return intent;
    }

    private List<IntentType> intents(String prompt, IntelligenceMode mode) {
        List<IntentType> intents = new ArrayList<>();
        if (mode == IntelligenceMode.BOOKING_ID) {
            intents.add(IntentType.TRACE);
            intents.add(IntentType.VALIDATE);
            return intents;
        }
        addIntent(intents, prompt.contains("TRIGGER") || prompt.contains("PUBLISH") || prompt.contains("SEND"), IntentType.TRIGGER);
        addIntent(intents, prompt.contains("TRACE") || prompt.contains("TRACK") || prompt.contains("TILL"), IntentType.TRACE);
        addIntent(intents, prompt.contains("VALIDATE") || prompt.contains("VERIFY") || prompt.contains("CHECK"), IntentType.VALIDATE);
        addIntent(intents, prompt.contains("DEBUG") || prompt.contains("RCA") || prompt.contains("ROOT CAUSE"), IntentType.DEBUG);
        addIntent(intents, prompt.contains("REPLAY") || prompt.contains("RE-RUN") || prompt.contains("RERUN"), IntentType.REPLAY);
        if (intents.isEmpty()) {
            intents.add(IntentType.TRACE);
            intents.add(IntentType.VALIDATE);
        }
        return intents;
    }

    private void addIntent(List<IntentType> intents, boolean condition, IntentType intent) {
        if (condition && !intents.contains(intent)) {
            intents.add(intent);
        }
    }

    private String triggerMode(String prompt) {
        if (prompt.contains("JMS")) {
            return "JMS";
        }
        if (prompt.contains("KAFKA")) {
            return "KAFKA";
        }
        if (prompt.contains("RABBIT")) {
            return "RABBIT";
        }
        if (prompt.contains("SOAP") || prompt.contains("VRP")) {
            return "SOAP";
        }
        if (prompt.contains("REST") || prompt.contains("APIGEE")) {
            return "REST";
        }
        return "JMS";
    }

    private String flow(String prompt) {
        if (prompt.contains("DMS")) {
            return "DMS";
        }
        if (prompt.contains("SAP")) {
            return "SAP";
        }
        if (prompt.contains("AO") || prompt.contains("OFFER")) {
            return "AO";
        }
        if (prompt.contains("GIP")) {
            return "GIP";
        }
        if (prompt.contains("C4C")) {
            return "C4C";
        }
        if (prompt.contains("MONGO") || prompt.contains("TDA")) {
            return "TDA";
        }
        return "DMS";
    }

    private String target(String prompt) {
        if (prompt.contains("MONGO") || prompt.contains("TDA")) {
            return "MongoTDA";
        }
        if (prompt.contains("APIGEE") || prompt.contains("OFFER")) {
            return "APIGEE";
        }
        if (prompt.contains("SAP")) {
            return "SAP";
        }
        if (prompt.contains("GIP")) {
            return "GIP";
        }
        if (prompt.contains("C4C")) {
            return "C4C";
        }
        if (prompt.contains("DMS")) {
            return "DMS";
        }
        return "DMS";
    }

    private String bookingId(String prompt) {
        Matcher matcher = BOOKING_PATTERN.matcher(value(prompt));
        return matcher.find() ? matcher.group() : null;
    }

    private String bookingId(IntelligenceIntentRequest request, String prompt) {
        String promptBookingId = bookingId(prompt);
        if (request != null && request.getMode() == IntelligenceMode.SMART_PROMPT && hasText(promptBookingId)) {
            return promptBookingId;
        }
        return firstText(request == null ? null : request.getBookingId(), promptBookingId);
    }

    private int confidence(IntelligenceIntent intent, String prompt, IntelligenceIntentRequest request) {
        int score = 65;
        if (hasText(prompt)) {
            score += 10;
        }
        if (hasText(intent.getTriggerMode())) {
            score += 8;
        }
        if (hasText(intent.getFlow())) {
            score += 7;
        }
        if (hasText(intent.getBookingId())) {
            score += 5;
        }
        if (request != null && request.getMode() == IntelligenceMode.GUIDED) {
            score = Math.max(score, 92);
        }
        return Math.min(96, score);
    }

    private String firstValue(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : fallback;
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
