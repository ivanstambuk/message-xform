package io.messagexform.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.error.ExpressionCompileException;
import io.messagexform.core.error.SpecParseException;
import io.messagexform.core.model.TransformSpec;
import io.messagexform.core.spi.CompiledExpression;
import io.messagexform.core.spi.ExpressionEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Parses YAML transform spec files into {@link TransformSpec} instances
 * (FR-001-01, API-001-03).
 * Resolves the expression engine via {@link EngineRegistry} and compiles
 * expressions at load time.
 *
 * <p>
 * Thread-safe if the underlying {@link EngineRegistry} and YAML parser are
 * thread-safe (they
 * are).
 */
public final class SpecParser {

    private static final String DEFAULT_LANG = "jslt";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final EngineRegistry engineRegistry;

    /**
     * Creates a new spec parser backed by the given engine registry.
     *
     * @param engineRegistry registry for resolving expression engine identifiers
     */
    public SpecParser(EngineRegistry engineRegistry) {
        this.engineRegistry = Objects.requireNonNull(engineRegistry, "engineRegistry must not be null");
    }

    /**
     * Parses the YAML file at the given path into a {@link TransformSpec}.
     *
     * @param path path to the spec YAML file
     * @return a fully parsed and compiled transform spec
     * @throws SpecParseException         if the YAML is invalid or required fields
     *                                    are missing
     * @throws ExpressionCompileException if the expression fails to compile
     */
    public TransformSpec parse(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        String source = path.toString();

        JsonNode root = readYaml(path, source);

        // Extract required metadata
        String id = requireString(root, "id", source);
        String version = requireString(root, "version", source);
        String description = optionalString(root, "description");

        // Resolve expression engine
        String lang = optionalString(root, "lang");
        JsonNode transformBlock = root.get("transform");
        JsonNode forwardBlock = root.get("forward");
        JsonNode reverseBlock = root.get("reverse");

        // Determine lang: check transform block first, then forward block, then default
        if (lang == null && transformBlock != null && transformBlock.has("lang")) {
            lang = transformBlock.get("lang").asText();
        } else if (lang == null && forwardBlock != null && forwardBlock.has("lang")) {
            lang = forwardBlock.get("lang").asText();
        }
        if (lang == null) {
            lang = DEFAULT_LANG;
        }

        ExpressionEngine engine = resolveEngine(lang, id, source);

        // Extract schemas
        JsonNode inputSchema = extractSchema(root, "input", id, source);
        JsonNode outputSchema = extractSchema(root, "output", id, source);

        // Compile expressions
        CompiledExpression compiledExpr = null;
        CompiledExpression forward = null;
        CompiledExpression reverse = null;

        if (transformBlock != null) {
            // Unidirectional spec
            String expr = requireExpr(transformBlock, "transform", id, source);
            compiledExpr = compileExpression(engine, expr, id, source);
        } else if (forwardBlock != null && reverseBlock != null) {
            // Bidirectional spec
            String forwardExpr = requireExpr(forwardBlock, "forward", id, source);
            String reverseExpr = requireExpr(reverseBlock, "reverse", id, source);
            forward = compileExpression(engine, forwardExpr, id, source);
            reverse = compileExpression(engine, reverseExpr, id, source);
        } else {
            throw new SpecParseException(
                    "Spec must have either a 'transform' block or both 'forward' and 'reverse' blocks", id, source);
        }

        return new TransformSpec(
                id, version, description, lang, inputSchema, outputSchema, compiledExpr, forward, reverse);
    }

    // --- Private helpers ---

    private JsonNode readYaml(Path path, String source) {
        try {
            return YAML_MAPPER.readTree(path.toFile());
        } catch (IOException e) {
            throw new SpecParseException("Failed to read or parse YAML: " + e.getMessage(), e, null, source);
        }
    }

    private String requireString(JsonNode root, String field, String source) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            throw new SpecParseException(
                    "Missing or invalid required field: '" + field + "'", extractIdSafe(root), source);
        }
        return node.asText();
    }

    private String optionalString(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private ExpressionEngine resolveEngine(String lang, String specId, String source) {
        return engineRegistry
                .getEngine(lang)
                .orElseThrow(() ->
                        new ExpressionCompileException("Unknown expression engine: '" + lang + "'", specId, source));
    }

    private JsonNode extractSchema(JsonNode root, String block, String specId, String source) {
        JsonNode blockNode = root.get(block);
        if (blockNode == null) {
            return null;
        }
        JsonNode schema = blockNode.get("schema");
        if (schema == null) {
            return null;
        }
        return schema;
    }

    private String requireExpr(JsonNode block, String blockName, String specId, String source) {
        JsonNode exprNode = block.get("expr");
        if (exprNode == null || exprNode.isNull() || !exprNode.isTextual()) {
            throw new SpecParseException("Missing or invalid 'expr' in '" + blockName + "' block", specId, source);
        }
        return exprNode.asText();
    }

    private CompiledExpression compileExpression(ExpressionEngine engine, String expr, String specId, String source) {
        try {
            return engine.compile(expr);
        } catch (ExpressionCompileException e) {
            // Re-throw with spec context attached
            throw new ExpressionCompileException(
                    "Failed to compile expression for spec '" + specId + "': " + e.getMessage(),
                    e.getCause(),
                    specId,
                    source);
        }
    }

    private String extractIdSafe(JsonNode root) {
        JsonNode idNode = root.get("id");
        return idNode != null && idNode.isTextual() ? idNode.asText() : null;
    }
}
