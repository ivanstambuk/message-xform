@ignore
Feature: Create protected Session Application
  # Called from pa-provision.feature for Phase 8 OAuth tests.
  # Requires: siteId, vhId, atvId, ruleId

  Background:
    * url paAdminUrl
    * configure headers = paAdminHeaders
    * configure ssl = true

  Scenario: Create and configure session app
    # Create the application
    Given path '/applications'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "E2E Session App",
        "contextRoot": "/api/session",
        "defaultAuthType": "API",
        "spaSupportEnabled": false,
        "applicationType": "API",
        "destination": "Site",
        "siteId": #(siteId),
        "virtualHostIds": [#(vhId)],
        "accessValidatorId": #(atvId)
      }
      """
    When method POST
    Then status 200
    * def sessionAppId = response.id

    # Enable it
    Given path '/applications/' + sessionAppId
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "E2E Session App",
        "contextRoot": "/api/session",
        "defaultAuthType": "API",
        "spaSupportEnabled": false,
        "applicationType": "API",
        "destination": "Site",
        "siteId": #(siteId),
        "virtualHostIds": [#(vhId)],
        "accessValidatorId": #(atvId),
        "enabled": true
      }
      """
    When method PUT
    Then status 200

    # Configure root resource (protected + transform rule)
    Given path '/applications/' + sessionAppId + '/resources'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def rootRes = karate.filter(response.items, function(r){ return r.rootResource == true })
    * def rootId = rootRes[0].id

    Given path '/applications/' + sessionAppId + '/resources/' + rootId
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "Root Resource",
        "methods": ["*"],
        "pathPatterns": [{"type": "WILDCARD", "pattern": "/*"}],
        "unprotected": false,
        "enabled": true,
        "auditLevel": "ON",
        "rootResource": true,
        "policy": {
          "API": [{ "type": "Rule", "id": #(ruleId) }]
        }
      }
      """
    When method PUT
    Then status 200
    * karate.log('Session app created and configured (id=' + sessionAppId + ')')
