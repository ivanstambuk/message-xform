Feature: Clean URL Routing — Login — E2E

  Tests the clean URL routing for the username/password authentication flow.
  Verifies that:
    - POST /api/v1/auth/login is rewritten to /am/json/authenticate by message-xform
    - Response-side transforms fire on the rewritten path (clean field prompts, authId in header)
    - Full login flow works end-to-end through clean URLs (initiate → submit → cookie)

  The existing auth-login.feature tests hit the raw /am/json/authenticate path.
  This feature tests the same flows via the clean /api/v1/auth/login URL to
  validate the request-side URL rewrite specs (am-auth-login-url@1.0.0).

  Background:
    * url cleanLoginUrl
    * header Host = paEngineHost
    * header Content-Type = 'application/json'

  Scenario: Clean URL — Initiate login returns transformed field prompts
    Given request {}
    When method POST
    Then status 200
    # Response should have clean field descriptions (URL rewrite + response transform)
    And match response.fields == '#present'
    And match response.fields[0].name == 'username'
    And match response.fields[0].type == 'text'
    And match response.fields[0].prompt == '#present'
    And match response.fields[1].name == 'password'
    And match response.fields[1].type == 'password'
    And match response.fields[1].prompt == '#present'
    # authId should be in response header, NOT in body
    And match responseHeaders['x-auth-session'] == '#present'
    And match response.authId == '#notpresent'
    # Internal fields stripped
    And match response.stage == '#notpresent'
    # Provider header injected
    And match responseHeaders['x-auth-provider'][0] == 'PingAM'

  Scenario: Clean URL — Full login flow returns session cookie
    # Step 1: Initiate via clean URL
    Given request {}
    When method POST
    Then status 200
    * def authSession = responseHeaders['x-auth-session'][0]

    # Step 2: Get raw AM callbacks from a direct call to fill them correctly
    # (Request body is NOT transformed — D9 — so we need AM's callback format)
    * configure cookies = null
    Given url amDirectUrl + '/json/authenticate'
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    * def rawResponse = response

    # Fill in the callback values
    * def filledCallbacks = karate.map(rawResponse.callbacks, function(cb){ if (cb.type == 'NameCallback') { cb.input[0].value = testUser; } else if (cb.type == 'PasswordCallback') { cb.input[0].value = testPassword; } return cb; })
    * def submitBody = { authId: '#(rawResponse.authId)', callbacks: '#(filledCallbacks)' }

    # Clear AM cookies and submit via clean URL
    * configure cookies = null
    Given url cleanLoginUrl
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request submitBody
    When method POST
    Then status 200
    And match response.authenticated == true
    And match response.realm == '/'
    # tokenId stripped from body
    And match response.tokenId == '#notpresent'
    # Session cookie injected
    And match responseHeaders['set-cookie'][0] contains 'iPlanetDirectoryPro='
    And match responseHeaders['set-cookie'][0] contains 'HttpOnly'
    And match responseHeaders['set-cookie'][0] contains 'Secure'
    # Provider header
    And match responseHeaders['x-auth-provider'][0] == 'PingAM'

  Scenario: Clean URL — Invalid credentials returns 401
    # Initiate via clean URL
    Given request {}
    When method POST
    Then status 200

    # Get raw callbacks from AM directly
    * configure cookies = null
    Given url amDirectUrl + '/json/authenticate'
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    * def rawResponse = response

    # Fill with wrong password
    * def filledCallbacks = karate.map(rawResponse.callbacks, function(cb){ if (cb.type == 'NameCallback') { cb.input[0].value = testUser; } else if (cb.type == 'PasswordCallback') { cb.input[0].value = 'WrongPassword'; } return cb; })
    * def submitBody = { authId: '#(rawResponse.authId)', callbacks: '#(filledCallbacks)' }

    # Submit via clean URL
    * configure cookies = null
    Given url cleanLoginUrl
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And request submitBody
    When method POST
    Then status 401
