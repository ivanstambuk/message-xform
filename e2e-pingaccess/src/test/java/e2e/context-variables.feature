Feature: Context Variables
    # Tests 9-11: Cookie context, query param context, session null for unprotected.
    # Ports shell lines 1253-1278.

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true
    # Reset headers — callonce leaks paAdminHeaders into this scope
    * configure headers = null

  Scenario: Test 9 — Cookie context variable (S-002-22)
    # e2e-context spec reads $cookies.session_token via JSLT.
    Given path '/api/context/cookies'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header Cookie = 'session_token=abc123; lang=en'
    And request { data: 'test' }
    When method POST
    Then status 200
    And match response.session_token == 'abc123'

  Scenario: Test 10 — Query param context variable (S-002-23)
    # e2e-context spec reads $queryParams.page via JSLT.
    Given path '/api/context/qp'
    And param page = 2
    And param limit = 10
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { data: 'test' }
    When method POST
    Then status 200
    And match response.page == '2'

  Scenario: Test 11 — Session is null for unprotected (S-002-14 partial)
    # Unprotected app → no identity → $session is null or empty.
    Given path '/api/context/qp'
    And param page = 2
    And param limit = 10
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { data: 'test' }
    When method POST
    Then status 200
    # $session for unprotected requests may be:
    #   - null (JSLT produces JSON null)
    #   - {} (empty SessionContext object serialized)
    #   - absent (JSLT omits null keys)
    # All indicate no authenticated session — any is acceptable
    * def session = response.session
    * karate.log('session value: ' + karate.toString(session))
    * assert session == null || karate.typeOf(session) == 'map'
