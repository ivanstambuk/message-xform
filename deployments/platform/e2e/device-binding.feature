Feature: Device Binding & Signing Verification — E2E

  Tests PingAM's Device Binding flow using the DeviceBindingCallback and
  DeviceSigningVerifierCallback with authenticationType=NONE (headless mode).

  Flow overview:
    1. Binding:  username/password → DeviceBindingCallback → RSA key gen + RS512 JWS → device stored
    2. Signing:  username/password → DeviceSigningVerifierCallback → RS512 JWS with stored key → verified
    3. Cleanup:  delete bound devices via AM Admin API → signing fails

  The device binding ceremony uses pure JDK crypto (RSA 2048 / RS512) via the
  helpers/device-binding.js helper. No external SDK or simulator required.

  Background:
    * def deviceBinding = call read('helpers/device-binding.js')
    # Direct AM URL for device binding journeys (not through PA)
    * def bindingUrl = amDirectUrl + '/json/authenticate?' + deviceBindingJourneyParams
    * def signingUrl = amDirectUrl + '/json/authenticate?' + deviceSigningJourneyParams
    # Dedicated user for device binding tests
    * def dbUser = deviceBindingTestUser
    * def dbPassword = testPassword

  Scenario: Full device binding registration + signing verification

    # -----------------------------------------------------------------------
    # Cleanup: Remove any pre-existing bound devices from the test user
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

    Given url amDirectUrl + '/json/realms/root/users/' + dbUser + '/devices/2fa/binding?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    * def existingDevices = response.result
    * karate.forEach(existingDevices, function(dev){ karate.call('helpers/delete-bound-device.feature', { amDirectUrl: amDirectUrl, amHostHeader: amHostHeader, adminToken: adminToken, user: dbUser, uuid: dev.uuid }) })

    # -----------------------------------------------------------------------
    # Step 1: Initiate binding journey — expect NameCallback (username)
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'NameCallback'
    * def step1 = response

    # -----------------------------------------------------------------------
    # Step 2: Submit username — expect PasswordCallback
    # -----------------------------------------------------------------------
    * set step1.callbacks[0].input[0].value = dbUser
    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step1
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'PasswordCallback'
    * def step2 = response

    # -----------------------------------------------------------------------
    # Step 3: Submit password — expect DeviceBindingCallback
    # -----------------------------------------------------------------------
    * set step2.callbacks[0].input[0].value = dbPassword
    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step2
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'DeviceBindingCallback'
    * def step3 = response

    # -----------------------------------------------------------------------
    # Step 4: Parse DeviceBindingCallback, generate RSA key pair, sign JWS,
    #         and submit the binding response
    # -----------------------------------------------------------------------
    * def bindResult = deviceBinding.bind(step3.callbacks)
    And match bindResult.parsed.authenticationType == 'NONE'
    And match bindResult.parsed.challenge == '#present'
    And match bindResult.parsed.userId == '#present'

    # Submit the binding response (callbacks mutated in place by bind())
    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request step3
    When method POST
    Then status 200
    # Binding success — tokenId present (authenticated session)
    And match response.tokenId == '#present'
    And match response.successUrl == '#present'
    * def bindingToken = response.tokenId
    * karate.log('Device binding successful — tokenId:', bindingToken)

    # Save key pair for signing verification below
    * def savedPrivateKey = bindResult.keyPair.privateKey
    * def savedKid = bindResult.keyPair.kid

    # -----------------------------------------------------------------------
    # Step 5: Verify the device was stored — query bound devices
    # -----------------------------------------------------------------------
    Given url amDirectUrl + '/json/realms/root/users/' + dbUser + '/devices/2fa/binding?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    And match response.result == '#[_ > 0]'
    * karate.log('Bound devices count:', response.result.length)

    # -----------------------------------------------------------------------
    # Step 6: Initiate signing verification journey — expect NameCallback
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url signingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'NameCallback'
    * def signStep1 = response

    # -----------------------------------------------------------------------
    # Step 7: Submit username — expect PasswordCallback
    # -----------------------------------------------------------------------
    * set signStep1.callbacks[0].input[0].value = dbUser
    Given url signingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request signStep1
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'PasswordCallback'
    * def signStep2 = response

    # -----------------------------------------------------------------------
    # Step 8: Submit password — expect DeviceSigningVerifierCallback
    # -----------------------------------------------------------------------
    * set signStep2.callbacks[0].input[0].value = dbPassword
    Given url signingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request signStep2
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'DeviceSigningVerifierCallback'
    * def signStep3 = response

    # -----------------------------------------------------------------------
    # Step 9: Parse DeviceSigningVerifierCallback, sign JWS with stored key,
    #         and submit the signing response
    # -----------------------------------------------------------------------
    * def signResult = deviceBinding.sign(signStep3.callbacks, savedPrivateKey, savedKid)
    And match signResult.parsed.challenge == '#present'

    Given url signingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request signStep3
    When method POST
    Then status 200
    # Signing verification success — tokenId present
    And match response.tokenId == '#present'
    * karate.log('Device signing verification successful')


  Scenario: Signing verification fails after device cleanup

    # -----------------------------------------------------------------------
    # First, bind a device so we have something to clean up
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

    # Clean up any existing devices first
    Given url amDirectUrl + '/json/realms/root/users/' + dbUser + '/devices/2fa/binding?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    * karate.forEach(response.result, function(dev){ karate.call('helpers/delete-bound-device.feature', { amDirectUrl: amDirectUrl, amHostHeader: amHostHeader, adminToken: adminToken, user: dbUser, uuid: dev.uuid }) })

    # Bind a fresh device
    * configure cookies = null
    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    * def s1 = response
    * set s1.callbacks[0].input[0].value = dbUser

    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request s1
    When method POST
    Then status 200
    * def s2 = response
    * set s2.callbacks[0].input[0].value = dbPassword

    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request s2
    When method POST
    Then status 200
    And match response.callbacks[0].type == 'DeviceBindingCallback'
    * def s3 = response
    * def bindResult = deviceBinding.bind(s3.callbacks)
    * def savedPrivateKey = bindResult.keyPair.privateKey
    * def savedKid = bindResult.keyPair.kid

    Given url bindingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request s3
    When method POST
    Then status 200
    And match response.tokenId == '#present'

    # Verify device is stored
    Given url amDirectUrl + '/json/realms/root/users/' + dbUser + '/devices/2fa/binding?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    And match response.result == '#[_ > 0]'
    * def boundDevices = response.result

    # -----------------------------------------------------------------------
    # Delete ALL bound devices
    # -----------------------------------------------------------------------
    * karate.forEach(boundDevices, function(dev){ karate.call('helpers/delete-bound-device.feature', { amDirectUrl: amDirectUrl, amHostHeader: amHostHeader, adminToken: adminToken, user: dbUser, uuid: dev.uuid }) })

    # Verify no devices remain
    Given url amDirectUrl + '/json/realms/root/users/' + dbUser + '/devices/2fa/binding?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    And match response.result == '#[0]'

    # -----------------------------------------------------------------------
    # Attempt signing verification — should fail (no bound device)
    # -----------------------------------------------------------------------
    * configure cookies = null
    Given url signingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request {}
    When method POST
    Then status 200
    * def vs1 = response
    * set vs1.callbacks[0].input[0].value = dbUser

    Given url signingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request vs1
    When method POST
    Then status 200
    * def vs2 = response
    * set vs2.callbacks[0].input[0].value = dbPassword

    Given url signingUrl
    And header Host = amHostHeader
    And header Content-Type = 'application/json'
    And header Accept-API-Version = amApiVersion
    And request vs2
    When method POST
    # With no bound device, the DeviceSigningVerifier node should fail.
    # AM may return:
    #   - 401 (unauthorized) if the journey routes failure to a Failure node
    #   - 200 with no DeviceSigningVerifierCallback (journey skips to failure)
    # Either outcome confirms that signing fails after device cleanup.
    Then assert responseStatus == 200 || responseStatus == 401
    * if (responseStatus == 200) karate.log('Signing journey returned 200 — checking for failure outcome')
    * if (responseStatus == 200 && response.tokenId) karate.fail('Expected signing to fail after device cleanup, but got tokenId')
    * if (responseStatus == 401) karate.log('Signing verification correctly failed with 401 after device cleanup')


  Scenario: Self-test — verify device-binding.js crypto helper

    # Run the built-in self-test to validate JWS generation + RS512 verification
    * deviceBinding.selfTest()
