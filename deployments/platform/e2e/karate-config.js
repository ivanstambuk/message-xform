function fn() {
    // ---------------------------------------------------------------------------
    // Karate global configuration for Platform E2E tests
    // ---------------------------------------------------------------------------
    // Target: PingAccess + PingAM + PingDirectory
    //
    // Environments:
    //   docker (default) — 3-container docker-compose, port-mapped
    //   k8s              — k3s cluster, Traefik IngressRoute + port-forward
    //
    // Runner: standalone Karate JAR (no Gradle submodule)
    //   java -jar karate.jar .    OR    ./run-e2e.sh
    //
    // Docker Compose port mapping:
    //   PA Engine:  3000 → 13000 (HTTPS)
    //   PA Admin:   9000 → 19000 (HTTPS)
    //   AM HTTP:    8080 → 18080
    //   AM HTTPS:   8443 → 18443
    //   PD LDAPS:   1636 → 1636
    //
    // K8s port access:
    //   PA Engine:  https://localhost (port 443 via Traefik IngressRoute)
    //   PA Admin:   kubectl port-forward svc/pingaccess-admin 29000:9000 -n message-xform
    //   AM Direct:  kubectl port-forward svc/pingam 28080:8080 -n message-xform
    //
    // Prerequisites:
    //   Docker:  cd deployments/platform && docker compose up -d
    //   K8s:     All pods Running + port-forwards active (see run-e2e.sh --env k8s)
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
    if (env === 'k8s') {
        karate.log('K8s environment — using Traefik Ingress + port-forward');
        // PA engine: Traefik IngressRoute on port 443 (default HTTPS)
        // PA has *:443 VH — no explicit Host header override needed
        config.paEngineUrl = 'https://localhost';
        config.paEngineHost = 'localhost';  // matches *:443 VH (implied port for HTTPS)

        // PA Admin: port-forwarded from K8s service
        config.paAdminUrl = 'https://localhost:29000/pa-admin-api/v3';
        config.paPassword = '2Access';     // matches values-local.yaml

        // AM direct: port-forwarded from K8s service
        config.amDirectUrl = 'http://127.0.0.1:28080/am';

        // AM through PA — same paths, different base URL (Traefik port 443)
        config.loginUrl = 'https://localhost/am/json/authenticate';
        config.logoutUrl = 'https://localhost/am/json/sessions/?_action=logout';
        config.passkeyUrl = 'https://localhost/am/json/authenticate';
        config.passkeyUsernamelessUrl = 'https://localhost/am/json/authenticate';

        // Clean URL endpoints — same paths, Traefik base
        config.cleanLoginUrl = 'https://localhost/api/v1/auth/login';
        config.cleanPasskeyUrl = 'https://localhost/api/v1/auth/passkey';
        config.cleanPasskeyUsernamelessUrl = 'https://localhost/api/v1/auth/passkey/usernameless';

        // Logout through PA (Traefik)
        config.logoutUrl = 'https://localhost/am/json/sessions/?_action=logout';

        // AM Host header for direct AM admin calls (K8s service name)
        config.amHostHeader = 'pingam:8080';  // matches boot.json instance URL
    } else if (env === 'ci') {
        karate.log('CI environment — using default ports');
    }

    // Normalize response header keys to lowercase for case-insensitive matching.
    // Required because Traefik (K8s Ingress) title-cases HTTP/1.1 headers, e.g.
    // x-auth-session → X-Auth-Session. PA sends lowercase. Without this, header
    // assertions fail when running behind an Ingress controller.
    karate.configure('lowerCaseResponseHeaders', true);

    // Karate SSL configuration — skip TLS verification for self-signed certs
    karate.configure('ssl', true);
    // Don't follow redirects — important for auth flows
    karate.configure('followRedirects', false);
    // Increase timeouts for AM authentication flows
    karate.configure('connectTimeout', 10000);
    karate.configure('readTimeout', 30000);

    return config;
}
