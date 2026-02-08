package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Tests for structured log entries on matched transforms (T-001-41,
 * NFR-001-08).
 *
 * <p>
 * Every matched request MUST produce a structured log entry containing:
 * profile id, spec id@version, request path, specificity score, eval duration,
 * and direction. Passthrough requests MUST NOT produce transform log entries.
 */
@DisplayName("StructuredLoggingTest")
class StructuredLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TransformEngine engine;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger engineLogger;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);

        // Attach a log capture appender to TransformEngine's logger
        engineLogger = (Logger) LoggerFactory.getLogger(TransformEngine.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        engineLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        engineLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    @DisplayName("Matched transform → structured log entry with required fields")
    void matchedTransformEmitsStructuredLog() throws IOException {
        // Arrange: load a spec and profile
        Path specPath = tempDir.resolve("test-spec.yaml");
        Files.writeString(specPath, """
                id: callback-prettify
                version: "2.1.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    { "transformed": true, "data": .data }
                """);
        engine.loadSpec(specPath);

        Path profilePath = tempDir.resolve("test-profile.yaml");
        Files.writeString(profilePath, """
                profile: pingam-prettify
                version: "1.0.0"
                transforms:
                  - spec: callback-prettify@2.1.0
                    direction: response
                    match:
                      path: "/json/alpha/authenticate"
                      method: POST
                """);
        engine.loadProfile(profilePath);

        JsonNode inputBody = JSON.readTree("{\"data\": \"hello\"}");
        Message message =
                new Message(inputBody, Map.of(), Map.of(), 200, "application/json", "/json/alpha/authenticate", "POST");

        // Act
        TransformResult result = engine.transform(message, Direction.RESPONSE);

        // Assert: transform succeeded
        assertThat(result.isSuccess()).isTrue();

        // Assert: structured log entry with required fields
        List<ILoggingEvent> transformLogs = logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains("transform.matched"))
                .toList();

        assertThat(transformLogs)
                .as("should emit exactly one transform.matched log entry")
                .hasSize(1);

        ILoggingEvent logEvent = transformLogs.get(0);

        // Verify key-value pairs contain all required NFR-001-08 fields
        Map<String, Object> kvMap = extractKeyValuePairs(logEvent);

        assertThat(kvMap).containsKey("profile_id");
        assertThat(kvMap.get("profile_id")).isEqualTo("pingam-prettify");

        assertThat(kvMap).containsKey("spec_id");
        assertThat(kvMap.get("spec_id")).isEqualTo("callback-prettify");

        assertThat(kvMap).containsKey("spec_version");
        assertThat(kvMap.get("spec_version")).isEqualTo("2.1.0");

        assertThat(kvMap).containsKey("request_path");
        assertThat(kvMap.get("request_path")).isEqualTo("/json/alpha/authenticate");

        assertThat(kvMap).containsKey("specificity_score");
        assertThat(((Number) kvMap.get("specificity_score")).intValue()).isEqualTo(3);

        assertThat(kvMap).containsKey("eval_duration_ms");
        assertThat(((Number) kvMap.get("eval_duration_ms")).longValue()).isGreaterThanOrEqualTo(0);

        assertThat(kvMap).containsKey("direction");
        assertThat(kvMap.get("direction")).isEqualTo("RESPONSE");
    }

    @Test
    @DisplayName("Passthrough request → no transform.matched log entry")
    void passthroughEmitsNoTransformLog() throws IOException {
        // Arrange: load a spec and profile that won't match
        Path specPath = tempDir.resolve("test-spec.yaml");
        Files.writeString(specPath, """
                id: callback-prettify
                version: "1.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    { "transformed": true }
                """);
        engine.loadSpec(specPath);

        Path profilePath = tempDir.resolve("test-profile.yaml");
        Files.writeString(profilePath, """
                profile: test-profile
                version: "1.0.0"
                transforms:
                  - spec: callback-prettify@1.0.0
                    direction: response
                    match:
                      path: "/json/alpha/authenticate"
                      method: POST
                """);
        engine.loadProfile(profilePath);

        // Non-matching request path
        JsonNode inputBody = JSON.readTree("{\"data\": \"hello\"}");
        Message message = new Message(inputBody, Map.of(), Map.of(), 200, "application/json", "/api/unmatched", "GET");

        // Act
        TransformResult result = engine.transform(message, Direction.RESPONSE);

        // Assert: passthrough
        assertThat(result.isPassthrough()).isTrue();

        // Assert: no transform.matched log entry
        List<ILoggingEvent> transformLogs = logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains("transform.matched"))
                .toList();

        assertThat(transformLogs)
                .as("passthrough should NOT produce transform.matched log")
                .isEmpty();
    }

    @Test
    @DisplayName("Chain transform → structured log entries include chain_step")
    void chainTransformEmitsStructuredLogPerStep() throws IOException {
        // Arrange: 2 specs chained in a profile
        for (int i = 1; i <= 2; i++) {
            Path specPath = tempDir.resolve("chain-spec-" + i + ".yaml");
            Files.writeString(specPath, """
                    id: chain-step-%d
                    version: "1.0.0"
                    input:
                      schema:
                        type: object
                    output:
                      schema:
                        type: object
                    transform:
                      lang: jslt
                      expr: |
                        { "step%d": true, "data": .data }
                    """.formatted(i, i));
            engine.loadSpec(specPath);
        }

        Path profilePath = tempDir.resolve("chain-profile.yaml");
        Files.writeString(profilePath, """
                profile: chain-test
                version: "1.0.0"
                transforms:
                  - spec: chain-step-1@1.0.0
                    direction: response
                    match:
                      path: "/api/chain"
                      method: POST
                  - spec: chain-step-2@1.0.0
                    direction: response
                    match:
                      path: "/api/chain"
                      method: POST
                """);
        engine.loadProfile(profilePath);

        JsonNode inputBody = JSON.readTree("{\"data\": \"test\"}");
        Message message = new Message(inputBody, Map.of(), Map.of(), 200, "application/json", "/api/chain", "POST");

        // Act
        TransformResult result = engine.transform(message, Direction.RESPONSE);

        // Assert
        assertThat(result.isSuccess()).isTrue();

        List<ILoggingEvent> transformLogs = logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains("transform.matched"))
                .toList();

        assertThat(transformLogs)
                .as("should emit one transform.matched log per chain step")
                .hasSize(2);

        // First step
        Map<String, Object> step1Kv = extractKeyValuePairs(transformLogs.get(0));
        assertThat(step1Kv.get("spec_id")).isEqualTo("chain-step-1");
        assertThat(step1Kv.get("chain_step")).isEqualTo("1/2");

        // Second step
        Map<String, Object> step2Kv = extractKeyValuePairs(transformLogs.get(1));
        assertThat(step2Kv.get("spec_id")).isEqualTo("chain-step-2");
        assertThat(step2Kv.get("chain_step")).isEqualTo("2/2");
    }

    /**
     * Extracts SLF4J 2.0 key-value pairs from a logback event.
     */
    private Map<String, Object> extractKeyValuePairs(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null) {
            return Map.of();
        }
        return pairs.stream().collect(java.util.stream.Collectors.toMap(kvp -> kvp.key, kvp -> kvp.value));
    }
}
