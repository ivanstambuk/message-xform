package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.spi.TelemetryListener;
import io.messagexform.core.spi.TelemetryListener.*;
import io.messagexform.core.testkit.TestMessages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the TelemetryListener SPI (T-001-42, NFR-001-09).
 *
 * <p>
 * Verifies that a registered TelemetryListener receives semantic transform
 * lifecycle events: started, completed, failed, matched, loaded, rejected.
 */
@DisplayName("TelemetryListenerTest")
class TelemetryListenerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TransformEngine engine;
    private CapturingTelemetryListener listener;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        listener = new CapturingTelemetryListener();
        engine = new TransformEngine(
                specParser, new ErrorResponseBuilder(), EvalBudget.DEFAULT, SchemaValidationMode.LENIENT, listener);
    }

    @Test
    @DisplayName("Successful transform → started + completed + matched events")
    void successfulTransformEmitsLifecycleEvents() throws IOException {
        Path specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                id: test-spec
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
                profile: test-profile
                version: "1.0.0"
                transforms:
                  - spec: test-spec@1.0.0
                    direction: response
                    match:
                      path: "/api/test"
                      method: GET
                """);
        engine.loadProfile(profilePath);

        JsonNode body = JSON.readTree("{\"data\": 1}");
        Message msg = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());
        TransformResult result = engine.transform(msg, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();

        // Verify started event
        assertThat(listener.startedEvents).hasSize(1);
        TransformStartedEvent started = listener.startedEvents.get(0);
        assertThat(started.specId()).isEqualTo("test-spec");
        assertThat(started.specVersion()).isEqualTo("1.0.0");
        assertThat(started.direction()).isEqualTo(Direction.RESPONSE);

        // Verify completed event
        assertThat(listener.completedEvents).hasSize(1);
        TransformCompletedEvent completed = listener.completedEvents.get(0);
        assertThat(completed.specId()).isEqualTo("test-spec");
        assertThat(completed.specVersion()).isEqualTo("1.0.0");
        assertThat(completed.direction()).isEqualTo(Direction.RESPONSE);
        assertThat(completed.durationMs()).isGreaterThanOrEqualTo(0);

        // Verify matched event
        assertThat(listener.matchedEvents).hasSize(1);
        ProfileMatchedEvent matched = listener.matchedEvents.get(0);
        assertThat(matched.profileId()).isEqualTo("test-profile");
        assertThat(matched.specId()).isEqualTo("test-spec");
        assertThat(matched.requestPath()).isEqualTo("/api/test");

        // No failed events
        assertThat(listener.failedEvents).isEmpty();
    }

    @Test
    @DisplayName("Failed transform → started + failed events (no completed)")
    void failedTransformEmitsFailedEvent() throws IOException {
        Path specPath = tempDir.resolve("bad-spec.yaml");
        Files.writeString(specPath, """
                id: bad-spec
                version: "2.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    { "result": $undefined_var }
                """);
        engine.loadSpec(specPath);

        Path profilePath = tempDir.resolve("profile.yaml");
        Files.writeString(profilePath, """
                profile: fail-profile
                version: "1.0.0"
                transforms:
                  - spec: bad-spec@2.0.0
                    direction: response
                    match:
                      path: "/api/fail"
                      method: POST
                """);
        engine.loadProfile(profilePath);

        JsonNode body = JSON.readTree("{\"data\": 1}");
        Message msg = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/fail",
                "POST",
                null,
                SessionContext.empty());
        TransformResult result = engine.transform(msg, Direction.RESPONSE);

        assertThat(result.isError()).isTrue();

        // Verify started event was emitted
        assertThat(listener.startedEvents).hasSize(1);
        assertThat(listener.startedEvents.get(0).specId()).isEqualTo("bad-spec");

        // Verify failed event
        assertThat(listener.failedEvents).hasSize(1);
        TransformFailedEvent failed = listener.failedEvents.get(0);
        assertThat(failed.specId()).isEqualTo("bad-spec");
        assertThat(failed.specVersion()).isEqualTo("2.0.0");
        assertThat(failed.errorDetail()).isNotBlank();

        // No completed events
        assertThat(listener.completedEvents).isEmpty();
    }

    @Test
    @DisplayName("Spec loaded → onSpecLoaded event emitted")
    void specLoadedEmitsEvent() throws IOException {
        Path specPath = tempDir.resolve("load-test.yaml");
        Files.writeString(specPath, """
                id: loaded-spec
                version: "3.0.0"
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: '.'
                """);
        engine.loadSpec(specPath);

        assertThat(listener.loadedEvents).hasSize(1);
        SpecLoadedEvent loaded = listener.loadedEvents.get(0);
        assertThat(loaded.specId()).isEqualTo("loaded-spec");
        assertThat(loaded.specVersion()).isEqualTo("3.0.0");
        assertThat(loaded.sourcePath()).contains("load-test.yaml");
    }

    @Test
    @DisplayName("Spec rejected → onSpecRejected event emitted")
    void specRejectedEmitsEvent() throws IOException {
        Path specPath = tempDir.resolve("broken.yaml");
        Files.writeString(specPath, """
                id: broken-spec
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
                    { "bad": if (.x "missing paren }
                """);
        try {
            engine.loadSpec(specPath);
        } catch (Exception ignored) {
            // Expected — the spec is invalid
        }

        assertThat(listener.rejectedEvents).hasSize(1);
        SpecRejectedEvent rejected = listener.rejectedEvents.get(0);
        assertThat(rejected.sourcePath()).contains("broken.yaml");
        assertThat(rejected.errorDetail()).isNotBlank();
    }

    @Test
    @DisplayName("Events MUST NOT contain body content or header values")
    void eventsDoNotContainSensitiveData() throws IOException {
        Path specPath = tempDir.resolve("spec.yaml");
        Files.writeString(specPath, """
                id: safe-spec
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
                profile: safe-profile
                version: "1.0.0"
                transforms:
                  - spec: safe-spec@1.0.0
                    direction: response
                    match:
                      path: "/api/safe"
                      method: GET
                """);
        engine.loadProfile(profilePath);

        JsonNode body = JSON.readTree("{\"password\": \"super-secret-123\"}");
        Message msg = new Message(
                TestMessages.toBody(body, "application/json"),
                TestMessages.toHeaders(
                        Map.of("authorization", "Bearer token-xyz"),
                        Map.of("authorization", List.of("Bearer token-xyz"))),
                200,
                "/api/safe",
                "GET",
                null,
                SessionContext.empty());
        engine.transform(msg, Direction.RESPONSE);

        // Verify no event toString() contains body or header content
        String allEvents = listener.toString();
        assertThat(allEvents).doesNotContain("super-secret-123");
        assertThat(allEvents).doesNotContain("token-xyz");
    }

    /**
     * Test double that captures all telemetry events.
     */
    private static class CapturingTelemetryListener implements TelemetryListener {
        final List<TransformStartedEvent> startedEvents = new ArrayList<>();
        final List<TransformCompletedEvent> completedEvents = new ArrayList<>();
        final List<TransformFailedEvent> failedEvents = new ArrayList<>();
        final List<ProfileMatchedEvent> matchedEvents = new ArrayList<>();
        final List<SpecLoadedEvent> loadedEvents = new ArrayList<>();
        final List<SpecRejectedEvent> rejectedEvents = new ArrayList<>();

        @Override
        public void onTransformStarted(TransformStartedEvent event) {
            startedEvents.add(event);
        }

        @Override
        public void onTransformCompleted(TransformCompletedEvent event) {
            completedEvents.add(event);
        }

        @Override
        public void onTransformFailed(TransformFailedEvent event) {
            failedEvents.add(event);
        }

        @Override
        public void onProfileMatched(ProfileMatchedEvent event) {
            matchedEvents.add(event);
        }

        @Override
        public void onSpecLoaded(SpecLoadedEvent event) {
            loadedEvents.add(event);
        }

        @Override
        public void onSpecRejected(SpecRejectedEvent event) {
            rejectedEvents.add(event);
        }

        @Override
        public String toString() {
            return "started=" + startedEvents + ", completed=" + completedEvents
                    + ", failed=" + failedEvents + ", matched=" + matchedEvents
                    + ", loaded=" + loadedEvents + ", rejected=" + rejectedEvents;
        }
    }
}
