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
import io.messagexform.core.spi.TelemetryListener;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
 * Thread-safe: uses {@link AtomicReference} to hold an immutable
 * {@link TransformRegistry} snapshot. {@link #reload} atomically swaps the
 * entire registry so in-flight requests complete with the old snapshot while
 * new requests pick up the new one (NFR-001-05).
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
    private final TelemetryListener telemetryListener;
    private final AtomicReference<TransformRegistry> registryRef = new AtomicReference<>(TransformRegistry.empty());

    /**
     * Creates a new engine backed by the given spec parser, using the default
     * error response builder (HTTP 502).
     *
     * @param specParser the parser used to load and compile spec YAML files
     */
    public TransformEngine(SpecParser specParser) {
        this(specParser, new ErrorResponseBuilder(), EvalBudget.DEFAULT, SchemaValidationMode.LENIENT, null);
    }

    /**
     * Creates a new engine with a custom error response builder.
     *
     * @param specParser           the parser used to load and compile spec YAML
     *                             files
     * @param errorResponseBuilder the builder for RFC 9457 error responses
     */
    public TransformEngine(SpecParser specParser, ErrorResponseBuilder errorResponseBuilder) {
        this(specParser, errorResponseBuilder, EvalBudget.DEFAULT, SchemaValidationMode.LENIENT, null);
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
        this(specParser, errorResponseBuilder, budget, SchemaValidationMode.LENIENT, null);
    }

    /**
     * Creates a new engine with all configuration options (no telemetry listener).
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
        this(specParser, errorResponseBuilder, budget, schemaValidationMode, null);
    }

    /**
     * Creates a new engine with all configuration options and an optional
     * telemetry listener (T-001-42, NFR-001-09).
     *
     * @param specParser           the parser used to load and compile spec YAML
     *                             files
     * @param errorResponseBuilder the builder for RFC 9457 error responses
     * @param budget               evaluation budget (max-eval-ms, max-output-bytes)
     * @param schemaValidationMode STRICT or LENIENT (FR-001-09, CFG-001-09)
     * @param telemetryListener    optional listener for transform lifecycle events,
     *                             may be null
     */
    public TransformEngine(
            SpecParser specParser,
            ErrorResponseBuilder errorResponseBuilder,
            EvalBudget budget,
            SchemaValidationMode schemaValidationMode,
            TelemetryListener telemetryListener) {
        this.specParser = Objects.requireNonNull(specParser, "specParser must not be null");
        this.errorResponseBuilder =
                Objects.requireNonNull(errorResponseBuilder, "errorResponseBuilder must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.schemaValidationMode =
                Objects.requireNonNull(schemaValidationMode, "schemaValidationMode must not be null");
        this.telemetryListener = telemetryListener; // nullable
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
        try {
            TransformSpec spec = specParser.parse(path);
            // Atomically add the spec to the registry snapshot (by id and
            // id@version)
            registryRef.updateAndGet(old -> {
                Map<String, TransformSpec> updated = new HashMap<>(old.allSpecs());
                updated.put(spec.id(), spec);
                updated.put(spec.id() + "@" + spec.version(), spec);
                return new TransformRegistry(updated, old.activeProfile());
            });
            // T-001-42: Notify telemetry listener of successful spec load
            notifySpecLoaded(spec, path);
            return spec;
        } catch (Exception e) {
            // T-001-42: Notify telemetry listener of spec rejection
            notifySpecRejected(path, e);
            throw e;
        }
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
        TransformRegistry current = registryRef.get();
        ProfileParser profileParser = new ProfileParser(current.allSpecs());
        TransformProfile profile = profileParser.parse(path);
        registryRef.updateAndGet(old -> new TransformRegistry(old.allSpecs(), profile));
        return profile;
    }

    /**
     * Returns the currently active profile, or {@code null} if no profile is
     * loaded.
     */
    public TransformProfile activeProfile() {
        return registryRef.get().activeProfile();
    }

    /**
     * Atomically reloads the engine with a fresh set of specs and an optional
     * profile (T-001-46, NFR-001-05, API-001-04).
     *
     * <p>
     * Builds a new {@link TransformRegistry} from the given spec files and
     * profile, then atomically swaps it in. In-flight requests that captured
     * the old registry reference via {@link #transform} will complete with it;
     * new requests pick up the new registry.
     *
     * @param specPaths   paths to spec YAML files to load
     * @param profilePath optional profile YAML file, or null for no profile
     * @throws io.messagexform.core.error.SpecParseException         if any spec
     *                                                               fails to parse
     * @throws io.messagexform.core.error.ExpressionCompileException if any
     *                                                               expression
     *                                                               fails
     *                                                               to compile
     * @throws io.messagexform.core.error.ProfileResolveException    if the profile
     *                                                               cannot be
     *                                                               resolved
     */
    public void reload(List<Path> specPaths, Path profilePath) {
        // Build new registry from scratch
        TransformRegistry.Builder builder = TransformRegistry.builder();
        for (Path specPath : specPaths) {
            TransformSpec spec = specParser.parse(specPath);
            builder.addSpec(spec);
            notifySpecLoaded(spec, specPath);
        }

        // Resolve profile against the new spec set
        TransformProfile profile = null;
        if (profilePath != null) {
            TransformRegistry tempRegistry = builder.build();
            ProfileParser profileParser = new ProfileParser(tempRegistry.allSpecs());
            profile = profileParser.parse(profilePath);
            builder.activeProfile(profile);
        }

        // Atomic swap — in-flight requests keep old reference
        TransformRegistry newRegistry = builder.build();
        registryRef.set(newRegistry);
        LOG.info(
                "Registry reloaded: specs={}, profile={}",
                newRegistry.specCount(),
                profile != null ? profile.id() : "none");
    }

    /**
     * Returns the current registry snapshot. Primarily for testing and
     * introspection.
     *
     * @return the current immutable registry
     */
    public TransformRegistry registry() {
        return registryRef.get();
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

        // T-001-44: Propagate trace context headers to MDC (NFR-001-10)
        setTraceContext(message);
        try {
            return transformInternal(message, direction);
        } finally {
            clearTraceContext();
        }
    }

    /**
     * Internal transform logic — separated to allow try-finally MDC cleanup in
     * {@link #transform(Message, Direction)}.
     */
    private TransformResult transformInternal(Message message, Direction direction) {
        // Capture the registry snapshot — in-flight request uses this snapshot
        // even if reload() swaps in a new registry concurrently (NFR-001-05).
        TransformRegistry snapshot = registryRef.get();

        // Phase 5: Profile-based routing via ProfileMatcher
        TransformProfile profile = snapshot.activeProfile();
        if (profile != null) {
            List<ProfileEntry> matches = ProfileMatcher.findMatches(
                    profile, message.requestPath(), message.requestMethod(), message.contentType(), direction);
            if (matches.isEmpty()) {
                return TransformResult.passthrough();
            }
            // Single match → direct transform (common fast path)
            if (matches.size() == 1) {
                ProfileEntry entry = matches.get(0);
                LogContext logCtx =
                        new LogContext(profile.id(), entry.specificityScore(), message.requestPath(), direction, null);
                return transformWithSpec(entry.spec(), message, direction, logCtx);
            }
            // Multiple matches → pipeline chain (T-001-31, ADR-0012, S-001-49)
            return transformChain(matches, message, direction);
        }

        // Phase 4 fallback: single-spec mode (no profile loaded)
        Map<String, TransformSpec> allSpecs = snapshot.allSpecs();
        if (allSpecs.isEmpty()) {
            return TransformResult.passthrough();
        }

        // Use the first loaded spec (for backward compatibility with Phase 4 tests)
        TransformSpec spec = allSpecs.values().iterator().next();
        return transformWithSpec(spec, message, direction, null);
    }

    /**
     * Executes a pipeline chain of transforms (T-001-31, ADR-0012).
     * Each step's output message feeds the next step. If any step fails,
     * the entire chain aborts — no partial results reach the caller.
     */
    private TransformResult transformChain(List<ProfileEntry> chain, Message message, Direction direction) {
        TransformProfile profile = registryRef.get().activeProfile();
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

            LogContext logCtx =
                    new LogContext(profileId, entry.specificityScore(), message.requestPath(), direction, stepLabel);
            TransformResult stepResult = transformWithSpec(entry.spec(), current, direction, logCtx);

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
     *
     * @param logCtx optional logging context for structured log emission (T-001-41,
     *               NFR-001-08);
     *               null when no profile is loaded (Phase 4 fallback)
     */
    private TransformResult transformWithSpec(
            TransformSpec spec, Message message, Direction direction, LogContext logCtx) {
        // Resolve the expression based on directionality
        CompiledExpression expr = resolveExpression(spec, direction);

        // Build context from message metadata (T-001-21, FR-001-10/11).
        // For REQUEST transforms, $status is null (ADR-0017).
        Integer status = direction == Direction.RESPONSE ? message.statusCode() : null;
        TransformContext context = new TransformContext(message.headers(), message.headersAll(), status, null, null);

        // Save the original body for URL rewriting (ADR-0027: "route the input")
        JsonNode originalBody = message.body();

        // T-001-42: Notify telemetry listener of transform start
        notifyTransformStarted(spec, direction);

        // Evaluate the expression — catch eval exceptions per ADR-0022
        long startNanos = System.nanoTime();
        try {
            // T-001-26: Strict-mode input schema validation
            if (schemaValidationMode == SchemaValidationMode.STRICT && spec.inputSchema() != null) {
                validateInputSchema(message.body(), spec);
            }

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

            // T-001-41: Emit structured log entry for matched transform (NFR-001-08)
            emitTransformMatchedLog(spec, elapsedMs, logCtx);

            // T-001-42: Notify telemetry listener of successful completion
            notifyTransformCompleted(spec, direction, elapsedMs);

            // T-001-42: Notify profile matched event
            notifyProfileMatched(spec, logCtx);

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
            long failedMs = (System.nanoTime() - startNanos) / 1_000_000;
            // T-001-42: Notify telemetry listener of transform failure
            notifyTransformFailed(spec, direction, failedMs, e.getMessage());
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
        return registryRef.get().specCount();
    }

    // --- Private helpers ---

    /**
     * Logging context for structured log entries (T-001-41, NFR-001-08).
     * Captures profile-level metadata that is not available inside
     * {@code transformWithSpec}.
     *
     * @param profileId        the matched profile id
     * @param specificityScore the specificity score of the matched entry
     * @param requestPath      the request path
     * @param direction        the transform direction
     * @param chainStep        optional chain step label (e.g. "1/3"), null for
     *                         single match
     */
    private record LogContext(
            String profileId, int specificityScore, String requestPath, Direction direction, String chainStep) {}

    /**
     * Emits a structured log entry for a matched transform (T-001-41, NFR-001-08).
     * Uses SLF4J 2.0 fluent API with key-value pairs for structured logging.
     */
    private void emitTransformMatchedLog(TransformSpec spec, long evalDurationMs, LogContext logCtx) {
        if (logCtx == null) {
            // Phase 4 fallback — no profile context available
            return;
        }
        var builder = LOG.atInfo()
                .addKeyValue("profile_id", logCtx.profileId())
                .addKeyValue("spec_id", spec.id())
                .addKeyValue("spec_version", spec.version())
                .addKeyValue("request_path", logCtx.requestPath())
                .addKeyValue("specificity_score", logCtx.specificityScore())
                .addKeyValue("eval_duration_ms", evalDurationMs)
                .addKeyValue("direction", logCtx.direction().name());
        if (logCtx.chainStep() != null) {
            builder = builder.addKeyValue("chain_step", logCtx.chainStep());
        }
        builder.log("transform.matched");
    }

    // --- Telemetry notification helpers (T-001-42, NFR-001-09) ---
    // Listener exceptions are caught and logged — they MUST NOT affect
    // transform execution.

    private void notifyTransformStarted(TransformSpec spec, Direction direction) {
        if (telemetryListener == null) return;
        try {
            telemetryListener.onTransformStarted(
                    new TelemetryListener.TransformStartedEvent(spec.id(), spec.version(), direction));
        } catch (Exception e) {
            LOG.warn("TelemetryListener.onTransformStarted failed: {}", e.getMessage());
        }
    }

    private void notifyTransformCompleted(TransformSpec spec, Direction direction, long durationMs) {
        if (telemetryListener == null) return;
        try {
            telemetryListener.onTransformCompleted(
                    new TelemetryListener.TransformCompletedEvent(spec.id(), spec.version(), direction, durationMs));
        } catch (Exception e) {
            LOG.warn("TelemetryListener.onTransformCompleted failed: {}", e.getMessage());
        }
    }

    private void notifyTransformFailed(TransformSpec spec, Direction direction, long durationMs, String errorDetail) {
        if (telemetryListener == null) return;
        try {
            telemetryListener.onTransformFailed(new TelemetryListener.TransformFailedEvent(
                    spec.id(), spec.version(), direction, durationMs, errorDetail));
        } catch (Exception e) {
            LOG.warn("TelemetryListener.onTransformFailed failed: {}", e.getMessage());
        }
    }

    private void notifyProfileMatched(TransformSpec spec, LogContext logCtx) {
        if (telemetryListener == null || logCtx == null) return;
        try {
            telemetryListener.onProfileMatched(new TelemetryListener.ProfileMatchedEvent(
                    logCtx.profileId(), spec.id(), spec.version(), logCtx.requestPath(), logCtx.specificityScore()));
        } catch (Exception e) {
            LOG.warn("TelemetryListener.onProfileMatched failed: {}", e.getMessage());
        }
    }

    private void notifySpecLoaded(TransformSpec spec, Path path) {
        if (telemetryListener == null) return;
        try {
            telemetryListener.onSpecLoaded(
                    new TelemetryListener.SpecLoadedEvent(spec.id(), spec.version(), path.toString()));
        } catch (Exception e) {
            LOG.warn("TelemetryListener.onSpecLoaded failed: {}", e.getMessage());
        }
    }

    private void notifySpecRejected(Path path, Exception cause) {
        if (telemetryListener == null) return;
        try {
            telemetryListener.onSpecRejected(
                    new TelemetryListener.SpecRejectedEvent(path.toString(), cause.getMessage()));
        } catch (Exception e) {
            LOG.warn("TelemetryListener.onSpecRejected failed: {}", e.getMessage());
        }
    }

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

    // --- Trace context propagation (T-001-44, NFR-001-10) ---

    /** MDC key for X-Request-ID header. */
    private static final String MDC_REQUEST_ID = "requestId";
    /** MDC key for traceparent header (W3C Trace Context). */
    private static final String MDC_TRACEPARENT = "traceparent";

    /**
     * Extracts trace context headers from the message and sets them in MDC.
     * Called at the start of {@link #transform(Message, Direction)}.
     */
    private void setTraceContext(Message message) {
        String requestId = message.headers().get("x-request-id");
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }
        String traceparent = message.headers().get("traceparent");
        if (traceparent != null && !traceparent.isBlank()) {
            MDC.put(MDC_TRACEPARENT, traceparent);
        }
    }

    /**
     * Clears trace context from MDC. Called in finally block of
     * {@link #transform(Message, Direction)}.
     */
    private void clearTraceContext() {
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_TRACEPARENT);
    }
}
