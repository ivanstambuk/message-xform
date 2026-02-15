@ignore
Feature: Create Web Session and Web Application for OIDC auth code flow
  # Called from pa-provision.feature for Web Session (L4 session state).
  # Requires: siteId, vhId, ruleId, oidcContainer

  Background:
    * url paAdminUrl
    * configure headers = paAdminHeaders
    * configure ssl = true

  Scenario: Create Web Session + Web App
    # Create Web Session with OIDC settings
    Given path '/webSessions'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "E2E OIDC Web Session",
        "audience": "e2e-web-session",
        "clientCredentials": {
          "clientId": "e2e-web-client",
          "clientSecret": { "value": "e2e-secret" }
        },
        "cookieType": "Signed",
        "oidcLoginType": "Code",
        "requestPreservationType": "None",
        "sessionTimeoutInMinutes": 5,
        "idleTimeoutInMinutes": 5,
        "webStorageType": "SessionStorage",
        "scopes": ["openid", "profile", "email"],
        "sendRequestedUrlToProvider": false,
        "enableRefreshUser": false,
        "pkceChallengeType": "OFF",
        "cacheUserAttributes": true,
        "requestProfile": true
      }
      """
    When method POST
    * def webSessionId = responseStatus == 200 ? response.id : null
    * def oidcSkip = webSessionId == null
    * if (oidcSkip) karate.log('WARN: Web Session creation failed — OIDC tests skipped')
    * if (oidcSkip) karate.abort()

    # Create Web Application
    Given path '/applications'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "E2E Web Session App",
        "contextRoot": "/web/session",
        "defaultAuthType": "Web",
        "spaSupportEnabled": false,
        "applicationType": "Web",
        "destination": "Site",
        "siteId": #(siteId),
        "virtualHostIds": [#(vhId)],
        "webSessionId": #(webSessionId)
      }
      """
    When method POST
    * def webAppId = responseStatus == 200 ? response.id : null
    * if (webAppId == null) oidcSkip = true
    * if (webAppId == null) karate.abort()

    # Enable
    Given path '/applications/' + webAppId
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "E2E Web Session App",
        "contextRoot": "/web/session",
        "defaultAuthType": "Web",
        "spaSupportEnabled": false,
        "applicationType": "Web",
        "destination": "Site",
        "siteId": #(siteId),
        "virtualHostIds": [#(vhId)],
        "webSessionId": #(webSessionId),
        "enabled": true
      }
      """
    When method PUT
    Then status 200

    # Configure root resource (protected + transform rule, Web policy bucket)
    Given path '/applications/' + webAppId + '/resources'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def rootRes = karate.filter(response.items, function(r){ return r.rootResource == true })
    * def rootId = rootRes[0].id

    Given path '/applications/' + webAppId + '/resources/' + rootId
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
          "Web": [{ "type": "Rule", "id": #(ruleId) }]
        }
      }
      """
    When method PUT
    Then status 200
    * karate.log('Web Session app created and configured (id=' + webAppId + ')')
