# Feature 002 — Scenario Coverage Matrix

| Field | Value |
|-------|-------|
| Status | Complete |
| Last updated | 2026-02-14 |
| Total scenarios | 36 |
| Unit-covered | 36/36 (100%) |
| E2E-covered | 22/36 (61%) |
| E2E backlogged | 9 (with rationale) |
| E2E not feasible | 5 (with rationale) |

---

## Coverage Matrix

| Scenario | Name | Unit Test Evidence | E2E |
|----------|------|--------------------|:---:|
| S-002-01 | Request Body Transform | `TransformFlowTest.successAppliesChangesAndContinues`, `PingAccessAdapterRequestTest.validJsonBody`, `ApplyChangesTest.requestBodyApplied*` | ✅ |
| S-002-02 | Response Body Transform | `TransformFlowTest.responseSuccessAppliesChanges`, `PingAccessAdapterResponseTest.validJsonResponseBody`, `ApplyChangesTest.responseBody*` | ✅ |
| S-002-03 | Bidirectional Transform | `TransformFlowTest.successAppliesChangesAndContinues` + `TransformFlowTest.responseSuccessAppliesChanges` (combined) | ✅ |
| S-002-04 | Header Transform | `ApplyChangesTest.requestHeadersApplied*`, `ApplyChangesTest.responseHeadersApplied*`, `PingAccessAdapterRequestTest.singleValueHeaders`, `PingAccessAdapterRequestTest.multiValueHeaders`, `PingAccessAdapterRequestTest.mixedCaseHeadersNormalizedToLowercase` | ✅ |
| S-002-05 | Status Code Transform | `PingAccessAdapterResponseTest.statusCodeIsMapped`, `ApplyChangesTest.responseStatusApplied*` | ✅ |
| S-002-06 | URL Rewrite | `PingAccessAdapterRequestTest.uriWithQueryString`, `PingAccessAdapterRequestTest.uriWithoutQueryString`, `PingAccessAdapterRequestTest.rootUri`, `ApplyChangesTest.request*Path*` | ✅ |
| S-002-07 | Empty Body | `PingAccessAdapterRequestTest.emptyBodyContent`, `PingAccessAdapterRequestTest.nullBodyContent`, `PingAccessAdapterResponseTest.emptyResponseBody` | ✅ |
| S-002-08 | Non-JSON Body | `PingAccessAdapterRequestTest.malformedJsonBody`, `TransformFlowTest.bodyParseFailedSkipsBodyTransformButContinues`, `PingAccessAdapterRequestTest.bodyParseFailedResetsPerCall` | ✅ |
| S-002-09 | Profile Matching | `RuleLifecycleTest.configureLoadsSpecFromDir` (with profile config), `SecurityPathTest.validProfilesDirIsAccepted` | ✅ |
| S-002-10 | No Matching Spec | `TransformFlowTest.passthroughSkipsApplyAndContinues`, `TransformFlowTest.responsePassthroughSkipsApply` | ✅ |
| S-002-11 | Error Mode PASS_THROUGH | `TransformFlowTest.requestErrorWithPassThroughContinues`, `TransformFlowTest.responseErrorWithPassThroughPreservesResponse` | ✅ |
| S-002-12 | Error Mode DENY | `TransformFlowTest.requestErrorWithDenyReturnsOutcomeReturn`, `TransformFlowTest.requestErrorWithDenySetsResponse`, `TransformFlowTest.requestErrorWithDenySetsTransformDeniedProperty`, `TransformFlowTest.responseErrorWithDenyRewritesInPlace` | ✅ |
| S-002-13 | Session Context in JSLT | `IdentityMappingTest.*` (14 tests), `ContextMappingTest.*` (14 tests) | 🔶 |
| S-002-14 | No Identity (Unauthenticated) | `PingAccessAdapterRequestTest.nullIdentityProducesEmptySession`, `IdentityMappingTest.nullIdentityProducesEmptySession` | ✅¹ |
| S-002-15 | Multiple Specs Loaded | `RuleLifecycleTest.configureLoadsSpecFromDir` (loads multiple YAML files) | ✅ |
| S-002-16 | Large Body (64 KB) | `PingAccessAdapterPerformanceTest.requestAdapterOverheadUnder10ms`, `PingAccessAdapterPerformanceTest.responseAdapterOverheadUnder10ms` | ❌² |
| S-002-17 | Plugin Configuration via Admin UI | `MessageTransformConfigTest.*` (21 tests: defaults, setters, validation) | ✅ |
| S-002-18 | Invalid Spec Directory | `SecurityPathTest.nonExistentDirThrowsValidationException`, `SecurityPathTest.regularFileRejectedAsSpecsDir`, `SecurityPathTest.traversalPathIsNormalizedAndRejected`, `SecurityPathTest.emptySpecsDirThrowsValidationException`, `RuleLifecycleTest.configureWithInvalidSpecsDirThrowsValidationException` | ❌² |
| S-002-19 | Plugin SPI Registration | `SpiRegistrationTest.serviceFileExistsOnClasspath`, `SpiRegistrationTest.serviceFileContainsFqcn`, `SpiRegistrationTest.serviceFactoryRecognizesImplName`, `SpiRegistrationTest.pingAccessAdapterIsNotRegisteredAsPlugin` | ✅ |
| S-002-20 | Thread Safety | `ConcurrentTest.concurrentRequestsDoNotCorruptState`, `ConcurrentTest.concurrentResponsesDoNotCorruptState`, `ConcurrentTest.interleavedRequestsAndResponsesDoNotCorruptState` | ❌² |
| S-002-21 | ExchangeProperty Metadata | `ExchangePropertyTest.*` (12 tests: property create, get, set, namespace, type safety) | ❌² |
| S-002-22 | Cookie Access in JSLT | `ContextMappingTest.cookiesMapped*` | ✅ |
| S-002-23 | Query Param Access in JSLT | `ContextMappingTest.queryParamsMapped*`, `PingAccessAdapterRequestTest.uriWithQueryString` | ✅ |
| S-002-24 | Shadow JAR Correctness | `ShadowJarTest.*` (12 tests: includes adapter/core/SPI, excludes Jackson/SLF4J/Jakarta/SDK, size < 5 MB) | ✅ |
| S-002-25 | OAuth Context in JSLT | `IdentityMappingTest.oauthToken*`, `IdentityMappingTest.clientId*` | 🔶 |
| S-002-26 | Session State in JSLT | `IdentityMappingTest.sessionState*`, `ContextMappingTest.sessionContext*` | 🔶 |
| S-002-27 | Prior Rule URI Rewrite | `PingAccessAdapterRequestTest.uriWithQueryString` (reads exchange URI after prior rules) | 🔶 |
| S-002-28 | DENY + handleResponse Interaction | `TransformFlowTest.denyGuardSkipsResponseProcessing`, `TransformFlowTest.noDenyAllowsNormalResponseProcessing`, `TransformFlowTest.wrapResponseBodyParseFailedDenySkipsBody` | ✅³ |
| S-002-29 | Spec Hot-Reload (Success) | `HotReloadTest.reloadPicksUpNewSpec`, `HotReloadTest.schedulerStartsOnPositiveInterval` | 🔶 |
| S-002-30 | Spec Hot-Reload (Failure) | `HotReloadTest.reloadFailureRetainsPreviousRegistry`, `HotReloadTest.reloadWithMissingProfileLogsWarning` | 🔶 |
| S-002-31 | Concurrent Reload During Active Transform | `ConcurrentTest.interleavedRequestsAndResponsesDoNotCorruptState` (demonstrates no corruption under concurrent load) | 🔶 |
| S-002-32 | Non-JSON Response Body | `PingAccessAdapterResponseTest.malformedJsonResponse`, `PingAccessAdapterResponseTest.gzipBodyFailsParseGracefully`, `PingAccessAdapterResponseTest.deflateBodyFailsParseGracefully` | ✅ |
| S-002-33 | JMX Metrics Opt-In | `JmxIntegrationTest.enableJmxMetricsRegistersMBean`, `JmxIntegrationTest.metricsAccessibleViaJmx`, `JmxIntegrationTest.activeSpecCountReflectedInMBean`, `JmxIntegrationTest.resetMetricsInvocableViaJmx` | 🔶 |
| S-002-34 | JMX Metrics Disabled (Default) | `JmxIntegrationTest.disabledJmxDoesNotRegisterMBean`, `JmxIntegrationTest.defaultConfigDoesNotRegisterMBean`, `JmxIntegrationTest.shutdownWithNoMBeanIsNoOp` | 🔶 |
| S-002-35 | PA-Specific Non-Standard Status Codes | `PingAccessAdapterResponseTest.statusCodeIsMapped` (maps HttpStatus enum values including non-standard) | ✅ |
| S-002-36 | Runtime Version Mismatch Warning | `VersionGuardTest.checkDoesNotThrowOnMatch`, `VersionGuardTest.checkDoesNotFailFast`, `VersionGuardTest.checkIsIdempotent`, `VersionGuardTest.propertiesFileExistsOnClasspath`, `VersionGuardTest.compiledJacksonVersionIsValidSemver` | ❌² |

### Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Validated by E2E test |
| ✅¹ | Partial E2E coverage (unauth path only; full session requires IdP) |
| ✅³ | Best-effort E2E (guard check; either outcome valid) |
| 🔶 | E2E backlogged — requires infrastructure not yet available (see `e2e-results.md` gap analysis) |
| ❌² | E2E not feasible — measurement/API not accessible via HTTP (unit-only with rationale, see `e2e-results.md`) |
