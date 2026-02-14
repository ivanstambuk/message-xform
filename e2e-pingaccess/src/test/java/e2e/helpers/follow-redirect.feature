@ignore
Feature: Follow redirect
  # Helper: follows a single redirect Location header.

  Scenario:
    * def targetUrl = location.replace('https://localhost:3000', 'https://localhost:' + paEnginePort)
    Given url targetUrl
    And header Host = paEngineHost
    When method GET
