@ignore
Feature: POST OIDC login form
  # Helper for interactive OIDC mode — posts username to extract callback URL.

  Scenario:
    Given url formUrl
    And form field username = 'e2e-user'
    When method POST
    Then status 302
    * def callbackUrl = responseHeaders['Location'][0]
