package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.testkit.TestMessages;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for session context binding (T-001-57, FR-001-13, ADR-0030).
 *
 * <p>
 * Verifies the full data flow: {@code Message.session()} →
 * {@code TransformContext.session()} → {@code $session} in JSLT.
 */
class SessionContextE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TransformEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Test
    void sessionSubject_injectedIntoTransformedBody() throws Exception {
        // Load a spec that references $session.sub
        Path specFile = createSpec("""
                id: "session-e2e-subject"
                version: "1.0.0"
                description: "Injects session subject into body"

                input:
                  schema:
                    type: object

                output:
                  schema:
                    type: object

                transform:
                  lang: jslt
                  expr: |
                    . + {"subject": $session.sub}
                """);
        engine.loadSpec(specFile);

        // Create message WITH session context
        JsonNode body = MAPPER.readTree("{\"action\": \"login\"}");
        JsonNode session = MAPPER.readTree("{\"sub\": \"bjensen\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                null,
                "/api/test",
                "POST",
                null,
                TestMessages.toSessionContext(session));

        // Transform — uses the 2-arg overload which builds context from message
        TransformResult result = engine.transform(message, Direction.REQUEST);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TestMessages.parseBody(result.message().body()).get("action").asText())
                .isEqualTo("login");
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("subject")
                        .asText())
                .isEqualTo("bjensen");
    }

    @Test
    void nullSessionContext_nullSafeBehavior() throws Exception {
        // Load a spec that uses $session with an if-guard for null safety
        Path specFile = createSpec("""
                id: "session-e2e-null"
                version: "1.0.0"
                description: "Null session context should not crash"

                input:
                  schema:
                    type: object

                output:
                  schema:
                    type: object

                transform:
                  lang: jslt
                  expr: |
                    if ($session.sub) . + {"subject": $session.sub} else . + {"subject_present": false}
                """);
        engine.loadSpec(specFile);

        // Create message WITHOUT session context
        JsonNode body = MAPPER.readTree("{\"action\": \"login\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                null,
                "/api/test",
                "POST",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.REQUEST);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TestMessages.parseBody(result.message().body()).get("action").asText())
                .isEqualTo("login");
        // $session is null → $session.sub evaluates to absent → if-guard falls to else
        assertThat(TestMessages.parseBody(result.message().body()).has("subject"))
                .isFalse();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("subject_present")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void sessionContext_responseTransform() throws Exception {
        Path specFile = createSpec("""
                id: "session-e2e-response"
                version: "1.0.0"
                description: "Session context in response transforms"

                input:
                  schema:
                    type: object

                output:
                  schema:
                    type: object

                transform:
                  lang: jslt
                  expr: |
                    . + {"user": $session.sub}
                """);
        engine.loadSpec(specFile);

        JsonNode body = MAPPER.readTree("{\"data\": \"result\"}");
        JsonNode session = MAPPER.readTree("{\"sub\": \"admin\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/test",
                "GET",
                null,
                TestMessages.toSessionContext(session));

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TestMessages.parseBody(result.message().body()).get("user").asText())
                .isEqualTo("admin");
    }

    @Test
    void sessionContext_complexNestedStructure() throws Exception {
        Path specFile = createSpec("""
                id: "session-e2e-complex"
                version: "1.0.0"
                description: "Complex session context with nested data"

                input:
                  schema:
                    type: object

                output:
                  schema:
                    type: object

                transform:
                  lang: jslt
                  expr: |
                    {
                      "user": $session.sub,
                      "role_count": size($session.roles),
                      "tenant": $session.claims.tenant
                    }
                """);
        engine.loadSpec(specFile);

        JsonNode body = MAPPER.readTree("{}");
        JsonNode session = MAPPER.readTree("""
                {
                    "sub": "bjensen",
                    "roles": ["admin", "user", "auditor"],
                    "claims": {"tenant": "acme", "level": 3}
                }
                """);
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                null,
                "/api/test",
                "POST",
                null,
                TestMessages.toSessionContext(session));

        TransformResult result = engine.transform(message, Direction.REQUEST);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TestMessages.parseBody(result.message().body()).get("user").asText())
                .isEqualTo("bjensen");
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("role_count")
                        .asInt())
                .isEqualTo(3);
        assertThat(TestMessages.parseBody(result.message().body()).get("tenant").asText())
                .isEqualTo("acme");
    }

    private Path createSpec(String yamlContent) throws Exception {
        Path specFile = tempDir.resolve("spec-" + System.nanoTime() + ".yaml");
        Files.writeString(specFile, yamlContent);
        return specFile;
    }
}
