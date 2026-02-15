function fn() {
    // ---------------------------------------------------------------------------
    // Karate global configuration for PingAccess E2E tests
    // ---------------------------------------------------------------------------
    // Variable scopes (RQ-14):
    //   Global:      this file (karate-config.js) — shared across all features
    //   Feature:     Background + callonce in each .feature file
    //   Scenario:    scenario-scoped variables (def)
    //   Environment: karate.env selects port/host overrides
    // ---------------------------------------------------------------------------

    var env = karate.env || 'docker';
    karate.log('karate.env =', env);

    var config = {
        // -- PA Admin API ---------------------------------------------------------
        paAdminUrl: 'https://localhost:19000/pa-admin-api/v3',
        paUser: 'administrator',
        paPassword: '2Access',
        paAdminPort: 19000,

        // -- PA Engine ------------------------------------------------------------
        paEnginePort: 13000,
        paEngineHost: 'localhost:3000',  // Must match PA virtual host, not mapped port

        // -- Echo backend ---------------------------------------------------------
        echoPort: 18080,

        // -- Mock OIDC server -----------------------------------------------------
        oidcPort: 18443,
        oidcContainer: 'pa-e2e-oidc',

        // -- Container names (for docker exec) ------------------------------------
        paContainer: 'pa-e2e-test',
        echoContainer: 'pa-e2e-echo',

        // -- Paths ----------------------------------------------------------------
        paLogFile: '/opt/out/instance/log/pingaccess.log',
        paAuditLogFile: '/opt/out/instance/log/pingaccess_engine_audit.log',

        // -- Shadow JAR (host-local, for RQ-19 artifact inspection) ---------------
        shadowJar: karate.properties['shadowJar'] ||
            '../adapter-pingaccess/build/libs/adapter-pingaccess-0.1.0-SNAPSHOT.jar',

        // -- Host-side spec/staging paths (for Phase 9 hot-reload) ----------------
        hostSpecsDir: '../e2e/pingaccess/specs',
        hostStagingDir: '../e2e/pingaccess/staging',

        // -- Common headers -------------------------------------------------------
        paAdminHeaders: { 'X-XSRF-Header': 'PingAccess', 'Content-Type': 'application/json' }
    };

    // Environment-specific overrides
    if (env === 'ci') {
        // CI may use different ports or hostnames
        karate.log('CI environment — using default ports');
    }

    // Karate SSL configuration — skip TLS verification for self-signed PA certs
    karate.configure('ssl', true);
    // Don't follow redirects automatically — needed for OIDC flow (RQ-17)
    karate.configure('followRedirects', false);
    // Increase timeouts for PA startup scenarios
    karate.configure('connectTimeout', 10000);
    karate.configure('readTimeout', 30000);

    return config;
}
