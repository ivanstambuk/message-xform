@ignore
Feature: Configure Common Token Provider, PingFederate Runtime, and OIDC Provider
  # Called from pa-provision.feature for Web Session / OIDC provisioning.
  # Requires: oidcContainer
  #
  # PA needs THREE separate OIDC configurations:
  #   1. /auth/tokenProvider     — admin-level, sets type to "Common" + issuer
  #   2. /pingfederate/runtime   — required for Web Session apps (validates OIDC infra)
  #   3. /oidc/provider          — application-level, for Web Session apps
  #
  # All must point to the same issuer URL. See operations guide §25.
  #
  # After configuration, we force PA to fetch OIDC metadata so the
  # engine caches it before any Web Session request arrives (see §25
  # "Metadata Caching Gotcha").

  Background:
    * url paAdminUrl
    * configure headers = paAdminHeaders
    * configure ssl = true

  Scenario: Configure Common Token Provider + PingFederate Runtime + OIDC Provider
    * def issuerUrl = 'https://' + oidcContainer + ':8443/default'
    * karate.log('OIDC issuer URL: ' + issuerUrl)

    # Step 1: Switch Token Provider to "Common" type
    Given path '/auth/tokenProvider'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def current = response

    * def updated = current
    * set updated.type = 'Common'
    * set updated.issuer = issuerUrl
    * set updated.trustedCertificateGroupId = 2
    * set updated.useProxy = false

    Given path '/auth/tokenProvider'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request updated
    When method PUT
    Then status 200
    * karate.log('Token Provider set to Common (issuer=' + issuerUrl + ')')

    # Step 2: Configure PingFederate Runtime
    # This is required before PA allows creating Web Session apps.
    # PA validates the OIDC metadata has 'ping_end_session_endpoint'.
    Given path '/pingfederate/runtime'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def pfRuntime = response

    * set pfRuntime.issuer = issuerUrl
    * set pfRuntime.trustedCertificateGroupId = 2
    * set pfRuntime.skipHostnameVerification = true

    Given path '/pingfederate/runtime'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request pfRuntime
    When method PUT
    Then status 200
    * karate.log('PingFederate Runtime configured (issuer=' + issuerUrl + ')')

    # Step 3: Configure OIDC Provider for Web Session
    Given path '/oidc/provider'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    Then status 200
    * def oidcCurrent = response

    * set oidcCurrent.issuer = issuerUrl
    * set oidcCurrent.trustedCertificateGroupId = 2
    * set oidcCurrent.useProxy = false

    Given path '/oidc/provider'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And request oidcCurrent
    When method PUT
    Then status 200
    * karate.log('OIDC Provider configured (issuer=' + issuerUrl + ')')

    # Step 4: Force PA to fetch and cache OIDC metadata
    * def pause = function(millis){ java.lang.Thread.sleep(millis) }
    * eval pause(3000)

    Given path '/auth/tokenProvider/metadata'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    * def metaStatus = responseStatus
    * karate.log('Token Provider metadata fetch: status=' + metaStatus)

    * if (metaStatus != 200) pause(5000)
    * if (metaStatus != 200) karate.log('WARN: metadata not available yet, retrying...')

    Given path '/auth/tokenProvider/metadata'
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    When method GET
    * karate.log('Token Provider metadata (final): status=' + responseStatus)
    * if (responseStatus == 200) karate.log('OIDC metadata cached successfully')
    * if (responseStatus != 200) karate.log('WARN: OIDC metadata NOT cached — web session tests may fail')
