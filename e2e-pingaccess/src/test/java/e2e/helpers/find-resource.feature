    @ignore
Feature: Find a PA resource by name
    # Looks up a PA resource collection and returns the ID of the item matching
    # the given name, or null if not found.
    # Args: collectionPath (e.g. '/sites'), resourceName (e.g. 'Echo Backend')
    # Returns: resourceId (number or null)

  Scenario:
    Given url paAdminUrl
    And path collectionPath
    And header Authorization = call read('classpath:e2e/helpers/basic-auth.js')
    And configure headers = paAdminHeaders
    And configure ssl = true
    When method GET
    * def items = responseStatus == 200 ? response.items : []
    * def matches = karate.filter(items, function(x){ return x.name == resourceName })
    * def resourceId = matches.length > 0 ? matches[0].id : null
