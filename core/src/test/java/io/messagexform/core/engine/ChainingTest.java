package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for profile-level chaining (T-001-31, FR-001-05, ADR-0012, S-001-49).
 * When a profile has multiple matching entries for the same direction, they
 * execute in declaration order as a pipeline.
 */
@DisplayName("ChainingTest")
class ChainingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TransformEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        engine = new TransformEngine(specParser);
    }

    @Test
    @DisplayName("chain of 2 transforms: output of step 1 feeds step 2")
    void twoStepChain() throws IOException {
        // Step 1 spec: renames user_id → userId
        Path spec1 = tempDir.resolve("step1.yaml");
        Files.writeString(spec1, """
                id: step1-rename
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
                      "userId": .user_id,
                      "name": .name,
                      "age": .age
                    }
                """);

        // Step 2 spec: adds a "processed" flag and filters fields
        Path spec2 = tempDir.resolve("step2.yaml");
        Files.writeString(spec2, """
                id: step2-enrich
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
                      "userId": .userId,
                      "name": .name,
                      "processed": true
                    }
                """);

        engine.loadSpec(spec1);
        engine.loadSpec(spec2);

        // Profile with 2 entries that both match the same path (chaining)
        Path profilePath = tempDir.resolve("chain-profile.yaml");
        Files.writeString(profilePath, """
                profile: test-chain
                version: "1.0.0"
                transforms:
                  - spec: step1-rename@1.0.0
                    direction: response
                    match:
                      path: "/api/users"
                      method: POST
                  - spec: step2-enrich@1.0.0
                    direction: response
                    match:
                      path: "/api/users"
                      method: POST
                """);

        engine.loadProfile(profilePath);

        // Input message
        JsonNode inputBody = JSON.readTree("""
                {
                  "user_id": "u123",
                  "name": "John Doe",
                  "age": 30
                }
                """);
        Message message = new Message(
                inputBody,
                Map.of("content-type", "application/json"),
                Map.of(),
                200,
                "application/json",
                "/api/users",
                "POST");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        JsonNode body = result.message().body();

        // Step 1 should have renamed user_id → userId
        assertThat(body.get("userId").asText()).isEqualTo("u123");
        assertThat(body.get("name").asText()).isEqualTo("John Doe");

        // Step 2 should have added "processed" and removed "age"
        assertThat(body.get("processed").asBoolean()).isTrue();
        assertThat(body.has("age"))
                .as("age should have been filtered out by step 2")
                .isFalse();
    }

    @Test
    @DisplayName("chain step fails → entire chain aborted, error response returned")
    void chainStepFailsAbortsChain() throws IOException {
        // Use strict mode to trigger eval error on schema violation
        EngineRegistry registry = new EngineRegistry();
        registry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        TransformEngine strictEngine = new TransformEngine(
                specParser, new ErrorResponseBuilder(), EvalBudget.DEFAULT, SchemaValidationMode.STRICT);

        // Step 1: valid transform — produces {"data": "hello"}
        Path spec1 = tempDir.resolve("step1.yaml");
        Files.writeString(spec1, """
                id: step1-ok
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
                      "data": .value
                    }
                """);

        // Step 2: requires "requiredField" in input schema — step 1's output lacks it
        Path spec2 = tempDir.resolve("step2-strict.yaml");
        Files.writeString(spec2, """
                id: step2-strict
                version: "1.0.0"
                input:
                  schema:
                    type: object
                    required: [requiredField]
                    properties:
                      requiredField:
                        type: string
                output:
                  schema:
                    type: object
                transform:
                  lang: jslt
                  expr: |
                    { "result": .requiredField }
                """);

        strictEngine.loadSpec(spec1);
        strictEngine.loadSpec(spec2);

        Path profilePath = tempDir.resolve("fail-chain.yaml");
        Files.writeString(profilePath, """
                profile: test-fail-chain
                version: "1.0.0"
                transforms:
                  - spec: step1-ok@1.0.0
                    direction: response
                    match:
                      path: "/api/test"
                      method: GET
                  - spec: step2-strict@1.0.0
                    direction: response
                    match:
                      path: "/api/test"
                      method: GET
                """);

        strictEngine.loadProfile(profilePath);

        JsonNode inputBody = JSON.readTree("{\"value\": \"hello\"}");
        Message message = new Message(inputBody, Map.of(), Map.of(), 200, "application/json", "/api/test", "GET");

        TransformResult result = strictEngine.transform(message, Direction.RESPONSE);

        // The chain should abort on step 2 failure — returning an error, not partial
        // results
        assertThat(result.isError())
                .as("chain should abort on step failure with error response")
                .isTrue();
        assertThat(result.errorStatusCode()).isNotNull();
    }

    @Test
    @DisplayName("single matching entry profile still works (no chain)")
    void singleEntryNoChain() throws IOException {
        Path spec1 = tempDir.resolve("single.yaml");
        Files.writeString(spec1, """
                id: single-spec
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
                    { "result": .input }
                """);

        engine.loadSpec(spec1);

        Path profilePath = tempDir.resolve("single-profile.yaml");
        Files.writeString(profilePath, """
                profile: test-single
                version: "1.0.0"
                transforms:
                  - spec: single-spec@1.0.0
                    direction: response
                    match:
                      path: "/api/test"
                      method: GET
                """);

        engine.loadProfile(profilePath);

        JsonNode inputBody = JSON.readTree("{\"input\": \"data\"}");
        Message message = new Message(inputBody, Map.of(), Map.of(), 200, "application/json", "/api/test", "GET");

        TransformResult result = engine.transform(message, Direction.RESPONSE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.message().body().get("result").asText()).isEqualTo("data");
    }
}
