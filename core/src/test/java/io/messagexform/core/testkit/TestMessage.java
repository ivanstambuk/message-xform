package io.messagexform.core.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable in-memory "native" message for testing the gateway adapter lifecycle
 * (T-001-49). Represents a gateway-native HTTP exchange (request or response).
 *
 * <p>
 * Fields are mutable so {@link TestGatewayAdapter#applyChanges} can write
 * back the transformed state. This mirrors the mutability of real
 * gateway-native
 * types (e.g., PingAccess Exchange, servlet HttpServletResponse).
 *
 * <p>
 * Provides a fluent builder via static factory methods for concise test setup.
 */
public final class TestMessage {

    private String bodyJson;
    private Map<String, List<String>> headers;
    private Integer statusCode;
    private String path;
    private String method;
    private String contentType;
    private String queryString;

    private TestMessage() {
        this.headers = new LinkedHashMap<>();
    }

    // --- Static factory methods (fluent builder pattern) ---

    /** Creates an empty test message. */
    public static TestMessage create() {
        return new TestMessage();
    }

    /** Creates a request test message with the given body, path, and method. */
    public static TestMessage request(String bodyJson, String path, String method) {
        return new TestMessage().withBodyJson(bodyJson).withPath(path).withMethod(method);
    }

    /** Creates a response test message with the given body and status code. */
    public static TestMessage response(String bodyJson, int statusCode) {
        return new TestMessage().withBodyJson(bodyJson).withStatusCode(statusCode);
    }

    /**
     * Creates a response with request metadata (needed for profile matching on
     * responses).
     */
    public static TestMessage response(String bodyJson, int statusCode, String requestPath, String requestMethod) {
        return new TestMessage()
                .withBodyJson(bodyJson)
                .withStatusCode(statusCode)
                .withPath(requestPath)
                .withMethod(requestMethod);
    }

    // --- Fluent setters ---

    public TestMessage withBodyJson(String bodyJson) {
        this.bodyJson = bodyJson;
        return this;
    }

    public TestMessage withHeaders(Map<String, List<String>> headers) {
        this.headers = headers != null ? new LinkedHashMap<>(headers) : new LinkedHashMap<>();
        return this;
    }

    public TestMessage withHeader(String name, String... values) {
        this.headers.put(name.toLowerCase(), new ArrayList<>(List.of(values)));
        return this;
    }

    public TestMessage withStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    public TestMessage withPath(String path) {
        this.path = path;
        return this;
    }

    public TestMessage withMethod(String method) {
        this.method = method;
        return this;
    }

    public TestMessage withContentType(String contentType) {
        this.contentType = contentType;
        this.headers.put("content-type", new ArrayList<>(List.of(contentType)));
        return this;
    }

    public TestMessage withQueryString(String queryString) {
        this.queryString = queryString;
        return this;
    }

    // --- Getters ---

    public String bodyJson() {
        return bodyJson;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String path() {
        return path;
    }

    public String method() {
        return method;
    }

    public String contentType() {
        return contentType;
    }

    public String queryString() {
        return queryString;
    }

    // --- Mutable setters for applyChanges write-back ---

    public void setBodyJson(String bodyJson) {
        this.bodyJson = bodyJson;
    }

    public void setHeaders(Map<String, List<String>> headers) {
        this.headers = headers;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    @Override
    public String toString() {
        return "TestMessage{"
                + "path='"
                + path
                + '\''
                + ", method='"
                + method
                + '\''
                + ", status="
                + statusCode
                + ", body='"
                + (bodyJson != null && bodyJson.length() > 60 ? bodyJson.substring(0, 60) + "..." : bodyJson)
                + '\''
                + '}';
    }
}
