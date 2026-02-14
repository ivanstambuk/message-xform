    @ignore
Feature: Create PA resource if absent
    # Creates a PA resource via POST. Returns the created resource's ID.
    # Args: path (e.g. '/sites'), body (JSON object)
    # Returns: resourceId (number)

  Scenario:
    Given url paAdminUrl
    And path path
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And configure headers = paAdminHeaders
    And configure ssl = true
    And request body
    When method POST
    Then status 200
    * def resourceId = response.id
