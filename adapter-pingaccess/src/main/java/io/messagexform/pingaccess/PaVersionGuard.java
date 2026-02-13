package io.messagexform.pingaccess;

import com.fasterxml.jackson.databind.cfg.PackageVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime misdeployment guard (ADR-0035).
 *
 * <p>
 * Compares the PA dependency versions baked at build time with detected runtime
 * versions.
 * Logs a WARN if versions differ — the operator should deploy the adapter
 * version matching
 * their PA installation.
 */
final class PaVersionGuard {

    private static final Logger LOG = LoggerFactory.getLogger(PaVersionGuard.class);
    private static final String PROPS_PATH = "META-INF/message-xform/pa-compiled-versions.properties";

    private PaVersionGuard() {}

    /**
     * Check PA runtime dependency versions and warn on mismatch.
     * Called once during plugin {@code configure()}.
     */
    static void check() {
        Properties compiled = loadCompiledVersions();
        if (compiled.isEmpty()) {
            LOG.warn("Cannot read compiled PA versions from {} — version guard skipped", PROPS_PATH);
            return;
        }

        String compiledJackson = compiled.getProperty("pa.jackson.version", "unknown");
        String compiledSdk = compiled.getProperty("pa.sdk.version", "unknown");

        // Detect runtime Jackson version via PackageVersion (jackson-databind).
        // Direct static access — no reflection needed (NFR-002-04).
        // Jackson is PA-provided (compileOnly) and always on the runtime classpath.
        String runtimeJackson = PackageVersion.VERSION.toString();

        if (!runtimeJackson.equals(compiledJackson)) {
            LOG.warn(
                    "Adapter compiled for Jackson {} but running with Jackson {}. "
                            + "Deploy adapter version matching this PA instance (ADR-0035).",
                    compiledJackson,
                    runtimeJackson);
        }

        LOG.info(
                "PA version guard: compiled against SDK={}, Jackson={}, runtime Jackson={}",
                compiledSdk,
                compiledJackson,
                runtimeJackson);
    }

    private static Properties loadCompiledVersions() {
        Properties props = new Properties();
        try (InputStream is = PaVersionGuard.class.getClassLoader().getResourceAsStream(PROPS_PATH)) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            LOG.debug("Failed to load compiled PA versions", e);
        }
        return props;
    }
}
