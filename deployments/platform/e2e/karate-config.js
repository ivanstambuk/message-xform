function fn() {
    // ---------------------------------------------------------------------------
    // Karate global configuration for Platform E2E tests
    // ---------------------------------------------------------------------------
    // Target: PingAccess + PingAM + PingDirectory (3-container docker-compose)
    //
    // Runner: standalone Karate JAR (no Gradle submodule)
    //   java -jar karate.jar .    OR    ./run-e2e.sh
    //
    // Port mapping (docker-compose → host):
    //   PA Engine:  3000 → 13000 (HTTPS)
    //   PA Admin:   9000 → 19000 (HTTPS)
    //   AM HTTP:    8080 → 18080
    //   AM HTTPS:   8443 → 18443
    //   PD LDAPS:   1636 → 1636
    //
    // Prerequisites:
    //   cd deployments/platform && docker compose up -d
    //   Wait for all containers to be healthy before running tests.
    // ---------------------------------------------------------------------------

    var env = karate.env || 'docker';
    karate.log('karate.env =', env);

    var config = {
        // -- PA Engine (external-facing) ------------------------------------------
        // PA engine listens on container port 3000, mapped to host 13000.
        // VH is configured as localhost:3000, so Host header must match.
        paEngineUrl: 'https://localhost:13000',
        paEngineHost: 'localhost:3000',

        // -- PA Admin API ---------------------------------------------------------
        paAdminUrl: 'https://localhost:19000/pa-admin-api/v3',
        paUser: 'administrator',
        paPassword: '2Access',

        // -- PingAM (direct, for setup/verification) ------------------------------
        // AM is behind PA in production, but direct access needed for provisioning
        amDirectUrl: 'http://127.0.0.1:18080/am',
        amAdminUser: 'amAdmin',
        amAdminPassword: 'Password1',

        // -- AM endpoints through PA (transformed by message-xform) ---------------
        // PA reverse-proxies /am/* to PingAM. The transform profile fires on
        // POST /am/json/authenticate and cleans up the response.
        //
        // "Raw" URLs go through PA's /am application (direct AM paths):
        loginUrl: 'https://localhost:13000/am/json/authenticate',
        logoutUrl: 'https://localhost:13000/am/json/sessions/?_action=logout',
        passkeyUrl: 'https://localhost:13000/am/json/authenticate',
        passkeyUsernamelessUrl: 'https://localhost:13000/am/json/authenticate',

        // AM query params for specific journeys (appended by tests)
        loginJourneyParams: '',
        passkeyJourneyParams: 'authIndexType=service&authIndexValue=WebAuthnJourney',
        usernamelessJourneyParams: 'authIndexType=service&authIndexValue=UsernamelessJourney',

        // -- Clean URL endpoints (request-side URL rewriting by message-xform) ----
        // PA's /api application + message-xform URL rewrite specs:
        //   /api/v1/auth/login                → /am/json/authenticate
        //   /api/v1/auth/passkey              → /am/json/authenticate + WebAuthnJourney
        //   /api/v1/auth/passkey/usernameless → /am/json/authenticate + UsernamelessJourney
        cleanLoginUrl: 'https://localhost:13000/api/v1/auth/login',
        cleanPasskeyUrl: 'https://localhost:13000/api/v1/auth/passkey',
        cleanPasskeyUsernamelessUrl: 'https://localhost:13000/api/v1/auth/passkey/usernameless',

        // -- PingDirectory (direct, for setup/verification) -----------------------
        pdLdapsPort: 1636,

        // -- Test users -----------------------------------------------------------
        testUser: 'user.1',
        testPassword: 'Password1',
        // Dedicated passkey test user (must not have a pre-registered WebAuthn device)
        passkeyTestUser: 'user.4',
        // Dedicated usernameless passkey test user (separate from identifier-first)
        usernamelessTestUser: 'user.5',

        // AM hostname for Host header (required for AM API calls through PA)
        amHostHeader: 'am.platform.local:18080',

        // -- Container names (for docker exec if needed) --------------------------
        paContainer: 'platform-pingaccess-1',
        amContainer: 'platform-pingam-1',
        pdContainer: 'platform-pingdirectory-1',

        // -- AM REST API headers --------------------------------------------------
        amApiVersion: 'resource=2.0, protocol=1.0',

        // -- PA Admin headers -----------------------------------------------------
        paAdminHeaders: {
            'X-XSRF-Header': 'PingAccess',
            'Content-Type': 'application/json'
        }
    };

    // Environment-specific overrides
    if (env === 'ci') {
        karate.log('CI environment — using default ports');
    }

    // Karate SSL configuration — skip TLS verification for self-signed certs
    karate.configure('ssl', true);
    // Don't follow redirects — important for auth flows
    karate.configure('followRedirects', false);
    // Increase timeouts for AM authentication flows
    karate.configure('connectTimeout', 10000);
    karate.configure('readTimeout', 30000);

    return config;
}
