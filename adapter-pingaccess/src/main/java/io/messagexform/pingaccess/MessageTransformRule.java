package io.messagexform.pingaccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.Exchange;
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
import io.messagexform.core.spec.SpecParser;
import jakarta.validation.ValidationException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    private TransformEngine engine;
    private PingAccessAdapter adapter;

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

        LOG.info(
                "MessageTransformRule configured: specsDir={}, specs={}, profile={}",
                config.getSpecsDir(),
                engine.specCount(),
                activeProfile != null ? activeProfile : "none");
    }

    @Override
    public CompletionStage<Outcome> handleRequest(Exchange exchange) {
        // Stub — full orchestration in I7 (T-002-20)
        return CompletableFuture.completedFuture(Outcome.CONTINUE);
    }

    @Override
    public CompletionStage<Void> handleResponse(Exchange exchange) {
        // Stub — full orchestration in I7 (T-002-21)
        return CompletableFuture.completedFuture(null);
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

    // ── Internal helpers ──

    /** Accessor for the adapter — used by flow tests. */
    PingAccessAdapter adapter() {
        return adapter;
    }

    /** Accessor for the engine — used by flow tests. */
    TransformEngine engine() {
        return engine;
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
