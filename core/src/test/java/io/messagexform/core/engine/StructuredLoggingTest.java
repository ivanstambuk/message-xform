package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.testkit.TestMessages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Tests for structured log entries on matched transforms (T-001-41,
 * NFR-001-08).
 *
 * <p>
 * Every matched request MUST produce a structured log entry containing:
 * profile id, spec id@version, request path, specificity score, eval duration,
 * and direction. Passthrough requests MUST NOT produce transform log entries.
 *
 * <p>
 * Log fields are emitted via SLF4J 1.x-compatible format strings
 * (key=value pairs) for compatibility with PingAccess's SLF4J 1.7.x runtime.
 */
@DisplayName("StructuredLoggingTest")
class StructuredLoggingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Pattern that extracts key=value pairs from the formatted log message. */
    private static final Pattern KV_PATTERN = Pattern.compile("(\\w+)=(\\S+)");

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
        Message message = new Message(
                TestMessages.toBody(inputBody, "application/json"),
                HttpHeaders.empty(),
                200,
                "/json/alpha/authenticate",
                "POST",
                null,
                SessionContext.empty());

        // Act
        TransformResult result = engine.transform(message, Direction.RESPONSE);

        // Assert: transform succeeded
        assertThat(result.isSuccess()).isTrue();

        // Assert: structured log entry with required fields
        List<ILoggingEvent> transformLogs = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage() != null
                        && e.getFormattedMessage().contains("transform.matched"))
                .toList();

        assertThat(transformLogs)
                .as("should emit exactly one transform.matched log entry")
                .hasSize(1);

        ILoggingEvent logEvent = transformLogs.get(0);
        Map<String, String> kvMap = extractKeyValuePairs(logEvent);

        assertThat(kvMap).containsEntry("profile_id", "pingam-prettify");
        assertThat(kvMap).containsEntry("spec_id", "callback-prettify");
        assertThat(kvMap).containsEntry("spec_version", "2.1.0");
        assertThat(kvMap).containsEntry("request_path", "/json/alpha/authenticate");
        assertThat(kvMap).containsKey("specificity_score");
        assertThat(Integer.parseInt(kvMap.get("specificity_score"))).isEqualTo(3);
        assertThat(kvMap).containsKey("eval_duration_ms");
        assertThat(Long.parseLong(kvMap.get("eval_duration_ms"))).isGreaterThanOrEqualTo(0);
        assertThat(kvMap).containsEntry("direction", "RESPONSE");
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
        Message message = new Message(
                TestMessages.toBody(inputBody, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/unmatched",
                "GET",
                null,
                SessionContext.empty());

        // Act
        TransformResult result = engine.transform(message, Direction.RESPONSE);

        // Assert: passthrough
        assertThat(result.isPassthrough()).isTrue();

        // Assert: no transform.matched log entry
        List<ILoggingEvent> transformLogs = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage() != null
                        && e.getFormattedMessage().contains("transform.matched"))
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
        Message message = new Message(
                TestMessages.toBody(inputBody, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/chain",
                "POST",
                null,
                SessionContext.empty());

        // Act
        TransformResult result = engine.transform(message, Direction.RESPONSE);

        // Assert
        assertThat(result.isSuccess()).isTrue();

        List<ILoggingEvent> transformLogs = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage() != null
                        && e.getFormattedMessage().contains("transform.matched"))
                .toList();

        assertThat(transformLogs)
                .as("should emit one transform.matched log per chain step")
                .hasSize(2);

        // First step
        Map<String, String> step1Kv = extractKeyValuePairs(transformLogs.get(0));
        assertThat(step1Kv).containsEntry("spec_id", "chain-step-1");
        assertThat(step1Kv).containsEntry("chain_step", "1/2");

        // Second step
        Map<String, String> step2Kv = extractKeyValuePairs(transformLogs.get(1));
        assertThat(step2Kv).containsEntry("spec_id", "chain-step-2");
        assertThat(step2Kv).containsEntry("chain_step", "2/2");
    }

    /**
     * Extracts key=value pairs from the formatted log message string.
     * The production code emits structured fields as key=value tokens in
     * the SLF4J 1.x-compatible message format.
     */
    private Map<String, String> extractKeyValuePairs(ILoggingEvent event) {
        String formatted = event.getFormattedMessage();
        if (formatted == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = KV_PATTERN.matcher(formatted);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }
}
