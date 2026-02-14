Feature: Profile Routing & Header Injection
  # Tests 7-8: Header injection via profile routing, multi-spec routing.
  # Ports shell lines 1224-1251.

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true

  Scenario: Test 7 — Header injection via profile routing (S-002-04)
    # Profile routes /api/headers/** → e2e-header-inject (request direction).
    # Spec injects X-Transformed: true and X-Transform-Version: 1.0.0.
    # Echo reflects injected headers back as X-Echo-Req-* response headers.
    Given path '/api/headers/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { key: 'value' }
    When method POST
    Then status 200
    And match responseHeaders['X-Echo-Req-X-Transformed'][0] == 'true'
    And match responseHeaders['X-Echo-Req-X-Transform-Version'][0] == '1.0.0'

  Scenario: Test 8 — Multiple spec routing (S-002-09, S-002-15)
    # Two specs routed by profile to different paths simultaneously.
    # /api/transform/ → e2e-rename
    Given path '/api/transform/multi'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { user_id: 'm1', first_name: 'X', last_name: 'Y', email_address: 'z@z.com' }
    When method POST
    Then status 200
    And match response.user_id == 'm1'

    # /api/headers/ → e2e-header-inject
    Given url 'https://localhost:' + paEnginePort
    Given path '/api/headers/multi'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { marker: 'routing-test' }
    When method POST
    Then status 200
    And match responseHeaders['X-Echo-Req-X-Transformed'][0] == 'true'
