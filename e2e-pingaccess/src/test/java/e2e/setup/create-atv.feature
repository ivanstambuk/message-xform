@ignore
Feature: Create Access Token Validator
  # Called from pa-provision.feature when OAuth is configured.
  # Requires: oidcSvcId, oidcContainer

  Background:
    * url paAdminUrl
    * configure headers = paAdminHeaders
    * configure ssl = true

  Scenario: Create JWKS-based Access Token Validator
    Given path '/accessTokenValidators'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request
      """
      {
        "className": "com.pingidentity.pa.accesstokenvalidators.JwksEndpoint",
        "name": "Mock OIDC Validator",
        "configuration": {
          "path": "/default/jwks",
          "subjectAttributeName": "sub",
          "issuer": "#('https://' + oidcContainer + ':8443/default')",
          "audience": null,
          "thirdPartyService": "#('' + oidcSvcId)"
        }
      }
      """
    When method POST
    Then status 200
    * def atvId = response.id
    * karate.log('Access Token Validator created (id=' + atvId + ')')
