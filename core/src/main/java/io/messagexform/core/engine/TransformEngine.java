package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.messagexform.core.error.EvalBudgetExceededException;
import io.messagexform.core.error.InputSchemaViolation;
import io.messagexform.core.error.TransformEvalException;
import io.messagexform.core.model.ApplyStep;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.ProfileEntry;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformProfile;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.model.TransformSpec;
import io.messagexform.core.spec.ProfileParser;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.spi.CompiledExpression;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core transformation engine (API-001-01/03, FR-001-01, FR-001-02, FR-001-04,
 * FR-001-05).
 * Loads transform specs and profiles from YAML files and applies them to
 * messages.
 *
 * <p>
 * Phase 5 scope: profile-based routing with most-specific-wins matching
 * (ADR-0006).
 * When a profile is loaded, the engine uses {@link ProfileMatcher} to select
 * the appropriate spec. When no profile is loaded, falls back to single-spec
 * mode
 * (Phase 4 behaviour).
 *
 * <p>
 * Thread-safe: uses {@link ConcurrentHashMap} for the spec registry.
 */
public final class TransformEngine {

    private static final Logger LOG = LoggerFactory.getLogger(TransformEngine.class);
    private static final ObjectMapper SIZE_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final SpecParser specParser;
    private final ErrorResponseBuilder errorResponseBuilder;
    private final EvalBudget budget;
    private final SchemaValidationMode schemaValidationMode;
    private final Map<String, TransformSpec> specs = new ConcurrentHashMap<>();
    private volatile TransformProfile activeProfile;

    /**
     * Creates a new engine backed by the given spec parser, using the default
     * error response builder (HTTP 502).
     *
     * @param specParser the parser used to load and compile spec YAML files
     */
    public TransformEngine(SpecParser specParser) {
        this(specParser, new ErrorResponseBuilder(), EvalBudget.DEFAULT, SchemaValidationMode.LENIENT);
    }

    /**
     * Creates a new engine with a custom error response builder.
     *
     * @param specParser           the parser used to load and compile spec YAML
     *                             files
     * @param errorResponseBuilder the builder for RFC 9457 error responses
     */
    public TransformEngine(SpecParser specParser, ErrorResponseBuilder errorResponseBuilder) {
        this(specParser, errorResponseBuilder, EvalBudget.DEFAULT, SchemaValidationMode.LENIENT);
    }

    /**
     * Creates a new engine with a custom error response builder and evaluation
     * budget.
     *
     * @param specParser           the parser used to load and compile spec YAML
     *                             files
     * @param errorResponseBuilder the builder for RFC 9457 error responses
     * @param budget               evaluation budget (max-eval-ms, max-output-bytes)
     */
    public TransformEngine(SpecParser specParser, ErrorResponseBuilder errorResponseBuilder, EvalBudget budget) {
        this(specParser, errorResponseBuilder, budget, SchemaValidationMode.LENIENT);
    }

