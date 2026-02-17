Feature: WebAuthn Passkey Registration & Authentication — E2E

  Tests the full FIDO2/WebAuthn passkey flow through PingAM's WebAuthnJourney:
    1. Registration: username/password auth → WebAuthn credential creation → recovery codes → auto-authenticate
    2. Authentication: username → WebAuthn assertion → session token

  The WebAuthn ceremony uses pure JDK crypto (EC P-256 / ES256) via the helpers/webauthn.js helper.
  No external simulator dependencies are required.

  Background:
    * def webauthn = call read('helpers/webauthn.js')
    * def origin = 'https://localhost:13000'
    # Direct AM URL for WebAuthn journey (not through PA — passkey transforms deferred per D13)
    * def journeyUrl = amDirectUrl + '/json/authenticate?' + passkeyJourneyParams
    # Dedicated user for passkey tests (separate from testUser to avoid cross-test conflicts)
    * def pkUser = passkeyTestUser
    * def pkPassword = testPassword

  Scenario: Full passkey registration + authentication for a fresh user
    # -----------------------------------------------------------------------
    # Cleanup: Remove any pre-existing WebAuthn devices from the test user
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url amDirectUrl + '/json/authenticate'
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And header X-OpenAM-Username = amAdminUser
    And header X-OpenAM-Password = amAdminPassword
    And request {}
    When method POST
    Then status 200
    * def adminToken = response.tokenId

    Given url amDirectUrl + '/json/realms/root/users/' + pkUser + '/devices/2fa/webauthn?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    * def devices = response.result
    * karate.forEach(devices, function(dev){ karate.call('helpers/delete-device.feature', { adminToken: adminToken, user: pkUser, uuid: dev.uuid }) })

    # -----------------------------------------------------------------------
    # Step 1: Initiate journey — expect NameCallback
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'NameCallback'
    * def step1 = response

    # -----------------------------------------------------------------------
    # Step 2: Submit username — expect PasswordCallback (no device registered)
    # -----------------------------------------------------------------------
    * set step1.callbacks[0].input[0].value = pkUser
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step1
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'PasswordCallback'
    * def step2 = response

    # -----------------------------------------------------------------------
    # Step 3: Submit password — expect WebAuthn Registration callbacks
    # -----------------------------------------------------------------------
    * set step2.callbacks[0].input[0].value = pkPassword
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step2
    When method POST
    Then status 200
    # WebAuthn Registration: TextOutputCallback (JS), TextOutputCallback (UI), HiddenValueCallback
    And match response.callbacks[0].type == 'TextOutputCallback'
    And match response.callbacks[2].type == 'HiddenValueCallback'
    * def step3 = response

    # -----------------------------------------------------------------------
    # Step 4: Parse challenge, generate attestation, submit registration
    # -----------------------------------------------------------------------
    * def parsed = webauthn.parseCallbacks(step3.callbacks)
    And match parsed.isRegistration == true
    * def regResult = webauthn.register(parsed.challenge, parsed.rpId, origin)

    # Set the webAuthnOutcome in the HiddenValueCallback
    * set step3.callbacks[2].input[0].value = regResult.outcome
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step3
    When method POST
    Then status 200
    # Registration success → RecoveryCodeDisplayNode (TextOutputCallback with JS)
    And match response.callbacks[0].type == 'TextOutputCallback'
    * def step4 = response

    # -----------------------------------------------------------------------
    # Step 5: Acknowledge recovery codes → WebAuthn Authentication challenge
    # -----------------------------------------------------------------------
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step4
    When method POST
    Then status 200
    # Journey loops back to WebAuthn Authentication with the newly registered device
    And match response.callbacks[0].type == 'TextOutputCallback'
    And match response.callbacks[2].type == 'HiddenValueCallback'
    And match response.callbacks[3].type == 'ConfirmationCallback'
    * def step5 = response

    # -----------------------------------------------------------------------
    # Step 6: Parse auth challenge, generate assertion, submit authentication
    # -----------------------------------------------------------------------
    * def authParsed = webauthn.parseCallbacks(step5.callbacks)
    And match authParsed.isRegistration == false
    * def authResult = webauthn.authenticate(authParsed.challenge, authParsed.rpId, origin, regResult.privateKey, regResult.credentialIdB64, pkUser)

    # Set the webAuthnOutcome only — do NOT set ConfirmationCallback to 0
    # (value 0 = "Use Recovery Code" which takes the recoveryCode branch;
    #  default value 100 = "not selected" which lets AM verify the assertion)
    * set step5.callbacks[2].input[0].value = authResult.outcome
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step5
    When method POST
    Then status 200
    # Authentication success — tokenId present
    And match response.tokenId == '#present'
    And match response.successUrl == '#present'

  Scenario: Passkey authentication for a user with registered device
    # This scenario assumes pkUser already has a registered device (from the previous scenario).
    # It tests the direct authentication path without registration.

    # -----------------------------------------------------------------------
    # Step 1: Initiate journey — expect NameCallback
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'NameCallback'
    * def step1 = response

    # -----------------------------------------------------------------------
    # Step 2: Submit username — expect WebAuthn Authentication (device exists)
    # -----------------------------------------------------------------------
    * set step1.callbacks[0].input[0].value = pkUser
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step1
    When method POST
    Then status 200
    # When device is registered, step 2 returns WebAuthn Auth directly
    And match response.callbacks[0].type == 'TextOutputCallback'
    And match response.callbacks[2].type == 'HiddenValueCallback'
    * def step2 = response
    * def authParsed = webauthn.parseCallbacks(step2.callbacks)
    And match authParsed.isRegistration == false
    # allowCredentials should contain at least one credential ID
    And match authParsed.credentialIds == '#[_ > 0]'

  Scenario: WebAuthn unsupported device falls back to error
    # Test that submitting "unsupported" as the webAuthnOutcome is handled gracefully

    * configure cookies = null
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    * def step1 = response
    * set step1.callbacks[0].input[0].value = pkUser
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step1
    When method POST
    Then status 200
    * def step2 = response

    # Submit "unsupported" as the outcome
    * def unsupported = '{"error":"NotSupportedError: The operation is not supported."}'
    * karate.forEach(step2.callbacks, function(cb){ if (cb.type == 'HiddenValueCallback') cb.input[0].value = unsupported })
    # Set confirmation callback if present
    * karate.forEach(step2.callbacks, function(cb){ if (cb.type == 'ConfirmationCallback') cb.input[0].value = 0 })

    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step2
    When method POST
    # Journey should restart or show an error — not crash
    Then assert responseStatus == 200 || responseStatus == 401
