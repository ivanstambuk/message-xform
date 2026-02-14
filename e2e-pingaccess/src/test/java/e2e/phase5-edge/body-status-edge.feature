Feature: Body & Status Edge Cases
  # Tests 12-16: Non-JSON body pass-through, non-JSON response, non-standard
  # status code, status transform, URL rewrite.
  # Ports shell lines 1280-1325.

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true

  Scenario: Test 12 — Non-JSON request body pass-through (S-002-08)
    # POST with text/plain → adapter detects non-JSON → skips body JSLT → forwards raw bytes.
    Given path '/api/transform/plain'
    And header Host = paEngineHost
    And header Content-Type = 'text/plain'
    And request 'Hello, world!'
    When method POST
    Then status 200
    And match response == 'Hello, world!'
    And match responseHeaders['X-Echo-Req-Content-Type'][0] contains 'text/plain'

  Scenario: Test 13 — Non-JSON response body pass-through (S-002-32)
    # GET /api/html/page → echo returns text/html → adapter skips JSLT → HTML forwarded.
    Given path '/api/html/page'
    And header Host = paEngineHost
    When method GET
    Then status 200
    And match response contains '<html>'

  Scenario: Test 14 — Non-standard status code pass-through (S-002-35)
    # GET /api/status/277 → echo returns 277 → adapter passes through.
    Given path '/api/status/277'
    And header Host = paEngineHost
    When method GET
    Then status 277

  Scenario: Test 15 — Status code transform (S-002-05)
    # GET /api/status-test/ok → echo returns 200 → e2e-status-override → status.set: 201.
    Given path '/api/status-test/ok'
    And header Host = paEngineHost
    When method GET
    Then status 201

  Scenario: Test 16 — URL path rewrite (S-002-06)
    # POST /api/rewrite/test → e2e-url-rewrite → url.path.expr rewrites to "/api/rewritten".
    # Echo reflects rewritten path in X-Echo-Path header.
    Given path '/api/rewrite/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { target: '/api/rewritten' }
    When method POST
    Then status 200
    And match responseHeaders['X-Echo-Path'][0] == '/api/rewritten'
