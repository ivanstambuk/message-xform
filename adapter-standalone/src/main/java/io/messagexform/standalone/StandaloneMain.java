package io.messagexform.standalone;

/**
 * Entry point for the standalone HTTP proxy (FR-004-26, FR-004-27).
 *
 * <p>
 * This class bootstraps the proxy by loading configuration, initializing
 * the core {@code TransformEngine}, and starting the Javalin HTTP server.
 * Implementation will be added incrementally across Phase 3–7 tasks.
 */
public final class StandaloneMain {

    private StandaloneMain() {
        // utility class
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (e.g.
     *             {@code --config path/to/config.yaml})
     */
    public static void main(String[] args) {
        // Placeholder — implementation starts in T-004-08 (ProxyApp bootstrap)
        System.out.println("message-xform-proxy starting…");
    }
}
