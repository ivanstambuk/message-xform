@ignore
Feature: List bound devices via AM Admin API
  # Called from device-binding.js cleanupBoundDevices()

  Scenario:
    Given url amDirectUrl + '/json/realms/root/users/' + user + '/devices/2fa/binding?_queryFilter=true'
    And header Host = amHostHeader
    And header iplanetDirectoryPro = adminToken
    And header Accept-API-Version = 'resource=1.0, protocol=1.0'
    When method GET
    Then status 200
    * def devices = response.result