    /**
     * Creates a new engine with all configuration options.
     *
     * @param specParser           the parser used to load and compile spec YAML
     *                             files
     * @param errorResponseBuilder the builder for RFC 9457 error responses
     * @param budget               evaluation budget (max-eval-ms, max-output-bytes)
     * @param schemaValidationMode STRICT or LENIENT (FR-001-09, CFG-001-09)
     */
    public TransformEngine(
            SpecParser specParser,
            ErrorResponseBuilder errorResponseBuilder,
            EvalBudget budget,
            SchemaValidationMode schemaValidationMode) {
        this.specParser = Objects.requireNonNull(specParser, "specParser must not be null");
        this.errorResponseBuilder =
                Objects.requireNonNull(errorResponseBuilder, "errorResponseBuilder must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.schemaValidationMode =
                Objects.requireNonNull(schemaValidationMode, "schemaValidationMode must not be null");
    }

    /**
     * Loads a transform spec from a YAML file and registers it by its spec id.
     * If a spec with the same id is already loaded, it is replaced.
     *
     * @param path path to the spec YAML file
     * @return the loaded and compiled spec
     * @throws io.messagexform.core.error.SpecParseException         if the YAML is
     *                                                               invalid
     * @throws io.messagexform.core.error.ExpressionCompileException if the
     *                                                               expression
     *                                                               fails to
     *                                                               compile
     */
    public TransformSpec loadSpec(Path path) {
        TransformSpec spec = specParser.parse(path);
        // Store by both "id" (for Phase 4 compat) and "id@version" (for profile
        // resolution)
        specs.put(spec.id(), spec);
        specs.put(spec.id() + "@" + spec.version(), spec);
        return spec;
    }

    /**
     * Loads a transform profile from a YAML file. The profile's spec references
     * are resolved against already-loaded specs. Specs MUST be loaded before
     * the profile that references them.
     *
     * @param path path to the profile YAML file
     * @return the loaded and resolved profile
     * @throws io.messagexform.core.error.ProfileResolveException if the profile
     *                                                            YAML is invalid or
     *                                                            spec references
     *                                                            cannot be resolved
     */
    public TransformProfile loadProfile(Path path) {
        ProfileParser profileParser = new ProfileParser(specs);
        TransformProfile profile = profileParser.parse(path);
        this.activeProfile = profile;
        return profile;
    }

    /**
     * Returns the currently active profile, or {@code null} if no profile is
     * loaded.
     */
    public TransformProfile activeProfile() {
        return activeProfile;
    }

    /**
     * Transforms the given message. When a profile is loaded, the engine uses
     * {@link ProfileMatcher} to select matching profile entries. If multiple
     * entries match, they execute as a pipeline in declaration order
     * (T-001-31, ADR-0012) — the output of step N feeds step N+1. If any step
     * fails, the entire chain aborts with an error response.
     *
     * <p>
     * When no profile is loaded, falls back to single-spec mode (Phase 4).
     *
     * @param message   the input message to transform
     * @param direction the direction of the transform (REQUEST or RESPONSE)
     * @return a {@link TransformResult} — SUCCESS with the transformed message,
     *         ERROR with an RFC 9457 body if evaluation fails (ADR-0022),
     *         or PASSTHROUGH if no spec or profile matches
     */
    public TransformResult transform(Message message, Direction direction) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(direction, "direction must not be null");

        // Phase 5: Profile-based routing via ProfileMatcher
        TransformProfile profile = this.activeProfile;
        if (profile != null) {
            List<ProfileEntry> matches = ProfileMatcher.findMatches(
                    profile, message.requestPath(), message.requestMethod(), message.contentType(), direction);
            if (matches.isEmpty()) {
                return TransformResult.passthrough();
            }
            // Single match → direct transform (common fast path)
            if (matches.size() == 1) {
                return transformWithSpec(matches.get(0).spec(), message, direction);
            }
            // Multiple matches → pipeline chain (T-001-31, ADR-0012, S-001-49)
            return transformChain(matches, message, direction);
        }

        // Phase 4 fallback: single-spec mode (no profile loaded)
        if (specs.isEmpty()) {
            return TransformResult.passthrough();
        }

        // Use the first loaded spec (for backward compatibility with Phase 4 tests)
        TransformSpec spec = specs.values().iterator().next();
        return transformWithSpec(spec, message, direction);
    }

    /**
     * Executes a pipeline chain of transforms (T-001-31, ADR-0012).
     * Each step's output message feeds the next step. If any step fails,
     * the entire chain aborts — no partial results reach the caller.
     */
    private TransformResult transformChain(List<ProfileEntry> chain, Message message, Direction direction) {
        TransformProfile profile = this.activeProfile;
        String profileId = profile != null ? profile.id() : "unknown";
        int totalSteps = chain.size();

        LOG.info(
                "Starting chain execution: profile_id={}, chain_steps={}, direction={}",
                profileId,
                totalSteps,
                direction);

        Message current = message;
        for (int i = 0; i < chain.size(); i++) {
            ProfileEntry entry = chain.get(i);
            String stepLabel = (i + 1) + "/" + totalSteps;

            LOG.info(
                    "Executing chain step: chain_step={}, spec_id={}, profile_id={}",
                    stepLabel,
                    entry.spec().id(),
                    profileId);

            TransformResult stepResult = transformWithSpec(entry.spec(), current, direction);

            if (stepResult.isError()) {
                LOG.warn(
                        "Chain aborted at step {}: spec_id={}, profile_id={}",
                        stepLabel,
                        entry.spec().id(),
                        profileId);
                return stepResult;
            }
            if (stepResult.isPassthrough()) {
                LOG.debug(
                        "Chain step {} passthrough: spec_id={}",
                        stepLabel,
                        entry.spec().id());
                continue;
            }
            // SUCCESS — feed the output to the next step
            current = stepResult.message();
        }

        LOG.info("Chain execution complete: profile_id={}, steps={}", profileId, totalSteps);
        return TransformResult.success(current);
    }

