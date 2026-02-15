    @ignore
Feature: Concurrent request helper
    # Helper feature called by hot-reload.feature to make a single
    # HTTPS request to the PA engine.  Returns the response status
    # and body for assertion by the caller.

  Scenario: Fire a single request
    Given url 'https://localhost:' + requestPort
    Given path requestPath
    And header Host = requestHost
    And header Content-Type = 'application/json'
    And configure ssl = true
    And request requestBody
    When method POST
    * def status = responseStatus
    * def body = response
