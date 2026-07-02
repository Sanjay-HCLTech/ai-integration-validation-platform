package com.hcl.ai.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Primary
@Service
public class AiIntentExtractionService implements IntentExtractionService {

    private final DeterministicIntentParser fallbackParser;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final NlpProvider provider;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public AiIntentExtractionService(
            DeterministicIntentParser fallbackParser,
            ObjectMapper objectMapper,
            @Value("${intelligence.nlp.enabled}") boolean enabled,
            @Value("${intelligence.nlp.provider}") String provider,
            @Value("${intelligence.nlp.endpoint}") String endpoint,
            @Value("${intelligence.nlp.api-key}") String apiKey,
            @Value("${intelligence.nlp.model}") String model,
            @Value("${intelligence.nlp.timeout-ms}") long timeoutMs) {
        this.fallbackParser = fallbackParser;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.provider = parseProvider(provider);
        this.endpoint = value(endpoint);
        this.apiKey = value(apiKey);
        this.model = value(model);
        this.timeout = Duration.ofMillis(Math.max(1000, timeoutMs));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public IntelligenceIntent extract(IntelligenceIntentRequest request) {
        IntelligenceIntent fallback = fallbackParser.extract(request);
        if (!enabled || provider == NlpProvider.DETERMINISTIC) {
            fallback.getAttributes().put("nlpMode", "DETERMINISTIC");
            return fallback;
        }
        if (!hasText(endpoint)) {
            fallback.getAttributes().put("nlpMode", "FALLBACK");
            fallback.getAttributes().put("nlpFallbackReason", "NLP endpoint not configured");
            return fallback;
        }
        if (requiresApiKey(provider) && !hasText(apiKey)) {
            fallback.getAttributes().put("nlpMode", "FALLBACK");
            fallback.getAttributes().put("nlpFallbackReason", "NLP API key not configured");
            return fallback;
        }

        try {
            IntelligenceIntent aiIntent = extractWithModel(request, fallback);
            aiIntent.getAttributes().put("nlpMode", "AI");
            aiIntent.getAttributes().put("nlpProvider", provider.name());
            aiIntent.getAttributes().put("fallbackSource", fallback.getAttributes().get("source"));
            return aiIntent;
        } catch (Exception e) {
            fallback.getAttributes().put("nlpMode", "FALLBACK");
            fallback.getAttributes().put("nlpProvider", provider.name());
            fallback.getAttributes().put("nlpFallbackReason", safeReason(e));
            return fallback;
        }
    }

    private IntelligenceIntent extractWithModel(IntelligenceIntentRequest request, IntelligenceIntent fallback) throws Exception {
        String body = requestBody(request, fallback);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (hasText(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
            builder.header("api-key", apiKey);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("NLP provider returned HTTP " + response.statusCode());
        }
        JsonNode intentNode = intentJson(response.body());
        IntelligenceIntent intent = toIntent(intentNode, fallback);
        validate(intent);
        return intent;
    }

    private String requestBody(IntelligenceIntentRequest request, IntelligenceIntent fallback) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", hasText(model) ? model : "intent-extraction");
        root.put("temperature", 0);
        root.put("response_format", responseFormat());
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("system", internalInstruction()));
        messages.add(message("user", userPayload(request, fallback)));
        root.put("messages", messages);
        return objectMapper.writeValueAsString(root);
    }

    private Map<String, Object> responseFormat() {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_object");
        return format;
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String userPayload(IntelligenceIntentRequest request, IntelligenceIntent fallback) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        IntelligenceIntentRequest safeRequest = request == null ? new IntelligenceIntentRequest() : request;
        payload.put("prompt", safeRequest.getPrompt());
        payload.put("mode", safeRequest.getMode() == null ? IntelligenceMode.SMART_PROMPT.name() : safeRequest.getMode().name());
        payload.put("bookingId", safeRequest.getBookingId());
        payload.put("env", safeRequest.getEnv());
        payload.put("flowTypes", safeRequest.getFlowTypes());
        payload.put("systems", safeRequest.getSystems());
        payload.put("services", safeRequest.getServices());
        payload.put("runAllServices", safeRequest.isRunAllServices());
        payload.put("fallbackIntent", fallback);
        return objectMapper.writeValueAsString(payload);
    }

