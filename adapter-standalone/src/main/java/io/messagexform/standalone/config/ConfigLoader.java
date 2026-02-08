package io.messagexform.standalone.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link ProxyConfig} from a YAML file (T-004-06, FR-004-10).
 *
 * <p>
 * Supports two invocation patterns:
 * <ul>
 * <li>Default: loads {@code message-xform-proxy.yaml} from the current
 * directory</li>
 * <li>{@code --config /path/to/config.yaml}: loads from the specified path</li>
 * </ul>
 *
 * <p>
 * YAML keys are mapped to {@link ProxyConfig} fields using Jackson YAML.
 * Missing keys receive the documented defaults from
 * {@link ProxyConfig.Builder}.
 */
public final class ConfigLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String DEFAULT_CONFIG_FILE = "message-xform-proxy.yaml";

    private ConfigLoader() {
        // utility class
    }

    /**
     * Loads a {@link ProxyConfig} from the given YAML file path.
     *
     * @param configPath path to the YAML configuration file
     * @return a fully constructed {@link ProxyConfig} with defaults applied
     * @throws ConfigLoadException if the file is missing or contains invalid YAML
     */
    public static ProxyConfig load(Path configPath) {
        if (!Files.exists(configPath)) {
            throw new ConfigLoadException(
                    "Configuration file not found: " + configPath + ". Use --config <path> to specify a config file.");
        }

        try {
            JsonNode root = YAML_MAPPER.readTree(Files.newInputStream(configPath));
            return mapToConfig(root);
        } catch (ConfigLoadException e) {
            throw e;
        } catch (IOException e) {
            throw new ConfigLoadException("Failed to parse YAML configuration: " + configPath, e);
        } catch (Exception e) {
            throw new ConfigLoadException("Failed to load configuration from: " + configPath, e);
        }
    }

    /**
     * Resolves the config file path from CLI arguments.
     *
     * @param args command-line arguments
     * @return the resolved config file path
     */
    public static Path resolveConfigPath(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--config requires a file path argument");
                }
                return Path.of(args[i + 1]);
            }
        }
        return Path.of(DEFAULT_CONFIG_FILE);
    }

    /** Maps a parsed YAML tree to a {@link ProxyConfig} via the builder. */
    private static ProxyConfig mapToConfig(JsonNode root) {
        ProxyConfig.Builder builder = ProxyConfig.builder();

        // Proxy section
        JsonNode proxy = root.path("proxy");
        if (proxy.has("host")) builder.proxyHost(proxy.get("host").asText());
        if (proxy.has("port")) builder.proxyPort(proxy.get("port").asInt());
        if (proxy.has("max-body-bytes"))
            builder.maxBodyBytes(proxy.get("max-body-bytes").asInt());

        // Proxy TLS
        JsonNode proxyTls = proxy.path("tls");
        if (!proxyTls.isMissingNode() && proxyTls.isObject()) {
            builder.proxyTls(new TlsConfig(
                    boolOrDefault(proxyTls, "enabled", false),
                    textOrNull(proxyTls, "keystore"),
                    textOrNull(proxyTls, "keystore-password"),
                    textOrDefault(proxyTls, "keystore-type", "PKCS12"),
                    textOrDefault(proxyTls, "client-auth", "none"),
                    textOrNull(proxyTls, "truststore"),
                    textOrNull(proxyTls, "truststore-password"),
                    textOrDefault(proxyTls, "truststore-type", "PKCS12")));
        }

        // Proxy shutdown
        JsonNode shutdown = proxy.path("shutdown");
        if (shutdown.has("drain-timeout-ms"))
            builder.shutdownDrainTimeoutMs(shutdown.get("drain-timeout-ms").asInt());

        // Proxy forwarded headers
        JsonNode forwarded = proxy.path("forwarded-headers");
        if (forwarded.has("enabled"))
            builder.forwardedHeadersEnabled(forwarded.get("enabled").asBoolean());

        // Backend section
        JsonNode backend = root.path("backend");
        if (backend.has("scheme")) builder.backendScheme(backend.get("scheme").asText());
        if (backend.has("host")) builder.backendHost(backend.get("host").asText());
        if (backend.has("port")) builder.backendPort(backend.get("port").asInt());
        if (backend.has("connect-timeout-ms"))
            builder.backendConnectTimeoutMs(backend.get("connect-timeout-ms").asInt());
        if (backend.has("read-timeout-ms"))
            builder.backendReadTimeoutMs(backend.get("read-timeout-ms").asInt());

        // Backend TLS
        JsonNode backendTls = backend.path("tls");
        if (!backendTls.isMissingNode() && backendTls.isObject()) {
            builder.backendTls(new BackendTlsConfig(
                    textOrNull(backendTls, "truststore"),
                    textOrNull(backendTls, "truststore-password"),
                    textOrDefault(backendTls, "truststore-type", "PKCS12"),
                    boolOrDefault(backendTls, "verify-hostname", true),
                    textOrNull(backendTls, "keystore"),
                    textOrNull(backendTls, "keystore-password"),
                    textOrDefault(backendTls, "keystore-type", "PKCS12")));
        }

        // Backend pool
        JsonNode pool = backend.path("pool");
        if (!pool.isMissingNode() && pool.isObject()) {
            builder.pool(new PoolConfig(
                    intOrDefault(pool, "max-connections", 100),
                    boolOrDefault(pool, "keep-alive", true),
                    intOrDefault(pool, "idle-timeout-ms", 60000)));
        }

        // Engine section
        JsonNode engine = root.path("engine");
        if (engine.has("specs-dir")) builder.specsDir(engine.get("specs-dir").asText());
        if (engine.has("profiles-dir"))
            builder.profilesDir(engine.get("profiles-dir").asText());
        if (engine.has("profile")) builder.profilePath(engine.get("profile").asText());
        if (engine.has("schema-validation"))
            builder.schemaValidation(engine.get("schema-validation").asText());

        // Reload section
        JsonNode reload = root.path("reload");
        if (reload.has("enabled")) builder.reloadEnabled(reload.get("enabled").asBoolean());
        if (reload.has("debounce-ms"))
            builder.reloadDebounceMs(reload.get("debounce-ms").asInt());

        // Health section
        JsonNode health = root.path("health");
        if (health.has("enabled")) builder.healthEnabled(health.get("enabled").asBoolean());
        if (health.has("path")) builder.healthPath(health.get("path").asText());
        if (health.has("ready-path")) builder.readyPath(health.get("ready-path").asText());

        // Logging section
        JsonNode logging = root.path("logging");
        if (logging.has("format")) builder.loggingFormat(logging.get("format").asText());
        if (logging.has("level")) builder.loggingLevel(logging.get("level").asText());

        // Admin section
        JsonNode admin = root.path("admin");
        if (admin.has("reload-path"))
            builder.adminReloadPath(admin.get("reload-path").asText());

        return builder.build();
    }

    // --- Helpers ---

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : null;
    }

    private static String textOrDefault(JsonNode node, String field, String defaultValue) {
        return node.has(field) ? node.get(field).asText() : defaultValue;
    }

    private static boolean boolOrDefault(JsonNode node, String field, boolean defaultValue) {
        return node.has(field) ? node.get(field).asBoolean() : defaultValue;
    }

    private static int intOrDefault(JsonNode node, String field, int defaultValue) {
        return node.has(field) ? node.get(field).asInt() : defaultValue;
    }
}
