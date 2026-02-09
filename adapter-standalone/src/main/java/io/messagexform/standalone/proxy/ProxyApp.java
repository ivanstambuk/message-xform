package io.messagexform.standalone.proxy;

import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.messagexform.core.engine.EngineRegistry;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.standalone.adapter.StandaloneAdapter;
import io.messagexform.standalone.config.ConfigLoader;
import io.messagexform.standalone.config.ProxyConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the full proxy startup sequence (IMPL-004-05, FR-004-27).
 *
 * <p>
 * Lifecycle:
 * <ol>
 * <li>Load configuration from YAML + env overlay</li>
 * <li>Register expression engines (JSLT by default)</li>
 * <li>Validate TLS configuration</li>
 * <li>Load and compile all specs</li>
 * <li>Load active profile</li>
 * <li>Initialize the upstream HTTP client</li>
 * <li>Start the Javalin HTTP server</li>
 * <li>Start the file watcher (if enabled)</li>
 * </ol>
 *
 * <p>
 * This class is separate from {@link io.messagexform.standalone.StandaloneMain}
 * to allow clean integration testing without going through {@code main()}.
 */
public final class ProxyApp {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyApp.class);

    /** HTTP methods accepted by the proxy (FR-004-05). */
    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private final Javalin app;
    private final TransformEngine engine;
    private final FileWatcher fileWatcher;
    private final ProxyConfig config;

    private ProxyApp(Javalin app, TransformEngine engine, FileWatcher fileWatcher, ProxyConfig config) {
        this.app = app;
        this.engine = engine;
        this.fileWatcher = fileWatcher;
        this.config = config;
    }

    /**
     * Executes the full startup sequence and returns a running {@code ProxyApp}.
     *
     * @param args command-line arguments (e.g.
     *             {@code --config path/to/config.yaml})
     * @return a running proxy application
     * @throws Exception if any startup step fails
     */
    public static ProxyApp start(String[] args) throws Exception {
        long startTime = System.nanoTime();

        // 1. Load configuration (FR-004-10, FR-004-11)
        Path configPath = ConfigLoader.resolveConfigPath(args);
        ProxyConfig config = ConfigLoader.load(configPath);

        // 1b. Configure Logback based on logging.format + logging.level (NFR-004-07)
        LogbackConfigurator.configure(config.loggingFormat(), config.loggingLevel());

        LOG.info("Configuration loaded from {}", configPath);

        // 2. Register expression engines (FR-004-27 step 2)
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        LOG.info("Expression engines registered: [jslt]");

        // 3. Validate TLS configuration (S-004-43)
        TlsConfigValidator.validateInbound(config.proxyTls());
        TlsConfigValidator.validateOutbound(config.backendTls(), config.backendScheme());

        // 4. Load and compile specs (FR-004-27 step 3)
        SpecParser specParser = new SpecParser(registry);
        TransformEngine engine = new TransformEngine(specParser);

        Path specsDir = Path.of(config.specsDir());
        List<Path> specPaths = AdminReloadHandler.scanSpecFiles(specsDir);
        for (Path specPath : specPaths) {
            engine.loadSpec(specPath);
        }
        int specCount = specPaths.size();
        LOG.info("Specs loaded: {}", specCount);

        // 5. Load active profile (FR-004-27 step 4)
        String profileId = "none";
        if (config.profilePath() != null && !config.profilePath().isBlank()) {
            engine.loadProfile(Path.of(config.profilePath()));
            profileId = engine.activeProfile() != null ? engine.activeProfile().id() : "none";
        }
        LOG.info("Active profile: {}", profileId);

        // 6. Initialize upstream HTTP client (FR-004-27 step 5)
        UpstreamClient upstreamClient = new UpstreamClient(config);
        StandaloneAdapter adapter = new StandaloneAdapter();
        ProxyHandler proxyHandler = new ProxyHandler(
                engine, adapter, upstreamClient, config.maxBodyBytes(), config.forwardedHeadersEnabled());

        // 7. Start Javalin HTTP server (FR-004-27 step 6)
        Javalin app = Javalin.create(javalinConfig -> {
            // Configure inbound TLS if enabled (FR-004-14)
            if (config.proxyTls().enabled()) {
                TlsConfigurator.configureInboundTls(javalinConfig, config.proxyTls());
            }
        });

        // Register health/readiness endpoints (FR-004-21, FR-004-22)
        if (config.healthEnabled()) {
            app.get(config.healthPath(), new HealthHandler());
            app.get(
                    config.readyPath(),
                    new ReadinessHandler(
                            () -> engine.specCount() > 0 || specCount == 0,
                            config.backendHost(),
                            config.backendPort(),
                            config.backendConnectTimeoutMs()));
        }

        // Register admin reload endpoint (FR-004-20)
        Path profilePathObj =
                config.profilePath() != null && !config.profilePath().isBlank() ? Path.of(config.profilePath()) : null;
        app.post(config.adminReloadPath(), new AdminReloadHandler(engine, specsDir, profilePathObj));

        // Register proxy wildcard handler for all allowed methods (FR-004-05)
        app.before("/<path>", ctx -> {
            String method = ctx.method().name();
            if (!ALLOWED_METHODS.contains(method)) {
                ctx.status(405);
                ctx.contentType("application/problem+json");
                ctx.result(ProblemDetail.methodNotAllowed("HTTP method " + method + " is not supported", ctx.path())
                        .toString());
                ctx.skipRemainingHandlers();
            }
        });
        app.addHttpHandler(HandlerType.GET, "/<path>", proxyHandler);
        app.addHttpHandler(HandlerType.POST, "/<path>", proxyHandler);
        app.addHttpHandler(HandlerType.PUT, "/<path>", proxyHandler);
        app.addHttpHandler(HandlerType.DELETE, "/<path>", proxyHandler);
        app.addHttpHandler(HandlerType.PATCH, "/<path>", proxyHandler);
        app.addHttpHandler(HandlerType.HEAD, "/<path>", proxyHandler);
        app.addHttpHandler(HandlerType.OPTIONS, "/<path>", proxyHandler);

        app.start(config.proxyPort());
        int actualPort = app.port();

        // 8. Start file watcher (FR-004-27 step 7, FR-004-19)
        FileWatcher fileWatcher = null;
        if (config.reloadEnabled()) {
            Runnable reloadCallback = () -> {
                try {
                    List<Path> paths = AdminReloadHandler.scanSpecFiles(specsDir);
                    engine.reload(paths, profilePathObj);
                    LOG.info("Hot reload complete: {} specs", paths.size());
                } catch (Exception e) {
                    LOG.error("Hot reload failed: {}", e.getMessage(), e);
                }
            };
            fileWatcher = new FileWatcher(specsDir, config.reloadDebounceMs(), reloadCallback);
            fileWatcher.start();
        }

        // Log structured startup summary (FR-004-27, S-004-44)
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        LOG.info(
                "message-xform-proxy started: port={}, backend={}://{}:{}, specs={}, profile={}, engines=[jslt], startupMs={}",
                actualPort,
                config.backendScheme(),
                config.backendHost(),
                config.backendPort(),
                specCount,
                profileId,
                elapsedMs);

        return new ProxyApp(app, engine, fileWatcher, config);
    }

    /** Returns the port the proxy is listening on. */
    public int port() {
        return app.port();
    }

    /** Returns the Javalin application. */
    public Javalin javalin() {
        return app;
    }

    /** Returns the transform engine. */
    public TransformEngine engine() {
        return engine;
    }

    /** Returns the proxy configuration. */
    public ProxyConfig config() {
        return config;
    }

    /**
     * Stops the proxy: stops file watcher, stops Javalin server.
     */
    public void stop() {
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        if (app != null) {
            app.stop();
        }
        LOG.info("message-xform-proxy stopped");
    }
}
