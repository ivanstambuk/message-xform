package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for fail-safe reload behaviour (T-001-47, NFR-001-05).
 *
 * <p>
 * Verifies that when {@code TransformEngine.reload()} encounters a broken spec,
 * the old registry is preserved — the engine does NOT end up in a broken state.
 */
class FailSafeReloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpecParser specParser;
    private TransformEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        EngineRegistry engineRegistry = new EngineRegistry();
        engineRegistry.register(new io.messagexform.core.engine.jslt.JsltExpressionEngine());
        specParser = new SpecParser(engineRegistry);
        engine = new TransformEngine(specParser);
    }

    private Path writeValidSpec(String filename, String id) throws Exception {
        String yaml = String.format("""
                        id: %s
                        version: "1.0.0"
                        lang: jslt
                        input:
                          schema:
                            type: object
                        output:
                          schema:
                            type: object
                        transform:
                          expr: |
                            { "result": .name }
                        """, id);
        Path path = tempDir.resolve(filename);
        java.nio.file.Files.writeString(path, yaml);
        return path;
    }

    private Path writeBrokenSpec(String filename) throws Exception {
        // Missing required 'id' field — will fail at parse time
        String yaml = """
                version: "1.0.0"
                lang: jslt
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  expr: |
                    { "result": .name }
                """;
        Path path = tempDir.resolve(filename);
        java.nio.file.Files.writeString(path, yaml);
        return path;
    }

    private Path writeBadJsltSpec(String filename) throws Exception {
        // Valid YAML but broken JSLT expression — will fail at compile time
        String yaml = """
                id: bad-jslt
                version: "1.0.0"
                lang: jslt
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  expr: |
                    { "result": .name +++ INVALID SYNTAX
                """;
        Path path = tempDir.resolve(filename);
        java.nio.file.Files.writeString(path, yaml);
        return path;
    }

    private Message stubMessage() {
        ObjectNode body = MAPPER.createObjectNode().put("name", "alice");
        return new Message(body, Map.of(), null, null, "application/json", "/test", "POST", null);
    }

    // --- Tests ---

    @Test
    void reloadWithBrokenSpecPreservesOldRegistry() throws Exception {
        // Load a valid spec and verify it works
        Path validSpec = writeValidSpec("good.yaml", "good-spec");
        engine.loadSpec(validSpec);

        Message msg = stubMessage();
        TransformResult resultBefore = engine.transform(msg, Direction.RESPONSE);
        assertThat(resultBefore.isSuccess()).isTrue();
        assertThat(resultBefore.message().body().get("result").asText()).isEqualTo("alice");

        // Attempt reload with a broken spec — should fail
        Path brokenSpec = writeBrokenSpec("broken.yaml");
        assertThatThrownBy(() -> engine.reload(List.of(brokenSpec), null)).isInstanceOf(Exception.class);

        // Engine still serves with the OLD registry
        TransformResult resultAfter = engine.transform(msg, Direction.RESPONSE);
        assertThat(resultAfter.isSuccess()).isTrue();
        assertThat(resultAfter.message().body().get("result").asText()).isEqualTo("alice");
    }

    @Test
    void reloadWithBadJsltPreservesOldRegistry() throws Exception {
        // Load a valid spec
        Path validSpec = writeValidSpec("valid.yaml", "valid-spec");
        engine.loadSpec(validSpec);

        // Attempt reload with bad JSLT — compile error
        Path badJslt = writeBadJsltSpec("bad-jslt.yaml");
        assertThatThrownBy(() -> engine.reload(List.of(badJslt), null)).isInstanceOf(Exception.class);

        // Engine still functional
        Message msg = stubMessage();
        TransformResult result = engine.transform(msg, Direction.RESPONSE);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void reloadWithMixOfGoodAndBrokenPreservesOldRegistry() throws Exception {
        // Load initial spec
        Path validSpec = writeValidSpec("initial.yaml", "initial-spec");
        engine.loadSpec(validSpec);

        // Attempt reload with one good and one broken spec
        Path goodSpec = writeValidSpec("new-good.yaml", "new-good");
        Path brokenSpec = writeBrokenSpec("broken-in-mix.yaml");
        assertThatThrownBy(() -> engine.reload(List.of(goodSpec, brokenSpec), null))
                .isInstanceOf(Exception.class);

        // Old registry preserved — initial-spec still available
        TransformRegistry registry = engine.registry();
        assertThat(registry.getSpec("initial-spec")).isNotNull();
        // new-good should NOT be in the registry (reload was atomic — all or nothing)
        assertThat(registry.getSpec("new-good")).isNull();
    }

    @Test
    void reloadWithBrokenProfilePreservesOldRegistry() throws Exception {
        // Load valid spec
        Path validSpec = writeValidSpec("pf-base.yaml", "pf-base");
        engine.loadSpec(validSpec);

        // Write a profile referencing a non-existent spec
        String brokenProfileYaml = """
                profile: broken-profile
                version: "1.0.0"
                description: "references a non-existent spec"
                transforms:
                  - spec: "nonexistent-spec@9.9.9"
                    direction: response
                    match:
                      path: "/test"
                      method: POST
                """;
        Path brokenProfile = tempDir.resolve("broken-profile.yaml");
        java.nio.file.Files.writeString(brokenProfile, brokenProfileYaml);

        assertThatThrownBy(() -> engine.reload(List.of(validSpec), brokenProfile))
                .isInstanceOf(Exception.class);

        // Old registry preserved
        TransformRegistry registry = engine.registry();
        assertThat(registry.getSpec("pf-base")).isNotNull();
    }

    @Test
    void registrySpecCountUnchangedAfterFailedReload() throws Exception {
        Path validSpec = writeValidSpec("count.yaml", "count-spec");
        engine.loadSpec(validSpec);
        int countBefore = engine.specCount();

        Path brokenSpec = writeBrokenSpec("count-broken.yaml");
        assertThatThrownBy(() -> engine.reload(List.of(brokenSpec), null)).isInstanceOf(Exception.class);

        assertThat(engine.specCount()).isEqualTo(countBefore);
    }
}
