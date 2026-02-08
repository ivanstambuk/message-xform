package io.messagexform.standalone.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Loads {@link ProxyConfig} from a YAML file (T-004-06, FR-004-10) with
 * optional
 * environment variable overlay (T-004-07, FR-004-11).
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
 *
 * <p>
 * Environment variable overlay (FR-004-11): every configuration key can be
 * overridden via an environment variable. Env vars take precedence over YAML
 * values. An env var is considered "set" if and only if it is defined AND its
 * trimmed value is non-empty; empty or whitespace-only values are treated as
 * "unset" and the YAML value is used.
 */
public final class ConfigLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String DEFAULT_CONFIG_FILE = "message-xform-proxy.yaml";

    private ConfigLoader() {
        // utility class
    }

    /**
     * Loads a {@link ProxyConfig} from the given YAML file path, applying
     * environment variable overrides from {@link System#getenv}.
     *
     * @param configPath path to the YAML configuration file
     * @return a fully constructed {@link ProxyConfig} with defaults applied
     * @throws ConfigLoadException if the file is missing or contains invalid YAML
     */
    public static ProxyConfig load(Path configPath) {
        return load(configPath, System::getenv);
    }

    /**
     * Loads a {@link ProxyConfig} from the given YAML file path, applying
     * environment variable overrides from the supplied lookup function.
     *
     * <p>
     * The {@code envLookup} function maps environment variable names to their
     * values. Returning {@code null} means the variable is not defined.
     *
     * @param configPath path to the YAML configuration file
     * @param envLookup  environment variable lookup function
     * @return a fully constructed {@link ProxyConfig} with env overrides applied
     * @throws ConfigLoadException if the file is missing or contains invalid YAML
     */
    public static ProxyConfig load(Path configPath, Function<String, String> envLookup) {
        if (!Files.exists(configPath)) {
            throw new ConfigLoadException(
                    "Configuration file not found: " + configPath + ". Use --config <path> to specify a config file.");
        }

        try {
            JsonNode root = YAML_MAPPER.readTree(Files.newInputStream(configPath));
            return mapToConfig(root, envLookup);
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

    /**
     * Maps a parsed YAML tree to a {@link ProxyConfig} via the builder, then
     * overlays environment variable overrides.
     */
    private static ProxyConfig mapToConfig(JsonNode root, Function<String, String> envLookup) {
        ProxyConfig.Builder builder = ProxyConfig.builder();

        // --- YAML mapping ---

        // Proxy section
        JsonNode proxy = root.path("proxy");
        if (proxy.has("host")) builder.proxyHost(proxy.get("host").asText());
        if (proxy.has("port")) builder.proxyPort(proxy.get("port").asInt());
        if (proxy.has("max-body-bytes"))
            builder.maxBodyBytes(proxy.get("max-body-bytes").asInt());

        // Proxy TLS (from YAML)
        JsonNode proxyTls = proxy.path("tls");
        boolean yamlProxyTlsEnabled = boolOrDefault(proxyTls, "enabled", false);
        String yamlProxyTlsKeystore = textOrNull(proxyTls, "keystore");
        String yamlProxyTlsKeystorePassword = textOrNull(proxyTls, "keystore-password");
        String yamlProxyTlsKeystoreType = textOrDefault(proxyTls, "keystore-type", "PKCS12");
        String yamlProxyTlsClientAuth = textOrDefault(proxyTls, "client-auth", "none");
        String yamlProxyTlsTruststore = textOrNull(proxyTls, "truststore");
        String yamlProxyTlsTruststorePassword = textOrNull(proxyTls, "truststore-password");
        String yamlProxyTlsTruststoreType = textOrDefault(proxyTls, "truststore-type", "PKCS12");

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

        // Backend TLS (from YAML)
        JsonNode backendTls = backend.path("tls");
        String yamlBackendTlsTruststore = textOrNull(backendTls, "truststore");
        String yamlBackendTlsTruststorePassword = textOrNull(backendTls, "truststore-password");
        String yamlBackendTlsTruststoreType = textOrDefault(backendTls, "truststore-type", "PKCS12");
        boolean yamlBackendTlsVerifyHostname = boolOrDefault(backendTls, "verify-hostname", true);
        String yamlBackendTlsKeystore = textOrNull(backendTls, "keystore");
        String yamlBackendTlsKeystorePassword = textOrNull(backendTls, "keystore-password");
        String yamlBackendTlsKeystoreType = textOrDefault(backendTls, "keystore-type", "PKCS12");

        // Backend pool (from YAML)
        JsonNode pool = backend.path("pool");
        int yamlPoolMaxConnections = intOrDefault(pool, "max-connections", 100);
        boolean yamlPoolKeepAlive = boolOrDefault(pool, "keep-alive", true);
        int yamlPoolIdleTimeoutMs = intOrDefault(pool, "idle-timeout-ms", 60000);

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

        // --- Environment variable overlay (FR-004-11) ---
        applyEnvOverrides(
                builder,
                envLookup,
                yamlProxyTlsEnabled,
                yamlProxyTlsKeystore,
                yamlProxyTlsKeystorePassword,
                yamlProxyTlsKeystoreType,
                yamlProxyTlsClientAuth,
                yamlProxyTlsTruststore,
                yamlProxyTlsTruststorePassword,
                yamlProxyTlsTruststoreType,
                yamlBackendTlsTruststore,
                yamlBackendTlsTruststorePassword,
                yamlBackendTlsTruststoreType,
                yamlBackendTlsVerifyHostname,
                yamlBackendTlsKeystore,
                yamlBackendTlsKeystorePassword,
                yamlBackendTlsKeystoreType,
                yamlPoolMaxConnections,
                yamlPoolKeepAlive,
                yamlPoolIdleTimeoutMs);

        return builder.build();
    }

    /**
     * Applies environment variable overrides to the builder. Each env var maps
     * to a specific config field per the spec Environment Variable Mapping table.
     *
     * <p>
     * An env var is "set" if {@code envLookup.apply(name)} returns a non-null,
     * non-empty (after trim) string. Otherwise the YAML/default value stands.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void applyEnvOverrides(
            ProxyConfig.Builder builder,
            Function<String, String> envLookup,
            // Proxy TLS YAML values (needed to construct TlsConfig)
            boolean yamlProxyTlsEnabled,
            String yamlProxyTlsKeystore,
            String yamlProxyTlsKeystorePassword,
            String yamlProxyTlsKeystoreType,
            String yamlProxyTlsClientAuth,
            String yamlProxyTlsTruststore,
            String yamlProxyTlsTruststorePassword,
            String yamlProxyTlsTruststoreType,
            // Backend TLS YAML values
            String yamlBackendTlsTruststore,
            String yamlBackendTlsTruststorePassword,
            String yamlBackendTlsTruststoreType,
            boolean yamlBackendTlsVerifyHostname,
            String yamlBackendTlsKeystore,
            String yamlBackendTlsKeystorePassword,
            String yamlBackendTlsKeystoreType,
            // Pool YAML values
            int yamlPoolMaxConnections,
            boolean yamlPoolKeepAlive,
            int yamlPoolIdleTimeoutMs) {

        // --- Simple top-level string overrides ---
        envString(envLookup, "PROXY_HOST", builder::proxyHost);
        envString(envLookup, "BACKEND_SCHEME", builder::backendScheme);
        envString(envLookup, "BACKEND_HOST", builder::backendHost);
        envString(envLookup, "SPECS_DIR", builder::specsDir);
        envString(envLookup, "PROFILES_DIR", builder::profilesDir);
        envString(envLookup, "ENGINE_PROFILE", builder::profilePath);
        envString(envLookup, "SCHEMA_VALIDATION", builder::schemaValidation);
        envString(envLookup, "HEALTH_PATH", builder::healthPath);
        envString(envLookup, "HEALTH_READY_PATH", builder::readyPath);
        envString(envLookup, "LOG_FORMAT", builder::loggingFormat);
        envString(envLookup, "LOG_LEVEL", builder::loggingLevel);
        envString(envLookup, "ADMIN_RELOAD_PATH", builder::adminReloadPath);

        // --- Simple top-level integer overrides ---
        envInt(envLookup, "PROXY_PORT", builder::proxyPort);
        envInt(envLookup, "BACKEND_PORT", builder::backendPort);
        envInt(envLookup, "BACKEND_CONNECT_TIMEOUT_MS", builder::backendConnectTimeoutMs);
        envInt(envLookup, "BACKEND_READ_TIMEOUT_MS", builder::backendReadTimeoutMs);
        envInt(envLookup, "PROXY_MAX_BODY_BYTES", builder::maxBodyBytes);
        envInt(envLookup, "RELOAD_DEBOUNCE_MS", builder::reloadDebounceMs);
        envInt(envLookup, "PROXY_SHUTDOWN_DRAIN_TIMEOUT_MS", builder::shutdownDrainTimeoutMs);

        // --- Simple top-level boolean overrides ---
        envBool(envLookup, "RELOAD_ENABLED", builder::reloadEnabled);
        envBool(envLookup, "HEALTH_ENABLED", builder::healthEnabled);
        envBool(envLookup, "PROXY_FORWARDED_HEADERS_ENABLED", builder::forwardedHeadersEnabled);

        // --- Proxy TLS (records are immutable → reconstruct with overrides) ---
        builder.proxyTls(new TlsConfig(
                envBoolOrDefault(envLookup, "PROXY_TLS_ENABLED", yamlProxyTlsEnabled),
                envStringOrDefault(envLookup, "PROXY_TLS_KEYSTORE", yamlProxyTlsKeystore),
                envStringOrDefault(envLookup, "PROXY_TLS_KEYSTORE_PASSWORD", yamlProxyTlsKeystorePassword),
                envStringOrDefault(envLookup, "PROXY_TLS_KEYSTORE_TYPE", yamlProxyTlsKeystoreType),
                envStringOrDefault(envLookup, "PROXY_TLS_CLIENT_AUTH", yamlProxyTlsClientAuth),
                envStringOrDefault(envLookup, "PROXY_TLS_TRUSTSTORE", yamlProxyTlsTruststore),
                envStringOrDefault(envLookup, "PROXY_TLS_TRUSTSTORE_PASSWORD", yamlProxyTlsTruststorePassword),
                envStringOrDefault(envLookup, "PROXY_TLS_TRUSTSTORE_TYPE", yamlProxyTlsTruststoreType)));

        // --- Backend TLS ---
        builder.backendTls(new BackendTlsConfig(
                envStringOrDefault(envLookup, "BACKEND_TLS_TRUSTSTORE", yamlBackendTlsTruststore),
                envStringOrDefault(envLookup, "BACKEND_TLS_TRUSTSTORE_PASSWORD", yamlBackendTlsTruststorePassword),
                envStringOrDefault(envLookup, "BACKEND_TLS_TRUSTSTORE_TYPE", yamlBackendTlsTruststoreType),
                envBoolOrDefault(envLookup, "BACKEND_TLS_VERIFY_HOSTNAME", yamlBackendTlsVerifyHostname),
                envStringOrDefault(envLookup, "BACKEND_TLS_KEYSTORE", yamlBackendTlsKeystore),
                envStringOrDefault(envLookup, "BACKEND_TLS_KEYSTORE_PASSWORD", yamlBackendTlsKeystorePassword),
                envStringOrDefault(envLookup, "BACKEND_TLS_KEYSTORE_TYPE", yamlBackendTlsKeystoreType)));

        // --- Pool ---
        builder.pool(new PoolConfig(
                envIntOrDefault(envLookup, "BACKEND_POOL_MAX_CONNECTIONS", yamlPoolMaxConnections),
                envBoolOrDefault(envLookup, "BACKEND_POOL_KEEP_ALIVE", yamlPoolKeepAlive),
                envIntOrDefault(envLookup, "BACKEND_POOL_IDLE_TIMEOUT_MS", yamlPoolIdleTimeoutMs)));
    }

    // --- Env var helpers ---

    /**
     * Returns {@code true} if the env var is "set": defined AND non-blank after
     * trimming.
     */
    private static boolean isSet(Function<String, String> envLookup, String envVar) {
        String value = envLookup.apply(envVar);
        return value != null && !value.trim().isEmpty();
    }

    /** Applies a string env var override if set. */
    private static void envString(
            Function<String, String> envLookup, String envVar, java.util.function.Consumer<String> setter) {
        if (isSet(envLookup, envVar)) {
            setter.accept(envLookup.apply(envVar).trim());
        }
    }

    /** Applies an integer env var override if set. */
    private static void envInt(
            Function<String, String> envLookup, String envVar, java.util.function.IntConsumer setter) {
        if (isSet(envLookup, envVar)) {
            setter.accept(Integer.parseInt(envLookup.apply(envVar).trim()));
        }
    }

    /** Applies a boolean env var override if set. */
    private static void envBool(
            Function<String, String> envLookup, String envVar, java.util.function.Consumer<Boolean> setter) {
        if (isSet(envLookup, envVar)) {
            setter.accept(Boolean.parseBoolean(envLookup.apply(envVar).trim()));
        }
    }

    /** Returns the env var value if set, otherwise the YAML default. */
    private static String envStringOrDefault(Function<String, String> envLookup, String envVar, String yamlDefault) {
        return isSet(envLookup, envVar) ? envLookup.apply(envVar).trim() : yamlDefault;
    }

    /** Returns the env var boolean if set, otherwise the YAML default. */
    private static boolean envBoolOrDefault(Function<String, String> envLookup, String envVar, boolean yamlDefault) {
        return isSet(envLookup, envVar)
                ? Boolean.parseBoolean(envLookup.apply(envVar).trim())
                : yamlDefault;
    }

    /** Returns the env var integer if set, otherwise the YAML default. */
    private static int envIntOrDefault(Function<String, String> envLookup, String envVar, int yamlDefault) {
        return isSet(envLookup, envVar)
                ? Integer.parseInt(envLookup.apply(envVar).trim())
                : yamlDefault;
    }

    // --- YAML helpers ---

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
