Feature: Status Routing
    # Tests 33-36: Response routing based on HTTP status code class.
    # Validates match.status (FR-001-15, S-001-87 – S-001-92).
    #
    # Architecture: Karate → PA → Echo backend
    # The echo backend returns configurable status codes via X-Echo-Status header.
    # PA's response-direction profile entries route based on status class:
    #   2xx → e2e-status-route-success (reshapes body)
    #   4xx → e2e-status-route-error (reshapes body + sets status 502)
    #   Other → passthrough (no match)

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true
    # Reset headers — callonce leaks paAdminHeaders into this scope
    * configure headers = null

  Scenario: Test 33 — Status routing: 200 → success spec (S-001-87)
    # Echo returns 200 with body — matched by status:"2xx" → e2e-status-route-success
    # Body is reshaped: { result: "success", original_status: 200, data: ... }
    Given path '/api/status-route/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header X-Echo-Status = '200'
    And header X-Echo-Body = '{"data": "hello"}'
    And request {}
    When method POST
    Then status 200
    And match response.result == 'success'
    And match response.original_status == 200
    And match response.data == 'hello'

  Scenario: Test 34 — Status routing: 404 → error spec + status override (S-001-88)
    # Echo returns 404 — matched by status:"4xx" → e2e-status-route-error
    # Body is reshaped to error format, status overridden to 502.
    Given path '/api/status-route/not-found'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header X-Echo-Status = '404'
    And header X-Echo-Body = '{"errorCode": "NOT_FOUND", "message": "Resource not found"}'
    And request {}
    When method POST
    Then status 502
    And match response.result == 'error'
    And match response.original_status == 404
    And match response.error_code == 'NOT_FOUND'
    And match response.error_message == 'Resource not found'

  Scenario: Test 35 — Status routing: 301 → passthrough (S-001-89)
    # Echo returns 301 — no profile entry matches status 3xx.
    # PA passes through the response unchanged.
    Given path '/api/status-route/redirect'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header X-Echo-Status = '301'
    And header X-Echo-Body = '{"redirect": "/new-location"}'
    And request {}
    When method POST
    Then status 301
    And match response.redirect == '/new-location'

  Scenario: Test 36 — Status routing: 200 without X-Echo-Status (default) (S-001-90)
    # Echo returns default 200 — matched by status:"2xx" → success spec.
    # Verifies that missing X-Echo-Status defaults to 200.
    Given path '/api/status-route/default'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { data: 'default-test' }
    When method POST
    Then status 200
    And match response.result == 'success'
    And match response.data == 'default-test'
