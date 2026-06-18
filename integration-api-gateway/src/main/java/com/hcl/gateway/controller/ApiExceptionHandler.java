package com.hcl.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception exception, WebRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ERROR");
        body.put("httpStatus", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("reason", oneLine(rootMessage(exception)));
        body.put("path", requestPath(request));
        body.put("timestamp", ISO_FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC)));
        body.put("action", "Check gateway-server.out.log and gateway-server.err.log for the matching EXECUTION END Status=ERROR block.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "Unexpected server error";
        }

        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().trim().isEmpty()
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private String requestPath(WebRequest request) {
        if (request instanceof ServletWebRequest) {
            return ((ServletWebRequest) request).getRequest().getRequestURI();
        }
        return "UNKNOWN";
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
