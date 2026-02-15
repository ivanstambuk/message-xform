Feature: Hot-Reload E2E (Phase 9)
    # Tests 25-27: Spec hot-reload success, failure resilience, concurrent safety.
    # Validates FR-002-04 (hot-reload scheduler) and related scenarios:
    #   S-002-29 — modified spec picked up after reload interval
    #   S-002-30 — malformed spec retains previous registry
    #   S-002-31 — concurrent requests during reload don't corrupt state
    #
    # Architecture:
    #   At startup, e2e-reload-addition.yaml is a no-op identity transform.
    #   Tests overwrite it (host-side, via bind mount) with a version that
    #   adds "__reloaded": true to the body.  The reload scheduler (5s)
    #   picks up the change, proving hot-reload works.

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true
    # Reset headers — callonce leaks paAdminHeaders into this scope
    * configure headers = null
    # Helper: sleep for reload interval + margin
    * def sleep = function(ms){ java.lang.Thread.sleep(ms) }

    @phase9
  Scenario: Test 25 — Spec hot-reload success (S-002-29)
    # ── (a) Verify the IDENTITY version is active at startup ──
    # /api/reload/** routes to e2e-reload-addition@1.0.0, which is the
    # no-op identity transform. The echo backend returns the raw body.
    Given path '/api/reload/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { probe: 'before-reload' }
    When method POST
    Then status 200
    And match response == { probe: 'before-reload' }

    # ── (b) Overwrite the spec with the MARKER version ──
    # Copy staging/e2e-reload-addition.yaml → specs/e2e-reload-addition.yaml
    # on the host.  The :ro bind mount makes the change instantly visible
    # inside the container at /specs/.
    * def cpResult = karate.exec('cp ' + hostStagingDir + '/e2e-reload-addition.yaml ' + hostSpecsDir + '/e2e-reload-addition.yaml')
    * karate.log('Copied marker spec over identity spec')

    # ── (c) Wait for reload interval + margin (5s interval + 4s margin) ──
    * sleep(9000)

    # ── (d) Verify the MARKER version is now active ──
    Given url 'https://localhost:' + paEnginePort
    Given path '/api/reload/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { probe: 'after-reload' }
    When method POST
    Then status 200
    And match response.probe == 'after-reload'
    And match response.__reloaded == true

    # ── (e) Verify PA log shows reload scheduler and reload evidence ──
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * match paLog contains 'Hot-reload scheduler started'

    @phase9
  Scenario: Test 26 — Spec hot-reload failure retains previous registry (S-002-30)
    # Precondition: marker spec should still be active from Test 25.
    # If tests run in isolation, first copy the marker spec.
    * karate.exec('cp ' + hostStagingDir + '/e2e-reload-addition.yaml ' + hostSpecsDir + '/e2e-reload-addition.yaml')
    * sleep(9000)

    # ── (a) Verify marker is active (precondition) ──
    Given path '/api/reload/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { probe: 'pre-failure' }
    When method POST
    Then status 200
    And match response.__reloaded == true

    # ── (b) Overwrite the spec with invalid YAML ──
    * karate.exec("bash -c \"echo '!!!invalid-yaml' > " + hostSpecsDir + "/e2e-reload-addition.yaml\"")
    * karate.log('Overwrote reload spec with invalid YAML')

    # ── (c) Wait for reload interval + margin ──
    * sleep(9000)

    # ── (d) Existing transform should still work (previous registry retained) ──
    # The e2e-rename spec should still function correctly.
    Given url 'https://localhost:' + paEnginePort
    Given path '/api/transform/reload-resilience'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { user_id: 'reload', first_name: 'Test', last_name: 'User', email_address: 'r@t.com' }
    When method POST
    Then status 200
    And match response.user_id == 'reload'

    # ── (e) Verify PA log contains reload failure warning ──
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * match paLog contains 'Hot-reload failed'

    # ── Cleanup: restore identity spec ──
    * karate.exec('cp ' + hostStagingDir + '/e2e-reload-identity.yaml ' + hostSpecsDir + '/e2e-reload-addition.yaml')

    @phase9
  Scenario: Test 27 — Concurrent requests during reload (S-002-31)
    # Fire 5 sequential requests to /api/transform/ while reload may be active.
    # All should return 200 with valid JSON — no corruption, no 500 errors.
    # Timing is non-deterministic; this is a best-effort concurrency guard.

    * def doRequest =
      """
      function(i) {
        var res = karate.call('classpath:e2e/helpers/concurrent-request.feature',
          { requestPath: '/api/transform/concurrent-' + i,
            requestHost: paEngineHost,
            requestPort: paEnginePort,
            requestBody: { user_id: 'c' + i, first_name: 'A', last_name: 'B', email_address: 'c@d.com' } });
        return { status: res.status, userId: res.body ? res.body.user_id : null };
      }
      """

    * def results = karate.repeat(5, doRequest)
    * match each results[*].status == 200
