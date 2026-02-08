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
import org.slf4j.MDC;

/**
 * Tests for trace context propagation (T-001-44, NFR-001-10).
 *
 * <p>
 * Verifies that X-Request-ID and traceparent headers are propagated through
 * MDC to all structured log entries during transform execution.
 */
@DisplayName("TraceContextTest")
class TraceContextTest {

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

        // Capture log events from TransformEngine
        engineLogger = (Logger) LoggerFactory.getLogger(TransformEngine.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        engineLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        engineLogger.detachAppender(logAppender);
        logAppender.stop();
        MDC.clear();
    }

    @Test
    @DisplayName("X-Request-ID header → requestId in MDC on log entries")
    void requestIdPropagatedToMdc() throws IOException {
        Path specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                id: trace-spec
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
                    { "ok": true }
                """);
        engine.loadSpec(specPath);

        Path profilePath = tempDir.resolve("profile.yaml");
        Files.writeString(profilePath, """
                profile: trace-profile
                version: "1.0.0"
                transforms:
                  - spec: trace-spec@1.0.0
                    direction: response
                    match:
                      path: "/api/trace"
                      method: GET
                """);
        engine.loadProfile(profilePath);

        JsonNode body = JSON.readTree("{\"data\": 1}");
        Message msg = new Message(
                body,
                Map.of("x-request-id", "abc-123"),
                Map.of("x-request-id", List.of("abc-123")),
                200,
                "application/json",
                "/api/trace",
                "GET");

        TransformResult result = engine.transform(msg, Direction.RESPONSE);
        assertThat(result.isSuccess()).isTrue();

        // Find the transform.matched log entry
        ILoggingEvent matchedLog = logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains("transform.matched"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No transform.matched log entry found"));

        // Verify MDC contains requestId
        assertThat(matchedLog.getMDCPropertyMap()).containsEntry("requestId", "abc-123");
    }

    @Test
    @DisplayName("traceparent header → traceparent in MDC on log entries")
    void traceparentPropagatedToMdc() throws IOException {
        Path specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                id: trace-spec-2
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
                    { "ok": true }
                """);
        engine.loadSpec(specPath);

        Path profilePath = tempDir.resolve("profile.yaml");
        Files.writeString(profilePath, """
                profile: trace-profile-2
                version: "1.0.0"
                transforms:
                  - spec: trace-spec-2@1.0.0
                    direction: response
                    match:
                      path: "/api/tp"
                      method: GET
                """);
        engine.loadProfile(profilePath);

        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        JsonNode body = JSON.readTree("{\"data\": 1}");
        Message msg = new Message(
                body,
                Map.of("traceparent", traceparent),
                Map.of("traceparent", List.of(traceparent)),
                200,
                "application/json",
                "/api/tp",
                "GET");

        TransformResult result = engine.transform(msg, Direction.RESPONSE);
        assertThat(result.isSuccess()).isTrue();

        ILoggingEvent matchedLog = logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains("transform.matched"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No transform.matched log entry found"));

        assertThat(matchedLog.getMDCPropertyMap()).containsEntry("traceparent", traceparent);
    }

    @Test
    @DisplayName("No trace headers → MDC entries absent (not empty string)")
    void noTraceHeadersNoMdc() throws IOException {
        Path specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                id: trace-spec-3
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
                    { "ok": true }
                """);
        engine.loadSpec(specPath);

        Path profilePath = tempDir.resolve("profile.yaml");
        Files.writeString(profilePath, """
                profile: trace-profile-3
                version: "1.0.0"
                transforms:
                  - spec: trace-spec-3@1.0.0
                    direction: response
                    match:
                      path: "/api/no-trace"
                      method: GET
                """);
        engine.loadProfile(profilePath);

        JsonNode body = JSON.readTree("{\"data\": 1}");
        Message msg = new Message(body, Map.of(), Map.of(), 200, "application/json", "/api/no-trace", "GET");

        TransformResult result = engine.transform(msg, Direction.RESPONSE);
        assertThat(result.isSuccess()).isTrue();

        ILoggingEvent matchedLog = logAppender.list.stream()
                .filter(e -> e.getMessage() != null && e.getMessage().contains("transform.matched"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No transform.matched log entry found"));

        // MDC should NOT contain requestId or traceparent
        assertThat(matchedLog.getMDCPropertyMap()).doesNotContainKey("requestId");
        assertThat(matchedLog.getMDCPropertyMap()).doesNotContainKey("traceparent");
    }

    @Test
    @DisplayName("MDC cleaned up after transform completes — no leakage")
    void mdcCleanedUpAfterTransform() throws IOException {
        Path specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                id: trace-spec-4
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
                    { "ok": true }
                """);
        engine.loadSpec(specPath);

        Path profilePath = tempDir.resolve("profile.yaml");
        Files.writeString(profilePath, """
                profile: trace-profile-4
                version: "1.0.0"
                transforms:
                  - spec: trace-spec-4@1.0.0
                    direction: response
                    match:
                      path: "/api/cleanup"
                      method: GET
                """);
        engine.loadProfile(profilePath);

        JsonNode body = JSON.readTree("{\"data\": 1}");
        Message msg = new Message(
                body,
                Map.of("x-request-id", "cleanup-test"),
                Map.of("x-request-id", List.of("cleanup-test")),
                200,
                "application/json",
                "/api/cleanup",
                "GET");

        engine.transform(msg, Direction.RESPONSE);

        // After transform completes, MDC should not contain trace context
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("traceparent")).isNull();
    }
}
