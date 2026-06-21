package com.hcl.execution.soap;

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

@Service
public class SoapResponseValidator {

    public SoapValidationResult validate(
            int actualHttpStatus,
            int expectedHttpStatus,
            String responseBody,
            String statusXPath,
            String expectedStatusValues,
            boolean allowErrorStatus) {
        SoapValidationResult result = new SoapValidationResult();
        result.add("HTTP_STATUS", actualHttpStatus == expectedHttpStatus,
                "expected=" + expectedHttpStatus + " actual=" + actualHttpStatus);

        Document document = parse(responseBody, result);
        if (document != null && hasText(statusXPath)) {
            String statusValue = xpathValue(document, statusXPath, result);
            if (statusValue != null) {
                result.add("XPATH_STATUS", expectedValues(expectedStatusValues).contains(normalize(statusValue)),
                        "xpath=" + statusXPath + " value=" + value(statusValue));
            }
        }

        boolean errorFound = containsError(responseBody);
        result.add("ERROR_CHECK", allowErrorStatus || !errorFound,
                errorFound ? "Unexpected ERROR found in SOAP response" : "No unexpected ERROR found");
        return result;
    }

    private Document parse(String responseBody, SoapValidationResult result) {
        if (!hasText(responseBody)) {
            result.add("SOAP_VALID", false, "SOAP response body is empty");
            return null;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(responseBody)));
            result.add("SOAP_VALID", true, "Well-formed XML/SOAP response");
            return document;
        } catch (Exception e) {
            result.add("SOAP_VALID", false, "Invalid XML/SOAP response: " + e.getMessage());
            return null;
        }
    }

    private String xpathValue(Document document, String statusXPath, SoapValidationResult result) {
        try {
            String value = (String) XPathFactory.newInstance()
                    .newXPath()
                    .evaluate(statusXPath, document, XPathConstants.STRING);
            return hasText(value) ? value.trim() : "";
        } catch (Exception e) {
            result.add("XPATH_STATUS", false, "XPath evaluation failed: " + e.getMessage());
            return null;
        }
    }

    private List<String> expectedValues(String expectedStatusValues) {
        String configured = hasText(expectedStatusValues) ? expectedStatusValues : "SUCCESS";
        String[] parts = configured.split("[,;|]");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            if (hasText(part)) {
                values.add(normalize(part));
            }
        }
        if (values.isEmpty()) {
            values.add("SUCCESS");
        }
        return values;
    }

    private boolean containsError(String responseBody) {
        return responseBody != null && responseBody.toUpperCase(Locale.ROOT).contains("ERROR");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }
}
