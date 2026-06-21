package com.hcl.execution.rest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RestResponseValidator {

    private final String expectedHttpStatusCodes;
    private final String expectedStatusValues;
    private final String statusJsonPath;
    private final String statusXPath;

    public RestResponseValidator(
            @Value("${rest.expected.http.status.codes:200,400,401,403,500}") String expectedHttpStatusCodes,
            @Value("${rest.expected.status.values:SUCCESS,ERROR}") String expectedStatusValues,
            @Value("${rest.status.json.path:status}") String statusJsonPath,
            @Value("${rest.status.xpath://*[translate(local-name(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')='status']/text()}") String statusXPath) {
        this.expectedHttpStatusCodes = expectedHttpStatusCodes;
        this.expectedStatusValues = expectedStatusValues;
        this.statusJsonPath = statusJsonPath;
        this.statusXPath = statusXPath;
    }

    public RestValidationResult validate(int httpStatus, String responseBody) {
        return validate(httpStatus, responseBody, null);
    }

    public RestValidationResult validate(int httpStatus, String responseBody, String successMarkers) {
        RestValidationResult result = new RestValidationResult();
        List<Integer> expectedCodes = expectedCodes();
        result.add("HTTP_CODE", expectedCodes.contains(httpStatus),
                "expectedOneOf=" + expectedCodes + " actual=" + httpStatus);

        boolean hasSuccessOrError = containsIgnoreCase(responseBody, "SUCCESS")
                || containsIgnoreCase(responseBody, "ERROR")
                || containsAnyMarker(responseBody, successMarkers);
        result.add("SUCCESS_ERROR_CHECK", hasSuccessOrError,
                hasSuccessOrError
                        ? "Response contains SUCCESS, ERROR, or configured success marker"
                        : "Response does not contain SUCCESS, ERROR, or configured success marker");

        String statusValue = looksLikeXml(responseBody)
                ? xpathValue(responseBody, statusXPath, result)
                : jsonFieldValue(responseBody, statusJsonPath);
        if (statusValue != null) {
            result.add("BUSINESS_STATUS", expectedValues().contains(normalize(statusValue)),
                    "field=" + statusJsonPath + " value=" + value(statusValue));
        }
        return result;
    }

    private boolean containsAnyMarker(String responseBody, String successMarkers) {
        if (responseBody == null || successMarkers == null || successMarkers.trim().isEmpty()) {
            return false;
        }
        String[] markers = successMarkers.split("[,;|]");
        for (String marker : markers) {
            if (marker != null && !marker.trim().isEmpty() && containsIgnoreCase(responseBody, marker.trim())) {
                return true;
            }
        }
        return false;
    }

    private List<Integer> expectedCodes() {
        List<Integer> codes = new ArrayList<>();
        String[] parts = expectedHttpStatusCodes == null ? new String[0] : expectedHttpStatusCodes.split("[,;|]");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            try {
                codes.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // Skip invalid config tokens rather than failing every validation.
            }
        }
        if (codes.isEmpty()) {
            codes.add(200);
        }
        return codes;
    }

    private List<String> expectedValues() {
        List<String> values = new ArrayList<>();
        String[] parts = expectedStatusValues == null ? new String[0] : expectedStatusValues.split("[,;|]");
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                values.add(normalize(part));
            }
        }
        if (values.isEmpty()) {
            values.add("SUCCESS");
            values.add("ERROR");
        }
        return values;
    }

    private String jsonFieldValue(String responseBody, String fieldName) {
        if (responseBody == null || fieldName == null || fieldName.trim().isEmpty()) {
            return null;
        }
        String field = fieldName.trim();
        int dot = field.lastIndexOf('.');
        if (dot >= 0 && dot < field.length() - 1) {
            field = field.substring(dot + 1);
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?([^\",}\\]\\s]+)\"?",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(responseBody);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String xpathValue(String responseBody, String xpath, RestValidationResult result) {
        if (responseBody == null || xpath == null || xpath.trim().isEmpty()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(responseBody)));
            String value = (String) XPathFactory.newInstance()
                    .newXPath()
                    .evaluate(xpath, document, XPathConstants.STRING);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            result.add("XML_STATUS", false, "XPath evaluation failed: " + e.getMessage());
            return null;
        }
    }

    private boolean looksLikeXml(String responseBody) {
        return responseBody != null && responseBody.trim().startsWith("<");
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null
                && expected != null
                && value.toUpperCase(Locale.ROOT).contains(expected.toUpperCase(Locale.ROOT));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value.trim();
    }
}
