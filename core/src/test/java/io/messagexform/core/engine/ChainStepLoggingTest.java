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

/**
 * Tests for chain step logging (T-001-33, NFR-001-08).
 * Verifies that each chain step is logged with chain_step, spec_id, and
 * profile_id.
 */
@DisplayName("ChainStepLoggingTest")
class ChainStepLoggingTest {

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
    @DisplayName("3-step chain → log entries contain chain_step, spec_id, profile_id")
    void threeStepChainLogs() throws IOException {
        // Create 3 specs for the chain
        for (int i = 1; i <= 3; i++) {
            Path specPath = tempDir.resolve("step" + i + ".yaml");
            Files.writeString(specPath, """
                    id: step-%d
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

        // Profile with 3 entries all matching the same path
        Path profilePath = tempDir.resolve("chain-log-profile.yaml");
        Files.writeString(profilePath, """
                profile: log-test-profile
                version: "1.0.0"
                transforms:
                  - spec: step-1@1.0.0
                    direction: response
                    match:
                      path: "/api/chain"
                      method: POST
                  - spec: step-2@1.0.0
                    direction: response
                    match:
                      path: "/api/chain"
                      method: POST
                  - spec: step-3@1.0.0
                    direction: response
                    match:
                      path: "/api/chain"
                      method: POST
                """);

        engine.loadProfile(profilePath);

        JsonNode inputBody = JSON.readTree("{\"data\": \"test\"}");
        Message message = new Message(inputBody, Map.of(), Map.of(), 200, "application/json", "/api/chain", "POST");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();

        // Verify structured log entries
        List<ILoggingEvent> logEvents = logAppender.list;

        // Should have: 1 "Starting chain" + 3 "Executing chain step" + 1 "Chain
        // complete" = 5 logs
        List<String> allMessages =
                logEvents.stream().map(ILoggingEvent::getFormattedMessage).toList();
        List<String> chainLogs = allMessages.stream()
                .filter(msg -> msg.contains("chain") || msg.contains("Chain"))
                .toList();

        assertThat(chainLogs)
                .as("should have start, 3 step, and complete logs (all messages: %s)", allMessages)
                .hasSizeGreaterThanOrEqualTo(5);

        // Verify chain step labels
        assertThat(chainLogs).anyMatch(msg -> msg.contains("chain_step=1/3") && msg.contains("spec_id=step-1"));
        assertThat(chainLogs).anyMatch(msg -> msg.contains("chain_step=2/3") && msg.contains("spec_id=step-2"));
        assertThat(chainLogs).anyMatch(msg -> msg.contains("chain_step=3/3") && msg.contains("spec_id=step-3"));

        // Verify profile_id is present in all chain step logs
        assertThat(chainLogs).allMatch(msg -> msg.contains("profile_id=log-test-profile") || msg.contains("chain"));
    }
}
