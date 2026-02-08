package io.messagexform.standalone.config;

/**
 * Root configuration for the standalone HTTP proxy (DO-004-01, CFG-004-01..41).
 *
 * <p>
 * All fields provide sensible defaults except {@code backendHost}, which is
 * required.
 * Use {@link #builder()} to construct instances with the builder pattern.
 *
 * @param proxyHost               bind address for HTTP server (CFG-004-01)
 * @param proxyPort               listen port for HTTP server (CFG-004-02)
 * @param backendScheme           backend scheme: http or https (CFG-004-11)
 * @param backendHost             backend hostname or IP — REQUIRED (CFG-004-12)
 * @param backendPort             backend port, auto-derived from scheme if
 *                                omitted (CFG-004-13)
 * @param backendConnectTimeoutMs TCP connect timeout in ms (CFG-004-14)
 * @param backendReadTimeoutMs    response read timeout in ms (CFG-004-15)
 * @param maxBodyBytes            max body size in both directions (CFG-004-16)
 * @param specsDir                directory containing transform spec YAML files
 *                                (CFG-004-27)
 * @param profilesDir             directory containing transform profile YAML
 *                                files (CFG-004-28)
 * @param profilePath             explicit single profile path (CFG-004-29)
 * @param schemaValidation        lenient or strict (CFG-004-30)
 * @param reloadEnabled           enable file-system watching for hot reload
 *                                (CFG-004-31)
 * @param reloadDebounceMs        debounce period for file change events in ms
 *                                (CFG-004-33)
 * @param healthEnabled           enable health/readiness endpoints (CFG-004-34)
 * @param healthPath              liveness probe path (CFG-004-35)
 * @param readyPath               readiness probe path (CFG-004-36)
 * @param loggingFormat           json or text (CFG-004-37)
 * @param loggingLevel            root log level (CFG-004-38)
 * @param shutdownDrainTimeoutMs  max wait for in-flight requests during
 *                                shutdown (CFG-004-39)
 * @param forwardedHeadersEnabled add X-Forwarded-* headers to upstream requests
 *                                (CFG-004-40)
 * @param adminReloadPath         reload trigger endpoint path (CFG-004-41)
 * @param proxyTls                inbound TLS configuration (CFG-004-03..10)
 * @param backendTls              outbound TLS configuration (CFG-004-20..26)
 * @param pool                    backend connection pool configuration
 *                                (CFG-004-17..19)
 */
