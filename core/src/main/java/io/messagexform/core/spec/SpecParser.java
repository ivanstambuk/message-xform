package io.messagexform.core.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.error.ExpressionCompileException;
import io.messagexform.core.error.SchemaValidationException;
import io.messagexform.core.error.SensitivePathSyntaxError;
import io.messagexform.core.error.SpecParseException;
import io.messagexform.core.model.ApplyStep;
import io.messagexform.core.model.HeaderSpec;
import io.messagexform.core.model.StatusSpec;
import io.messagexform.core.model.TransformSpec;
import io.messagexform.core.model.UrlSpec;
import io.messagexform.core.spi.CompiledExpression;
import io.messagexform.core.spi.ExpressionEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** Valid HTTP methods for url.method.set (T-001-38d, RFC 9110 §9). */
    private static final Set<String> VALID_HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    // ── Strict unknown-key detection ──
    // Each YAML block has a set of recognized keys. Any key not in the set
    // triggers a SpecParseException so that typos and structural mistakes
    // (e.g. "headers.request.add" instead of "headers.add") are caught at
    // load time instead of silently ignored.

    /** Recognized top-level spec keys. */
    private static final Set<String> KNOWN_ROOT_KEYS = Set.of(
            "id",
            "version",
            "description",
            "lang",
            "input",
            "output",
            "transform",
            "forward",
            "reverse",
            "headers",
            "status",
            "url",
            "mappers",
            "sensitive");

    /** Recognized keys inside the {@code headers} block. */
    private static final Set<String> KNOWN_HEADER_KEYS = Set.of("add", "remove", "rename");

    /** Recognized keys inside the {@code status} block. */
    private static final Set<String> KNOWN_STATUS_KEYS = Set.of("set", "when");

    /** Recognized keys inside the {@code url} block. */
    private static final Set<String> KNOWN_URL_KEYS = Set.of("path", "query", "method");

    /** Recognized keys inside the {@code url.query} sub-block. */
    private static final Set<String> KNOWN_URL_QUERY_KEYS = Set.of("add", "remove");

    /** Recognized keys inside the {@code url.method} sub-block. */
    private static final Set<String> KNOWN_URL_METHOD_KEYS = Set.of("set", "when");

    /**
     * Recognized keys inside the {@code transform}/{@code forward}/{@code reverse}
     * block.
     */
    private static final Set<String> KNOWN_TRANSFORM_KEYS = Set.of("lang", "expr", "apply");

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
     * Returns the engine registry used by this parser.
     * Exposed so that {@code TransformEngine} can pass it to
     * {@code ProfileParser} for compiling {@code match.when} expressions
     * (FR-001-16, ADR-0036).
     *
     * @return the engine registry, never null
     */
    public EngineRegistry engineRegistry() {
        return engineRegistry;
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

        // Strict key check: reject unknown top-level keys
        rejectUnknownKeys(root, KNOWN_ROOT_KEYS, "spec root", id, source);

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
            rejectUnknownKeys(transformBlock, KNOWN_TRANSFORM_KEYS, "transform", id, source);
            String expr = requireExpr(transformBlock, "transform", id, source);
            compiledExpr = compileExpression(engine, expr, id, source);
        } else if (forwardBlock != null && reverseBlock != null) {
            // Bidirectional spec
            rejectUnknownKeys(forwardBlock, KNOWN_TRANSFORM_KEYS, "forward", id, source);
            rejectUnknownKeys(reverseBlock, KNOWN_TRANSFORM_KEYS, "reverse", id, source);
            String forwardExpr = requireExpr(forwardBlock, "forward", id, source);
            String reverseExpr = requireExpr(reverseBlock, "reverse", id, source);
            forward = compileExpression(engine, forwardExpr, id, source);
            reverse = compileExpression(engine, reverseExpr, id, source);
        } else {
            throw new SpecParseException(
                    "Spec must have either a 'transform' block or both 'forward' and 'reverse' blocks", id, source);
        }

        // Parse optional headers block (FR-001-10, T-001-34)
        HeaderSpec headerSpec = parseHeaderSpec(root, engine, id, source);

        // Parse optional status block (FR-001-11, T-001-37)
        StatusSpec statusSpec = parseStatusSpec(root, engine, id, source);

        // Parse optional url block (FR-001-12, ADR-0027, T-001-38a)
        UrlSpec urlSpec = parseUrlSpec(root, engine, id, source);

        // Parse optional mappers block + apply pipeline (FR-001-08, ADR-0014, T-001-39)
        Map<String, CompiledExpression> compiledMappers = parseMappers(root, engine, id, source);
        List<ApplyStep> applySteps = parseApplyDirective(transformBlock, compiledMappers, id, source);

        // Parse optional sensitive paths (NFR-001-06, ADR-0019, T-001-43)
        List<String> sensitivePaths = parseSensitivePaths(root, id, source);

        return new TransformSpec(
                id,
                version,
                description,
                lang,
                inputSchema,
                outputSchema,
                compiledExpr,
                forward,
                reverse,
                headerSpec,
                statusSpec,
                urlSpec,
                applySteps,
                sensitivePaths);
    }

    /**
     * Parses the optional {@code headers} block from the spec YAML (FR-001-10).
     * Returns {@code null} if no headers block is present.
     */
    private HeaderSpec parseHeaderSpec(JsonNode root, ExpressionEngine engine, String specId, String source) {
        JsonNode headersNode = root.get("headers");
        if (headersNode == null || headersNode.isNull()) {
            return null;
        }

        // Strict key check: reject unknown keys (catches e.g. "headers.request.add")
        rejectUnknownKeys(headersNode, KNOWN_HEADER_KEYS, "headers", specId, source);

        // Parse 'add' — static values (T-001-34) and dynamic expr (T-001-35)
        Map<String, String> staticAdd = new LinkedHashMap<>();
        Map<String, CompiledExpression> dynamicAdd = new LinkedHashMap<>();
        JsonNode addNode = headersNode.get("add");
        if (addNode != null && addNode.isObject()) {
            for (Map.Entry<String, JsonNode> field : addNode.properties()) {
                String headerName = field.getKey().toLowerCase();
                JsonNode value = field.getValue();
                if (value.isTextual()) {
                    staticAdd.put(headerName, value.asText());
                } else if (value.isObject() && value.has("expr")) {
                    // Dynamic header expression — compile at load time (T-001-35)
                    String expr = value.get("expr").asText();
                    CompiledExpression compiled = compileExpression(engine, expr, specId, source);
                    dynamicAdd.put(headerName, compiled);
                }
            }
        }

        // Parse 'remove' — list of glob patterns
        List<String> remove = new ArrayList<>();
        JsonNode removeNode = headersNode.get("remove");
        if (removeNode != null && removeNode.isArray()) {
            for (JsonNode pattern : removeNode) {
                if (pattern.isTextual()) {
                    remove.add(pattern.asText().toLowerCase());
                }
            }
        }

        // Parse 'rename' — old name → new name
        Map<String, String> rename = new LinkedHashMap<>();
        JsonNode renameNode = headersNode.get("rename");
        if (renameNode != null && renameNode.isObject()) {
            for (Map.Entry<String, JsonNode> field : renameNode.properties()) {
                rename.put(
                        field.getKey().toLowerCase(), field.getValue().asText().toLowerCase());
            }
        }

        HeaderSpec spec = new HeaderSpec(staticAdd, dynamicAdd, remove, rename);
        return spec.isEmpty() ? null : spec;
    }

    /**
     * Parses the optional {@code status} block from the spec YAML (FR-001-11,
     * ADR-0003).
     * Returns {@code null} if no status block is present.
     *
     * <p>
     * Validates that:
     * <ul>
     * <li>{@code set} is required and must be an integer in range 100–599</li>
     * <li>{@code when} is optional; if present, it is compiled as a JSLT
     * expression</li>
     * </ul>
     */
    private StatusSpec parseStatusSpec(JsonNode root, ExpressionEngine engine, String specId, String source) {
        JsonNode statusNode = root.get("status");
        if (statusNode == null || statusNode.isNull()) {
            return null;
        }

        // Strict key check
        rejectUnknownKeys(statusNode, KNOWN_STATUS_KEYS, "status", specId, source);

        // Require 'set' field
        JsonNode setNode = statusNode.get("set");
        if (setNode == null || setNode.isNull()) {
            throw new SpecParseException(
                    "status block requires a 'set' field with the target HTTP status code", specId, source);
        }
        int statusCode = setNode.asInt();
        if (statusCode < 100 || statusCode > 599) {
            throw new SpecParseException(
                    "Invalid status code " + statusCode + " — must be in range 100–599", specId, source);
        }

        // Optional 'when' predicate — compiled at load time
        CompiledExpression whenExpr = null;
        JsonNode whenNode = statusNode.get("when");
        if (whenNode != null && whenNode.isTextual()) {
            String whenStr = whenNode.asText();
            whenExpr = compileExpression(engine, whenStr, specId, source);
        }

        return new StatusSpec(statusCode, whenExpr);
    }

    /**
     * Parses the optional {@code url} block from the spec YAML (FR-001-12,
     * ADR-0027).
     * Returns {@code null} if no url block is present.
     *
     * <p>
     * Parses:
     * <ul>
     * <li>{@code url.path.expr} — compiled JSLT path rewrite expression
     * (T-001-38a)</li>
     * <li>{@code url.query.add} — static and dynamic query param additions
     * (T-001-38b)</li>
     * <li>{@code url.query.remove} — glob patterns for query param removal
     * (T-001-38b)</li>
     * </ul>
     * Method parsing added in T-001-38c.
     */
    private UrlSpec parseUrlSpec(JsonNode root, ExpressionEngine engine, String specId, String source) {
        JsonNode urlNode = root.get("url");
        if (urlNode == null || urlNode.isNull()) {
            return null;
        }

        // Strict key checks
        rejectUnknownKeys(urlNode, KNOWN_URL_KEYS, "url", specId, source);

        // Parse path.expr (T-001-38a)
        CompiledExpression pathExpr = null;
        JsonNode pathNode = urlNode.get("path");
        if (pathNode != null && pathNode.has("expr")) {
            JsonNode exprNode = pathNode.get("expr");
            if (exprNode.isTextual()) {
                pathExpr = compileExpression(engine, exprNode.asText(), specId, source);
            } else {
                throw new SpecParseException("url.path.expr must be a string expression", specId, source);
            }
        }

        // Parse query block (T-001-38b)
        List<String> queryRemove = new ArrayList<>();
        Map<String, String> queryStaticAdd = new LinkedHashMap<>();
        Map<String, CompiledExpression> queryDynamicAdd = new LinkedHashMap<>();
        JsonNode queryNode = urlNode.get("query");
        if (queryNode != null) {
            rejectUnknownKeys(queryNode, KNOWN_URL_QUERY_KEYS, "url.query", specId, source);
            // Parse query.remove — list of glob patterns
            JsonNode qRemoveNode = queryNode.get("remove");
            if (qRemoveNode != null && qRemoveNode.isArray()) {
                for (JsonNode pattern : qRemoveNode) {
                    if (pattern.isTextual()) {
                        queryRemove.add(pattern.asText());
                    }
                }
            }

            // Parse query.add — static values and dynamic expr (same pattern as
            // headers.add)
            JsonNode qAddNode = queryNode.get("add");
            if (qAddNode != null && qAddNode.isObject()) {
                for (Map.Entry<String, JsonNode> field : qAddNode.properties()) {
                    String paramName = field.getKey(); // NOT lowercased — query params are case-sensitive
                    JsonNode value = field.getValue();
                    if (value.isTextual()) {
                        queryStaticAdd.put(paramName, value.asText());
                    } else if (value.isObject() && value.has("expr")) {
                        String expr = value.get("expr").asText();
                        CompiledExpression compiled = compileExpression(engine, expr, specId, source);
                        queryDynamicAdd.put(paramName, compiled);
                    }
                }
            }
        }

        // Parse method block (T-001-38c)
        String methodSet = null;
        CompiledExpression methodWhen = null;
        JsonNode methodNode = urlNode.get("method");
        if (methodNode != null) {
            rejectUnknownKeys(methodNode, KNOWN_URL_METHOD_KEYS, "url.method", specId, source);
            JsonNode setNode = methodNode.get("set");
            if (setNode != null && setNode.isTextual()) {
                methodSet = setNode.asText().toUpperCase();
                // T-001-38d: Validate HTTP method at load time
                if (!VALID_HTTP_METHODS.contains(methodSet)) {
                    throw new SpecParseException(
                            "Invalid HTTP method '" + setNode.asText()
                                    + "' in url.method.set — must be one of: "
                                    + VALID_HTTP_METHODS,
                            specId,
                            source);
                }
            }
            JsonNode whenNode = methodNode.get("when");
            if (whenNode != null && whenNode.isTextual()) {
                methodWhen = compileExpression(engine, whenNode.asText(), specId, source);
            }
        }

        // Check if any URL operations exist
        boolean hasAnyOps = pathExpr != null
                || !queryRemove.isEmpty()
                || !queryStaticAdd.isEmpty()
                || !queryDynamicAdd.isEmpty()
                || methodSet != null;
        if (!hasAnyOps) {
            return null;
        }

        return new UrlSpec(pathExpr, queryRemove, queryStaticAdd, queryDynamicAdd, methodSet, methodWhen);
    }

    /**
     * Parses the optional {@code mappers} block from the spec YAML (FR-001-08,
     * ADR-0014, T-001-39).
     * Each mapper is a named expression compiled at load time.
     *
     * @return map of mapper id → compiled expression, empty if no mappers block
     */
    private Map<String, CompiledExpression> parseMappers(
            JsonNode root, ExpressionEngine engine, String specId, String source) {
        JsonNode mappersNode = root.get("mappers");
        if (mappersNode == null || mappersNode.isNull() || !mappersNode.isObject()) {
            return Map.of();
        }

        Map<String, CompiledExpression> mappers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : mappersNode.properties()) {
            String mapperId = entry.getKey();
            JsonNode mapperBlock = entry.getValue();

            // Each mapper must have an 'expr' field
            String expr = requireExpr(mapperBlock, "mappers." + mapperId, specId, source);

            // Resolve mapper-specific lang if present, otherwise use the spec-level engine
            ExpressionEngine mapperEngine = engine;
            if (mapperBlock.has("lang")) {
                String mapperLang = mapperBlock.get("lang").asText();
                mapperEngine = resolveEngine(mapperLang, specId, source);
            }

            CompiledExpression compiled = compileExpression(mapperEngine, expr, specId, source);
            mappers.put(mapperId, compiled);
        }

        return mappers;
    }

    /**
     * Parses the optional {@code apply} directive from the transform block
     * (FR-001-08, ADR-0014, T-001-39).
     *
     * <p>
     * Returns {@code null} if no apply directive is present (backwards compatible
     * — only main expr is evaluated).
     *
     * @param transformBlock  the transform YAML block (may be null for
     *                        bidirectional specs)
     * @param compiledMappers map of mapper id → compiled expression
     * @param specId          spec identifier for error messages
     * @param source          source file path for error messages
     * @return ordered list of apply steps, or null if no apply directive
     */
    private List<ApplyStep> parseApplyDirective(
            JsonNode transformBlock, Map<String, CompiledExpression> compiledMappers, String specId, String source) {
        if (transformBlock == null || !transformBlock.has("apply")) {
            return null;
        }

        JsonNode applyNode = transformBlock.get("apply");
        if (!applyNode.isArray()) {
            throw new SpecParseException("'apply' directive must be a YAML list", specId, source);
        }

        List<ApplyStep> steps = new ArrayList<>();
        int exprCount = 0;
        java.util.Set<String> seenMapperRefs = new java.util.HashSet<>();

        for (JsonNode stepNode : applyNode) {
            if (stepNode.isTextual() && "expr".equals(stepNode.asText())) {
                exprCount++;
                // Reference to the main transform expression
                steps.add(ApplyStep.expr());
            } else if (stepNode.isObject() && stepNode.has("mapperRef")) {
                // Reference to a named mapper
                String mapperRef = stepNode.get("mapperRef").asText();
                CompiledExpression compiled = compiledMappers.get(mapperRef);
                if (compiled == null) {
                    throw new SpecParseException(
                            "Unknown mapper id '" + mapperRef
                                    + "' in apply directive — available mappers: "
                                    + compiledMappers.keySet(),
                            specId,
                            source);
                }
                if (!seenMapperRefs.add(mapperRef)) {
                    throw new SpecParseException(
                            "Mapper '" + mapperRef
                                    + "' appears more than once in apply — duplicate mapper references are not allowed",
                            specId,
                            source);
                }
                steps.add(ApplyStep.mapper(mapperRef, compiled));
            } else {
                throw new SpecParseException(
                        "Invalid apply step: expected 'expr' or '{ mapperRef: <id> }', got: " + stepNode,
                        specId,
                        source);
            }
        }

        // T-001-40: Validation — expr must appear exactly once in apply list
        if (exprCount == 0) {
            throw new SpecParseException(
                    "'apply' directive must include 'expr' exactly once — the main transform expression is required",
                    specId,
                    source);
        }
        if (exprCount > 1) {
            throw new SpecParseException(
                    "'apply' directive must include 'expr' exactly once — found " + exprCount + " occurrences",
                    specId,
                    source);
        }

        return Collections.unmodifiableList(steps);
    }

    // --- Private helpers ---

    /**
     * Parses the optional {@code sensitive} block from the spec YAML
     * (NFR-001-06, ADR-0019, T-001-43).
     *
     * <p>
     * Each entry must be a valid RFC 9535 JSONPath expression starting
     * with {@code $}. Invalid syntax triggers {@link SensitivePathSyntaxError}
     * at load time.
     *
     * @return immutable list of validated paths, or null if no sensitive block
     */
    private List<String> parseSensitivePaths(JsonNode root, String specId, String source) {
        JsonNode sensitiveNode = root.get("sensitive");
        if (sensitiveNode == null || sensitiveNode.isNull()) {
            return null;
        }
        if (!sensitiveNode.isArray()) {
            throw new SpecParseException("'sensitive' must be a YAML list of JSON path expressions", specId, source);
        }

        List<String> paths = new ArrayList<>();
        for (JsonNode pathNode : sensitiveNode) {
            if (!pathNode.isTextual()) {
                throw new SensitivePathSyntaxError(
                        "Sensitive path entry must be a string, got: " + pathNode.getNodeType(), specId, source);
            }
            String path = pathNode.asText();
            validateSensitivePath(path, specId, source);
            paths.add(path);
        }
        return Collections.unmodifiableList(paths);
    }

    /**
     * Validates that a sensitive path conforms to RFC 9535 JSONPath syntax.
     * Minimum requirements: must start with '$', must contain only valid
     * segment characters (dot-notation with optional [*] wildcards).
     */
    private void validateSensitivePath(String path, String specId, String source) {
        if (path == null || path.isBlank()) {
            throw new SensitivePathSyntaxError("Sensitive path must not be blank", specId, source);
        }
        if (!path.startsWith("$")) {
            throw new SensitivePathSyntaxError(
                    "Invalid sensitive path '" + path + "' — must start with '$' (RFC 9535)", specId, source);
        }
        // Validate path segments: after '$', expect dot-separated identifiers
        // with optional [*] or [n] array accessors
        String afterRoot = path.substring(1);
        if (!afterRoot.isEmpty() && !afterRoot.matches("(\\.[a-zA-Z_][a-zA-Z0-9_]*(\\[\\*?\\d*\\])*)*")) {
            throw new SensitivePathSyntaxError(
                    "Invalid sensitive path syntax '" + path
                            + "' — expected RFC 9535 dot-notation with optional [*] wildcards",
                    specId,
                    source);
        }
    }

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
        if (blockNode == null || !blockNode.has("schema")) {
            throw new SpecParseException(
                    "Missing required '" + block + ".schema' block — "
                            + "every spec MUST declare input.schema and output.schema (FR-001-09)",
                    specId,
                    source);
        }
        JsonNode schema = blockNode.get("schema");
        if (schema == null || schema.isNull()) {
            throw new SpecParseException(
                    "Missing required '" + block + ".schema' block — "
                            + "every spec MUST declare input.schema and output.schema (FR-001-09)",
                    specId,
                    source);
        }
        validateJsonSchema(schema, block, specId, source);
        return schema;
    }

    private void validateJsonSchema(JsonNode schema, String block, String specId, String source) {
        try {
            JsonSchema metaSchema = SCHEMA_FACTORY.getSchema(schema);
            // Walk the schema structure — this triggers validation of known keywords.
            // If the schema itself is invalid (e.g., type: not-a-type), the factory
            // or a test validation will catch it.
            Set<ValidationMessage> errors = metaSchema.validate(YAML_MAPPER.createObjectNode());
            // Exercise the schema — we don't use the errors directly but the
            // call validates the schema's structural integrity.
            errors.size(); // suppress unused warning
            // The above validates an empty object against 'schema' as a way to exercise
            // the schema. But we specifically want meta-schema validation. The networknt
            // library validates the schema at getSchema() time for structural issues.
            // For semantic issues like invalid 'type' values, we check directly:
            JsonNode typeNode = schema.get("type");
            if (typeNode != null && typeNode.isTextual()) {
                String typeValue = typeNode.asText();
                if (!isValidJsonSchemaType(typeValue)) {
                    throw new SchemaValidationException(
                            "Invalid JSON Schema in '"
                                    + block
                                    + ".schema': unknown type '"
                                    + typeValue
                                    + "' — expected one of: object, array, string, number,"
                                    + " integer, boolean, null",
                            specId,
                            source);
                }
            }
        } catch (SchemaValidationException e) {
            throw e; // re-throw our own exceptions
        } catch (Exception e) {
            throw new SchemaValidationException(
                    "Invalid JSON Schema in '" + block + ".schema': " + e.getMessage(), e, specId, source);
        }
    }

    private boolean isValidJsonSchemaType(String type) {
        return "object".equals(type)
                || "array".equals(type)
                || "string".equals(type)
                || "number".equals(type)
                || "integer".equals(type)
                || "boolean".equals(type)
                || "null".equals(type);
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

    /**
     * Rejects unknown keys in a YAML block by throwing {@link SpecParseException}
     * if any key is not in the allowed set.
     *
     * <p>
     * This is the primary shift-left guard against spec misconfiguration.
     * Without this check, typos and structural mistakes (e.g.
     * {@code headers.request.add}
     * instead of {@code headers.add}) are silently ignored, leading to specs that
     * load successfully but don't apply the intended transformations.
     *
     * @param node      the YAML object node to check
     * @param knownKeys the set of recognized key names for this block
     * @param blockName human-readable block name for error messages
     * @param specId    spec identifier for error context
     * @param source    source file path for error context
     */
    private void rejectUnknownKeys(
            JsonNode node, Set<String> knownKeys, String blockName, String specId, String source) {
        if (node == null || !node.isObject()) {
            return;
        }
        List<String> unknown = StreamSupport.stream(((Iterable<String>) node::fieldNames).spliterator(), false)
                .filter(key -> !knownKeys.contains(key))
                .collect(Collectors.toList());
        if (!unknown.isEmpty()) {
            throw new SpecParseException(
                    "Unknown key" + (unknown.size() > 1 ? "s" : "") + " in '" + blockName + "': " + unknown
                            + " — recognized keys are: " + knownKeys,
                    specId,
                    source);
        }
    }
}
