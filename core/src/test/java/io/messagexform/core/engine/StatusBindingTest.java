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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for $status binding in JSLT expressions (T-001-38, FR-001-11,
 * ADR-0017).
 * Validates that JSLT body expressions can reference $status for response
 * transforms, and that $status is null for request transforms.
 */
@DisplayName("T-001-38: $status binding in JSLT expressions")
class StatusBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransformEngine engine;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Test
    @DisplayName("S-001-37: Response transform — $status < 400 evaluates correctly")
    void statusBinding_responseTransform_evaluatesCorrectly() throws Exception {
        Path specPath = createTempSpec("""
                id: status-binding-response
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
                    {
                      "success": $status < 400,
                      "httpStatus": $status,
                      "data": .data
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"data\": \"payload\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                200,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("success")
                        .asBoolean())
                .isTrue();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("httpStatus")
                        .asInt())
                .isEqualTo(200);
        assertThat(TestMessages.parseBody(result.message().body()).get("data").asText())
                .isEqualTo("payload");
    }

    @Test
    @DisplayName("S-001-37: Response transform — $status >= 400 evaluates correctly")
    void statusBinding_errorStatus_evaluatesCorrectly() throws Exception {
        Path specPath = createTempSpec("""
                id: status-binding-error
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
                    {
                      "success": $status < 400,
                      "httpStatus": $status,
                      "data": .data
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"data\": \"error payload\"}");
        Message message = new Message(
                TestMessages.toBody(body, "application/json"),
                HttpHeaders.empty(),
                500,
                "/api/test",
                "GET",
                null,
                SessionContext.empty());

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("success")
                        .asBoolean())
                .isFalse();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("httpStatus")
                        .asInt())
                .isEqualTo(500);
    }

    @Test
    @DisplayName("S-001-61: Request transform — $status is null (ADR-0017)")
    void statusBinding_requestTransform_statusIsNull() throws Exception {
        Path specPath = createTempSpec("""
                id: status-binding-request
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
                    {
                      "data": .data,
                      "statusKnown": $status != null,
                      "httpStatus": if ($status) $status else "N/A"
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"data\": \"hello\"}");
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
        assertThat(TestMessages.parseBody(result.message().body()).get("data").asText())
                .isEqualTo("hello");
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("statusKnown")
                        .asBoolean())
                .isFalse();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("httpStatus")
                        .asText())
                .isEqualTo("N/A");
    }

    @Test
    @DisplayName("S-001-63: Request transform — $status is null, not -1 (ADR-0020)")
    void statusBinding_requestTransform_nullNotSentinel() throws Exception {
        Path specPath = createTempSpec("""
                id: status-type-check
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
                    {
                      "statusIsNull": $status == null,
                      "statusType": if ($status == null) "absent" else "present",
                      "data": .data
                    }
                """);

        engine.loadSpec(specPath);

        JsonNode body = MAPPER.readTree("{\"data\": \"test\"}");
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
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("statusIsNull")
                        .asBoolean())
                .isTrue();
        assertThat(TestMessages.parseBody(result.message().body())
                        .get("statusType")
                        .asText())
                .isEqualTo("absent");
        assertThat(TestMessages.parseBody(result.message().body()).get("data").asText())
                .isEqualTo("test");
    }

    // --- Helper ---

    private Path createTempSpec(String yamlContent) throws IOException {
        Path tempFile = Files.createTempFile("spec-", ".yaml");
        Files.writeString(tempFile, yamlContent);
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }
}
