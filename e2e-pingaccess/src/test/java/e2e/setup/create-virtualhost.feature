@ignore
Feature: Create virtual host
  # Fallback: creates localhost:3000 virtual host if PA default is missing

  Background:
    * url paAdminUrl
    * configure headers = paAdminHeaders
    * configure ssl = true

  Scenario: Create localhost:3000 virtual host
    Given path '/virtualhosts'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request { host: 'localhost', port: 3000, agentResourceCacheTTL: 0, keyPairId: 0, trustedCertificateGroupId: 0 }
    When method POST
    Then status 200
    * def vhId = response.id
    * karate.log('Virtual host created (id=' + vhId + ')')
