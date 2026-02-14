@setup
Feature: PA Admin API provisioning
  # Converts shell lines 370-940 (PA Admin API provisioning) to Karate.
  # Called via `callonce` from test features to ensure one-time setup.
  # Exports: siteId, ruleId, denyRuleId, appId, denyAppId, sessionAppId, atvId,
  #          webSessionId, webAppId, phase8Skip, phase8bSkip

  Background:
    * url paAdminUrl
    * configure headers = paAdminHeaders
    * configure ssl = true

  Scenario: Provision PingAccess for E2E tests

    # -----------------------------------------------------------------------
    # Plugin discovery
    # -----------------------------------------------------------------------
    Given path '/rules/descriptors/MessageTransform'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    And match response.type == 'MessageTransform'
    And match response.className == 'io.messagexform.pingaccess.MessageTransformRule'
    And match response.modes contains 'Site'
    * def pluginDiscovered = true

    # -----------------------------------------------------------------------
    # Virtual host lookup (default localhost:3000)
    # -----------------------------------------------------------------------
    Given path '/virtualhosts'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def vhList = response.items
    * def vhMatch = karate.filter(vhList, function(vh){ return vh.host == 'localhost' && vh.port == 3000 })
    * def vhId = vhMatch.length > 0 ? vhMatch[0].id : null

    # Create virtual host if default not found
    * if (vhId == null) karate.call('classpath:e2e/setup/create-virtualhost.feature')

    # -----------------------------------------------------------------------
    # Create Site → Echo Backend
    # -----------------------------------------------------------------------
    Given path '/sites'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request { name: 'Echo Backend', targets: ['pa-e2e-echo:8080'], secure: false, maxConnections: 10, sendPaCookie: false, availabilityProfileId: 1, trustedCertificateGroupId: 0 }
    When method POST
    Then status 200
    * def siteId = response.id
    * karate.log('Site created (id=' + siteId + ')')

    # -----------------------------------------------------------------------
    # Create MessageTransform rule (PASS_THROUGH)
    # -----------------------------------------------------------------------
    Given path '/rules'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "className": "io.messagexform.pingaccess.MessageTransformRule",
        "name": "E2E Transform Rule",
        "supportedDestinations": ["Site"],
        "configuration": {
          "specsDir": "/specs",
          "profilesDir": "/profiles",
          "activeProfile": "e2e-profile",
          "errorMode": "PASS_THROUGH",
          "reloadIntervalSec": "0",
          "schemaValidation": "LENIENT",
          "enableJmxMetrics": "true"
        }
      }
      """
    When method POST
    Then status 200
    * def ruleId = response.id
    * karate.log('PASS_THROUGH rule created (id=' + ruleId + ')')

    # -----------------------------------------------------------------------
    # Create DENY rule
    # -----------------------------------------------------------------------
    Given path '/rules'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "className": "io.messagexform.pingaccess.MessageTransformRule",
        "name": "E2E DENY Rule",
        "supportedDestinations": ["Site"],
        "configuration": {
          "specsDir": "/specs",
          "profilesDir": "/profiles",
          "activeProfile": "e2e-profile",
          "errorMode": "DENY",
          "reloadIntervalSec": "0",
          "schemaValidation": "LENIENT",
          "enableJmxMetrics": "true"
        }
      }
      """
    When method POST
    Then status 200
    * def denyRuleId = response.id
    * karate.log('DENY rule created (id=' + denyRuleId + ')')

    # -----------------------------------------------------------------------
    # Create Application (PASS_THROUGH) — /api
    # -----------------------------------------------------------------------
    Given path '/applications'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request { name: 'E2E Test App', contextRoot: '/api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: '#(siteId)', virtualHostIds: ['#(vhId)'] }
    When method POST
    Then status 200
    * def appId = response.id

    # Enable the app (PA ignores 'enabled' in POST)
    Given path '/applications/' + appId
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request { name: 'E2E Test App', contextRoot: '/api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: '#(siteId)', virtualHostIds: ['#(vhId)'], enabled: true }
    When method PUT
    Then status 200
    And match response.enabled == true
    * karate.log('Application created and enabled (id=' + appId + ')')

    # Configure root resource — unprotected, attach transform rule
    Given path '/applications/' + appId + '/resources'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def rootRes = karate.filter(response.items, function(r){ return r.rootResource == true })
    * def rootResourceId = rootRes[0].id

    Given path '/applications/' + appId + '/resources/' + rootResourceId
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "Root Resource",
        "methods": ["*"],
        "pathPatterns": [{"type": "WILDCARD", "pattern": "/*"}],
        "unprotected": true,
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

    # -----------------------------------------------------------------------
    # Create DENY Application — /deny-api
    # -----------------------------------------------------------------------
    Given path '/applications'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request { name: 'E2E DENY App', contextRoot: '/deny-api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: '#(siteId)', virtualHostIds: ['#(vhId)'] }
    When method POST
    Then status 200
    * def denyAppId = response.id

    Given path '/applications/' + denyAppId
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request { name: 'E2E DENY App', contextRoot: '/deny-api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: '#(siteId)', virtualHostIds: ['#(vhId)'], enabled: true }
    When method PUT
    Then status 200
    And match response.enabled == true

    # DENY app root resource
    Given path '/applications/' + denyAppId + '/resources'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def denyRootRes = karate.filter(response.items, function(r){ return r.rootResource == true })
    * def denyRootId = denyRootRes[0].id

    Given path '/applications/' + denyAppId + '/resources/' + denyRootId
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "name": "Root Resource",
        "methods": ["*"],
        "pathPatterns": [{"type": "WILDCARD", "pattern": "/*"}],
        "unprotected": true,
        "enabled": true,
        "auditLevel": "ON",
        "rootResource": true,
        "policy": {
          "API": [{ "type": "Rule", "id": #(denyRuleId) }]
        }
      }
      """
    When method PUT
    Then status 200
    * karate.log('DENY app configured (id=' + denyAppId + ')')

    # -----------------------------------------------------------------------
    # Phase 8: OAuth — Third-Party Service + ATV + Session App
    # -----------------------------------------------------------------------
    * def phase8Skip = false

    # Create Third-Party Service for mock-OIDC
    Given path '/thirdPartyServices'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request { name: 'Mock OIDC Server', targets: ['#(oidcContainer):8080'], secure: false, maxConnections: 5, availabilityProfileId: 1, trustedCertificateGroupId: 0 }
    When method POST
    * def oidcSvcId = response.id
    * if (responseStatus != 200) phase8Skip = true
    * if (responseStatus != 200) karate.log('WARN: Third-Party Service creation failed — skipping Phase 8')

    # Create Access Token Validator
    * def atvId = null
    * if (!phase8Skip) karate.call('classpath:e2e/setup/create-atv.feature', { oidcSvcId: oidcSvcId, oidcContainer: oidcContainer })

    # Create protected Session App
    * def sessionAppId = null
    * if (!phase8Skip) karate.call('classpath:e2e/setup/create-session-app.feature', { siteId: siteId, vhId: vhId, atvId: __arg ? __arg.atvId : atvId, ruleId: ruleId })

    # -----------------------------------------------------------------------
    # Phase 8b: Web Session / OIDC
    # -----------------------------------------------------------------------
    * def phase8bSkip = phase8Skip
    * def webSessionId = null
    * def webAppId = null
    * if (!phase8bSkip) karate.call('classpath:e2e/setup/create-web-session.feature', { siteId: siteId, vhId: vhId, ruleId: ruleId, oidcContainer: oidcContainer })
