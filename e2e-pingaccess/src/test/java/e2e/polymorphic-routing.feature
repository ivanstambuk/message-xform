Feature: Polymorphic Body Routing
    # Tests 37-40: Response routing based on body content via match.when predicates.
    # Validates match.when (FR-001-16, ADR-0036, S-001-93 – S-001-99).
    #
    # Architecture: Karate → PA → Echo backend
    # The echo backend returns configurable status and body via X-Echo-* headers.
    # PA's response-direction profile entries use match.when to inspect the body:
    #   2xx + role=="admin" → e2e-polymorphic-admin (richer response)
    #   2xx + role!="admin" → e2e-polymorphic-user (simpler response)
    #   4xx               → e2e-status-route-error (error format, no when needed)

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true
    # Reset headers — callonce leaks paAdminHeaders into this scope
    * configure headers = null

  Scenario: Test 37 — Polymorphic: 200 + role=admin → admin transform (S-001-93)
    # Echo returns admin user JSON. match.when(.role == "admin") triggers
    # e2e-polymorphic-admin which reshapes to include permissions.
    Given path '/api/polymorphic/user'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header X-Echo-Status = '200'
    And header X-Echo-Body = '{"role":"admin","name":"Alice","permissions":["read","write","delete"]}'
    And request {}
    When method POST
    Then status 200
    And match response.role == 'admin'
    And match response.display_name == 'Alice'
    And match response.permissions == ['read', 'write', 'delete']

  Scenario: Test 38 — Polymorphic: 200 + role=user → user transform (S-001-94)
    # Echo returns regular user JSON. match.when(.role != "admin") triggers
    # e2e-polymorphic-user which produces a simpler response.
    Given path '/api/polymorphic/user'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header X-Echo-Status = '200'
    And header X-Echo-Body = '{"role":"user","name":"Bob"}'
    And request {}
    When method POST
    Then status 200
    And match response.role == 'standard'
    And match response.display_name == 'Bob'
    # No permissions field for standard users
    And match response !contains { permissions: '#notnull' }

  Scenario: Test 39 — Polymorphic: 400 error → error transform (S-001-95)
    # Echo returns 400 error. status:"4xx" matches e2e-status-route-error
    # regardless of body content (no when predicate on the error entry).
    Given path '/api/polymorphic/user'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header X-Echo-Status = '400'
    And header X-Echo-Body = '{"errorCode":"BAD_REQUEST","message":"Invalid user ID"}'
    And request {}
    When method POST
    Then status 502
    And match response.result == 'error'
    And match response.original_status == 400
    And match response.error_code == 'BAD_REQUEST'
    And match response.error_message == 'Invalid user ID'

  Scenario: Test 40 — Polymorphic: non-JSON body → passthrough (S-001-97)
    # Echo returns 200 with non-JSON body. The when predicate cannot evaluate
    # against non-JSON → graceful skip → no match → passthrough.
    Given path '/api/polymorphic/plain'
    And header Host = paEngineHost
    And header Content-Type = 'text/plain'
    And header X-Echo-Status = '200'
    And header X-Echo-Body = 'plain text response'
    And request 'test'
    When method POST
    Then status 200
    And match response == 'plain text response'
