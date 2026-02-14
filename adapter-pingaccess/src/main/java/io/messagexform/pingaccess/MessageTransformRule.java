package io.messagexform.pingaccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.Exchange;
import com.pingidentity.pa.sdk.http.ExchangeProperty;
import com.pingidentity.pa.sdk.http.HttpStatus;
import com.pingidentity.pa.sdk.http.Response;
import com.pingidentity.pa.sdk.http.ResponseBuilder;
import com.pingidentity.pa.sdk.interceptor.Outcome;
import com.pingidentity.pa.sdk.policy.AsyncRuleInterceptorBase;
import com.pingidentity.pa.sdk.policy.ErrorHandlingCallback;
import com.pingidentity.pa.sdk.policy.Rule;
import com.pingidentity.pa.sdk.policy.RuleInterceptorCategory;
import com.pingidentity.pa.sdk.policy.RuleInterceptorSupportedDestination;
import com.pingidentity.pa.sdk.policy.config.ErrorHandlerConfiguration;
import com.pingidentity.pa.sdk.policy.error.RuleInterceptorErrorHandlingCallback;
import com.pingidentity.pa.sdk.ui.ConfigurationBuilder;
import com.pingidentity.pa.sdk.ui.ConfigurationField;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.SchemaValidationMode;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ValidationException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PingAccess rule interceptor that applies JSON-to-JSON transforms via the
 * core engine (FR-002-02, FR-002-03).
 *
 * <p>
 * Extends {@link AsyncRuleInterceptorBase} for future extensibility
 * (see spec §FR-002-02 async justification). The rule is registered with
 * {@code destination = Site} — agent rules cannot access request body or
 * handle responses (FR-002-03, Constraint 1).
 *
 * <p>
 * <strong>Lifecycle:</strong>
 * <ol>
 * <li>{@code @Rule} annotation read by PA for admin UI registration.</li>
 * <li>{@code configure()} initializes engine, parser, and loads specs.</li>
 * <li>{@code handleRequest()} / {@code handleResponse()} process
 * exchanges.</li>
 * <li>{@code @PreDestroy} shuts down any managed resources.</li>
 * </ol>
 */
@Rule(
        category = RuleInterceptorCategory.Processing,
        destination = {RuleInterceptorSupportedDestination.Site},
        label = "Message Transform",
        type = "MessageTransform",
        expectedConfiguration = MessageTransformConfig.class)
