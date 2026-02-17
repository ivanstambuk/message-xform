@ignore
Feature: Delete a WebAuthn device via AM Admin API
  # Called from auth-passkey.feature cleanup step

  Scenario:
    Given url amDirectUrl + '/json/realms/root/users/' + user + '/devices/2fa/webauthn/' + uuid
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method DELETE
    Then status 200
