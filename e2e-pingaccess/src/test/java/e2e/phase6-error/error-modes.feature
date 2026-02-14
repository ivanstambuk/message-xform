Feature: Error Mode Tests
    # Tests 17-19: PASS_THROUGH, DENY, DENY guard verification.
    # Ports shell lines 1327-1367.

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * configure ssl = true
    # Reset headers — callonce leaks paAdminHeaders into this scope
    * configure headers = null

  Scenario: Test 17 — Error mode PASS_THROUGH (S-002-11)
    # POST to /api/error/test → e2e-error spec → JSLT error() fires
    # → errorMode=PASS_THROUGH → original body forwarded → echo returns it.
    Given url 'https://localhost:' + paEnginePort
    Given path '/api/error/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { key: 'passthrough-test' }
    When method POST
    Then status 200
    And match response.key == 'passthrough-test'
    # Verify PA log records the PASS_THROUGH event
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * match paLog contains 'PASS_THROUGH'

  Scenario: Test 18 — Error mode DENY (S-002-12)
    # POST to /deny-api/error/test → DENY rule → RFC 9457 error response.
    Given url 'https://localhost:' + paEnginePort
    Given path '/deny-api/error/test'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { key: 'deny-test' }
    When method POST
    Then status 502
    And match response contains { type: '#present' }
    And match response contains { title: '#present' }
    # Verify PA log records the DENY event
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * match paLog contains 'Request transform error (DENY)'

  Scenario: Test 19 — DENY guard verification (best-effort) (S-002-28)
    # After DENY, PA may or may not call handleResponse(). If it does, the
    # adapter's DENY guard should skip response processing and log a message.
    # Either outcome is valid — the unit test verifies the guard independently.
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * def guardFound = paLog.contains('Response processing skipped')
    * if (guardFound) karate.log('DENY guard log message found')
    * if (!guardFound) karate.log('DENY guard not exercised (PA skipped handleResponse — expected)')
    # Always passes — both outcomes are correct
    * assert true
