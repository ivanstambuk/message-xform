Feature: Clean URL Routing — Passkey — E2E

  Tests the clean URL routing for WebAuthn/passkey authentication flows.
  Verifies that:
    - POST /api/v1/auth/passkey is rewritten to /am/json/authenticate + WebAuthnJourney
    - POST /api/v1/auth/passkey/usernameless is rewritten to ... + UsernamelessJourney
    - Response-side transforms fire on the rewritten path (WebAuthn challenge parsing)

  The existing auth-passkey.feature and auth-passkey-usernameless.feature hit
  the raw AM paths. This feature tests clean URL initiation to validate the
  request-side URL rewrite specs (am-passkey-url, am-passkey-usernameless-url).

  Background:
    * header Host = paEngineHost
    * header Content-Type = 'application/json'

  Scenario: Clean URL — Passkey identifier-first returns username prompt
    # /api/v1/auth/passkey → WebAuthnJourney, which starts with NameCallback
    Given url cleanPasskeyUrl
    And request {}
    When method POST
    Then status 200
    # WebAuthnJourney starts with NameCallback (username prompt)
    # The response transform should produce clean field output
    And match response.fields == '#present'
    And match response.fields[0].name == 'username'
    And match response.fields[0].type == 'text'
    # authId in header, not body
    And match responseHeaders['x-auth-session'] == '#present'
    And match response.authId == '#notpresent'
    # Provider header
    And match responseHeaders['x-auth-provider'][0] == 'PingAM'

  Scenario: Clean URL — Passkey usernameless returns WebAuthn challenge
    # /api/v1/auth/passkey/usernameless → UsernamelessJourney, which starts at
    # WebAuthnAuthenticationNode (no username prompt — discoverable credential)
    Given url cleanPasskeyUsernamelessUrl
    And request {}
    When method POST
    Then status 200
    # Response should be WebAuthn challenge (parsed from JS by am-webauthn-response)
    And match response.type == 'webauthn-auth'
    And match response.rpId == 'localhost'
    And match response.challengeRaw == '#present'
    And match response.userVerification == 'required'
    And match response.timeout == '#present'
    # authId in header, not body
    And match responseHeaders['x-auth-session'] == '#present'
    And match response.authId == '#notpresent'
