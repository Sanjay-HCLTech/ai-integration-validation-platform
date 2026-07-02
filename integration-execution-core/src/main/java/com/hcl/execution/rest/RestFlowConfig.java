package com.hcl.execution.rest;

import com.hcl.execution.config.PlatformConfigResolver;
import com.hcl.execution.model.TestCase;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@Service
public class RestFlowConfig {

    private final Environment environment;
    private final PlatformConfigResolver platformConfigResolver;

    public RestFlowConfig(Environment environment, PlatformConfigResolver platformConfigResolver) {
        this.environment = environment;
        this.platformConfigResolver = platformConfigResolver;
    }

    public String env(TestCase testCase) {
        String requestEnv = testCase == null ? null : testCase.getEnv();
        return platformConfigResolver.normalize(firstText(requestEnv, property("rest.env", platformConfigResolver.env(null))));
    }

    public String collection(TestCase testCase) {
        String requestCollection = testCase == null ? null : testCase.getCollection();
        return normalizeCollection(firstText(requestCollection, property("rest.collection.default", "PackageOffers")));
    }

    public String brand(TestCase testCase) {
        String requestBrand = testCase == null ? null : testCase.getBrand();
        return platformConfigResolver.normalize(firstText(requestBrand, property("rest.brand.default", "TUI_UK")));
    }

    public String endpoint(String env, String collection, String brand) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedCollection = normalizeCollection(collection);
        String normalizedBrand = platformConfigResolver.normalize(brand);
        return firstText(
                property("rest.endpoint." + normalizedEnv + "." + normalizedCollection + "." + normalizedBrand, ""),
                property("rest.endpoint." + normalizedEnv + "." + normalizedCollection, ""),
                property("rest.endpoint." + normalizedCollection + "." + normalizedBrand, ""),
                property("rest.endpoint." + normalizedCollection, ""),
                property("rest.endpoint.default", ""),
                property("rest.endpoint", ""));
    }

    public String apiKey(String env, String collection, String brand) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedCollection = normalizeCollection(collection);
        String normalizedBrand = platformConfigResolver.normalize(brand);
        return firstText(
                property("rest.api.key." + normalizedEnv + "." + normalizedCollection + "." + normalizedBrand, ""),
                property("rest.api.key." + normalizedEnv + "." + normalizedCollection, ""),
                property("rest.api.key." + normalizedCollection + "." + normalizedBrand, ""),
                property("rest.api.key." + normalizedCollection, ""),
                property("rest.api.key." + normalizedEnv + "." + normalizedBrand, ""),
                property("rest.api.key." + normalizedBrand, ""),
                property("rest.api.key.default", ""),
                property("rest.api.key", ""));
    }

    public String method(String collection, String fallback) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(property("rest.method." + normalizedCollection, ""),
                property("rest.method.default", ""),
                property("rest.method", ""),
                fallback);
    }

    public String queryParam(String collection, String fallback) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(property("rest.get.query.param." + normalizedCollection, ""), fallback);
    }

    public String queryEncoding(String collection) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(
                property("rest.get.query.encoding." + normalizedCollection, ""),
                property("rest.get.query.encoding", "RFC3986"));
    }

    public String acceptHeader(String collection, String fallback) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(property("rest.accept." + normalizedCollection, ""),
                property("rest.accept.value", ""),
                property("rest.accept", ""),
                fallback);
    }

    public String combinedAcceptHeader(String collection) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(property("rest.accept.combined." + normalizedCollection, ""), property("rest.accept.combined", ""));
    }

    public String defaultAcceptHeader(String collection) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(property("rest.accept.default." + normalizedCollection, ""), property("rest.accept.default", ""));
    }

    public String connectionHeader(String collection) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(property("rest.connection." + normalizedCollection, ""), property("rest.connection", "close"));
    }

    public String acceptEncodingHeader(String collection) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(property("rest.accept.encoding." + normalizedCollection, ""), property("rest.accept.encoding", ""));
    }

    public boolean compactJsonQuery() {
        return Boolean.parseBoolean(property("rest.get.query.compact.json", "true"));
    }

    public String contentType(String collection, String fallback) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(
                property("rest.content.type." + normalizedCollection, ""),
                property("rest.content.type.default", ""),
                property("rest.content.type", ""),
                fallback);
    }

    public String successMarkers(String collection) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(
                property("rest.success.markers." + normalizedCollection, ""),
                property("rest.success.markers", ""));
    }

    public String logRemotePath(String env, String collection, String fallback) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedCollection = normalizeCollection(collection);
        return firstText(
                property("rest.log.remote.path." + normalizedEnv + "." + normalizedCollection, ""),
                property("rest.log.remote.path." + normalizedCollection, ""),
                property("rest.sftp.payload.log.dir." + normalizedEnv + "." + normalizedCollection, ""),
                property("rest.sftp.payload.log.dir." + normalizedCollection, ""),
                property("rest.log.remote.path", ""),
                property("rest.sftp.payload.log.dir", ""),
                fallback);
    }

    public String logSnapshotExclude(String env, String collection) {
        String normalizedEnv = platformConfigResolver.normalize(env);
        String normalizedCollection = normalizeCollection(collection);
        return firstText(
                property("rest.log.snapshot.exclude." + normalizedEnv + "." + normalizedCollection, ""),
                property("rest.log.snapshot.exclude." + normalizedCollection, ""),
                property("rest.log.snapshot.exclude", ""));
    }

    public String payloadFile(TestCase testCase, String collection) {
        String normalizedEnv = env(testCase);
        String normalizedCollection = normalizeCollection(collection);
        String normalizedScenario = platformConfigResolver.normalize(testCase == null ? null : testCase.getScenario());
        return firstText(
                property("rest.payload." + normalizedEnv + "." + normalizedCollection + "." + normalizedScenario, ""),
                property("rest.payload." + normalizedEnv + "." + normalizedCollection, ""),
                property("rest.payload." + normalizedCollection + "." + normalizedScenario, ""),
                property("rest.payload." + normalizedCollection, ""));
    }

    public Path payloadRoot() {
        return Paths.get(property("rest.payload.root", platformConfigResolver.payloadRoot().resolve("APIGEE").toString()))
                .normalize();
    }

    public String collectionPayloadFolder(String collection) {
        String normalizedCollection = normalizeCollection(collection);
        return firstText(
                property("rest.payload.collection." + normalizedCollection, ""),
                property("rest.payload.collection.folder." + normalizedCollection, ""),
                normalizedCollection);
    }

    public String normalizeCollection(String value) {
        String normalized = platformConfigResolver.normalize(value);
        if ("PACKAGEOFFER".equals(normalized)) {
            return "PACKAGEOFFERS";
        }
        return normalized;
    }

    private String property(String key, String fallback) {
        String value = environment.getProperty(configKey(key));
        return platformConfigResolver.hasText(value) ? value.trim() : fallback;
    }

    private String configKey(String key) {
        return key == null ? null : key.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private String firstText(String... values) {
        return platformConfigResolver.firstText(values);
    }
}
