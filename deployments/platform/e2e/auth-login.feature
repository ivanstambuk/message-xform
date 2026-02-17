Feature: Username/Password Login — E2E

  Tests the full username/password authentication flow through PingAccess
  with message-xform transformations:
    - POST /am/json/authenticate (initiate) → clean field prompts + authId in header
    - POST /am/json/authenticate (submit)   → authenticated + session cookie

  Background:
    * url loginUrl
    * header Host = paEngineHost
    * header Content-Type = 'application/json'
    * header Accept-API-Version = amApiVersion

  Scenario: Step 1 — Initiate login returns field prompts and auth session header
    Given request {}
    When method POST
    Then status 200
    # Response should have clean field descriptions (transformed from AM callbacks)
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
    And match response._authId == '#notpresent'
    # stage should be stripped from response
    And match response.stage == '#notpresent'
    # Provider header should be present
    And match responseHeaders['x-auth-provider'][0] == 'PingAM'

  Scenario: Step 2 — Submit credentials returns session cookie
    # Step 1: Initiate to get callbacks (raw from AM, transformed by message-xform)
    Given request {}
    When method POST
    Then status 200
    * def authSession = responseHeaders['x-auth-session'][0]

    # Step 2: Submit credentials
    # We need to send the original AM callback structure with filled-in values.
    # The authId from the header must be sent back in the body as authId
    # (request direction is NOT transformed — D9).
    # Get raw AM response from a direct call to fill callbacks correctly
    # Clear AM cookies before making a direct call (different domain)
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

    # Clear AM cookies before switching back to PA (domain mismatch)
    * configure cookies = null
    # Submit via PA (response will be transformed)
    Given url loginUrl
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request submitBody
    When method POST
    Then status 200
    And match response.authenticated == true
    And match response.realm == '/'
    # tokenId should NOT be in the body (stripped by am-strip-internal)
    And match response.tokenId == '#notpresent'
    And match response._tokenId == '#notpresent'
    # Session cookie should be set (injected by dynamic header expr)
    And match responseHeaders['set-cookie'][0] contains 'iPlanetDirectoryPro='
    And match responseHeaders['set-cookie'][0] contains 'HttpOnly'
    And match responseHeaders['set-cookie'][0] contains 'Secure'
    # Provider header
    And match responseHeaders['x-auth-provider'][0] == 'PingAM'

  Scenario: Step 2 — Invalid credentials returns 401
    # Step 1: Initiate via PA (get transformed response)
    Given url loginUrl
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200

    # Clear cookies before cross-domain call
    * configure cookies = null
    # Step 1b: Get raw callbacks from AM directly to fill them
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

    # Clear AM cookies before switching to PA
    * configure cookies = null
    # Submit bad credentials via PA
    Given url loginUrl
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request submitBody
    When method POST
    Then status 401