    /**
     * Applies a single spec to the message. Shared between profile-routed and
     * single-spec (Phase 4 fallback) paths.
     */
    private TransformResult transformWithSpec(TransformSpec spec, Message message, Direction direction) {
        // Resolve the expression based on directionality
        CompiledExpression expr = resolveExpression(spec, direction);

        // Build context from message metadata (T-001-21, FR-001-10/11).
        // For REQUEST transforms, $status is null (ADR-0017).
        Integer status = direction == Direction.RESPONSE ? message.statusCode() : null;
        TransformContext context = new TransformContext(message.headers(), message.headersAll(), status, null, null);

        // Save the original body for URL rewriting (ADR-0027: "route the input")
        JsonNode originalBody = message.body();

        // Evaluate the expression — catch eval exceptions per ADR-0022
        try {
            // T-001-26: Strict-mode input schema validation
            if (schemaValidationMode == SchemaValidationMode.STRICT && spec.inputSchema() != null) {
                validateInputSchema(message.body(), spec);
            }

            long startNanos = System.nanoTime();

            // T-001-39: Apply pipeline or single expression evaluation (FR-001-08,
            // ADR-0014)
            JsonNode transformedBody;
            if (spec.hasApplyPipeline()) {
                // Execute apply steps in declaration order — each step's output feeds the next
                JsonNode pipelineInput = message.body();
                for (ApplyStep step : spec.applySteps()) {
                    if (step.isExpr()) {
                        // Main transform expression
                        pipelineInput = expr.evaluate(pipelineInput, context);
                    } else {
                        // Named mapper expression
                        pipelineInput = step.compiledMapper().evaluate(pipelineInput, context);
                    }
                }
                transformedBody = pipelineInput;
            } else {
                // No apply directive — backwards-compatible single expression evaluation
                transformedBody = expr.evaluate(message.body(), context);
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            // T-001-25: Enforce time budget
            if (elapsedMs > budget.maxEvalMs()) {
                throw new EvalBudgetExceededException(
                        String.format(
                                "Evaluation exceeded time budget: %dms > %dms (spec '%s')",
                                elapsedMs, budget.maxEvalMs(), spec.id()),
                        spec.id(),
                        null);
            }

            // T-001-25: Enforce output size budget
            checkOutputSize(transformedBody, spec.id());

            // Build the transformed message, preserving envelope metadata
            Message transformedMessage = new Message(
                    transformedBody,
                    message.headers(),
                    message.headersAll(),
                    message.statusCode(),
                    message.contentType(),
                    message.requestPath(),
                    message.requestMethod(),
                    message.queryString());

            // T-001-38a: Apply declarative URL rewrite (FR-001-12, ADR-0027)
            // Processing order: body transform → URL rewrite (original body) → headers →
            // status
            // URL expressions evaluate against the ORIGINAL body ("route the input, enrich
            // the output")
            if (spec.urlSpec() != null && direction == Direction.REQUEST) {
                transformedMessage = UrlTransformer.apply(transformedMessage, spec.urlSpec(), originalBody, context);
            }

            // T-001-34/35: Apply declarative header operations (FR-001-10)
            if (spec.headerSpec() != null) {
                transformedMessage = HeaderTransformer.apply(transformedMessage, spec.headerSpec(), transformedBody);
            }

            // T-001-37: Apply declarative status code transformation (FR-001-11, ADR-0003)
            // Processing order: bind $status → JSLT body → headers → when predicate → set
            // status
            if (spec.statusSpec() != null) {
                Integer newStatus =
                        StatusTransformer.apply(transformedMessage.statusCode(), spec.statusSpec(), transformedBody);
                if (!java.util.Objects.equals(newStatus, transformedMessage.statusCode())) {
                    transformedMessage = new Message(
                            transformedMessage.body(),
                            transformedMessage.headers(),
                            transformedMessage.headersAll(),
                            newStatus,
                            transformedMessage.contentType(),
                            transformedMessage.requestPath(),
                            transformedMessage.requestMethod(),
                            transformedMessage.queryString());
                }
            }

            return TransformResult.success(transformedMessage);
        } catch (TransformEvalException e) {
            // ADR-0022: Never pass through the original message on eval error.
            // Build an RFC 9457 error response instead.
            JsonNode errorBody = errorResponseBuilder.buildErrorResponse(e, message.requestPath());
            return TransformResult.error(errorBody, errorResponseBuilder.status());
        }
    }

    /**
     * Returns the number of currently loaded specs.
     */
    public int specCount() {
        return specs.size();
    }

    // --- Private helpers ---

    private CompiledExpression resolveExpression(TransformSpec spec, Direction direction) {
        if (spec.isBidirectional()) {
            return direction == Direction.RESPONSE ? spec.forward() : spec.reverse();
        }
        return spec.compiledExpr();
    }

    private void checkOutputSize(JsonNode output, String specId) {
        try {
            byte[] bytes = SIZE_MAPPER.writeValueAsBytes(output);
            if (bytes.length > budget.maxOutputBytes()) {
                throw new EvalBudgetExceededException(
                        String.format(
                                "Output size %d bytes exceeds max-output-bytes %d (spec '%s')",
                                bytes.length, budget.maxOutputBytes(), specId),
                        specId,
                        null);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Should never happen for a valid JsonNode; treat as eval error
            throw new io.messagexform.core.error.ExpressionEvalException(
                    "Failed to measure output size: " + e.getMessage(), e, specId, null);
        }
    }

    private void validateInputSchema(JsonNode input, TransformSpec spec) {
        JsonSchema schema = SCHEMA_FACTORY.getSchema(spec.inputSchema());
        Set<ValidationMessage> errors = schema.validate(input);
        if (!errors.isEmpty()) {
            String detail = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            throw new InputSchemaViolation(
                    String.format("Input schema violation in spec '%s': %s", spec.id(), detail), spec.id(), null);
        }
    }
}
