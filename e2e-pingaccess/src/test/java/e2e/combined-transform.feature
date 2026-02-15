Feature: Combined Multi-Dimension Transform
    # Test 32: Exercises ALL transform dimensions in a single spec:
    # body rename, URL rewrite, header injection, status override, session context.
    # Proves that one spec replaces chaining multiple PA rules (ops guide §21).

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true
    # Reset headers — callonce leaks paAdminHeaders into this scope
    * configure headers = null

  Scenario: Test 32 — Combined transform: body + URL + headers + status + session (S-002-03, S-002-04, S-002-05, S-002-06, S-002-22)
    # One spec does everything:
    #   REQUEST:  body rename (snake→camel) + inject session/cookie/query context
    #             + URL rewrite (/api/combined → /api/combined-rewritten)
    #             + header injection (X-Combined-Test, X-Transform-Spec)
    #   RESPONSE: body rename (camel→snake) + status override (200→202)
    #             + header injection
    #
    # For this test we use the unprotected main app so $session fields will be null.
    Given path '/api/combined/test'
    And param page = 42
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header Cookie = 'auth_token=xyz789'
    And request { user_id: 'U-100', first_name: 'Ivan' }
    When method POST
    # ── Status override: 200 → 202 ──
    Then status 202
    # ── Body transform (request direction): snake_case → camelCase + context injection ──
    # The echo backend received the transformed body and reflected it back.
    # Then the response transform renamed camelCase → snake_case.
    And match response.user_id == 'U-100'
    And match response.first_name == 'Ivan'
    # ── Cookie context ──
    And match response.cookie_token == 'xyz789'
    # ── Query param context ──
    And match response.query_page == '42'
    # ── Session context (unprotected → null) ──
    * def sessionSubject = response.session_subject
    * assert sessionSubject == null
    # ── Verify URL rewrite via PA server log ──
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * match paLog contains 'e2e-combined'
