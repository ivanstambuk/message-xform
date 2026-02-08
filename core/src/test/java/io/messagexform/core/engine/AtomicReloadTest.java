package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the atomic registry swap via {@code TransformEngine.reload()}
 * (T-001-46, NFR-001-05, API-001-04).
 *
 * <p>
 * Verifies that in-flight requests complete with their captured registry
 * snapshot, while new requests after reload use the new registry.
 */
class AtomicReloadTest {

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

    // --- Helper to write a spec YAML ---

    private Path writeSpec(String filename, String id, String version, String jslt) throws Exception {
        String yaml = String.format("""
                id: %s
                version: "%s"
                lang: jslt
                input:
                  schema:
                    type: object
                output:
                  schema:
                    type: object
                transform:
                  expr: |
                    %s
                """, id, version, jslt);
        Path path = tempDir.resolve(filename);
        java.nio.file.Files.writeString(path, yaml);
        return path;
    }

    private Message stubMessage(String key, String value) {
        ObjectNode body = MAPPER.createObjectNode().put(key, value);
        return new Message(body, Map.of(), null, null, "application/json", "/test", "POST", null);
    }

    // --- Tests ---

    @Test
    void reloadSwapsRegistryAtomically() throws Exception {
        // Load an initial spec: renames "name" to "old_name"
        Path specV1 = writeSpec("v1.yaml", "swap-test", "1.0.0", "{ \"old_name\": .name }");
        engine.loadSpec(specV1);

        // Verify V1 works
        Message msg = stubMessage("name", "alice");
        TransformResult result1 = engine.transform(msg, Direction.RESPONSE);
        assertThat(result1.isSuccess()).isTrue();
        assertThat(result1.message().body().get("old_name").asText()).isEqualTo("alice");

        // Write V2 spec: renames "name" to "new_name"
        Path specV2 = writeSpec("v2.yaml", "swap-test", "2.0.0", "{ \"new_name\": .name }");

        // Reload with V2 — the old V1 registry is replaced
        engine.reload(java.util.List.of(specV2), null);

        // After reload, the engine uses the new registry
        TransformResult result2 = engine.transform(msg, Direction.RESPONSE);
        assertThat(result2.isSuccess()).isTrue();
        assertThat(result2.message().body().get("new_name").asText()).isEqualTo("alice");
        assertThat(result2.message().body().has("old_name")).isFalse();
    }

    @Test
    void reloadReplacesEntireRegistryNotJustAdding() throws Exception {
        // Load two specs
        Path specA = writeSpec("a.yaml", "spec-a", "1.0.0", "{ \"a\": .x }");
        Path specB = writeSpec("b.yaml", "spec-b", "1.0.0", "{ \"b\": .x }");
        engine.loadSpec(specA);
        engine.loadSpec(specB);

        // Reload with only spec-a — spec-b should disappear
        engine.reload(java.util.List.of(specA), null);

        // Registry should only contain spec-a, not spec-b
        TransformRegistry registry = engine.registry();
        assertThat(registry.getSpec("spec-a")).isNotNull();
        assertThat(registry.getSpec("spec-b")).isNull();
    }

    @Test
    void reloadWithProfileSetsActiveProfile() throws Exception {
        // Write a spec and a profile
        Path specPath = writeSpec("pf.yaml", "profile-spec", "1.0.0", "{ \"out\": .in }");

        String profileYaml = """
                profile: test-profile
                version: "1.0.0"
                description: "test profile"
                transforms:
                  - spec: "profile-spec@1.0.0"
                    direction: response
                    match:
                      path: "/test"
                      method: POST
                """;
        Path profilePath = tempDir.resolve("profile.yaml");
        java.nio.file.Files.writeString(profilePath, profileYaml);

        engine.reload(java.util.List.of(specPath), profilePath);

        assertThat(engine.activeProfile()).isNotNull();
        assertThat(engine.activeProfile().id()).isEqualTo("test-profile");
    }

    @Test
    void reloadWithNullProfileClearsActiveProfile() throws Exception {
        // Load a spec + profile first
        Path specPath = writeSpec("clear.yaml", "clear-spec", "1.0.0", "{ \"out\": .in }");
        engine.loadSpec(specPath);

        String profileYaml = """
                profile: old-profile
                version: "1.0.0"
                description: "will be cleared"
                transforms:
                  - spec: "clear-spec@1.0.0"
                    direction: response
                    match:
                      path: "/test"
                      method: POST
                """;
        Path profilePath = tempDir.resolve("old-profile.yaml");
        java.nio.file.Files.writeString(profilePath, profileYaml);
        engine.loadProfile(profilePath);
        assertThat(engine.activeProfile()).isNotNull();

        // Reload without a profile
        engine.reload(java.util.List.of(specPath), null);

        assertThat(engine.activeProfile()).isNull();
    }

    @Test
    void registryReturnsCurrentSnapshot() throws Exception {
        // Initially empty
        TransformRegistry reg1 = engine.registry();
        assertThat(reg1.specCount()).isZero();

        // Load a spec
        Path specPath = writeSpec("snap.yaml", "snap-spec", "1.0.0", "{ \"out\": .in }");
        engine.loadSpec(specPath);

        // Registry via accessor reflects loaded specs
        TransformRegistry reg2 = engine.registry();
        assertThat(reg2.getSpec("snap-spec")).isNotNull();
    }

    @Test
    void concurrentReadsObserveConsistentSnapshot() throws Exception {
        // This test verifies that concurrent reads see either the old or new
        // registry, never a mix.
        Path specV1 = writeSpec("conc-v1.yaml", "concurrent", "1.0.0", "{ \"version\": \"v1\" }");
        Path specV2 = writeSpec("conc-v2.yaml", "concurrent", "2.0.0", "{ \"version\": \"v2\" }");

        engine.loadSpec(specV1);

        int numReaders = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numReaders);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Message msg = stubMessage("x", "test");

        // Spawn reader threads that transform continuously
        for (int i = 0; i < numReaders; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 50; j++) {
                        TransformResult r = engine.transform(msg, Direction.RESPONSE);
                        assertThat(r.isSuccess()).isTrue();
                        JsonNode body = r.message().body();
                        // Must see either v1 or v2, but the result must be consistent
                        String version = body.get("version").asText();
                        assertThat(version).isIn("v1", "v2");
                    }
                } catch (Throwable t1) {
                    error.compareAndSet(null, t1);
                } finally {
                    doneLatch.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        // Fire all readers + trigger reload mid-flight
        startLatch.countDown();
        Thread.sleep(5); // Let some reads happen
        engine.reload(java.util.List.of(specV2), null);

        doneLatch.await();
        if (error.get() != null) {
            throw new AssertionError("Concurrent read failed", error.get());
        }
    }
}