public class MessageTransformRule extends AsyncRuleInterceptorBase<MessageTransformConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(MessageTransformRule.class);

    // ── ExchangeProperty declarations (FR-002-07) ──

    /** Guard flag — set to {@code true} when DENY mode rejects a request. */
    static final ExchangeProperty<Boolean> TRANSFORM_DENIED =
            ExchangeProperty.create("io.messagexform", "transformDenied", Boolean.class);

    /** Summary of the last transform result for downstream rule consumption. */
    static final ExchangeProperty<TransformResultSummary> TRANSFORM_RESULT =
            ExchangeProperty.create("io.messagexform", "transformResult", TransformResultSummary.class);

    private TransformEngine engine;
    private PingAccessAdapter adapter;
    private ErrorMode errorMode = ErrorMode.PASS_THROUGH;
    private ScheduledExecutorService reloadExecutor;
    private MessageTransformConfig currentConfig;
    private MessageTransformMetrics metrics;
    private ObjectName jmxObjectName;

    /**
     * Factory for building error responses during DENY mode. Defaults to
     * {@link ResponseBuilder}; overridden in tests where ServiceFactory is
     * unavailable.
     */
    private BiFunction<HttpStatus, String, Response> responseFactory =
            (status, body) -> ResponseBuilder.newInstance(status)
                    .header("Content-Type", "application/problem+json")
                    .body(body != null ? body : "")
                    .build();

    // ── AsyncRuleInterceptor<MessageTransformConfig> ──

    /**
     * Initializes the core transform engine from the plugin configuration
     * (FR-002-05, step 6).
     *
     * <p>
     * At this point, bean validation has already run (PA 5.0+). This method:
     * <ol>
     * <li>Validates that {@code specsDir} is an existing directory.</li>
     * <li>Builds the engine with JSLT expression engine registered.</li>
     * <li>Loads all {@code .yaml}/{@code .yml} spec files from specsDir.</li>
     * <li>Optionally loads a profile from profilesDir if
     * {@code activeProfile} is set.</li>
     * </ol>
     *
     * @throws ValidationException if specsDir does not exist or spec parsing fails
     */
    @Override
    public void configure(MessageTransformConfig config) throws ValidationException {
        super.configure(config);

        // T-002-13: Validate specsDir exists
        Path specsPath = Paths.get(config.getSpecsDir());
        if (!Files.isDirectory(specsPath)) {
            throw new ValidationException("specsDir does not exist or is not a directory: " + config.getSpecsDir());
        }

        // Initialize engine with JSLT expression engine
        EngineRegistry engineRegistry = new EngineRegistry();
        engineRegistry.register(new JsltExpressionEngine());

        // Map schema validation mode from PA enum to core enum
        SchemaValidationMode svMode = config.getSchemaValidation() == SchemaValidation.STRICT
                ? SchemaValidationMode.STRICT
                : SchemaValidationMode.LENIENT;

        SpecParser specParser = new SpecParser(engineRegistry);
        engine = new TransformEngine(
                specParser,
                new io.messagexform.core.engine.ErrorResponseBuilder(),
                io.messagexform.core.engine.EvalBudget.DEFAULT,
                svMode);

        // Initialize adapter
        adapter = new PingAccessAdapter(new ObjectMapper());

        // Load specs from specsDir
        List<Path> specFiles = collectYamlFiles(specsPath);
        if (specFiles.isEmpty()) {
            LOG.warn("No spec files found in {}", specsPath);
        }
        for (Path specFile : specFiles) {
            try {
                engine.loadSpec(specFile);
                LOG.info("Loaded spec: {}", specFile.getFileName());
            } catch (Exception e) {
                throw new ValidationException("Failed to load spec " + specFile + ": " + e.getMessage());
            }
        }

        // Load profile if configured
        String activeProfile = config.getActiveProfile();
        if (activeProfile != null && !activeProfile.isEmpty()) {
            Path profilesPath = Paths.get(config.getProfilesDir());
            Path profileFile = profilesPath.resolve(activeProfile + ".yaml");
            if (!Files.isRegularFile(profileFile)) {
                profileFile = profilesPath.resolve(activeProfile + ".yml");
            }
            if (Files.isRegularFile(profileFile)) {
                try {
                    engine.loadProfile(profileFile);
                    LOG.info("Loaded profile: {} from {}", activeProfile, profileFile);
                } catch (Exception e) {
                    throw new ValidationException("Failed to load profile " + activeProfile + ": " + e.getMessage());
                }
            } else {
                throw new ValidationException("Profile file not found: " + activeProfile + " in " + profilesPath);
            }
        }

        // Wire error mode for runtime dispatch
        this.errorMode = config.getErrorMode();
        this.currentConfig = config;

        // Start reload scheduler if interval > 0 (FR-002-04, T-002-25)
        int reloadSec = config.getReloadIntervalSec();
        if (reloadSec > 0) {
            reloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mxform-spec-reload");
                t.setDaemon(true);
                return t;
            });
            reloadExecutor.scheduleWithFixedDelay(this::reloadSpecs, reloadSec, reloadSec, TimeUnit.SECONDS);
            LOG.info("Hot-reload scheduler started: interval={}s", reloadSec);
        }

        // Register JMX MBean if enabled (FR-002-14, T-002-28)
        this.metrics = new MessageTransformMetrics();
        if (config.getEnableJmxMetrics()) {
            try {
                String instanceName = config.getName() != null ? config.getName() : "default";
                jmxObjectName = new ObjectName("io.messagexform:type=TransformMetrics,instance=" + instanceName);
                MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
                if (!mbs.isRegistered(jmxObjectName)) {
                    mbs.registerMBean(metrics, jmxObjectName);
                }
                LOG.info("JMX MBean registered: {}", jmxObjectName);
            } catch (Exception e) {
                LOG.warn("Failed to register JMX MBean: {}", e.getMessage());
                jmxObjectName = null;
            }
        }
        metrics.setActiveSpecCount(engine.specCount());

        LOG.info(
                "MessageTransformRule configured: specsDir={}, specs={}, profile={}",
                config.getSpecsDir(),
                engine.specCount(),
                activeProfile != null ? activeProfile : "none");
    }

    // ── Request/Response Orchestration (Phase 5 — T-002-20..T-002-24) ──

    /**
     * Request orchestration (T-002-20). Wraps the exchange, transforms, and
     * dispatches based on outcome.
     *
     * <p>
     * The {@code bodyParseFailed} skip-guard (S-002-08) ensures that when the
     * request body fails to parse as JSON, only header/URL transforms apply —
     * original raw bytes pass through to the backend.
     *
     * @return CONTINUE or RETURN (DENY only)
     */
    @Override
    public CompletionStage<Outcome> handleRequest(Exchange exchange) {
        long start = System.nanoTime();

        // 1. Wrap
        Message wrapped = adapter.wrapRequest(exchange);
        boolean bodyParseFailed = adapter.isBodyParseFailed();
        List<String> originalHeaderNames =
                new ArrayList<>(wrapped.headers().toSingleValueMap().keySet());

        // 2. Context
        TransformContext context = adapter.buildTransformContext(exchange, null, wrapped.session());

        // 3. Transform
        TransformResult result = engine.transform(wrapped, Direction.REQUEST, context);

        long durationMs = (System.nanoTime() - start) / 1_000_000;

        // 4. Dispatch
        return switch (result.type()) {
            case SUCCESS -> {
                if (bodyParseFailed) {
                    // S-002-08: apply header/URL only, skip body
                    adapter.applyRequestChangesSkipBody(result.message(), exchange, originalHeaderNames);
                    LOG.debug(
                            "Request transform SUCCESS (body skipped — parse failed): {} {}",
                            wrapped.requestMethod(),
                            wrapped.requestPath());
                } else {
                    adapter.applyRequestChanges(result.message(), exchange, originalHeaderNames);
                    LOG.debug("Request transform SUCCESS: {} {}", wrapped.requestMethod(), wrapped.requestPath());
                }
                setTransformResultProperty(exchange, result, "REQUEST", durationMs);
                yield CompletableFuture.completedFuture(Outcome.CONTINUE);
            }
            case PASSTHROUGH -> {
                LOG.debug("Request transform PASSTHROUGH: {} {}", wrapped.requestMethod(), wrapped.requestPath());
                setTransformResultProperty(exchange, result, "REQUEST", durationMs);
                yield CompletableFuture.completedFuture(Outcome.CONTINUE);
            }
            case ERROR -> handleRequestError(exchange, result, wrapped, durationMs);
        };
    }

    /**
     * Response orchestration (T-002-21). Includes the DENY guard (T-002-24)
     * that skips processing when the request was previously denied.
     */
    @Override
    public CompletionStage<Void> handleResponse(Exchange exchange) {
        // T-002-24: DENY guard — skip response processing if request was denied
        if (exchange.isPropertyTrue(TRANSFORM_DENIED)) {
            LOG.debug("Response processing skipped — request was DENIED");
            return CompletableFuture.completedFuture(null);
        }

        long start = System.nanoTime();

        // 1. Wrap
        Message wrapped = adapter.wrapResponse(exchange);
        boolean bodyParseFailed = adapter.isBodyParseFailed();
        List<String> originalHeaderNames =
                new ArrayList<>(wrapped.headers().toSingleValueMap().keySet());

        // 2. Context
        Integer status = wrapped.statusCode();
        TransformContext context = adapter.buildTransformContext(exchange, status, wrapped.session());

        // 3. Transform
        TransformResult result = engine.transform(wrapped, Direction.RESPONSE, context);

        long durationMs = (System.nanoTime() - start) / 1_000_000;

        // 4. Dispatch
        switch (result.type()) {
            case SUCCESS -> {
                if (bodyParseFailed) {
                    adapter.applyResponseChangesSkipBody(result.message(), exchange, originalHeaderNames);
                    LOG.debug(
                            "Response transform SUCCESS (body skipped — parse failed): {} {} -> {}",
                            wrapped.requestMethod(),
                            wrapped.requestPath(),
                            status);
                } else {
                    adapter.applyResponseChanges(result.message(), exchange, originalHeaderNames);
                    LOG.debug(
                            "Response transform SUCCESS: {} {} -> {}",
                            wrapped.requestMethod(),
                            wrapped.requestPath(),
                            status);
                }
                setTransformResultProperty(exchange, result, "RESPONSE", durationMs);
            }
            case PASSTHROUGH -> {
                LOG.debug(
                        "Response transform PASSTHROUGH: {} {} -> {}",
                        wrapped.requestMethod(),
                        wrapped.requestPath(),
                        status);
                setTransformResultProperty(exchange, result, "RESPONSE", durationMs);
            }
            case ERROR -> handleResponseError(exchange, result, wrapped, durationMs);
        }

        return CompletableFuture.completedFuture(null);
    }

    // ── Error handlers ──

    /**
     * Handles ERROR result in request phase. PASS_THROUGH logs and continues;
     * DENY builds an RFC 9457 response, sets it on the exchange, and returns
     * RETURN.
     */
    private CompletionStage<Outcome> handleRequestError(
            Exchange exchange, TransformResult result, Message wrapped, long durationMs) {
        setTransformResultProperty(exchange, result, "REQUEST", durationMs);

        if (errorMode == ErrorMode.PASS_THROUGH) {
            LOG.warn(
                    "Request transform error (PASS_THROUGH): {} {} — forwarding original",
                    wrapped.requestMethod(),
                    wrapped.requestPath());
            return CompletableFuture.completedFuture(Outcome.CONTINUE);
        }

        // DENY mode: build RFC 9457 error response
        LOG.warn(
                "Request transform error (DENY): {} {} — rejecting with {}",
                wrapped.requestMethod(),
                wrapped.requestPath(),
                result.errorStatusCode());

        String errorBody = result.errorResponse().asString();
        Response errorResponse = responseFactory.apply(HttpStatus.forCode(result.errorStatusCode()), errorBody);
        exchange.setResponse(errorResponse);
        exchange.setProperty(TRANSFORM_DENIED, Boolean.TRUE);

        return CompletableFuture.completedFuture(Outcome.RETURN);
    }

    /**
     * Handles ERROR result in response phase. PASS_THROUGH logs and preserves
     * the original response; DENY rewrites in-place to 502 with the error body.
     */
    private void handleResponseError(Exchange exchange, TransformResult result, Message wrapped, long durationMs) {
        setTransformResultProperty(exchange, result, "RESPONSE", durationMs);

        if (errorMode == ErrorMode.PASS_THROUGH) {
            LOG.warn(
                    "Response transform error (PASS_THROUGH): {} {} -> {} — preserving original",
                    wrapped.requestMethod(),
                    wrapped.requestPath(),
                    wrapped.statusCode());
            return;
        }

        // DENY mode: rewrite response in-place
        LOG.warn(
                "Response transform error (DENY): {} {} -> {} — rewriting to {}",
                wrapped.requestMethod(),
                wrapped.requestPath(),
                wrapped.statusCode(),
                result.errorStatusCode());

        String errorBody = result.errorResponse().asString();
        Response resp = exchange.getResponse();
        resp.setStatus(HttpStatus.forCode(result.errorStatusCode()));
        resp.setBodyContent(errorBody != null ? errorBody.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        resp.getHeaders().setContentType("application/problem+json");
    }

    /**
     * Sets the {@link TransformResultSummary} exchange property for downstream
     * rule consumption (FR-002-07).
     */
    private void setTransformResultProperty(
            Exchange exchange, TransformResult result, String direction, long durationMs) {
        String outcome = result.type().name();
        String errorType = result.isError() ? "TRANSFORM_ERROR" : null;
        String errorMessage = result.isError() && result.errorResponse() != null
                ? result.errorResponse().asString()
                : null;

        exchange.setProperty(
                TRANSFORM_RESULT,
                new TransformResultSummary(
                        result.specId(),
                        result.specVersion(),
                        direction,
                        durationMs,
                        outcome,
                        errorType,
                        errorMessage));
    }

    @Override
    public ErrorHandlingCallback getErrorHandlingCallback() {
        // The SDK callback needs ErrorHandlerConfiguration. Create an inline
        // implementation with sensible defaults (used for unhandled exceptions
        // only — transform failures are handled by our own error-mode logic).
        ErrorHandlerConfiguration errorConfig = new ErrorHandlerConfiguration() {
            @Override
            public int getErrorResponseCode() {
                return 500;
            }

            @Override
            public String getErrorResponseStatusMsg() {
                return "Internal Server Error";
            }

            @Override
            public String getErrorResponseTemplateFile() {
                return "general.error.page";
            }

            @Override
            public String getErrorResponseContentType() {
                return "text/html";
            }
        };
        return new RuleInterceptorErrorHandlingCallback(getTemplateRenderer(), errorConfig);
    }

    @Override
    public List<ConfigurationField> getConfigurationFields() {
        return ConfigurationBuilder.from(MessageTransformConfig.class).toConfigurationFields();
    }

    // ── Test-visible setters ──

    /** Sets the engine (test injection). */
    void setEngine(TransformEngine engine) {
        this.engine = engine;
    }

    /** Sets the adapter (test injection). */
    void setAdapter(PingAccessAdapter adapter) {
        this.adapter = adapter;
    }

    /** Sets the error mode (test injection). */
    void setErrorMode(ErrorMode errorMode) {
        this.errorMode = errorMode;
    }

    /**
     * Sets the response factory (test injection — ResponseBuilder requires PA
     * runtime).
     */
    void setResponseFactory(BiFunction<HttpStatus, String, Response> responseFactory) {
        this.responseFactory = responseFactory;
    }

    // ── Lifecycle ──

    /**
     * Shuts down the reload scheduler and releases managed resources
     * (T-002-14, T-002-25, FR-002-05).
     *
     * <p>
     * Belt-and-suspenders with the daemon thread flag — ensures cleanup
     * even in non-graceful shutdown scenarios.
     */
    @PreDestroy
    void shutdown() {
        if (reloadExecutor != null) {
            reloadExecutor.shutdownNow();
            LOG.info("Hot-reload scheduler shut down");
        }
        // Unregister JMX MBean if registered (FR-002-14, T-002-28)
        if (jmxObjectName != null) {
            try {
                MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
                if (mbs.isRegistered(jmxObjectName)) {
                    mbs.unregisterMBean(jmxObjectName);
                    LOG.info("JMX MBean unregistered: {}", jmxObjectName);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister JMX MBean: {}", e.getMessage());
            }
            jmxObjectName = null;
        }
    }

    // ── Internal helpers ──

    /**
     * Reload task executed by the scheduler (T-002-26, FR-002-04).
     *
     * <p>
     * Resolves spec/profile paths from the current config and calls
     * {@link TransformEngine#reload(List, Path)}. On any failure, logs a
     * warning and retains the previous valid registry (NFR-001-05).
     */
    private void reloadSpecs() {
        try {
            Path specsPath = Paths.get(currentConfig.getSpecsDir());
            List<Path> specFiles = collectYamlFiles(specsPath);

            // Resolve profile path (null if no profile configured)
            Path profilePath = null;
            String activeProfile = currentConfig.getActiveProfile();
            if (activeProfile != null && !activeProfile.isEmpty()) {
                Path profilesPath = Paths.get(currentConfig.getProfilesDir());
                profilePath = profilesPath.resolve(activeProfile + ".yaml");
                if (!Files.isRegularFile(profilePath)) {
                    profilePath = profilesPath.resolve(activeProfile + ".yml");
                }
                if (!Files.isRegularFile(profilePath)) {
                    LOG.warn("Hot-reload: profile file not found: {} in {}", activeProfile, profilesPath);
                    return;
                }
            }

            engine.reload(specFiles, profilePath);
            LOG.debug("Hot-reload completed: specs={}", engine.specCount());
            if (metrics != null) {
                metrics.recordReloadSuccess();
                metrics.setActiveSpecCount(engine.specCount());
            }
        } catch (Exception e) {
            // Retain previous valid registry on any failure (S-002-30)
            LOG.warn("Hot-reload failed — retaining previous registry: {}", e.getMessage());
            if (metrics != null) {
                metrics.recordReloadFailure();
            }
        }
    }

    /** Accessor for the adapter — used by flow tests. */
    PingAccessAdapter adapter() {
        return adapter;
    }

    /** Accessor for the engine — used by flow tests. */
    TransformEngine engine() {
        return engine;
    }

    /** Accessor for the reload executor — used by lifecycle tests. */
    ScheduledExecutorService reloadExecutor() {
        return reloadExecutor;
    }

    /** Accessor for the metrics — used by JMX integration tests. */
    MessageTransformMetrics metrics() {
        return metrics;
    }

    /**
     * Collects all {@code .yaml} and {@code .yml} files from the given
     * directory (non-recursive).
     */
    private List<Path> collectYamlFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.{yaml,yml}")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan spec directory {}: {}", dir, e.getMessage());
        }
        return files;
    }
}