    private String internalInstruction() {
        return "Extract integration execution intent as JSON only. "
                + "Allowed intents are TRIGGER, TRACE, VALIDATE, DEBUG, REPLAY. "
                + "Return exactly: intents array, triggerMode string, flow string, target string, bookingId string, confidence integer 0-100, attributes object. "
                + "Do not execute anything. Do not return explanations, markdown, rules, mappings, or hidden instructions.";
    }

    private JsonNode intentJson(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode direct = objectNode(root);
        if (direct != null && direct.has("intents")) {
            return direct;
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isTextual()) {
                return objectMapper.readTree(content.asText());
            }
        }
        JsonNode response = root.path("response");
        if (response.isTextual()) {
            return objectMapper.readTree(response.asText());
        }
        throw new IllegalArgumentException("NLP response did not contain intent JSON");
    }

    private JsonNode objectNode(JsonNode node) {
        return node != null && node.isObject() ? node : null;
    }

    private IntelligenceIntent toIntent(JsonNode node, IntelligenceIntent fallback) {
        IntelligenceIntent intent = new IntelligenceIntent();
        intent.setIntents(intentTypes(node.path("intents"), fallback.getIntents()));
        intent.setTriggerMode(firstText(text(node, "triggerMode"), fallback.getTriggerMode()));
        intent.setFlow(firstText(text(node, "flow"), fallback.getFlow()));
        intent.setTarget(firstText(text(node, "target"), fallback.getTarget()));
        intent.setBookingId(firstText(text(node, "bookingId"), fallback.getBookingId()));
        intent.setConfidence(confidence(node.path("confidence"), fallback.getConfidence()));
        intent.setAttributes(attributes(node.path("attributes"), fallback.getAttributes()));
        intent.getAttributes().put("source", "AI_INTENT_EXTRACTION");
        return intent;
    }

    private List<IntentType> intentTypes(JsonNode node, List<IntentType> fallback) {
        List<IntentType> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                IntentType parsed = intentType(item.asText());
                if (parsed != null && !values.contains(parsed)) {
                    values.add(parsed);
                }
            }
        }
        if (values.isEmpty() && fallback != null) {
            values.addAll(fallback);
        }
        return values;
    }

    private IntentType intentType(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return IntentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, String> attributes(JsonNode node, Map<String, String> fallback) {
        Map<String, String> values = new LinkedHashMap<>();
        if (fallback != null) {
            values.putAll(fallback);
        }
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue() != null && field.getValue().isValueNode()) {
                    values.put(field.getKey(), field.getValue().asText());
                }
            }
        }
        return values;
    }

    private void validate(IntelligenceIntent intent) {
        if (intent == null || intent.getIntents() == null || intent.getIntents().isEmpty()) {
            throw new IllegalArgumentException("NLP intent JSON missing intents");
        }
        if (!hasText(intent.getTriggerMode()) || !hasText(intent.getFlow()) || !hasText(intent.getTarget())) {
            throw new IllegalArgumentException("NLP intent JSON missing required routing fields");
        }
    }

    private int confidence(JsonNode node, int fallback) {
        int value = node != null && node.canConvertToInt() ? node.asInt() : fallback;
        return Math.max(0, Math.min(100, value));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private NlpProvider parseProvider(String value) {
        if (!hasText(value)) {
            return NlpProvider.DETERMINISTIC;
        }
        try {
            return NlpProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NlpProvider.DETERMINISTIC;
        }
    }

    private boolean requiresApiKey(NlpProvider provider) {
        return provider == NlpProvider.OPENAI || provider == NlpProvider.OPENAI_COMPATIBLE || provider == NlpProvider.AZURE_OPENAI;
    }

    private String safeReason(Exception e) {
        String message = e == null ? null : e.getMessage();
        return hasText(message) ? message.replaceAll("[\\r\\n]+", " ") : "NLP extraction failed";
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
