Feature: Logout — E2E

  Tests session invalidation via the AM sessions endpoint through PingAccess.
  Authenticates first to obtain a session, then logs out.

  Scenario: Logout invalidates session cookie
    # Step 1: Authenticate directly against AM to get a session token
    # (Direct call avoids transform complexity for test setup)
    Given url amDirectUrl + '/json/authenticate'
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    * def rawResponse = response

    # Fill callbacks
    * def filledCallbacks = karate.map(rawResponse.callbacks, function(cb){ if (cb.type == 'NameCallback') { cb.input[0].value = testUser; } else if (cb.type == 'PasswordCallback') { cb.input[0].value = testPassword; } return cb; })
    * def submitBody = { authId: '#(rawResponse.authId)', callbacks: '#(filledCallbacks)' }

    # Clear cookies from the initial call (prevents domain mismatch)
    * configure cookies = null

    Given url amDirectUrl + '/json/authenticate'
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request submitBody
    When method POST
    Then status 200
    And match response.tokenId == '#present'
    * def sessionToken = response.tokenId

    # Clear cookies again — AM set domain=platform.local cookies that would
    # cause issues when sent to PA's localhost:3000 VH
    * configure cookies = null

    # Step 2: Logout via PA (response-side transform fires)
    Given url logoutUrl
    And header Host = paEngineHost
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And header iPlanetDirectoryPro = sessionToken
    And request {}
    When method POST
    Then status 200

    # Step 3: Verify session is invalid — use the token again
    * configure cookies = null
    Given url amDirectUrl + '/json/sessions/?_action=validate'
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And header iPlanetDirectoryPro = sessionToken
    And request {}
    When method POST
    Then status 200
    And match response.valid == false
