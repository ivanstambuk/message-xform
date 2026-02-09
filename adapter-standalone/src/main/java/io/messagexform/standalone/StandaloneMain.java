package io.messagexform.standalone;

import io.messagexform.standalone.proxy.ProxyApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the standalone HTTP proxy (FR-004-26, FR-004-27).
 *
 * <p>
 * Delegates to {@link ProxyApp#start(String[])} for the full startup
 * sequence. On failure, logs the error and exits with a non-zero status
 * code (T-004-47, S-004-45).
 */
public final class StandaloneMain {

    private static final Logger LOG = LoggerFactory.getLogger(StandaloneMain.class);

    private StandaloneMain() {
        // utility class
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (e.g.
     *             {@code --config path/to/config.yaml})
     */
    @SuppressWarnings("SystemExitOutsideMain")
    public static void main(String[] args) {
        try {
            ProxyApp.start(args);
        } catch (Exception e) {
            LOG.error("Startup failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
