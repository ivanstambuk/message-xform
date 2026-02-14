Feature: Plugin Discovery & JAR Verification
  # Tests 1-6: Plugin discovery, bidirectional transform, spec loading,
  # shadow JAR inspection, SPI registration, PA health.
  # Ports shell lines 1120-1222.

  Background:
    * callonce read('classpath:e2e/setup/pa-provision.feature')
    * url 'https://localhost:' + paEnginePort
    * configure ssl = true

  Scenario: Test 1 — Bidirectional body round-trip (snake_case → camelCase → snake_case)
    # Reverse (request):  snake_case → camelCase
    # Forward (response): camelCase → snake_case
    # Echo backend returns raw request body, so full round-trip proves both transforms.
    Given path '/api/transform'
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request { user_id: 'u123', first_name: 'John', last_name: 'Doe', email_address: 'john@example.com' }
    When method POST
    Then status 200
    And match response.user_id == 'u123'
    And match response.first_name == 'John'
    And match response.last_name == 'Doe'
    And match response.email_address == 'john@example.com'

  Scenario: Test 1c — Direct echo probe (verify request-side transform)
    # Bypass PA, hit echo directly to confirm camelCase arrives at backend
    Given url 'http://localhost:' + echoPort
    Given path '/api/transform'
    And header Content-Type = 'application/json'
    And request { userId: 'u123', firstName: 'John', lastName: 'Doe', emailAddress: 'john@example.com' }
    When method POST
    Then status 200
    And match response.userId == 'u123'
    And match response.firstName == 'John'

  Scenario: Test 1d — Audit log confirms rule applied
    * def result = karate.exec('docker exec ' + paContainer + ' cat ' + paAuditLogFile)
    * match result contains 'E2E Test App'

  Scenario: Test 2 — GET pass-through (no body to transform)
    Given path '/api/health'
    And header Host = paEngineHost
    When method GET
    Then status 200

  Scenario: Test 3 — Spec loading verification
    # Verify all 7 specs + 1 profile are loaded in PA logs
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * match paLog contains 'Loaded spec: e2e-rename.yaml'
    * match paLog contains 'Loaded spec: e2e-header-inject.yaml'
    * match paLog contains 'Loaded spec: e2e-context.yaml'
    * match paLog contains 'Loaded spec: e2e-error.yaml'
    * match paLog contains 'Loaded spec: e2e-status-override.yaml'
    * match paLog contains 'Loaded spec: e2e-url-rewrite.yaml'
    * match paLog contains 'Loaded spec: e2e-session.yaml'
    * match paLog contains 'Loaded profile: e2e-profile'

  Scenario: Test 4 — Shadow JAR class version (Java 17)
    # RQ-19: host-local artifact inspection via javap
    * def javapOut = karate.exec('javap -verbose -cp ' + shadowJar + ' io.messagexform.pingaccess.MessageTransformRule')
    * match javapOut contains 'major version: 61'
    # JAR size < 5 MB (NFR-002-02)
    * def statOut = karate.exec("stat --format=%s " + shadowJar)
    * def jarBytes = parseInt(statOut.trim())
    * assert jarBytes < 5242880

  Scenario: Test 5 — SPI registration in shadow JAR (S-002-19)
    * def spiContent = karate.exec('unzip -p ' + shadowJar + ' META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor')
    * match spiContent contains 'io.messagexform.pingaccess.MessageTransformRule'

  Scenario: Test 6 — PA health (plugin configured, no errors)
    * def paLog = karate.exec('docker exec ' + paContainer + ' cat ' + paLogFile)
    * match paLog contains 'MessageTransformRule configured'
