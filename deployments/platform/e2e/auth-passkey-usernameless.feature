Feature: WebAuthn Usernameless (Discoverable Credential) Passkey — E2E

  Tests the FIDO2 discoverable credential (resident key) flow through PingAM's
  UsernamelessJourney. Unlike identifier-first WebAuthnJourney, this journey:
    - Starts directly at WebAuthn Authentication (no NameCallback / username prompt)
    - Uses empty allowCredentials (authenticator picks the credential)
    - Requires userVerification: "required" (UV flag must be set)
    - Falls back to username + password + registration for users with no device

  The flow for a fresh user:
    1. WebAuthn Auth (error/unsupported — no discoverable credential)
    2. UsernameCollector → PasswordCollector → DataStore Decision
    3. WebAuthn Registration → Recovery Codes → WebAuthn Auth (success)

  For a returning user:
    1. WebAuthn Auth → assertion with discoverable credential → success

  Background:
    * def webauthn = call read('helpers/webauthn.js')
    * def origin = 'https://localhost:13000'
    # Direct AM URL for UsernamelessJourney
    * def journeyUrl = amDirectUrl + '/json/authenticate?' + usernamelessJourneyParams
    # Dedicated user for usernameless tests (separate from identifier-first test user)
    * def ulUser = usernamelessTestUser
    * def ulPassword = testPassword

  Scenario: Full usernameless registration + authentication for a fresh user
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

    Given url amDirectUrl + '/json/realms/root/users/' + ulUser + '/devices/2fa/webauthn?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    * def devices = response.result
    * karate.forEach(devices, function(dev){ karate.call('helpers/delete-device.feature', { adminToken: adminToken, user: ulUser, uuid: dev.uuid }) })

    # -----------------------------------------------------------------------
    # Step 1: Initiate usernameless journey — expect WebAuthn Authentication
    #         (no username prompt — starts directly at WebAuthn Auth node)
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    # Usernameless journey starts at WebAuthn Authentication node
    And match response.callbacks[0].type == 'TextOutputCallback'
    And match response.callbacks[2].type == 'HiddenValueCallback'
    And match response.callbacks[3].type == 'ConfirmationCallback'
    * def step1 = response

    # -----------------------------------------------------------------------
    # Step 2: No discoverable credential — submit error to trigger fallback
    #         WebAuthn Auth error → UsernameCollector (NameCallback)
    # -----------------------------------------------------------------------
    * def errorOutcome = '{"error":"NotAllowedError: The operation timed out."}'
    * set step1.callbacks[2].input[0].value = errorOutcome
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step1
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'NameCallback'
    * def step2 = response

    # -----------------------------------------------------------------------
    # Step 3: Submit username → expect PasswordCallback
    # -----------------------------------------------------------------------
    * set step2.callbacks[0].input[0].value = ulUser
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step2
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'PasswordCallback'
    * def step3 = response

    # -----------------------------------------------------------------------
    # Step 4: Submit password → DataStore Decision → WebAuthn Registration
    # -----------------------------------------------------------------------
    * set step3.callbacks[0].input[0].value = ulPassword
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step3
    When method POST
    Then status 200
    # DataStore Decision passed → WebAuthn Registration callbacks
    And match response.callbacks[0].type == 'TextOutputCallback'
    And match response.callbacks[2].type == 'HiddenValueCallback'
    * def step4 = response

    # -----------------------------------------------------------------------
    # Step 5: Parse registration challenge, generate attestation with UV, submit
    # -----------------------------------------------------------------------
    * def regParsed = webauthn.parseCallbacks(step4.callbacks)
    And match regParsed.isRegistration == true

    * def uvReg = regParsed.userVerification == 'required'
    * def regResult = webauthn.register(regParsed.challenge, regParsed.rpId, origin, uvReg)

    * set step4.callbacks[2].input[0].value = regResult.outcome
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step4
    When method POST
    Then status 200
    # Registration success → RecoveryCodeDisplayNode
    And match response.callbacks[0].type == 'TextOutputCallback'
    * def step5 = response

    # -----------------------------------------------------------------------
    # Step 6: Acknowledge recovery codes → WebAuthn Authentication challenge
    # -----------------------------------------------------------------------
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step5
    When method POST
    Then status 200
    # Journey loops back to WebAuthn Authentication with the newly registered device
    And match response.callbacks[0].type == 'TextOutputCallback'
    And match response.callbacks[2].type == 'HiddenValueCallback'
    And match response.callbacks[3].type == 'ConfirmationCallback'
    * def step6 = response

    # -----------------------------------------------------------------------
    # Step 7: Parse auth challenge, generate assertion with UV, submit
    # -----------------------------------------------------------------------
    * def authParsed = webauthn.parseCallbacks(step6.callbacks)
    And match authParsed.isRegistration == false
    And match authParsed.userVerification == 'required'
    # Usernameless: no allowCredentials (empty list — discoverable credential)
    And match authParsed.credentialIds == '#[0]'

    * def uvAuth = authParsed.userVerification == 'required'
    # In usernameless flow, the userHandle must match the stored user.id from registration.
    # AM stores user.id as Uint8Array.from(base64(username)), so the userHandle in the
    # assertion response (decoded back to a string) is the base64 of the username.
    * def userHandle = java.util.Base64.getEncoder().encodeToString(ulUser.getBytes())
    * def authResult = webauthn.authenticate(authParsed.challenge, authParsed.rpId, origin, regResult.privateKey, regResult.credentialIdB64, userHandle, uvAuth)

    # Set the webAuthnOutcome only — do NOT set ConfirmationCallback to 0
    # (value 0 = "Use Recovery Code" which takes the recoveryCode branch;
    #  default value 100 = "not selected" which lets AM verify the assertion)
    * set step6.callbacks[2].input[0].value = authResult.outcome
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step6
    When method POST
    Then status 200
    # Authentication success — tokenId present
    And match response.tokenId == '#present'
    And match response.successUrl == '#present'

  Scenario: Usernameless authentication for a user with registered device
    # This scenario assumes ulUser already has a registered device (from the previous scenario).
    # The journey starts at WebAuthn Authentication — no username prompt at all.

    # -----------------------------------------------------------------------
    # Step 1: Initiate journey — expect WebAuthn Authentication directly
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url journeyUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    # Should get WebAuthn Authentication callbacks (discoverable credential)
    And match response.callbacks[0].type == 'TextOutputCallback'
    And match response.callbacks[2].type == 'HiddenValueCallback'
    * def step1 = response
    * def authParsed = webauthn.parseCallbacks(step1.callbacks)
    And match authParsed.isRegistration == false
    # Usernameless: should have empty allowCredentials
    And match authParsed.credentialIds == '#[0]'
    And match authParsed.userVerification == 'required'
