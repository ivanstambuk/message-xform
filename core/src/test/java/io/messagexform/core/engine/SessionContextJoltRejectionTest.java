package io.messagexform.core.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.error.ExpressionCompileException;
import io.messagexform.core.spec.SpecParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that JOLT specs cannot reference {@code $session} (T-001-56, S-001-85).
 *
 * <p>
 * Since the JOLT engine is not implemented, any spec with {@code lang: jolt}
 * is rejected at load time with an "Unknown expression engine" error. This
 * prevents JOLT specs from referencing any context variables including
 * {@code $session}.
 */
class SessionContextJoltRejectionTest {

    @TempDir
    Path tempDir;

    @Test
    void joltSpec_withSessionReference_rejectedAtLoadTime() throws Exception {
        // Create a spec that uses lang: jolt with a $session reference
        String specYaml = """
                id: "jolt-session-reject"
                version: "1.0.0"
                description: "JOLT spec referencing $session — should be rejected"

                input:
                  schema:
                    type: object

                output:
                  schema:
                    type: object

                transform:
                  lang: jolt
                  expr: |
                    [{"operation": "shift", "spec": {"sub": "$session.sub"}}]
                """;

        Path specFile = tempDir.resolve("jolt-session.yaml");
        Files.writeString(specFile, specYaml);

        // Only JSLT is registered — JOLT is not available
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);

        assertThatThrownBy(() -> engine.loadSpec(specFile))
                .isInstanceOf(ExpressionCompileException.class)
                .hasMessageContaining("Unknown expression engine")
                .hasMessageContaining("jolt");
    }

    @Test
    void joltSpec_withoutSessionReference_alsoRejectedAtLoadTime() throws Exception {
        // Even without $session, JOLT specs are rejected because the engine isn't
        // registered
        String specYaml = """
                id: "jolt-no-session"
                version: "1.0.0"
                description: "JOLT spec without $session — also rejected"

                input:
                  schema:
                    type: object

                output:
                  schema:
                    type: object

                transform:
                  lang: jolt
                  expr: |
                    [{"operation": "shift", "spec": {"name": "fullName"}}]
                """;

        Path specFile = tempDir.resolve("jolt-plain.yaml");
        Files.writeString(specFile, specYaml);

        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);

        assertThatThrownBy(() -> engine.loadSpec(specFile))
                .isInstanceOf(ExpressionCompileException.class)
                .hasMessageContaining("Unknown expression engine")
                .hasMessageContaining("jolt");
    }
}