public record ProxyConfig(
        String proxyHost,
        int proxyPort,
        String backendScheme,
        String backendHost,
        int backendPort,
        int backendConnectTimeoutMs,
        int backendReadTimeoutMs,
        int maxBodyBytes,
        String specsDir,
        String profilesDir,
        String profilePath,
        String schemaValidation,
        boolean reloadEnabled,
        int reloadDebounceMs,
        boolean healthEnabled,
        String healthPath,
        String readyPath,
        String loggingFormat,
        String loggingLevel,
        int shutdownDrainTimeoutMs,
        boolean forwardedHeadersEnabled,
        String adminReloadPath,
        TlsConfig proxyTls,
        BackendTlsConfig backendTls,
        PoolConfig pool) {

    /** Creates a new builder with sensible defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ProxyConfig}. All fields have defaults except
     * {@code backendHost}.
     */
    public static final class Builder {
        private String proxyHost = "0.0.0.0";
        private int proxyPort = 9090;
        private String backendScheme = "http";
        private String backendHost;
        private Integer backendPort; // null → auto-derive from scheme
        private int backendConnectTimeoutMs = 5000;
        private int backendReadTimeoutMs = 30000;
        private int maxBodyBytes = 10_485_760; // 10 MB
        private String specsDir = "./specs";
        private String profilesDir = "./profiles";
        private String profilePath;
        private String schemaValidation = "lenient";
        private boolean reloadEnabled = true;
        private int reloadDebounceMs = 500;
        private boolean healthEnabled = true;
        private String healthPath = "/health";
        private String readyPath = "/ready";
        private String loggingFormat = "json";
        private String loggingLevel = "INFO";
        private int shutdownDrainTimeoutMs = 30000;
        private boolean forwardedHeadersEnabled = true;
        private String adminReloadPath = "/admin/reload";
        private TlsConfig proxyTls = TlsConfig.DISABLED;
        private BackendTlsConfig backendTls = BackendTlsConfig.DEFAULT;
        private PoolConfig pool = PoolConfig.DEFAULT;

        Builder() {}

        public Builder proxyHost(String proxyHost) {
            this.proxyHost = proxyHost;
            return this;
        }

        public Builder proxyPort(int proxyPort) {
            this.proxyPort = proxyPort;
            return this;
        }

        public Builder backendScheme(String backendScheme) {
            this.backendScheme = backendScheme;
            return this;
        }

        public Builder backendHost(String backendHost) {
            this.backendHost = backendHost;
            return this;
        }

        public Builder backendPort(int backendPort) {
            this.backendPort = backendPort;
            return this;
        }

        public Builder backendConnectTimeoutMs(int backendConnectTimeoutMs) {
            this.backendConnectTimeoutMs = backendConnectTimeoutMs;
            return this;
        }

        public Builder backendReadTimeoutMs(int backendReadTimeoutMs) {
            this.backendReadTimeoutMs = backendReadTimeoutMs;
            return this;
        }

        public Builder maxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        public Builder specsDir(String specsDir) {
            this.specsDir = specsDir;
            return this;
        }

        public Builder profilesDir(String profilesDir) {
            this.profilesDir = profilesDir;
            return this;
        }

        public Builder profilePath(String profilePath) {
            this.profilePath = profilePath;
            return this;
        }

        public Builder schemaValidation(String schemaValidation) {
            this.schemaValidation = schemaValidation;
            return this;
        }

        public Builder reloadEnabled(boolean reloadEnabled) {
            this.reloadEnabled = reloadEnabled;
            return this;
        }

        public Builder reloadDebounceMs(int reloadDebounceMs) {
            this.reloadDebounceMs = reloadDebounceMs;
            return this;
        }

        public Builder healthEnabled(boolean healthEnabled) {
            this.healthEnabled = healthEnabled;
            return this;
        }

        public Builder healthPath(String healthPath) {
            this.healthPath = healthPath;
            return this;
        }

        public Builder readyPath(String readyPath) {
            this.readyPath = readyPath;
            return this;
        }

        public Builder loggingFormat(String loggingFormat) {
            this.loggingFormat = loggingFormat;
            return this;
        }

        public Builder loggingLevel(String loggingLevel) {
            this.loggingLevel = loggingLevel;
            return this;
        }

        public Builder shutdownDrainTimeoutMs(int shutdownDrainTimeoutMs) {
            this.shutdownDrainTimeoutMs = shutdownDrainTimeoutMs;
            return this;
        }

        public Builder forwardedHeadersEnabled(boolean forwardedHeadersEnabled) {
            this.forwardedHeadersEnabled = forwardedHeadersEnabled;
            return this;
        }

        public Builder adminReloadPath(String adminReloadPath) {
            this.adminReloadPath = adminReloadPath;
            return this;
        }

        public Builder proxyTls(TlsConfig proxyTls) {
            this.proxyTls = proxyTls;
            return this;
        }

        public Builder backendTls(BackendTlsConfig backendTls) {
            this.backendTls = backendTls;
            return this;
        }

        public Builder pool(PoolConfig pool) {
            this.pool = pool;
            return this;
        }

        /**
         * Builds the {@link ProxyConfig}, auto-deriving {@code backendPort} if not set.
         */
        public ProxyConfig build() {
            int resolvedPort = backendPort != null ? backendPort : ("https".equalsIgnoreCase(backendScheme) ? 443 : 80);

            return new ProxyConfig(
                    proxyHost,
                    proxyPort,
                    backendScheme,
                    backendHost,
                    resolvedPort,
                    backendConnectTimeoutMs,
                    backendReadTimeoutMs,
                    maxBodyBytes,
                    specsDir,
                    profilesDir,
                    profilePath,
                    schemaValidation,
                    reloadEnabled,
                    reloadDebounceMs,
                    healthEnabled,
                    healthPath,
                    readyPath,
                    loggingFormat,
                    loggingLevel,
                    shutdownDrainTimeoutMs,
                    forwardedHeadersEnabled,
                    adminReloadPath,
                    proxyTls,
                    backendTls,
                    pool);
        }
    }
}
