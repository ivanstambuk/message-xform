    @setup
Feature: PA Admin API provisioning (idempotent)
    # Converts shell lines 370-940 (PA Admin API provisioning) to Karate.
    # Called via `callonce` from test features to ensure one-time setup.
    # `callonce` caches globally for the JVM lifetime, BUT we make this
    # idempotent anyway for resilience against stale PA state from prior runs.
    #
    # Exports: siteId, ruleId, denyRuleId, appId, denyAppId, vhId,
    #          phase8Skip, phase8bSkip

  Background:
    * url paAdminUrl
    * configure headers = paAdminHeaders
    * configure ssl = true
    * def auth = call read('classpath:e2e/helpers/basic-auth.js')

    # Helper: look up a PA resource by name; returns id or null
    * def findId =
      """
      function(path, name) {
        var res = karate.call('classpath:e2e/helpers/find-resource.feature',
          { collectionPath: path, resourceName: name });
        return res.resourceId;
      }
      """

  Scenario: Provision PingAccess for E2E tests

    # -----------------------------------------------------------------------
    # Plugin discovery (read-only, always safe)
    # -----------------------------------------------------------------------
    Given path '/rules/descriptors/MessageTransform'
    And header Authorization = auth
    When method GET
    Then status 200
    And match response.type == 'MessageTransform'
    And match response.className == 'io.messagexform.pingaccess.MessageTransformRule'
    And match response.modes contains 'Site'

    # -----------------------------------------------------------------------
    # Virtual host (look up default localhost:3000)
    # -----------------------------------------------------------------------
    Given path '/virtualhosts'
    And header Authorization = auth
    When method GET
    Then status 200
    * def vhMatch = karate.filter(response.items, function(vh){ return vh.host == 'localhost' && vh.port == 3000 })
    * def vhId = vhMatch.length > 0 ? vhMatch[0].id : null
    * assert vhId != null

    # -----------------------------------------------------------------------
    # Site → Echo Backend (idempotent: find-or-create)
    # -----------------------------------------------------------------------
    * def siteId = findId('/sites', 'Echo Backend')

    * if (siteId == null) karate.set('siteId', karate.call('classpath:e2e/helpers/create-if-absent.feature', { path: '/sites', body: { name: 'Echo Backend', targets: ['pa-e2e-echo:8080'], secure: false, maxConnections: 10, sendPaCookie: false, availabilityProfileId: 1, trustedCertificateGroupId: 0 } }).resourceId)

    * karate.log('Site id=' + siteId)

    # -----------------------------------------------------------------------
    # PASS_THROUGH rule (idempotent)
    # -----------------------------------------------------------------------
    * def ruleId = findId('/rules', 'E2E Transform Rule')

    * def ptRuleBody = { className: 'io.messagexform.pingaccess.MessageTransformRule', name: 'E2E Transform Rule', supportedDestinations: ['Site'], configuration: { specsDir: '/specs', profilesDir: '/profiles', activeProfile: 'e2e-profile', errorMode: 'PASS_THROUGH', reloadIntervalSec: '5', schemaValidation: 'LENIENT', enableJmxMetrics: 'true' } }
    * if (ruleId == null) karate.set('ruleId', karate.call('classpath:e2e/helpers/create-if-absent.feature', { path: '/rules', body: ptRuleBody }).resourceId)

    * karate.log('PASS_THROUGH rule id=' + ruleId)

    # -----------------------------------------------------------------------
    # DENY rule (idempotent)
    # -----------------------------------------------------------------------
    * def denyRuleId = findId('/rules', 'E2E DENY Rule')

    * def denyRuleBody = { className: 'io.messagexform.pingaccess.MessageTransformRule', name: 'E2E DENY Rule', supportedDestinations: ['Site'], configuration: { specsDir: '/specs', profilesDir: '/profiles', activeProfile: 'e2e-profile', errorMode: 'DENY', reloadIntervalSec: '5', schemaValidation: 'LENIENT', enableJmxMetrics: 'true' } }
    * if (denyRuleId == null) karate.set('denyRuleId', karate.call('classpath:e2e/helpers/create-if-absent.feature', { path: '/rules', body: denyRuleBody }).resourceId)

    * karate.log('DENY rule id=' + denyRuleId)

    # -----------------------------------------------------------------------
    # Application (PASS_THROUGH) — /api (idempotent)
    # -----------------------------------------------------------------------
    * def appId = findId('/applications', 'E2E Test App')

    * if (appId == null) karate.set('appId', karate.call('classpath:e2e/helpers/create-if-absent.feature', { path: '/applications', body: { name: 'E2E Test App', contextRoot: '/api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: siteId, virtualHostIds: [vhId] } }).resourceId)

    # Enable (PUT is idempotent)
    Given path '/applications/' + appId
    And header Authorization = auth
    And request { name: 'E2E Test App', contextRoot: '/api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: '#(siteId)', virtualHostIds: ['#(vhId)'], enabled: true }
    When method PUT
    Then status 200
    And match response.enabled == true

    # Root resource (PUT is idempotent)
    Given path '/applications/' + appId + '/resources'
    And header Authorization = auth
    When method GET
    Then status 200
    * def rootRes = karate.filter(response.items, function(r){ return r.rootResource == true })
    * def rootResourceId = rootRes[0].id

    Given path '/applications/' + appId + '/resources/' + rootResourceId
    And header Authorization = auth
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
    * karate.log('App configured (id=' + appId + ')')

    # -----------------------------------------------------------------------
    # DENY Application — /deny-api (idempotent)
    # -----------------------------------------------------------------------
    * def denyAppId = findId('/applications', 'E2E DENY App')

    * if (denyAppId == null) karate.set('denyAppId', karate.call('classpath:e2e/helpers/create-if-absent.feature', { path: '/applications', body: { name: 'E2E DENY App', contextRoot: '/deny-api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: siteId, virtualHostIds: [vhId] } }).resourceId)

    Given path '/applications/' + denyAppId
    And header Authorization = auth
    And request { name: 'E2E DENY App', contextRoot: '/deny-api', defaultAuthType: 'API', spaSupportEnabled: false, applicationType: 'API', destination: 'Site', siteId: '#(siteId)', virtualHostIds: ['#(vhId)'], enabled: true }
    When method PUT
    Then status 200

    Given path '/applications/' + denyAppId + '/resources'
    And header Authorization = auth
    When method GET
    Then status 200
    * def denyRootRes = karate.filter(response.items, function(r){ return r.rootResource == true })
    * def denyRootId = denyRootRes[0].id

    Given path '/applications/' + denyAppId + '/resources/' + denyRootId
    And header Authorization = auth
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

    * def oidcSvcId = findId('/thirdPartyServices', 'Mock OIDC Server')
    * if (oidcSvcId == null) karate.set('oidcSvcId', karate.call('classpath:e2e/helpers/create-if-absent.feature', { path: '/thirdPartyServices', body: { name: 'Mock OIDC Server', targets: [oidcContainer + ':8080'], secure: false, maxConnections: 5, availabilityProfileId: 1, trustedCertificateGroupId: 0 } }).resourceId)
    * if (oidcSvcId == null) phase8Skip = true
    * if (phase8Skip) karate.log('WARN: Third-Party Service unavailable — skipping Phase 8')

    # ATV
    * def atvId = null
    * if (!phase8Skip) atvId = findId('/accessTokenValidators', 'Mock OIDC Validator')
    * if (!phase8Skip && atvId == null) karate.call('classpath:e2e/setup/create-atv.feature', { oidcSvcId: oidcSvcId, oidcContainer: oidcContainer })

    # Session App
    * def sessionAppId = null
    * if (!phase8Skip) sessionAppId = findId('/applications', 'E2E Session App')
    * if (!phase8Skip && sessionAppId == null) karate.call('classpath:e2e/setup/create-session-app.feature', { siteId: siteId, vhId: vhId, atvId: atvId, ruleId: ruleId })

    # -----------------------------------------------------------------------
    # Phase 8b: Web Session / OIDC
    # -----------------------------------------------------------------------
    * def phase8bSkip = phase8Skip
    * def webSessionId = null
    * def webAppId = null
    * if (!phase8bSkip) karate.call('classpath:e2e/setup/create-web-session.feature', { siteId: siteId, vhId: vhId, ruleId: ruleId, oidcContainer: oidcContainer })
