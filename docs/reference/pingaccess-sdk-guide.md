# PingAccess SDK Implementation Guide

> Single-source reference for implementing PingAccess 9.0.1 SDK plugins.
> Sourced from the official SDK Javadoc, sample rules, and the PingAccess 9.0.x
> documentation chapter "PingAccess Add-on SDK for Java".
> This document is optimized for LLM context — each section is self-contained.
> **Goal:** This guide should be sufficient without consulting the Javadoc directly.
> **Completeness:** Incorporates all content from the official PingAccess 9.0.x
> SDK documentation chapter. No external documentation dependency required.

| Field | Value |
|-------|-------|
| SDK Version | `com.pingidentity.pingaccess:pingaccess-sdk:9.0.1.0` |
| Java | 17 or 21 (Amazon Corretto, OpenJDK, Oracle JDK — 64-bit) |
| Source | Official SDK Javadoc (`binaries/pingaccess/dist/pingaccess-9.0.1/sdk/apidocs/`) + sample rules + official docs chapter |
| Related spec | [`docs/architecture/features/002/spec.md`](../architecture/features/002/spec.md) |

---

## Topic Index

| # | Section | One-liner | Tags |
|---|---------|-----------|------|
| | **Part I — Getting Started & Foundation** | | |
| 1 | [Quick Start: Create & Deploy a Plugin](#1-quick-start-create--deploy-a-plugin) | End-to-end 7-step plugin creation and deployment | `quickstart`, `create`, `deploy`, `Maven` |
| 2 | [SDK Layout, Build & Prerequisites](#2-sdk-layout-build--prerequisites) | SDK directory structure, prerequisites, Gradle adaptation | `SDK`, `layout`, `Maven`, `Gradle`, `prerequisites` |
| 3 | [Core Plugin SPI & Lifecycle](#3-core-plugin-spi--lifecycle) | Interceptor hierarchy, 7-step lifecycle, injection, @Rule | `SPI`, `interceptor`, `lifecycle`, `injection`, `@Rule` |
| 4 | [Plugin Configuration & Admin UI](#4-plugin-configuration--admin-ui) | @UIElement, ConfigurationBuilder, Bean Validation, dynamic dropdowns | `configuration`, `UIElement`, `admin`, `validation` |
| | **Part II — HTTP Model & Exchange** | | |
| 5 | [Exchange & Policy Context](#5-exchange--policy-context) | The request/response envelope, ExchangeProperty, PolicyConfiguration | `exchange`, `ExchangeProperty`, `PolicyConfiguration` |
| 6 | [HTTP Messages: Request, Response & Body](#6-http-messages-request-response--body) | Message interface, Request/Response, Body state model | `message`, `request`, `response`, `body`, `streaming` |
| 7 | [Headers](#7-headers) | Generic, typed, and CORS header accessors, cookies, HeaderField | `headers`, `CORS`, `cookies`, `content-type`, `HeaderField` |
| 8 | [Identity, Session & OAuth Context](#8-identity-session--oauth-context) | Identity, SessionStateSupport, OAuthTokenMetadata, OAuth constants, $session merge | `identity`, `session`, `OAuth`, `OIDC`, `OAuthConstants` |
| | **Part III — Response Building & Error Handling** | | |
| 9 | [ResponseBuilder & DENY Mode](#9-responsebuilder--deny-mode) | Building responses, DENY mode in request/response phases | `ResponseBuilder`, `DENY`, `Outcome`, `response` |
| 10 | [Error Handling & Error Callbacks](#10-error-handling--error-callbacks) | AccessException, ErrorHandlingCallback, ErrorInfo, localization | `AccessException`, `ErrorHandlingCallback`, `ErrorInfo` |
| | **Part IV — Cross-Cutting Concerns** | | |
| 11 | [Thread Safety & Concurrency](#11-thread-safety--concurrency) | Init-time vs per-request state, concurrency model, request immutability | `thread`, `concurrency`, `mutable`, `immutability` |
| 12 | [Deployment & Classloading](#12-deployment--classloading) | Shadow JAR, PA-provided deps, classloader model, SPI registration | `shadow`, `JAR`, `classloader`, `SPI`, `ADR-0031` |
| 13 | [Testing Patterns](#13-testing-patterns) | Mockito mock chains, config validation, dependency alignment | `test`, `Mockito`, `mock`, `Validator`, `JUnit` |
| 14 | [JMX Monitoring & Custom MBeans](#14-jmx-monitoring--custom-mbeans) | PA's JMX infrastructure, plugin MBean registration pattern | `JMX`, `MBean`, `monitoring`, `metrics` |
| 15 | [Logging](#15-logging) | SLF4J usage, audit logging, PA log integration | `logging`, `SLF4J`, `audit` |
| | **Part V — Extension Points & Vendor Patterns** | | |
| 16 | [Additional Extension SPIs](#16-additional-extension-spis) | IdentityMapping, SiteAuthenticator, LoadBalancing, LocaleOverride, MasterKeyEncryptor | `extension`, `identity`, `site-authenticator`, `load-balancing` |
| 17 | [Vendor-Built Plugin Analysis](#17-vendor-built-plugin-analysis) | Patterns from PA engine's built-in plugins, internal-only APIs | `vendor`, `internal`, `engine`, `built-in` |
| | **Part VI — Reference** | | |
| 18 | [Supporting Types & Enumerations](#18-supporting-types--enumerations) | Outcome, HttpStatus, Method, MediaType, SslData, TargetHost, SetCookie | `Outcome`, `HttpStatus`, `Method`, `SslData`, `SetCookie` |
| 19 | [Service Factories & Utilities](#19-service-factories--utilities) | ServiceFactory, BodyFactory, HeadersFactory, HttpClient, TemplateRenderer | `ServiceFactory`, `BodyFactory`, `HttpClient`, `TemplateRenderer` |
| 20 | [Complete SPI Inventory & Sources](#20-complete-spi-inventory--sources) | Extension point matrix, SPI registration, Java compat, artefact locations | `SPI`, `matrix`, `services`, `artefacts` |

---

# Part I — Getting Started & Foundation

## 1. Quick Start: Create & Deploy a Plugin

> **Tags:** `quickstart`, `create`, `deploy`, `Maven`

### Prerequisites

- **Java SDK** — 17 or 21 (Amazon Corretto, OpenJDK, Oracle JDK — 64-bit)
- **Apache Maven** (for official samples) or **Gradle** (for custom projects)
- PingAccess 9.0.x extracted

### 7-Step Creation Procedure (Official)

1. **Create a new, empty Maven project.** The root directory is referred to
   as `<PLUGIN_HOME>`.

2. **Copy the `pom.xml`** from the appropriate SDK sample in
   `<PA_HOME>/sdk/samples/`. For example, to create a rule, copy from
   `<PA_HOME>/sdk/samples/Rules/`.

3. **Modify the `pom.xml`:** Update `groupId`, `artifactId`, `name`, and
   `version` as appropriate for your plugin.

4. **Create a Java class** that implements the plugin SPI from the SDK, in
   `<PLUGIN_HOME>/src/main/java/com/yourpackagename/`. For each SPI, base
   classes are provided that simplify the implementation:

   | Plugin Type | SPI Interface | Base Class |
   |-------------|--------------|------------|
   | Sync rule | `RuleInterceptor` | `RuleInterceptorBase` |
   | Async rule | `AsyncRuleInterceptor` | `AsyncRuleInterceptorBase` |
   | Site authenticator | `SiteAuthenticatorInterceptor` | `SiteAuthenticatorInterceptorBase` |
   | Identity mapping | `IdentityMappingPlugin` | `IdentityMappingPluginBase` |
   | Load balancing | `LoadBalancingPlugin` | `LoadBalancingPluginBase` |

5. **Create a provider-configuration file** (SPI services file) for the plugin.
   The file name is the FQCN of the SPI interface, and its contents are the FQCN
   of your implementation class:
   ```
   <PLUGIN_HOME>/src/main/resources/META-INF/services/com.pingidentity.pa.sdk.policy.RuleInterceptor
   ```
   **Contents:** `com.yourpackagename.YourCustomRule`

6. **Build the Maven project** to obtain a JAR:
   ```bash
   cd <PLUGIN_HOME>
   mvn install
   ```

7. **Deploy the JAR** to `<PA_HOME>/deploy/` and **restart PingAccess**.

> ⚠️ **Mandatory Restart:** You **must** restart PingAccess after deploying any
> custom plugin JAR. There is no hot-reload mechanism.

### Installing the SDK Samples

```bash
cd <PA_HOME>/sdk/samples/Rules
mvn install
# Builds, tests, and copies the JAR to <PA_HOME>/lib
```

---

## 2. SDK Layout, Build & Prerequisites

> **Tags:** `SDK`, `layout`, `Maven`, `Gradle`, `prerequisites`

### SDK Directory Structure

The PingAccess SDK is located at `<PA_HOME>/sdk` alongside the `<PA_HOME>/deploy`
directory:

| Path | Description |
|------|-------------|
| `<PA_HOME>/deploy/` | Deployment directory for custom plugin JARs (auto-migrated on upgrade) |
| `<PA_HOME>/sdk/README.md` | Overview of the SDK contents |
| `<PA_HOME>/sdk/apidocs/` | SDK Javadocs (open `index.html` to browse) |
| `<PA_HOME>/sdk/samples/Rules/` | Maven project with example rule plugin implementations |
| `<PA_HOME>/sdk/samples/SiteAuthenticator/` | Maven project with example site authenticator plugins |
| `<PA_HOME>/sdk/samples/IdentityMappings/` | Maven project with example identity mapping plugins |
| `<PA_HOME>/sdk/samples/LoadBalancingStrategies/` | Maven project with example load balancing plugins |
| `<PA_HOME>/sdk/samples/LocaleOverrideService/` | Maven project with example locale override plugin |

Each `samples/` subdirectory contains its own `README.md` with sample-specific details.

### Ping Identity Maven Repository

The SDK samples reference Ping Identity's public Maven repository:

```
http://maven.pingidentity.com/release
```

> **Note:** This Maven repository **cannot be accessed through a browser** — it
> is designed solely for backend use (Maven/Gradle dependency resolution).

**Maven `pom.xml` configuration:**

```xml
<repositories>
    <repository>
        <releases>
            <enabled>true</enabled>
            <updatePolicy>always</updatePolicy>
            <checksumPolicy>warn</checksumPolicy>
        </releases>
        <id>PingIdentityMaven</id>
        <name>PingIdentity Release</name>
        <url>http://maven.pingidentity.com/release/</url>
        <layout>default</layout>
    </repository>
</repositories>
```

**Offline / no-internet fallback (Maven):**

If Internet access is unavailable, update the SDK dependency in `pom.xml` to
point to the local PingAccess installation using `system` scope. Replace
`<PA_HOME>` with the actual path:

```xml
<dependency>
    <groupId>com.pingidentity.pingaccess</groupId>
    <artifactId>pingaccess-sdk</artifactId>
    <version>9.0.1.0</version>
    <scope>system</scope>
    <systemPath><PA_HOME>/lib/pingaccess-sdk-9.0.1.0.jar</systemPath>
</dependency>
```

> **Version note:** The `systemPath` versions above are from the official docs
> (PA 4.x era). For PA 9.0.1.0, the actual JAR filenames in `<PA_HOME>/lib/`
> may differ. Always verify the exact filenames.

### Gradle Adaptation

For projects using **Gradle** instead of Maven:

| Maven Step | Gradle Equivalent |
|-----------|-------------------|
| `pom.xml` with SDK dependency | `build.gradle.kts` with `compileOnly` SDK dependency |
| `mvn install` | `./gradlew shadowJar` (produces shadow JAR) |
| Copy JAR to `deploy/` | CI/CD copies shadow JAR to `<PA_HOME>/deploy/` |
| Ping Maven repo in `<repositories>` | `maven { url = "http://maven.pingidentity.com/release" }` in `repositories {}` |
| System-scope local JAR | `compileOnly(files("<PA_HOME>/lib/pingaccess-sdk-9.0.1.0.jar"))` |

---

## 3. Core Plugin SPI & Lifecycle

> **Tags:** `SPI`, `interceptor`, `lifecycle`, `injection`, `@Rule`, `AsyncRuleInterceptorBase`

### Interceptor Hierarchy

```java
// com.pingidentity.pa.sdk.interceptor.Interceptor
// An Interceptor is the "receiving object" for an Exchange, following the
// Chain-of-responsibility pattern. This is a tagging interface.
//
// Sub-interfaces:
//   RequestInterceptor  — adds handleRequest(Exchange)
//   ResponseInterceptor — adds handleResponse(Exchange)
//   RuleInterceptor<T>  — extends both + ConfigurablePlugin + DescribesUIConfigurable
//   AsyncRuleInterceptor<T> — async equivalent of RuleInterceptor
```

### Interface: `AsyncRuleInterceptor<T>`

```java
// com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor<T extends PluginConfiguration>
//   extends Interceptor, DescribesUIConfigurable, ConfigurablePlugin<T>
CompletionStage<Outcome> handleRequest(Exchange exchange);
CompletionStage<Void>    handleResponse(Exchange exchange);
ErrorHandlingCallback    getErrorHandlingCallback();
```

**`handleRequest` contract:** Returns a `CompletionStage` representing the
policy decision. An **exceptionally completed** `CompletionStage` will terminate
processing of the current Exchange and invoke PingAccess' generic error handling
mechanism. Implementations should NOT return an exceptionally completed
`CompletionStage` to indicate the Rule denied access. Instead, **translate
expected denial errors to `Outcome.RETURN`**.

**`handleResponse` contract:** Invoked to allow the interceptor to handle the
response attached to the Exchange. Called on both CONTINUE and RETURN outcomes
(in reverse chain order for RETURN).

> ⚠️ **Request is read-only during response phase.** Any modifications to
> `exchange.getRequest()` or its headers inside `handleResponse()` are
> **silently ignored** by PingAccess. Only `exchange.getResponse()` may be
> modified.

```java
// ❌ WRONG — silently ignored
@Override
public CompletionStage<Void> handleResponse(Exchange exchange) {
    exchange.getRequest().getHeaders().add("X-Debug", "true");  // IGNORED
    return CompletableFuture.completedFuture(null);
}

// ✅ CORRECT — modify the response only
@Override
public CompletionStage<Void> handleResponse(Exchange exchange) {
    exchange.getResponse().getHeaders().add("X-Debug", "true");  // OK
    return CompletableFuture.completedFuture(null);
}
```

> **When to use Async vs Sync:** Per the SDK Javadoc, `AsyncRuleInterceptor`
> should only be used when the logic of the Rule requires using a `HttpClient`.
> If no `HttpClient` is needed, use `RuleInterceptor` instead.

### Base class: `AsyncRuleInterceptorBase<T>`

```java
// com.pingidentity.pa.sdk.policy.AsyncRuleInterceptorBase<T extends PluginConfiguration>
//   implements AsyncRuleInterceptor<T>
// Pre-wired fields (set via setter injection):
//   - HttpClient httpClient (via setHttpClient)
//   - TemplateRenderer templateRenderer (via setTemplateRenderer)
//   - T configuration (via configure(T))
void configure(T config) throws ValidationException;
T getConfiguration();
CompletionStage<Void> handleResponse(Exchange exchange);  // default no-op
HttpClient getHttpClient();
TemplateRenderer getTemplateRenderer();
```

### `DescribesUIConfigurable` interface

```java
// com.pingidentity.pa.sdk.plugins.DescribesUIConfigurable
List<ConfigurationField> getConfigurationFields();
// Returns an ordered set of ConfigurationFields (most easily built with ConfigurationBuilder)
```

### `ConfigurablePlugin<T>` interface

```java
// com.pingidentity.pa.sdk.plugins.ConfigurablePlugin<T extends PluginConfiguration>
void configure(T configuration) throws ValidationException;
T getConfiguration();
```

### Lifecycle (Official 7-Step Sequence)

The official PingAccess documentation defines a precise 7-step initialization
sequence before a plugin is available to process requests. Using `RuleInterceptor`
as the example (identical for other plugin types):

1. **Annotation interrogation:** The `@Rule` annotation on the `RuleInterceptor`
   implementation class is read to determine which `PluginConfiguration` class
   will be instantiated.
2. **Spring initialization:** Both the `RuleInterceptor` and `PluginConfiguration`
   beans are provided to Spring for:
   - **Autowiring** (dependency injection via `@Inject`)
   - **`@PostConstruct` initialization**
   
   > **Important:** The order in which the RuleInterceptor and PluginConfiguration
   > are processed by Spring is **not defined**. Do not assume one is initialized
   > before the other.
3. **Name assignment:** `PluginConfiguration.setName(String)` is called with the
   administrator-defined name.
4. **JSON → Configuration mapping:** PingAccess maps the incoming JSON
   configuration to the `PluginConfiguration` instance (via Jackson deserialization).
   
   > ⚠️ **Critical:** The JSON plugin configuration **must contain a JSON member
   > for each field**, regardless of implied value. Failure to include a field in
   > the JSON — even if the field has a default value in Java — can lead to errors.
5. **Validation:** `Validator.validate(Object, Class[])` is invoked on the
   `PluginConfiguration`. Since PA 5.0+, Bean Validation runs **before**
   `configure()` is called — constraint annotations on PluginConfiguration
   fields are guaranteed to be enforced before the plugin receives them.
6. **`configure()` call:** `ConfigurablePlugin.configure(PluginConfiguration)` is
   called, giving the plugin a chance to post-process configuration. At this
   point, all config fields have already passed Bean Validation.
7. **Available:** The instance is made available to service end-user requests via
   `handleRequest(Exchange)` and `handleResponse(Exchange)`.

**Teardown:** No explicit destroy lifecycle. Plugins are garbage-collected on PA
restart. Clean up resources (if any) using `@PreDestroy` or finalizers.

### Injection

Before plugins are put into use, rules, SiteAuthenticators, and their
`PluginConfiguration` instances are passed through Spring's Autowiring and
initialization (see Lifecycle step 2 above).

> ⚠️ **Future-proofing:** To protect your code against changes in PingAccess
> internals, **do not use Spring as a direct dependency**. Use the annotation
> `jakarta.inject.Inject` for any injection instead of Spring-specific annotations
> like `@Autowired`.

**Injectable classes (definitive, closed list):**

| # | Class | Purpose |
|---|-------|---------|
| 1 | `com.pingidentity.pa.sdk.util.TemplateRenderer` | Renders HTML error/response templates |
| 2 | `com.pingidentity.pa.sdk.accessor.tps.ThirdPartyServiceModel` | Handle to a third-party service (via `@OidcProvider` or `@Inject`) |
| 3 | `com.pingidentity.pa.sdk.http.client.HttpClient` | Async HTTP client for third-party service calls |

These are the **only** classes available for injection. No other PA-internal
classes can be injected into plugins.

**Injection pattern (from official docs):**

```java
public class MyPlugin extends AsyncRuleInterceptorBase<MyConfig> {
    private HttpClient httpClient;

    @Inject
    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
    // httpClient is set during Lifecycle step 2 (Spring initialization)
}
```

> **Note:** `AsyncRuleInterceptorBase` already provides pre-wired setter
> injection for `HttpClient` and `TemplateRenderer` via `setHttpClient()` and
> `setTemplateRenderer()`. You only need explicit `@Inject` if you're
> implementing the raw SPI interface without using a base class.

### @Rule Annotation

```java
// com.pingidentity.pa.sdk.policy.Rule (annotation)
@Rule(
    category = RuleInterceptorCategory.Processing,
    destination = { RuleInterceptorSupportedDestination.Site },
    label = "Message Transform",
    type = "MessageTransform",   // unique across all plugins
    expectedConfiguration = MessageTransformConfig.class
)
```

> **`destination` attribute:** Controls where the rule can be applied.

### Agent vs Site Rule Differences

Rules can be applied to applications associated with **agents** or **sites**.
Some SDK features are not available to rules applied to agents. Rules that use
site-only features should be explicitly marked.

**Rules applied to Agents are limited in the following ways:**

| Limitation | Detail |
|-----------|--------|
| **No `handleResponse`** | The `handleResponse` method is **not called** for agent rules. |
| **No request body** | The request body is **not present** — `exchange.getRequest().getBody()` returns an empty body. |
| **Empty destinations** | `Exchange.getDestinations()` list is empty. Modifying the destination list has **no effect**. |

**Annotation patterns:**

```java
// Site-only rule (use when you need handleResponse + body access)
@Rule(destination = { RuleInterceptorSupportedDestination.Site }, ...)

// Agent-only rule
@Rule(destination = { RuleInterceptorSupportedDestination.Agent }, ...)

// Both (default if destination is omitted)
@Rule(destination = { RuleInterceptorSupportedDestination.Site,
                      RuleInterceptorSupportedDestination.Agent }, ...)
```

### RuleInterceptorCategory (enum)

```java
// Available values:
AccessControl, Processing
```

### RuleInterceptorSupportedDestination (enum)

```java
// Available values:
Site, Agent
```

### Naming note

> Sync base class `RuleInterceptorBase` → method is `getRenderer()`.
> Async base class `AsyncRuleInterceptorBase` → method is `getTemplateRenderer()`.
> Always use `getTemplateRenderer()` for async plugins.

---

## 4. Plugin Configuration & Admin UI

> **Tags:** `configuration`, `UIElement`, `admin`, `validation`, `ConfigurationBuilder`, `ConfigurationType`

### PluginConfiguration (interface)

```java
// com.pingidentity.pa.sdk.policy.PluginConfiguration
// All configuration classes must implement this interface.
String getName();      // the name defined by the PingAccess administrator
void setName(String);  // called by PA during configuration (plugin should not call)
```

### SimplePluginConfiguration (base class)

```java
// com.pingidentity.pa.sdk.policy.SimplePluginConfiguration implements PluginConfiguration
// Provides a no-op implementation of PluginConfiguration.
// Use when your plugin has minimal configuration (just inherits getName/setName).
```

### ConfigurationType Enum

Represents the type of a `ConfigurationField` — each type corresponds to a
specific input or display format in the admin UI.

```java
// com.pingidentity.pa.sdk.ui.ConfigurationType
// All 13 values:
TEXT                // Single-line text input
TEXTAREA            // Multi-line text input
TIME                // Time input
SELECT              // Dropdown selection
GROOVY              // Groovy script editor
CONCEALED           // Password/secret field
LIST                // List-based input
TABLE               // Table with sub-fields
CHECKBOX            // Checkbox toggle
AUTOCOMPLETEOPEN    // Open autocomplete (free-text + suggestions)
AUTOCOMPLETECLOSED  // Closed autocomplete (selection from suggestions only)
COMPOSITE           // Composite field containing multiple subfields
RADIO_BUTTON        // Radio button group
```

### @UIElement Annotation (complete attributes)

```java
// com.pingidentity.pa.sdk.ui.UIElement — applied to fields
@UIElement(
    // Required
    order = 10,                          // field order in UI (0-based)
    type = ConfigurationType.TEXT,       // the ConfigurationType
    label = "Field Label",              // display label

    // Optional
    defaultValue = "",                   // default value shown in admin UI
    advanced = false,                    // if true, shown under "Advanced" toggle
    required = false,                    // if true, field is required
    options = {},                        // array of @Option for SELECT/RADIO_BUTTON
    help = @Help,                        // inline help (content, title, URL)
    modelAccessor = UIElement.NONE.class,// dynamic dropdown via ConfigurationModelAccessor
    subFieldClass = UIElement.NONE.class,// class for TABLE sub-fields
    buttonGroup = "default",             // radio button group name
    deselectable = false,                // allow radio button deselection
    parentField = @ParentField           // parent field for conditional visibility
)
```

### @Help, @Option, @ParentField, @DependentFieldOption Annotations

```java
// com.pingidentity.pa.sdk.ui.Help
@Help(
    content = "Inline help text",
    title = "Help Title",
    url = "https://docs.example.com"
)

// com.pingidentity.pa.sdk.ui.Option
@Option(value = "optionValue", label = "Display Label")
//       ^^^^^ NOT "name" — the attribute is "value"

// com.pingidentity.pa.sdk.ui.ParentField — conditional visibility
@ParentField(
    name = "parentFieldName",            // field name to depend on
    dependentFieldOptions = {            // value-dependent option sets
        @DependentFieldOption(value = "A", options = {@Option(value = "a1", label = "A1")}),
        @DependentFieldOption(value = "B", options = {@Option(value = "b1", label = "B1")})
    }
)
```

### Enum Auto-Discovery for SELECT Fields

If a `SELECT` field's Java type is an `Enum` and no `@Option` annotations are
specified, `ConfigurationBuilder.from()` **auto-generates** `ConfigurationOption`
instances from the enum constants. It uses `Enum.name()` for the option value and
`toString()` for the label.

```java
@UIElement(label = "A SELECT field using an enum", type = SELECT, order = 42)
public EnumField anEnumSelect = null;

public enum EnumField {
    OptionOne("Option One"),
    OptionTwo("Option Two");
    private final String label;
    EnumField(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
// ConfigurationBuilder.from() generates:
//   option(value="OptionOne", label="Option One")
//   option(value="OptionTwo", label="Option Two")
```

### LIST Type — Open vs Closed Variants

```java
// OPEN list — user can type free-text items
@UIElement(label = "An Open List", type = LIST,
           defaultValue = "[\"One\", \"Two\", \"Three\"]", order = 90)
public List<String> anOpenList = null;

// CLOSED list — restricted to predefined options
@UIElement(label = "A Closed List", type = LIST,
           defaultValue = "[\"1\", \"2\", \"3\"]",
           options = {
               @Option(label = "One", value = "1"),
               @Option(label = "Two", value = "2")
           }, order = 100)
public List<String> aClosedList = null;
```

> **Note:** The `defaultValue` for `LIST` fields is a **JSON array string**.

### COMPOSITE Type — Inline Sub-Fields

Similar to `TABLE` but displayed inline (not as a table with rows). Uses a
sub-field class with `@UIElement`-annotated fields:

```java
@UIElement(label = "A Composite Field", type = COMPOSITE, order = 80)
public CompositeRepresentation aComposite = null;

public static class CompositeRepresentation {
    @UIElement(label = "First field", type = TEXT, order = 1)
    public String first;
    @UIElement(label = "Second field", type = TEXT, order = 2)
    public String second;
}
```

### RADIO_BUTTON Mutual-Exclusion Groups

Multiple `RADIO_BUTTON` fields with the same `buttonGroup` form a mutually
exclusive group. Each field is a `Boolean` — only one in the group can be `true`.

```java
@UIElement(label = "Option A", type = RADIO_BUTTON, order = 110,
           buttonGroup = "chooseOneGroup")
@NotNull public Boolean radio1 = false;

@UIElement(label = "Option B", type = RADIO_BUTTON, order = 111,
           buttonGroup = "chooseOneGroup")
@NotNull public Boolean radio2 = false;
// Only one of radio1/radio2 will be true at any time
```

### ConfigurationModelAccessor — Dynamic Dropdowns

For SELECT fields that pull options from PingAccess admin data (certificate
groups, key pairs, third-party services), use a `ConfigurationModelAccessor`:

```java
// Annotation-driven — links to PA's built-in accessors
@UIElement(label = "A Private Key", type = SELECT, order = 1,
           modelAccessor = KeyPairAccessor.class)
public KeyPairModel aPrivateKey = null;

@UIElement(label = "Trusted Certs", type = SELECT, order = 5,
           modelAccessor = TrustedCertificateGroupAccessor.class)
public TrustedCertificateGroupModel trustedCerts = null;

// Programmatic equivalent (ConfigurationBuilder)
new ConfigurationBuilder()
    .privateKeysConfigurationField("aPrivateKey", "A Private Key")
        .required()
    .trustedCertificatesConfigurationField("trustedCerts", "Trusted Certs")
        .required()
    .toConfigurationFields();
```

**Built-in accessors:** `KeyPairAccessor`, `TrustedCertificateGroupAccessor`,
`ThirdPartyServiceAccessor`. Corresponding model types: `KeyPairModel`,
`TrustedCertificateGroupModel`, `ThirdPartyServiceModel` (with subtypes
`OAuthAuthorizationServer` and `OidcProvider` for OAuth AS / OIDC provider refs).

The `ConfigurationModelAccessor<T>` interface has two methods:

```java
Collection<ConfigurationOption> options();  // options for the admin UI dropdown
Optional<T> get(String id);                 // translate field value to model object
```

> **⚠️ Usage constraint:** `ConfigurationModelAccessor` instances must **only**
> be used inside `configure()`, **never** during `handleRequest()`/`handleResponse()`.

### Configuration Field Discovery Patterns

**Pattern 1 — Annotation-driven (recommended):**

```java
public List<ConfigurationField> getConfigurationFields() {
    return ConfigurationBuilder.from(Configuration.class)  // auto-discovers @UIElement
            .addAll(ErrorHandlerUtil.getConfigurationFields())  // appends error handler fields
            .toConfigurationFields();
}
```

**Pattern 2 — Programmatic (ParameterRule sample):**

```java
public List<ConfigurationField> getConfigurationFields() {
    return new ConfigurationBuilder()
        .configurationField("paramType", "Param Type", ConfigurationType.SELECT)
            .option("query", "Query")
            .option("header", "Header")
            .option("cookie", "Cookie")
            .helpContent("This allows the user to choose a source.")
            .helpTitle("Param Type")
            .helpURL("http://en.wikipedia.org/wiki/Query_string")
            .required()
        .configurationField("paramTable", "Parameters", ConfigurationType.TABLE)
            .subFields(
                new ConfigurationBuilder()
                    .configurationField("paramName", "Name", ConfigurationType.TEXT).required()
                    .configurationField("paramValue", "Value", ConfigurationType.TEXT).required()
                    .toConfigurationFields()
            )
        .toConfigurationFields();
}
```

### ConfigurationBuilder Fluent API

```java
// com.pingidentity.pa.sdk.ui.ConfigurationBuilder
// Constructors
ConfigurationBuilder();
ConfigurationBuilder(Set<ConfigurationField> fields);
static ConfigurationBuilder from(Class clazz);   // parse @UIElement annotations

// Adding fields
configurationField(String name, String label, ConfigurationType type);
configurationField(String name, String label, ConfigurationType type, Set<ConfigurationOption> opts);
configurationField(ConfigurationField field);
addAll(Collection<ConfigurationField> fields);

// Field modifiers (operate on last-added field)
required();                              required(boolean);
advanced();                              advanced(boolean);
defaultValue(String);
option(String name, String label);       // add SELECT/RADIO option
options(String... labels);               // convenience for multiple options
deselectable();                          deselectable(boolean);
buttonGroup(String);
dynamicOptions(Class<? extends ConfigurationModelAccessor>);
subFields(Set<ConfigurationField>);      // for TABLE type

// Convenience methods for PingAccess admin model fields:
privateKeysConfigurationField(String name, String label);
trustedCertificatesConfigurationField(String name, String label);

// Help
help();                                  // create empty Help
helpContent(String);
helpTitle(String);
helpURL(String);

// Output
List<ConfigurationField> toConfigurationFields();
clear();
```

> **Warning from Javadoc:** "You must provide a `configurationField` to perform
> operations upon. Ex. Calling `required()` before calling
> `configurationField(...)` will cause an NPE."

### ConfigurationField (runtime model)

```java
// com.pingidentity.pa.sdk.ui.ConfigurationField
// Constructors:
ConfigurationField(String fieldName, String fieldLabel, ConfigurationType fieldType);
ConfigurationField(String fieldName, String fieldLabel, ConfigurationType fieldType,
                   Collection<ConfigurationOption> options);
ConfigurationField(ConfigurationField other);  // COPY constructor

// All fields are mutable (get/set):
String getName();                              void setName(String);
String getLabel();                             void setLabel(String);
ConfigurationType getType();                   void setType(ConfigurationType);
Collection<ConfigurationOption> getOptions();  void setOptions(Collection<ConfigurationOption>);
ConfigurationParentField getParentField();     void setParentField(ConfigurationParentField);
List<ConfigurationField> getFields();          void setFields(List<ConfigurationField>);
ConfigurationField.Help getHelp();             void setHelp(ConfigurationField.Help);
boolean isAdvanced();                          void setAdvanced(boolean);
boolean isRequired();                          void setRequired(boolean);
String getDefaultValue();                      void setDefaultValue(String);
String getButtonGroup();                       void setButtonGroup(String);
boolean isDeselectable();                      void setDeselectable(boolean);
```

### ConfigurationField.Help (inner class)

```java
// com.pingidentity.pa.sdk.ui.ConfigurationField.Help
String getContent();     void setContent(String);
String getTitle();       void setTitle(String);
String getUrl();         void setUrl(String);
```

### ConfigurationOption

```java
// com.pingidentity.pa.sdk.ui.ConfigurationOption
// Constructors:
ConfigurationOption(String value);                       // value-only
ConfigurationOption(String value, String label);         // value + label
ConfigurationOption(Option option);                      // from @Option annotation

String getLabel();     void setLabel(String);
String getValue();     void setValue(String);
```

### DependantFieldAccessor (dynamic dependent options)

For parent-child SELECT relationships where the child options are loaded
dynamically at runtime (not statically via `@DependentFieldOption`):

```java
// com.pingidentity.pa.sdk.accessor.DependantFieldAccessor (interface)
List<ConfigurationDependentFieldOption> dependentOptions();

// com.pingidentity.pa.sdk.accessor.DependantFieldAccessorFactory (interface)
<T extends DependantFieldAccessor> T getDependantFieldAccessor(Class<T> clazz);

// com.pingidentity.pa.sdk.ui.ConfigurationDependentFieldOption
public final String value;
public final List<ConfigurationOption> options;
ConfigurationDependentFieldOption(String value, List<ConfigurationOption> options);

// com.pingidentity.pa.sdk.ui.DynamicConfigurationParentField extends ConfigurationParentField
DynamicConfigurationParentField(String fieldName, Class<? extends DependantFieldAccessor> accessor);
```

### DynamicOptionsConfigurationField

A `ConfigurationField` subclass that resolves its options dynamically at
runtime using a `ConfigurationModelAccessor`:

```java
// com.pingidentity.pa.sdk.ui.DynamicOptionsConfigurationField extends ConfigurationField
DynamicOptionsConfigurationField(ConfigurationField field, Class<? extends ConfigurationModelAccessor> accessor);
// The admin UI calls the accessor to populate the dropdown at render time.
```

### Validation Constraints (JSR-380 / Jakarta Bean Validation)

```java
public class AdvancedConfiguration implements PluginConfiguration {
    @UIElement(order = 10, type = ConfigurationType.TEXT, label = "Port")
    @Min(1) @Max(65535)
    private int port;

    @UIElement(order = 20, type = ConfigurationType.TEXT, label = "Hostname")
    @NotNull
    private String hostname;

    @JsonUnwrapped                 // flattens error handler fields into config JSON
    @Valid                         // enables nested validation
    private ErrorHandlerConfigurationImpl errorHandlerPolicyConfig;
}
```

### ErrorHandlerConfigurationImpl

The SDK provides a ready-made error handler configuration with validation:

```java
// com.pingidentity.pa.sdk.policy.config.ErrorHandlerConfigurationImpl
// implements ErrorHandlerConfiguration
@NotNull @Min(1) @Max(1000) Integer errorResponseCode;
@NotNull @Size(min=1)       String  errorResponseStatusMsg;
@NotNull @Size(min=1)       String  errorResponseTemplateFile;
@NotNull @Size(min=1)       String  errorResponseContentType;

int    getErrorResponseCode();
String getErrorResponseStatusMsg();
String getErrorResponseTemplateFile();
String getErrorResponseContentType();
```

### ErrorHandlerUtil

```java
// com.pingidentity.pa.sdk.policy.config.ErrorHandlerUtil
static List<ConfigurationField> getConfigurationFields();
// Returns ConfigurationFields corresponding to ErrorHandlerConfiguration fields.
// Used with ConfigurationBuilder.addAll() to append standard error handler UI.
```

---

# Part II — HTTP Model & Exchange

## 5. Exchange & Policy Context

> **Tags:** `exchange`, `ExchangeProperty`, `PolicyConfiguration`, `Application`, `Resource`

### Exchange

The `Exchange` is the central context object passed to every interceptor
invocation. It represents a single request/response transaction through
PingAccess.

```java
// com.pingidentity.pa.sdk.interceptor.Exchange (interface, since PA 4.1)
// Primary accessors:
Request            getRequest();
Response           getResponse();                // null during request phase
Identity           getIdentity();
SslData            getSslData();                 // TLS/SNI data (since PA 4.3)
List<TargetHost>   getTargetHosts();             // configured backend targets
List<TargetHost>   getDestinations();             // current destination list (modifiable)
PolicyConfiguration getPolicyConfiguration();    // owning application + resource context
<T> ExchangeProperty<T> getExchangeProperty(ExchangeProperty<T> property);

// Response management:
void setResponse(Response response);             // used in DENY mode during request phase
```

#### Key considerations:

- **`getResponse()` is null during request phase.** The backend has not yet
  responded. Use `ResponseBuilder` to construct a response if denying.
- **`getTargetHosts()`** returns the configured backend hosts as an unmodifiable
  list.
- **`getDestinations()`** is modifiable — allows plugins to alter routing.
  Returns empty list for Agent rules.

### ExchangeProperty<T>

```java
// com.pingidentity.pa.sdk.interceptor.ExchangeProperty<T>
// Typed key for storing/retrieving values on the Exchange.
// Instances are typically defined as static final constants.
static <T> ExchangeProperty<T> create(String name, Class<T> clazz);

// Usage pattern:
static final ExchangeProperty<String> MY_PROP =
    ExchangeProperty.create("my.plugin.property", String.class);

// Set on exchange:
exchange.getExchangeProperty(MY_PROP);  // returns T or null
```

> Useful for context-aware transforms (e.g., different behavior per application).
> Not exposed in v1 `$session` but available for future `$context` enrichment.

### PolicyConfiguration

```java
// com.pingidentity.pa.sdk.policy.PolicyConfiguration (interface)
Application getApplication();
Resource    getResource();
```

### Application

```java
// com.pingidentity.pa.sdk.policy.Application (interface)
int             getId();
String          getName();
String          getDescription();
ApplicationType getApplicationType();        // API or Web
String          getContextRoot();            // e.g., "/myapp"
String          getDefaultAuthType();
String          getVirtualHostIds();         // comma-separated IDs
int             getSiteId();                 // backend site ID
int             getAgentId();
boolean         isCaseSensitivePath();
```

### ApplicationType (enum)

```java
// com.pingidentity.pa.sdk.policy.ApplicationType
API, Web
```

### Resource

```java
// com.pingidentity.pa.sdk.policy.Resource (interface)
int         getId();
String      getName();
boolean     isAnonymous();
PathPattern getDefaultAuthTypeOverride();
PathPattern getPathPattern();
```

### PathPattern

```java
// com.pingidentity.pa.sdk.policy.PathPattern (interface)
String  getPattern();            // the path pattern string
String  getType();               // "WILDCARD" or "REGEX"
```

---

## 6. HTTP Messages: Request, Response & Body

> **Tags:** `message`, `request`, `response`, `body`, `streaming`, `Content-Length`

### Message (parent of Request and Response)

Both `Request` and `Response` extend `Message`, which provides shared header and
body access:

```java
// com.pingidentity.pa.sdk.http.Message
Headers getHeaders();
void    setHeaders(Headers headers);      // replaces headers entirely
Body    getBody();
void    setBody(Body body);               // replaces body entirely
```

> **`setHeaders()` validation:** Throws `IllegalArgumentException` if the
> specified `Headers` object was not created with the provided `HeadersFactory`
> (see `ServiceFactory.headersFactory()`).

### Request

```java
// com.pingidentity.pa.sdk.http.Request extends Message
Method  getMethod();           // GET, POST, PUT, etc.
String  getUri();              // path + query string
void    setUri(String uri);    // rewrite URI (path + query)
Map<String, String[]> getQueryStringParams();  // parsed query parameters
```

### Response

```java
// com.pingidentity.pa.sdk.http.Response extends Message
int     getStatusCode();
void    setStatus(HttpStatus status);      // change status code + reason phrase
```

### Body

The body is a reference to the data sent or received:

```java
// com.pingidentity.pa.sdk.http.Body (interface)
byte[]      getContent();             // read body into byte array
void        setBodyContent(byte[] content);  // replace body (auto-updates Content-Length)
boolean     isRead();                 // true if body has been read from the network
boolean     isInMemory();             // true if body has been fully loaded into memory
InputStream getInputStream();         // streaming access to body
long        getContentLength();       // declared Content-Length (-1 if unknown)
```

### State Model

The Body has a 3-state lifecycle (from the Javadoc scalability warning):

| State | `isRead()` | `isInMemory()` | `getContent()` | Notes |
|-------|-----------|----------------|----------------|-------|
| **Not read** | `false` | `false` | Reads from network → memory | First call transitions to "Read" |
| **Read (in memory)** | `true` | `true` | Returns cached byte[] | Subsequent calls return same bytes |
| **Read (discarded)** | `true` | `false` | **Throws** | Body was read/streamed but not retained |

### ⚠️ Scalability Warning (from Javadoc)

> `getContent()` reads the **entire** body into memory as a byte array. For
> large bodies, this can cause `OutOfMemoryError`. If you only need to inspect
> the body (e.g., check Content-Type), use `getInputStream()` with streaming.
>
> `isRead()` and `isInMemory()` should be used for diagnostic or guard purposes
> (not for controlling read behavior directly).

---

## 7. Headers

> **Tags:** `headers`, `CORS`, `cookies`, `content-type`, `HeaderField`, `HeaderName`

### Generic Header Access

```java
// com.pingidentity.pa.sdk.http.Headers
List<String>        getValues(String name);       // case-insensitive
Optional<String>    getFirstValue(String name);   // first value for header
List<HeaderField>   getFields();                  // all header field entries
Optional<HeaderField> getField(String name);      // first field by name
void add(String name, String value);              // adds a value (does not replace)
void add(HeaderField field);                      // adds a field
void removeFields(String name);                   // removes ALL values for header
void removeField(HeaderField field);              // removes specific field
```

### Typed Header Accessors

```java
// Convenience methods on Headers
String getContentType();                   void setContentType(String);
long   getContentLength();                 void setContentLength(long);
String getHost();                          void setHost(String);
String getAuthorization();                 void setAuthorization(String);
String getUserAgent();
String getAcceptEncoding();
String getTransferEncoding();
```

### CORS Header Accessors

```java
String getAccessControlAllowHeaders();
String getAccessControlAllowMethods();
String getAccessControlAllowOrigin();
String getAccessControlExposeHeaders();
String getAccessControlMaxAge();
String getAccessControlRequestHeaders();
String getAccessControlRequestMethod();
String getOrigin();
```

### Cookie Access

```java
Map<String, String> getCookies();              // request cookies: name → value
List<SetCookie>     getSetCookies();           // response Set-Cookie headers (parsed)
void                addCookie(SetCookie cookie); // add Set-Cookie to response
```

### HeaderField and HeaderName

```java
// com.pingidentity.pa.sdk.http.HeaderField
String getName();
String getValue();
HeaderField(String name, String value);
HeaderField(HeaderName name, String value);  // type-safe name

// com.pingidentity.pa.sdk.http.HeaderName
// Provides standard header name constants — e.g., HeaderName.CONTENT_TYPE
```

---

## 8. Identity, Session & OAuth Context

> **Tags:** `identity`, `session`, `OAuth`, `OIDC`, `OAuthTokenMetadata`, `OAuthConstants`, `$session`

### Identity

```java
// com.pingidentity.pa.sdk.policy.Identity (interface)
String                  getSubject();                // the authenticated user
JsonNode                getAttributes();             // full attribute set as Jackson tree
SessionStateSupport     getSessionStateSupport();
OAuthTokenMetadata      getOAuthTokenMetadata();
// Identity is null if the user is not authenticated.
```

### SessionStateSupport

```java
// com.pingidentity.pa.sdk.session.SessionStateSupport (interface)
Map<String, JsonNode> getAttributes();                     // all session attributes
JsonNode              getAttributeValue(String name);      // single attribute
void                  setAttribute(String name, JsonNode value);  // write attribute
void                  removeAttribute(String name);
```

> **Key pattern:** Session state persists across requests within a PA session.
> Use namespaced keys (e.g., `com.mycompany.myplugin.cacheKey`) to avoid
> collisions with other plugins.

### OAuthTokenMetadata

```java
// com.pingidentity.pa.sdk.policy.OAuthTokenMetadata (interface)
String      getClientId();
String      getGrantType();       // e.g., "authorization_code"
String      getTokenOwner();      // resource owner subject
Set<String> getScopes();          // granted scopes
Instant     getTokenExpiration(); // token expiry (null if not available)
```

### Building $session — Flat Merge Pattern

For the message-xform adapter, session context is built by merging all available
identity data into a single flat `ObjectNode`:

```java
ObjectNode session = objectMapper.createObjectNode();

// L1: Subject — always present if authenticated
session.put("subject", identity.getSubject());

// L2: Token metadata — present on OAuth-protected resources
OAuthTokenMetadata meta = identity.getOAuthTokenMetadata();
if (meta != null) {
    session.put("clientId", meta.getClientId());
    session.put("grantType", meta.getGrantType());
    ArrayNode scopeArr = session.putArray("scopes");
    meta.getScopes().forEach(scopeArr::add);
}

// L3: User attributes — from token validation (PingFederate, etc.)
JsonNode attrs = identity.getAttributes();
if (attrs != null && attrs.isObject()) {
    attrs.fields().forEachRemaining(e -> session.set(e.getKey(), e.getValue()));
}

// L4: Web session state — persistent custom data
SessionStateSupport sss = identity.getSessionStateSupport();
if (sss != null) {
    Map<String, JsonNode> sessAttrs = sss.getAttributes();
    if (sessAttrs != null) {
        ObjectNode sessNode = session.putObject("sessionState");
        sessAttrs.forEach(sessNode::set);
    }
}
```

### OAuthConstants

Standard OAuth 2.0 string constants per RFC 6749, RFC 6750, and DPoP.

```java
// com.pingidentity.pa.sdk.oauth.OAuthConstants

// Authentication schemes
static final String AUTHENTICATION_SCHEME_BEARER; // "Bearer"
static final String AUTHENTICATION_SCHEME_DPOP;   // "DPoP"

// Header names
static final String HEADER_NAME_WWW_AUTHENTICATE; // "WWW-Authenticate"
static final String HEADER_NAME_DPOP_NONCE;       // "DPoP-Nonce"

// WWW-Authenticate attributes
static final String ATTRIBUTE_REALM;              // "realm"
static final String ATTRIBUTE_SCOPE;              // "scope"
static final String ATTRIBUTE_ERROR;              // "error"
static final String ATTRIBUTE_ERROR_DESCRIPTION;  // "error_description"
static final String ATTRIBUTE_ERROR_URI;          // "error_uri"

// Error codes (RFC 6749/6750)
static final String ERROR_CODE_INVALID_REQUEST;           // "invalid_request"
static final String ERROR_CODE_UNAUTHORIZED_CLIENT;       // "unauthorized_client"
static final String ERROR_CODE_ACCESS_DENIED;             // "access_denied"
static final String ERROR_CODE_UNSUPPORTED_RESPONSE_TYPE; // "unsupported_response_type"
static final String ERROR_CODE_INVALID_SCOPE;             // "invalid_scope"
static final String ERROR_CODE_SERVER_ERROR;              // "server_error"
static final String ERROR_CODE_TEMPORARILY_UNAVAILABLE;   // "temporarily_unavailable"
static final String ERROR_CODE_INVALID_TOKEN;             // "invalid_token"
static final String ERROR_CODE_INSUFFICIENT_SCOPE;        // "insufficient_scope"
static final String ERROR_CODE_UNSUPPORTED_GRANT_TYPE;    // "unsupported_grant_type"
static final String ERROR_CODE_INVALID_CLIENT;            // "invalid_client"
// DPoP-specific
static final String ERROR_CODE_INVALID_DPOP_PROOF;        // "invalid_dpop_proof"
static final String ERROR_CODE_USE_DPOP_NONCE;            // "use_dpop_nonce"
```

### OAuthUtilities

Builder utility for constructing RFC 6750 `WWW-Authenticate` response headers.

```java
// com.pingidentity.pa.sdk.oauth.OAuthUtilities
static String getWwwAuthenticateHeaderValue(
    String authenticationScheme,  // "Bearer" or "DPoP"
    Map<String, String> attributes  // realm, scope, error, etc.
);
// Produces: Bearer realm="...", scope="...", error="invalid_token"
```

---

# Part III — Response Building & Error Handling

## 9. ResponseBuilder & DENY Mode

> **Tags:** `ResponseBuilder`, `DENY`, `Outcome`, `response`, `NPE`

### ResponseBuilder API

Builder used for creating `Response` objects. Each `build()` invocation creates
a new `Response` instance.

```java
// com.pingidentity.pa.sdk.http.ResponseBuilder — static factories
static ResponseBuilder newInstance(HttpStatus status);   // arbitrary status
static ResponseBuilder ok();                             // 200 OK
static ResponseBuilder ok(String msg);                   // 200 + text/html body
static ResponseBuilder badRequest();                     // 400
static ResponseBuilder badRequestJson(String json);      // 400 + application/json body
static ResponseBuilder unauthorized();                   // 401
static ResponseBuilder forbidden();                      // 403
static ResponseBuilder notFound();                       // 404 + text/html
static ResponseBuilder notFoundJson();                   // 404 + application/json
static ResponseBuilder unprocessableEntity();            // 422
static ResponseBuilder internalServerError();            // 500
static ResponseBuilder serviceUnavailable();             // 503
static ResponseBuilder found(String url);                // 302 redirect

// Builder methods
ResponseBuilder header(String name, String value);
ResponseBuilder header(HeaderField headerField);
ResponseBuilder headers(Headers headers);         // copies, does not store reference
ResponseBuilder contentType(String type);
ResponseBuilder contentType(MediaType type);
ResponseBuilder body(byte[] body);                // copies array, updates Content-Length
ResponseBuilder body(String msg);                 // UTF-8 encoding, updates Content-Length
ResponseBuilder body(String msg, Charset charset);
Response build();                                 // creates new Response instance
```

> **Note:** The `body(byte[])` and `headers(Headers)` methods copy the provided
> objects — they do not store references. The `headers()` method throws
> `IllegalArgumentException` if the `Headers` object was not created by the
> provided `HeadersFactory` (see `ServiceFactory.headersFactory()`).

### ResponseBuilder.Factory

```java
// com.pingidentity.pa.sdk.http.ResponseBuilder.Factory
// A factory creating ResponseBuilder instances. Obtained via ServiceFactory.
ResponseBuilder newInstance(HttpStatus status);
```

> **⚠️ Testing pitfall:** `ResponseBuilder.newInstance()` calls
> `ServiceFactory.getSingleImpl(ResponseBuilder.Factory.class)` internally.
> Outside the PA runtime (e.g. in unit tests), this throws
> `ExceptionInInitializerError` → `RuntimeException: No Impl found`.
> The workaround is to inject a `BiFunction<HttpStatus, String, Response>`
> factory into your rule class, defaulting to `ResponseBuilder` in production
> and swapped for a mock/lambda in tests. See `MessageTransformRule.responseFactory`.

### ⚠️ CRITICAL: DENY Mode in handleRequest — Response is NULL

During `handleRequest()`, `exchange.getResponse()` is **`null`** because the
backend has not yet responded. You MUST use `ResponseBuilder` to construct a new
`Response` and set it on the exchange:

```java
// DENY mode — handleRequest error path
byte[] errorBody = objectMapper.writeValueAsBytes(result.errorResponse());
Response errorResponse = ResponseBuilder.newInstance(HttpStatus.BAD_GATEWAY)
    .header("Content-Type", "application/problem+json")
    .body(errorBody)
    .build();
exchange.setResponse(errorResponse);
return CompletableFuture.completedFuture(Outcome.RETURN);
```

**Previous incorrect design (GAP-09):** The spec previously described calling
`exchange.getResponse().setBodyContent()` during request phase. This would cause
a `NullPointerException` because the response object does not exist.

**Alternative considered:** Throw `AccessException` and delegate to
`ErrorHandlingCallback`. Rejected because the callback writes a PA-formatted
HTML error page, not our RFC 9457 JSON format.

### DENY Mode in handleResponse — Response EXISTS

During `handleResponse()`, the response object already exists. Rewrite in-place:

```java
// DENY mode — handleResponse error path
byte[] errorBody = objectMapper.writeValueAsBytes(result.errorResponse());
exchange.getResponse().setBodyContent(errorBody);  // auto-updates Content-Length
exchange.getResponse().setStatus(HttpStatus.BAD_GATEWAY);
exchange.getResponse().getHeaders().setContentType("application/problem+json");
```

### SDK Sample References

- `Clobber404ResponseRule.handleResponse()` uses `ResponseBuilder.notFound()` +
  `getTemplateRenderer().renderResponse(exchange, ...)` to construct a new response.
- `RiskAuthorizationRule.handleRequest()` uses `Outcome.RETURN` with
  `POLICY_ERROR_INFO` to delegate to ErrorHandlingCallback.
- `WriteResponseRule` throws `AccessException` to trigger
  `getErrorHandlingCallback().writeErrorResponse(exchange)`.

---

## 10. Error Handling & Error Callbacks

> **Tags:** `AccessException`, `ErrorHandlingCallback`, `ErrorInfo`, `ErrorDescription`, `localization`

### AccessException

`AccessException` is thrown to abort the interceptor pipeline. It implements
`ErrorInfo`, which provides error information used to generate the HTTP response.

> **Not for expected denials** — use `Outcome.RETURN` instead. Use
> `AccessException` only for unexpected errors during processing.

```java
// com.pingidentity.pa.sdk.policy.AccessException extends Exception implements ErrorInfo
// Constructors:
AccessException(String message, HttpStatus status);
AccessException(String message, HttpStatus status, Throwable cause);
AccessException(AccessExceptionContext context);      // fluent builder pattern

// ErrorInfo methods (via interface):
HttpStatus       getErrorStatus();      // HTTP status for the error response
LocalizedMessage getErrorMessage();     // localized end-user message (may be null)
Throwable        getErrorCause();       // root cause (may be null)
```

> **"Any" Ruleset caveat (from Javadoc):** Depending on the state of the
> transaction, an `AccessException` may be ignored. For example, when a
> `RuleInterceptor` is evaluated within an "Any" Ruleset, the failure of one
> `RuleInterceptor` may be ignored (i.e., other rules in the set still proceed).

### AccessExceptionContext (fluent builder)

For richer error responses with localization support, use the builder pattern:

```java
// com.pingidentity.pa.sdk.policy.AccessExceptionContext
static AccessExceptionContext create(HttpStatus status);   // factory method

// Fluent setters (all return this):
AccessExceptionContext exceptionMessage(String message);   // for logging (not shown to users)
AccessExceptionContext cause(Throwable cause);             // root cause
AccessExceptionContext errorDescription(LocalizedMessage message);  // end-user message

// Usage:
throw new AccessException(
    AccessExceptionContext.create(HttpStatus.FORBIDDEN)
        .exceptionMessage("Invalid value: " + value)   // for server logs
        .cause(e)                                        // root exception
        .errorDescription(new BasicLocalizedMessage("invalid.value.msg"))  // i18n
);
```

> If `exceptionMessage` is not specified, the non-localized status message
> (`HttpStatus.getMessage()`) is used for the exception message.

### ErrorInfo (interface)

```java
// com.pingidentity.pa.sdk.policy.error.ErrorInfo
HttpStatus       getErrorStatus();    // status code + reason phrase
LocalizedMessage getErrorMessage();   // localized message for response body (null if N/A)
Throwable        getErrorCause();     // cause (null if unknown)
// AccessException implements this interface.
```

### ErrorDescription (enum)

```java
// com.pingidentity.pa.sdk.localization.ErrorDescription
ACCESS_DENIED      // Error description for access denied
PAGE_NOT_FOUND_404 // Error description for 404 Page Not Found
POLICY_ERROR       // Error description for a policy-related error

String getLocalizationKey();                   // key for ResourceBundle lookup
String getDescription(String... substitutions); // non-localized with placeholder replacement
```

### LocalizedMessage Hierarchy

```java
// com.pingidentity.pa.sdk.localization.LocalizedMessage (interface)
String resolve(Locale locale, LocalizedMessageResolver resolver);

// Implementations:
FixedMessage                    // returns a fixed string, no localization
BasicLocalizedMessage           // looked up via ResourceBundle key
ParameterizedLocalizedMessage   // key + parameters for placeholder substitution
```

> Templates for ResourceBundle properties are located at `conf/localization/`
> in the PingAccess installation directory.

### ErrorHandlingCallback (interface)

```java
// com.pingidentity.pa.sdk.policy.ErrorHandlingCallback
void writeErrorResponse(Exchange exchange) throws IOException;
// Writes an error response when errors are encountered.
```

### RuleInterceptorErrorHandlingCallback

The SDK's built-in error callback implementation. Reads `ErrorHandlerConfiguration`
to determine the HTTP status, template file, content type, and status message.

```java
// com.pingidentity.pa.sdk.policy.error.RuleInterceptorErrorHandlingCallback
RuleInterceptorErrorHandlingCallback(TemplateRenderer renderer, ErrorHandlerConfiguration config);

// Key field:
static final ExchangeProperty<String> POLICY_ERROR_INFO;
// String value denoting the error information for the policy failure.
// Set this on the exchange before returning Outcome.RETURN to pass
// error context to the callback.
```

### OAuthPolicyErrorHandlingCallback

Writes OAuth 2.0 RFC 6750 compliant `WWW-Authenticate` error responses for
OAuth-protected endpoints.

```java
// com.pingidentity.pa.sdk.policy.error.OAuthPolicyErrorHandlingCallback
OAuthPolicyErrorHandlingCallback(String realm, String scope);
// Produces 401 responses with WWW-Authenticate: Bearer realm="...", scope="..."
```

### LocalizedInternalServerErrorCallback

Localizes the internal server error message using the exchange's resolved locale:

```java
// com.pingidentity.pa.sdk.policy.error.LocalizedInternalServerErrorCallback
LocalizedInternalServerErrorCallback(LocalizedMessageResolver resolver);
// Constructs a callback that resolves the error message via ResourceBundle.
```

---

# Part IV — Cross-Cutting Concerns

## 11. Thread Safety & Concurrency

> **Tags:** `thread`, `concurrency`, `mutable`, `instance`, `shared`, `immutability`

Plugin instances may be shared across threads (PA uses a thread pool for request
processing). The adapter MUST distinguish between two categories of state:

### Init-time state (safe for concurrent reads)

These fields are set once during plugin initialization and never modified during
request processing:

- Fields set via setter injection: `HttpClient`, `TemplateRenderer`
- Configuration field set by `configure(T)`
- Any field populated in the constructor or `configure()` method

These are safe for concurrent access because they are effectively immutable
after initialization.

### Per-request state (MUST be method-local)

Any state derived from a specific request (parsed bodies, transform results,
error responses) MUST be local to the `handleRequest()` / `handleResponse()`
method. Do NOT store per-request state in instance fields.

### Evidence from SDK Samples

The `ExternalAuthorizationRule` sample uses instance field `objectMapper`
(injected via setter) across threads — confirming that PA treats injected
fields as thread-safe. It stores per-request authorization results in
`SessionStateSupport` (not instance fields).

### Request Immutability During Response Phase

Any modifications to `exchange.getRequest()` or its headers inside
`handleResponse()` are **silently ignored** by PingAccess (a warning is logged).
Only `exchange.getResponse()` may be modified during response processing.
See [§3 Core Plugin SPI](#3-core-plugin-spi--lifecycle) for code examples.

---

## 12. Deployment & Classloading

> **Tags:** `shadow`, `JAR`, `Jackson`, `compileOnly`, `classloader`, `SPI`, `SLF4J`, `ServiceLoader`, `ADR-0031`

### Classloader Model (Verified)

PingAccess uses a **flat classpath** with **no classloader isolation**.

**Evidence** (from `run.sh` line 59):
```bash
CLASSPATH="${CLASSPATH}:${SERVER_ROOT_DIR}/lib/*:${SERVER_ROOT_DIR}/deploy/*"
```

Both PA platform libraries (`lib/*`) and plugin JARs (`deploy/*`) are loaded
by the same `jdk.internal.loader.ClassLoaders$AppClassLoader`. There is no
custom classloader, no OSGi, no module isolation.

```
┌─────────────────────────────────────────────────────────┐
│     jdk.internal.loader.ClassLoaders$AppClassLoader      │
│                                                          │
│  /opt/server/lib/*      (146 JARs — PA platform)         │
│  /opt/server/deploy/*   (plugin JARs — our adapter)      │
│                                                          │
│  SAME classloader → SAME Class objects → NO isolation    │
└─────────────────────────────────────────────────────────┘
```

Plugin discovery uses `ServiceLoader.load(Class)` (single-argument form) in
both `ServiceFactory` and `ConfigurablePluginPostProcessor` — this uses the
thread context classloader, which on a flat classpath is the AppClassLoader.

Full analysis: ADR-0031 (PA-Provided Dependencies).

### Shadow JAR Contents

The adapter shadow JAR must contain:
- Adapter classes (`io.messagexform.pingaccess.*`)
- Core engine (`io.messagexform.core.*`)
- `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`
- Runtime dependencies NOT provided by PA: JSLT, SnakeYAML, JSON Schema Validator

**Excluded** (PA-provided — `compileOnly`, per ADR-0031):

| Library | PA 9.0.1 Version | Notes |
|---------|-----------------|-------|
| `jackson-databind` | 2.17.0 | SDK returns `JsonNode` — shared class |
| `jackson-core` | 2.17.0 | Transitive |
| `jackson-annotations` | 2.17.0 | Config annotations |
| `slf4j-api` | 1.7.36 | PA uses **Log4j2** (not Logback) as SLF4J backend |
| `jakarta.validation-api` | 3.1.1 | Bean Validation |
| `jakarta.inject-api` | 2.0.1 | CDI |
| PingAccess SDK | 9.0.1.0 | Plugin API |

> **Do NOT bundle or relocate Jackson.** PA provides Jackson 2.17.0 on the
> flat classpath. `Identity.getAttributes()` returns the same `JsonNode`
> class the adapter uses. Bundling Jackson (with or without relocation) is
> incorrect:
> - **With relocation:** `ClassCastException` — shaded `JsonNode` ≠ PA's `JsonNode`
> - **Without relocation:** Version conflict — two copies of same classes on classpath
> - **Correct:** `compileOnly` — PA provides it at runtime

### SPI Registration

```
META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor
```

Contains the FQCN of the plugin class. PingAccess discovers it via `ServiceLoader`.

### Deployment Path

#### The `<PA_HOME>/deploy` Directory

The `<PA_HOME>/deploy` directory is the designated location for all third-party
and custom plugin JAR files. Key characteristics:

- **Auto-loaded at startup:** Contents are loaded by `run.sh` (Linux) or
  `run.bat` (Windows) during PingAccess startup.
- **Auto-migrated on upgrade:** When PingAccess is upgraded, files in the
  `deploy` directory are **automatically migrated** to the new installation.
  This is the critical difference from `<PA_HOME>/lib` — files in `lib` are NOT
  migrated and are replaced by the new version's library set.
- **PingAccess does not generate contents:** The directory starts empty; all
  contents are user-provided.

#### Deployment Procedure

1. Build the shadow JAR.
2. Copy the JAR to `<PA_HOME>/deploy/` on each PA node.
3. **Restart PingAccess.**

> ⚠️ **Mandatory Restart:** You **must** restart PingAccess after deploying any
> custom plugin JAR. There is no hot-reload mechanism. This applies to initial
> deployment, updates, and removal of plugin JARs.

#### Our Adapter's Deployment

For the message-xform adapter, the deployment path is:
```
/opt/server/deploy/message-xform-adapter-<version>-shadow.jar
```

---

## 13. Testing Patterns

> **Tags:** `test`, `Mockito`, `mock`, `Validator`, `JUnit`, `Exchange`, `config`

### Mockito Mock Chain (from `ExternalAuthorizationRuleTest`)

The standard pattern for mocking the PA SDK object graph:

```java
// Create mocks
Exchange exchange = mock(Exchange.class);
Request request = mock(Request.class);
Body body = mock(Body.class);
Headers headers = mock(Headers.class);
Identity identity = mock(Identity.class);
SessionStateSupport sss = mock(SessionStateSupport.class);
OAuthTokenMetadata oauthMeta = mock(OAuthTokenMetadata.class);

// Wire mock chain
when(exchange.getRequest()).thenReturn(request);
when(exchange.getResponse()).thenReturn(null); // request phase: no response yet
when(exchange.getIdentity()).thenReturn(identity);
when(request.getBody()).thenReturn(body);
when(request.getHeaders()).thenReturn(headers);
when(body.getContent()).thenReturn("{\"key\":\"value\"}".getBytes(UTF_8));
when(body.isRead()).thenReturn(true);
when(body.isInMemory()).thenReturn(true);  // required for getContent() to not throw

// Identity chain
when(identity.getSubject()).thenReturn("joe");
when(identity.getAttributes()).thenReturn(objectMapper.readTree("{\"sub\":\"joe\"}"));
when(identity.getSessionStateSupport()).thenReturn(sss);
when(identity.getOAuthTokenMetadata()).thenReturn(oauthMeta);

// Session state
when(sss.getAttributes()).thenReturn(Map.of(
    "authzCache", objectMapper.readTree("{\"decision\":\"PERMIT\"}")
));

// OAuth metadata
when(oauthMeta.getClientId()).thenReturn("my-client");
when(oauthMeta.getScopes()).thenReturn(Set.of("openid", "profile"));
```

### DENY Mode Request-Phase Verification

```java
// Verify ResponseBuilder was used correctly (ArgumentCaptor pattern)
ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
verify(exchange).setResponse(responseCaptor.capture());
Response errorResponse = responseCaptor.getValue();
assertThat(errorResponse.getStatusCode()).isEqualTo(502);
```

### Configuration Validation (Standalone Validator)

Use standalone Jakarta Bean Validation — no Spring context needed:

```java
// In @BeforeAll or test setup
ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
Validator validator = factory.getValidator();

// Test config validation
MessageTransformConfig config = new MessageTransformConfig();
config.setSpecsDir("");  // invalid
Set<ConstraintViolation<MessageTransformConfig>> violations = validator.validate(config);
assertThat(violations).isNotEmpty();
```

### HttpClient Mocking (from `RiskAuthorizationRuleTest`)

When testing rules that make outbound HTTP calls via `HttpClient`, mock the
`send()` method to return `CompletableFuture`s. Use `same()` matcher to route
responses to the correct service:

```java
// Create service definitions
ThirdPartyServiceModel riskService = new ThirdPartyServiceModel("id1", "risk");
ThirdPartyServiceModel oauthService = new ThirdPartyServiceModel("id2", "OAuth AS");

// Mock HttpClient to return different responses per service
HttpClient client = mock(HttpClient.class);

// OAuth token endpoint
ClientResponse tokenResponse = createMockedResponse(
    "{\"access_token\":\"token\"}".getBytes(UTF_8), HttpStatus.OK);
when(client.send(any(), same(oauthService)))
    .thenAnswer(inv -> CompletableFuture.completedFuture(tokenResponse));

// Risk service endpoint
ClientResponse riskResponse = createMockedResponse(
    "{\"score\":75}".getBytes(UTF_8), HttpStatus.OK);
when(client.send(any(), same(riskService)))
    .thenAnswer(inv -> CompletableFuture.completedFuture(riskResponse));

// Helper for creating mocked ClientResponse
static ClientResponse createMockedResponse(byte[] body, HttpStatus status) {
    ClientResponse response = mock(ClientResponse.class);
    when(response.getBody()).thenReturn(body);
    when(response.getHeaders()).thenReturn(ClientRequest.createHeaders());
    when(response.getStatus()).thenReturn(status);
    return response;
}
```

**Simulating HttpClient failures:**
```java
when(client.send(any(), same(riskService))).thenAnswer(inv -> {
    CompletableFuture<ClientResponse> failureResult = new CompletableFuture<>();
    failureResult.completeExceptionally(
        new HttpClientException(HttpClientException.Type.SERVICE_UNAVAILABLE,
                                "Third-party service unavailable",
                                new Exception()));
    return failureResult;
});
```

### Async Rule Test Pattern (from `RiskAuthorizationRuleTest`)

When testing async rules, the `handleRequest()` returns `CompletionStage<Outcome>`.
Use `toCompletableFuture().get()` to extract the result:

```java
RiskAuthorizationRule rule = new RiskAuthorizationRule();
rule.configure(configuration);
rule.setHttpClient(mockedHttpClient);

CompletableFuture<Outcome> result = rule.handleRequest(exchange).toCompletableFuture();
assertTrue(result.isDone());
assertEquals(Outcome.RETURN, result.get());
```

### SessionStateSupport Verification (from `ExternalAuthorizationRuleTest`)

Verify session state read/write interactions using `ArgumentCaptor`:

```java
SessionStateSupport sss = mock(SessionStateSupport.class);
when(identity.getSessionStateSupport()).thenReturn(sss);

// Simulate no cached session data
when(sss.getAttributeValue("com.mycompany.auth.response.rule1")).thenReturn(null);

// Invoke rule
instance.handleRequest(exchange);

// Verify session state was written
ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
verify(sss).setAttribute(eq("com.mycompany.auth.response.rule1"), captor.capture());

// Inspect captured value
AuthorizationResponse response = objectMapper.convertValue(captor.getValue(),
    AuthorizationResponse.class);
assertTrue(response.isAllowed());
```

### SPI Registration Verification (from `TestValidateRulesAreAvailable`)

Use `ServiceFactory.isValidImplName()` to verify your plugin is correctly
registered via `META-INF/services/`:

```java
import com.pingidentity.pa.sdk.services.ServiceFactory;
import com.pingidentity.pa.sdk.policy.RuleInterceptor;

@Test
public void testPluginIsRegistered() {
    assertTrue(ServiceFactory.isValidImplName(
        RuleInterceptor.class, MyCustomRule.class.getName()));

    assertFalse(ServiceFactory.isValidImplName(
        RuleInterceptor.class, this.getClass().getName()));
}
```

> **Pattern:** this also works for `SiteAuthenticatorInterceptor.class`,
> `IdentityMappingPlugin.class`, etc. Always include an SPI registration
> test in your test suite.

### Configuration Deserialization Test (from `TestAllUITypesAnnotationRule`)

Test that configuration JSON correctly deserializes and validates:

```java
@ContextConfiguration("/mock-config.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class ConfigurationTest {
    @Autowired private Validator validator;

    @Test
    public void testValidConfiguration() throws IOException {
        Map<String, String> map = new HashMap<>();
        map.put("aText", "value");
        map.put("aSelect", "OptionOne");
        map.put("aTable", "[{\"col1\":\"val\",\"col2\":\"2\"}]");
        map.put("errorResponseCode", "403");

        MyPlugin.Configuration cfg = new MyPlugin.Configuration();
        cfg.setName("testRule");
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.readerForUpdating(cfg).readValue(TestUtil.getJsonForMap(map));

        Set<ConstraintViolation<?>> violations =
            new HashSet<>(validator.validate(cfg));
        assertThat(violations.size(), is(0));
    }
}
```

> **Key pattern:** Jackson's `readerForUpdating()` merges JSON into an existing
> object without replacing defaults. This simulates how PingAccess admin
> deserializes config posted from the UI.

### Test Infrastructure Helpers (from SDK samples)

```java
// Mock HeadersFactory — returns Mockito mocks for Headers
public static class MockHeadersFactoryImpl implements HeadersFactory {
    public Headers create()                        { return mock(Headers.class); }
    public Headers create(List<HeaderField> fields) { return mock(Headers.class); }
}
// Register via: META-INF/services/com.pingidentity.pa.sdk.http.HeadersFactory

// Mock ConfigurationModelAccessorFactory — delegates to ServiceFactory
public static class ConfigurationModelAccessorFactoryImpl
        implements ConfigurationModelAccessorFactory {
    public <T extends ConfigurationModelAccessor> T getConfigurationModelAccessor(Class<T> clazz) {
        List<Class<?>> impls = ServiceFactory.getImplClasses(ConfigurationModelAccessor.class);
        for (Class implClazz : impls) {
            if (clazz.isAssignableFrom(implClazz)) {
                return (T) implClazz.newInstance();
            }
        }
        return null;
    }
}

// Spring mock-config.xml for @Autowired Validator:
// <bean id="validator" class="org.springframework.validation.beanvalidation.LocalValidatorFactoryBean">
//   <property name="validationPropertyMap">
//     <util:map><entry key="hibernate.validator.fail_fast" value="false"/></util:map>
//   </property>
// </bean>
```

### Test Dependency Alignment

| Dependency | SDK Sample Version | Our Project | Notes |
|------------|-------------|-------------|-------|
| JUnit | 4.13.2 | 5 (Jupiter) | Intentional — our project uses JUnit 5 |
| Mockito | 5.15.2 | 5.x (inherited) | Compatible — verify alignment in `build.gradle.kts` |
| Hibernate Validator | 7.0.5.Final | (add as testImplementation) | Needed for config validation tests |
| Spring Test | 6.2.11 | (not used) | We use standalone `Validator` instead |

---

## 14. JMX Monitoring & Custom MBeans

> **Tags:** `JMX`, `MBean`, `monitoring`, `metrics`, `heartbeat`, `audit`

PingAccess provides a comprehensive JMX-based monitoring infrastructure.
The SDK itself does **not** expose a metrics API, but plugins can register
custom MBeans using standard `java.lang.management` — they run in the same
JVM as the PA engine.

### PA's built-in monitoring mechanisms

From the **PingAccess Monitoring Guide** (PA 9.0 docs, pages 833–849):

| Mechanism | Description | Configuration |
|-----------|-------------|---------------|
| **JMX MBeans** | Ping-recommended primary mechanism. Provides resource metric counters for performance analysis. Compatible with JConsole, Splunk, and SIEM tools. | `pa.mbean.site.connection.pool.enable=true` (default `false`) in `run.properties` |
| **Heartbeat endpoint** | `/pa/heartbeat.ping` — returns HTTP 200 when healthy. Can return detailed JSON with CPU, memory, response time stats. | `enable.detailed.heartbeat.response=true` + `pa.statistics.window.seconds=N` in `run.properties` |
| **Audit logging** | Per-transaction timing in `pingaccess_engine_audit.log`. Records total roundtrip, proxy roundtrip, and userinfo roundtrip (ms). | Enabled by `log4j2.xml` configuration |
| **Splunk integration** | Pre-built PingAccess for Splunk app ([Splunkbase #5368](https://splunkbase.splunk.com/app/5368)). Parses both audit logs and JMX MBean data. | Splunk-formatted audit log appenders |

### JMX connection setup

**Local connection:** In JConsole, select `com.pingidentity.pa.cli.Starter`
from the Local Process list.

**Remote connection:** Add to `jvm-memory.options`:

```
-Dcom.sun.management.jmxremote.port=5000
-Dcom.sun.management.jmxremote.login.config=<YourConfig>
-Djava.security.auth.login.config=conf/ldap.config
-Dcom.sun.management.jmxremote.ssl=false
```

> ⚠️ Enable SSL for production JMX. The `ssl=false` setting is for
> testing only.

### Heartbeat endpoint JSON response

When `enable.detailed.heartbeat.response=true`:

```json
{"items":[{
    "response.statistics.window.seconds": "5",
    "response.statistics.count": "1",
    "response.time.statistics.90.percentile": "129",
    "response.time.statistics.mean": "129",
    "response.time.statistics.max": "129",
    "response.time.statistics.min": "129",
    "response.concurrency.statistics.90.percentile": "1",
    "response.concurrency.statistics.mean": "1",
    "cpu.load": "15.53",
    "total.jvm.memory": "500.695 MB",
    "free.jvm.memory": "215.339 MB",
    "used.jvm.memory": "285.356 MB",
    "number.of.cpus": "8",
    "open.client.connections": "1",
    "number.of.applications": "11",
    "number.of.virtual.hosts": "6"
}]}
```

### SDK gap: no metrics API for plugins

The SDK JAR (`pingaccess-sdk-9.0.1.0.jar`) contains **zero** classes related
to JMX, MBeans, metrics, monitoring, or telemetry. There is no
`registerMetric()`, no `MetricsService`, and no counters API.

PA's own MBeans (connection pool metrics, heartbeat stats) are registered by
the engine internally – they are not accessible or extendable from the SDK.

### Plugin MBean registration pattern

Plugins can register their own JMX MBeans using standard Java. This is safe
because plugins run in the PA engine JVM and have access to
`ManagementFactory.getPlatformMBeanServer()`.

**Step 1: Define an MXBean interface**

Use the `MXBean` suffix convention — the JMX runtime automatically exposes
all public getters as attributes and methods as operations:

```java
public interface MyPluginMetricsMXBean {
    // Attributes (read-only via JConsole)
    long getRequestCount();
    long getErrorCount();
    double getAverageLatencyMs();
    long getActiveSpecCount();

    // Operations (invokable via JConsole)
    void resetCounters();
}
```

**Step 2: Implement the MXBean**

```java
public class MyPluginMetrics implements MyPluginMetricsMXBean {
    private final LongAdder requestCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder totalLatencyNanos = new LongAdder();
    private volatile int activeSpecCount;

    @Override public long getRequestCount() { return requestCount.sum(); }
    @Override public long getErrorCount() { return errorCount.sum(); }
    @Override public double getAverageLatencyMs() {
        long count = requestCount.sum();
        return count == 0 ? 0.0
            : (totalLatencyNanos.sum() / 1_000_000.0) / count;
    }
    @Override public long getActiveSpecCount() { return activeSpecCount; }
    @Override public void resetCounters() {
        requestCount.reset();
        errorCount.reset();
        totalLatencyNanos.reset();
    }

    // --- Mutators (called by plugin, not exposed via JMX) ---
    public void recordSuccess(long elapsedNanos) {
        requestCount.increment();
        totalLatencyNanos.add(elapsedNanos);
    }
    public void recordError() {
        requestCount.increment();
        errorCount.increment();
    }
    public void setActiveSpecCount(int count) {
        this.activeSpecCount = count;
    }
}
```

> **Thread safety:** `LongAdder` is specifically designed for high-contention
> counter updates from multiple threads. It outperforms `AtomicLong` under
> contention by using cell-based striping.

**Step 3: Register in `configure()`**

```java
private ObjectName jmxObjectName;
private final MyPluginMetrics metrics = new MyPluginMetrics();

@Override
public void configure(MessageTransformConfig config) {
    this.config = config;

    if (config.isEnableJmxMetrics()) {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(
                "io.messagexform:type=TransformMetrics,instance="
                    + ObjectName.quote(config.getName()));
            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(metrics, name);
                LOG.info("JMX MBean registered: {}", name);
            }
            this.jmxObjectName = name;
        } catch (Exception e) {
            LOG.warn("Failed to register JMX MBean — metrics disabled", e);
        }
    } else {
        unregisterMBean();
    }
}
```

**ObjectName convention:** Use a plugin-specific domain (`io.messagexform`)
to avoid collisions with PA's internal MBeans. The `instance` key property
uses `config.getName()` (from `PluginConfiguration.getName()`) to
distinguish multiple rule instances on the same engine.

> **`ObjectName.quote()`:** Always quote the instance name. PA allows
> arbitrary characters in rule names (including spaces, colons, commas)
> which are illegal in unquoted ObjectName values.

**Step 4: Unregister on reconfiguration**

PA does not expose a `destroy()` lifecycle callback in the SDK. However,
if the plugin is reconfigured (new `configure()` call), the pattern above
handles re-registration. For PA shutdown, the JVM termination implicitly
cleans up MBeans.

### ObjectName patterns for JConsole

After registration, the MBean appears in JConsole under:

```
io.messagexform
  └── TransformMetrics
      └── instance=<rule-name>
          ├── Attributes
          │   ├── RequestCount
          │   ├── ErrorCount
          │   ├── AverageLatencyMs
          │   └── ActiveSpecCount
          └── Operations
              └── resetCounters()
```

---

## 15. Logging

> **Tags:** `logging`, `SLF4J`, `audit`, `Log4j2`

### SLF4J Usage

Use the **SLF4j API** for all logging in plugin modules:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger LOG = LoggerFactory.getLogger(MyPlugin.class);
```

PingAccess ships its own SLF4J provider (Log4j2) — do **not** bundle SLF4J
in your plugin JAR (see [§12 Deployment & Classloading](#12-deployment--classloading)).

### Audit Logging (always available)

Even without JMX, every transform result is logged via SLF4J at INFO level.
PingAccess routes plugin SLF4J output to `pingaccess.log`. The engine's
own audit log (`pingaccess_engine_audit.log`) captures per-transaction
timing automatically — **no plugin code needed**.

Example `pingaccess_engine_audit.log` entry:
```
2026-02-11T19:15:12,192|...|tid:...|81 ms| 50 ms| 0 ms|app.example.com []...
```

Where `81 ms` is total roundtrip (includes our plugin processing time).

---

# Part V — Extension Points & Vendor Patterns

## 16. Additional Extension SPIs

> **Tags:** `extension`, `plugin`, `identity`, `site-authenticator`, `load-balancing`, `encryption`, `locale-override`
>
> These extension point interfaces exist in the SDK but are **not used** by the
> message-xform adapter. Documented here for completeness.

### IdentityMapping Plugins

Maps the `Identity` subject to a backend-system identifier sent to the
upstream server (e.g., as a header value).

```java
// com.pingidentity.pa.sdk.identitymapping.IdentityMapping (annotation)
@IdentityMapping(type = "...", label = "...", expectedConfiguration = ...)

// Sync interface:
// com.pingidentity.pa.sdk.identitymapping.IdentityMappingPlugin<T>
void handleMapping(Exchange exchange) throws IOException;
// Base: IdentityMappingPluginBase<T>

// Async interface:
// com.pingidentity.pa.sdk.identitymapping.AsyncIdentityMappingPlugin<T>
CompletionStage<Void> handleMapping(Exchange exchange);
// Base: AsyncIdentityMappingPluginBase<T>
```

**HeaderIdentityMappingPlugin** — built-in implementation that copies identity
attributes to request headers using `AttributeHeaderPair` mappings.

**SDK sample patterns:**

- **`SampleJsonIdentityMapping`**: Builds a JSON object with subject and role,
  Base64-encodes it, then sets it as a single header value:
  ```java
  exchange.getRequest().getHeaders().removeFields(HEADER_NAME);  // "clobber and set"
  exchange.getRequest().getHeaders().add(HEADER_NAME, encodedJson);
  ```

- **`SampleTableHeaderIdentityMapping`**: Uses `@UIElement(type = TABLE,
  subFieldClass = UserAttributeTableEntry.class)` to define a configurable
  table where each row maps a user attribute name to a header name. At runtime
  it iterates the table, reads attributes from `identity.getAttributes()`,
  and sets headers:
  ```java
  for (UserAttributeTableEntry entry : getConfiguration().entryList) {
      JsonNode value = attributes.get(entry.userAttribute);
      exchange.getRequest().getHeaders().add(entry.headerName, value.asText());
  }
  ```

### SiteAuthenticator Plugins

Adds authentication data when proxying to backend sites. Unlike rules, these
run as part of the **site connection** pipeline, not the application pipeline.

```java
// com.pingidentity.pa.sdk.siteauthenticator.SiteAuthenticator (annotation)
@SiteAuthenticator(type = "...", label = "...", expectedConfiguration = ...)

// Sync: SiteAuthenticatorInterceptor<T> / SiteAuthenticatorInterceptorBase<T>
void handleRequest(Exchange exchange) throws AccessException;

// Async: AsyncSiteAuthenticatorInterceptor<T> / AsyncSiteAuthenticatorInterceptorBase<T>
CompletionStage<Void> handleRequest(Exchange exchange);
```

**SDK sample pattern (`AddQueryStringSiteAuthenticator`):**

Demonstrates mutating the request URI to inject authentication query parameters:

```java
String uriString = exchange.getRequest().getUri();
URI uri = URI.create(uriString);
Map<String, String[]> query = exchange.getRequest().getQueryStringParams();
query.put(config.param, new String[]{ config.paramValue });
exchange.getRequest().setUri(uri.getPath() + "?" + urlEncode(query));
```

> **Note:** SiteAuthenticator uses `ConfigurationBuilder` (not `@UIElement`)
> for configuration fields:
> ```java
> return new ConfigurationBuilder()
>     .configurationField("param", "Query String Param", TEXT).required()
>     .configurationField("paramValue", "Query String Value", TEXT).required()
>     .toConfigurationFields();
> ```

#### SiteAuthenticatorInterceptor Interface Change (post-5.0)

The `SiteAuthenticatorInterceptor` interface no longer extends
`RequestInterceptor` and `ResponseInterceptor` (as it did pre-5.0), but it
still defines `handleRequest(Exchange)` and `handleResponse(Exchange)` directly.
Both methods now throw only `AccessException` (not `IOException` or
`InterruptedException` as in earlier versions).

### LoadBalancing Plugins

Custom load-balancing strategies for distributing traffic across sites.
The plugin creates a **handler** that lives for the lifetime of the
configuration. PA calls `getHandler()` on configuration changes.

```java
// com.pingidentity.pa.sdk.ha.lb.LoadBalancingStrategy (annotation)
@LoadBalancingStrategy(type = "...", label = "...", expectedConfiguration = ...)

// Sync:  LoadBalancingPlugin<H, T>   / LoadBalancingPluginBase<H, T>
// Async: AsyncLoadBalancingPlugin<H, T> / AsyncLoadBalancingPluginBase<H, T>
//   where H = Handler type, T = Configuration type

// Plugin lifecycle:
H getHandler();                     // called on first creation
H getHandler(H existingHandler);    // called on config change — return existing
                                    // if no change needed, or new instance
```

**Handler API (`AsyncLoadBalancingHandler`):**

```java
// com.pingidentity.pa.sdk.ha.lb.AsyncLoadBalancingHandler
CompletionStage<TargetHost> calculateTargetHost(
    Exchange exchange,             // the current request
    List<TargetHost> availableHosts,  // hosts currently UP
    List<TargetHost> configuredHosts  // all configured hosts
);

CompletionStage<Void> handleResponse(
    Exchange exchange,             // completed exchange
    TargetHost targetHost          // host that was selected
);
```

**SDK sample patterns:**

- **`BestEffortStickySessionPlugin`**: Server-side sticky sessions. Handler
  maintains a `ConcurrentHashMap<String, TargetHost>` mapping session IDs
  to target hosts. `handleResponse()` reads the upstream `Set-Cookie`
  session cookie and updates the map.

- **`MetricBasedPlugin`**: Queries an external monitoring API for capacity
  data and routes to the host with most remaining capacity. Demonstrates:
  - `HttpClient` injection via `getHttpClient()` on the plugin base
  - `CompletionStage` chaining with `.thenApply()` / `.exceptionally()`
  - Graceful degradation: falls back to random host on monitoring failure
  - Configuration change detection via `equals()` on Configuration objects

**Handler config change pattern (from `MetricBasedPlugin`):**
```java
@Override
public Handler getHandler(Handler existingHandler) {
    if (existingHandler.updateConfiguration(getConfiguration())) {
        return existingHandler;  // config unchanged — reuse
    }
    return getHandler();  // config changed — create fresh handler
}
```

### LocaleOverrideService

SPI for overriding the client's locale preference. If the returned list
is non-empty, PA uses these locales instead of the `Accept-Language` header.

```java
// com.pingidentity.pa.sdk.localization.LocaleOverrideService
List<Locale> getPreferredLocales(Exchange exchange);
// Returns ordered list of preferred locales. Empty = use Accept-Language.
```

**SDK sample (`CustomCookieLocaleOverrideService`):**
Reads a `custom-accept-language` cookie containing a BCP 47 language tag
(e.g., `nl-NL`) and returns it as the preferred locale.

### EntityScopedHandlerPlugin

```java
// com.pingidentity.pa.sdk.plugins.EntityScopedHandlerPlugin<T extends PluginConfiguration>
// Interface for plugins scoped to a specific entity (site or application).
// Extends ConfigurablePlugin<T> and DescribesUIConfigurable.
```

### MasterKeyEncryptor

```java
// com.pingidentity.sdk.key.MasterKeyEncryptor
// Interface for custom key encryption providers.
// Throws MasterKeyEncryptorException on errors.
```

---

## 17. Vendor-Built Plugin Analysis

> **Tags:** `vendor`, `internal`, `engine`, `built-in`
>
> These findings come from analysis of class signatures, SPI service files,
> and public API usage within `pingaccess-engine-9.0.1.0.jar`.
> They document implementation patterns used by PingIdentity's own built-in
> plugins. While these plugins use some engine-internal APIs not available in
> the SDK, they also use many SDK-public APIs and reveal best practices.

### Built-in Plugin Inventory (from engine SPI files)

| Extension Type | Count | Notable Implementations |
|---------------|-------|------------------------|
| Sync Rules | 28 | OAuthPolicyInterceptor, CIDRPolicy, RateLimiting, CORS, Rewrite |
| Async Rules | 4 | PingAuthorizePolicyDecision, PingDataGovernance |
| Identity Mappings | 5 | HeaderMapping, JWT, ClientCertificate, WebSessionAccessToken |
| Site Authenticators | 4 | BasicAuth, MutualTLS, TokenMediator, SAMLTokenMediator |
| Load Balancing | 2 | CookieBasedRoundRobin, HeaderBased |
| Availability | 1 | OnDemandAvailability |
| HSM Providers | 2 | AwsCloudHSM, PKCS11 |
| Risk Policy | 1 | PingOneRiskPolicy |
| Rejection Handlers | 2 | ErrorTemplate, Redirect |
| Token Providers | 1 | AzureTokenProvider |

### Key Patterns from Vendor Plugins

**1. PingAuthorizePolicyDecisionAccessControl** (async, extends `AsyncRuleInterceptorBase`)

The closest vendor plugin to our adapter. Key observations:

- Extends `AsyncRuleInterceptorBase` (the SDK base class), confirming this is
  the correct base for async rules.
- Uses `@UIElement` with `modelAccessor = ThirdPartyServiceAccessor.class` for
  the service target dropdown.
- Uses `ConfigurationBuilder.from(Configuration.class).toConfigurationFields()`
  — the annotation-driven auto-discovery pattern.
- Validates config in `configure()` using `CustomViolation` / `CustomViolationException`
  (engine-internal) for cross-field validation.
- `ObjectMapper` is injected via `@Inject`/setter, NOT created per-request.
- Request body is sent as `byte[]` via `objectMapper.writeValueAsBytes()`.
- Response body parsed via `objectMapper.readValue(clientResponse.getBody(), ...)` (byte[]).
- Error handling: `handleFailure()` returns `Outcome.RETURN` on `CompletionException`.
- `getConfiguration().getName()` used for error logging (admin-configured rule name).

**2. OutboundContentRewriteInterceptor** (sync, body rewriting)

- Extends internal `PolicyRuleInterceptorBase` (sync base, not available in SDK).
- Implements response body rewriting with gzip support and chunked encoding.
- Uses `@JsonIgnore` on `getErrorHandlingCallback()` to prevent serialization.
- Returns `new LocalizedInternalServerErrorCallback(localizedMessageResolver)`
  as the error callback.

**3. OutboundHeaderRewriteInterceptor** (sync, header rewriting)

- `@Rule(destination = {RuleInterceptorSupportedDestination.Site})` — Site-only.
- `ConfigurationBuilder.from()` + `ConfigurationFieldUtil.setHelpFromBundle()`
  — vendor pattern for externalized help text from ResourceBundle.

### Vendor Configuration Patterns (from engine Configuration classes)

```java
// Vendor pattern: full @UIElement usage with modelAccessor for dynamic dropdowns
@UIElement(label = "Third-Party Service", type = SELECT, order = 0,
           required = true, modelAccessor = ThirdPartyServiceAccessor.class)
@NotNull
private ThirdPartyServiceModel thirdPartyService;

// Vendor pattern: CONCEALED + advanced for secrets
@UIElement(label = "Shared Secret", type = CONCEALED, order = 20)
private String sharedSecret;

// Vendor pattern: CHECKBOX with defaultValue
@UIElement(label = "Include Request Body", type = CHECKBOX,
           order = 65, defaultValue = "true")
private boolean includeRequestBody = true;

// Vendor pattern: TABLE with subFieldClass and @Valid/@NotNull
@UIElement(label = "Mapped Attributes", type = TABLE, order = 70,
           subFieldClass = MappedAttributeTableEntry.class)
@Valid @NotNull
private List<MappedAttributeTableEntry> mappedPayloadAttributes;
```

### Internal-Only APIs (NOT available in SDK)

The following are used by vendor plugins but are **not** part of the public SDK:

| Internal Class | Purpose | SDK Alternative |
|---------------|---------|----------------|
| `PolicyRuleInterceptorBase` | Sync rule base class | `RuleInterceptorBase` |
| `ExchangeImpl` | Concrete exchange | `Exchange` (interface) |
| `CustomViolation`/`Exception` | Cross-field validation | `ValidationException` |
| `ConfigurationFieldUtil` | Help text from ResourceBundle | `ConfigurationBuilder.helpContent()` |
| `RejectionHandlerModel` | Rejection handler reference | `ErrorHandlerConfigurationImpl` |
| `RejectionHandlerAccessor` | Rejection handler dropdown | N/A |
| `BundleSupport` | ResourceBundle for plugin | `TemplateRenderer.getLocalizedMessageResolver()` |
| `ConflictAwareRuleInterceptor` | Rule conflict detection | N/A |

---

# Part VI — Reference

## 18. Supporting Types & Enumerations

> **Tags:** `Outcome`, `HttpStatus`, `Method`, `MediaType`, `SslData`, `TargetHost`, `SetCookie`

### Outcome (enum)

```java
// com.pingidentity.pa.sdk.interceptor.Outcome
CONTINUE  // Continue with the interceptor chain
RETURN    // Do not continue the interceptor chain, but start normal response handling:
          // All ResponseInterceptors in the interceptor chain up to this point will be
          // given a chance to handle the response IN REVERSE ORDER.
```

> **Important:** `RETURN` does NOT skip response interceptors — it triggers them
> in reverse order. This means `handleResponse()` will still be called even when
> `handleRequest()` returns `RETURN`.

### HttpStatus (class — NOT an enum)

`HttpStatus` is a **class with static final constants**, not an enum. It
supports custom/non-standard status codes and has a factory method.

```java
// com.pingidentity.pa.sdk.http.HttpStatus
// Construction
HttpStatus(int code, String message, LocalizedMessage localizedMessage);
HttpStatus(int code, String message);  // no localization
static HttpStatus forCode(int statusCode);  // lookup by code

// Methods
int getCode();
String getMessage();                    // standard Reason-Phrase
LocalizedMessage getLocalizedMessage(); // may be more suitable for end-user display
```

### Standard HTTP Status Constants

| Constant | Code | Constant | Code |
|----------|------|----------|------|
| `CONTINUE` | 100 | `SWITCHING_PROTOCOLS` | 101 |
| `OK` | 200 | `CREATED` | 201 |
| `ACCEPTED` | 202 | `NON_AUTHORITATIVE_INFORMATION` | 203 |
| `NO_CONTENT` | 204 | `RESET_CONTENT` | 205 |
| `PARTIAL_CONTENT` | 206 | `MULTIPLE_CHOICES` | 300 |
| `MOVED_PERMANENTLY` | 301 | `FOUND` | 302 |
| `SEE_OTHER` | 303 | `NOT_MODIFIED` | 304 |
| `USE_PROXY` | 305 | `TEMPORARY_REDIRECT` | 307 |
| `PERMANENT_REDIRECT` | 308 | `BAD_REQUEST` | 400 |
| `UNAUTHORIZED` | 401 | `PAYMENT_REQUIRED` | 402 |
| `FORBIDDEN` | 403 | `NOT_FOUND` | 404 |
| `METHOD_NOT_ALLOWED` | 405 | `NOT_ACCEPTABLE` | 406 |
| `PROXY_AUTHENTICATION_REQUIRED` | 407 | `REQUEST_TIMEOUT` | 408 |
| `CONFLICT` | 409 | `GONE` | 410 |
| `LENGTH_REQUIRED` | 411 | `PRECONDITION_FAILED` | 412 |
| `REQUEST_ENTITY_TOO_LARGE` | 413 | `REQUEST_URI_TOO_LONG` | 414 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `REQUESTED_RANGE_NOT_SATISFIABLE` | 416 |
| `EXPECTATION_FAILED` | 417 | `UNPROCESSABLE_ENTITY` | 422 |
| `PRECONDITION_REQUIRED` | 428 | `TOO_MANY_REQUESTS` | 429 |
| `REQUEST_HEADER_FIELDS_TOO_LARGE` | 431 | `INTERNAL_SERVER_ERROR` | 500 |
| `NOT_IMPLEMENTED` | 501 | `BAD_GATEWAY` | 502 |
| `SERVICE_UNAVAILABLE` | 503 | `GATEWAY_TIMEOUT` | 504 |
| `HTTP_VERSION_NOT_SUPPORTED` | 505 | `NETWORK_AUTHENTICATION_REQUIRED` | 511 |

### ⚠️ PingAccess-Specific Status Codes

```java
HttpStatus.ALLOWED               // 277 — PA internal "Allowed" status
HttpStatus.REQUEST_BODY_REQUIRED  // 477 — PA internal "Request Body Required"
```

These are non-standard HTTP status codes specific to PingAccess internals.

### Method (class — static constants)

```java
// com.pingidentity.pa.sdk.http.Method
static final Method GET, HEAD, POST, PUT, DELETE, CONNECT, OPTIONS, TRACE, PATCH;
static Method of(String methodName);   // factory for arbitrary methods
String getName();
```

### MediaType

```java
// com.pingidentity.pa.sdk.http.MediaType
static MediaType parse(String mediaTypeString);  // factory: throws IllegalArgumentException

String getBaseType();          // e.g., "application/json" (primary + sub, no params)
String getPrimaryType();       // e.g., "application"
String getSubType();           // e.g., "json"

boolean isPrimaryTypeWildcard();  // true if primary type is "*"
boolean isSubTypeWildcard();      // true if subtype is "*"
boolean match(MediaType other);   // wildcard-aware matching
```

### CommonMediaTypes

```java
// com.pingidentity.pa.sdk.http.CommonMediaTypes
static final MediaType TEXT_HTML;            // text/html
static final MediaType APPLICATION_JSON;     // application/json
```

### SslData

Per-connection, TLS/SSL-specific data. Available via `exchange.getSslData()`.

```java
// com.pingidentity.pa.sdk.ssl.SslData (since PA 4.3)
List<String> getSniServerNames();
// SNI server names sent by the client during the SSL/TLS handshake.
// Returns an unmodifiable list.

List<X509Certificate> getClientCertificateChain();
// Client certificate chain sent during the TLS/SSL handshake.
// Returns an unmodifiable list of X509Certificates.
```

> **Adapter usage:** Useful for mTLS-aware transforms. The client certificate
> subject/issuer could be exposed as a JSLT variable in future versions.

### TargetHost

```java
// com.pingidentity.pa.sdk.http.TargetHost
String getHost();
int    getPort();
boolean isSecure();
```

### SetCookie

```java
// com.pingidentity.pa.sdk.http.SetCookie
String getName();
String getValue();
String getDomain();
String getPath();
Instant getExpires();
long   getMaxAge();
boolean isSecure();
boolean isHttpOnly();
String  getSameSite();
```

---

## 19. Service Factories & Utilities

> **Tags:** `ServiceFactory`, `BodyFactory`, `HeadersFactory`, `HttpClient`, `TemplateRenderer`

### ServiceFactory

SPI discovery utility for creating SDK objects. Critical for tests when you
need real (not mocked) `Body` or `Headers` instances.

```java
// com.pingidentity.pa.sdk.services.ServiceFactory
static BodyFactory bodyFactory();
static HeadersFactory headersFactory();
static ConfigurationModelAccessorFactory configurationModelAccessorFactory();
static <T> T getSingleImpl(Class<T> serviceClass);
static <T> List<T> getImplInstances(Class<T> serviceClass);
```

### BodyFactory

```java
// com.pingidentity.pa.sdk.http.BodyFactory
Body createBody(byte[] content);       // create Body from bytes (copies array)
Body createBody(InputStream content);  // create Body from InputStream
Body createEmptyBody();                // create empty Body
```

### HeadersFactory

```java
// com.pingidentity.pa.sdk.http.HeadersFactory
Headers createHeaders();                    // create empty Headers
Headers createHeaders(Headers source);      // copy Headers (deep copy)
Headers createHeaders(List<HeaderField> fields);  // from header field list
```

### TemplateRenderer

Renders the templates defined in the `conf/template/` directory of the
PingAccess install. The current PA implementation uses Apache Velocity.

```java
// com.pingidentity.pa.sdk.util.TemplateRenderer
String render(Map<String, String> context, String templateName);
void render(Map<String, String> context, String templateName, Writer writer);

// Renders a template and produces a Response:
Response renderResponse(Exchange exc, Map<String, String> context,
                        String templateName, ResponseBuilder builder);
// Note: The Exchange is read-only — implementations will not mutate it.
// The ResponseBuilder IS mutated — the template output is set as the body.

LocalizedMessageResolver getLocalizedMessageResolver();
```

### HttpClient

Available via `AsyncRuleInterceptorBase.getHttpClient()` (async base) or
`AsyncLoadBalancingPluginBase.getHttpClient()` (LB base). Injected by PA.

```java
// com.pingidentity.pa.sdk.http.client.HttpClient

// Async — sends to a ThirdPartyServiceModel-defined host:
CompletionStage<ClientResponse> send(ClientRequest request, ThirdPartyServiceModel target);
// The target host, port, and TLS settings come from the ThirdPartyServiceModel.
// The ClientRequest's requestTarget is the path + query portion only.
```

> **Note:** The send method is **asynchronous** — it returns a `CompletionStage`,
> NOT a blocking result. Chain `.thenApply()` / `.thenCompose()` to process the
> response, and `.exceptionally()` to handle `HttpClientException`.

**Usage (from `RiskAuthorizationRule`):**
```java
return getHttpClient().send(accessTokenRequest, getConfiguration().getOAuthAuthorizationServer())
                      .thenApply(this::parseAccessToken);
```

### ClientRequest

```java
// com.pingidentity.pa.sdk.http.client.ClientRequest
ClientRequest(Method method, String requestTarget, Headers headers);             // no body (GET)
ClientRequest(Method method, String requestTarget, Headers headers, byte[] body); // with body (POST)

// Static factory:
static Headers createHeaders();   // creates a new mutable Headers instance for outbound requests

// Getters:
Method getMethod();
String getRequestTarget();   // path + query, e.g. "/as/token.oauth2"
byte[] getBody();            // may be null
Headers getHeaders();
```

**Usage (from `RiskAuthorizationRule`):**
```java
Headers headers = ClientRequest.createHeaders();
headers.setAuthorization(getClientAuthorization());
headers.setContentType(CommonMediaTypes.APPLICATION_FORM_URL);
byte[] body = "grant_type=client_credentials".getBytes(StandardCharsets.UTF_8);
ClientRequest request = new ClientRequest(Method.POST, "/as/token.oauth2", headers, body);
```

### ClientResponse

```java
// com.pingidentity.pa.sdk.http.client.ClientResponse
HttpStatus getStatus();     // HttpStatus, NOT int — use .equals(HttpStatus.OK) to compare
byte[]     getBody();       // raw bytes — use ObjectMapper.readTree()/readValue() to parse
Headers    getHeaders();
```

### HttpClientException

Wraps I/O failures from the HttpClient.

```java
// com.pingidentity.pa.sdk.http.client.HttpClientException
HttpClientException(HttpClientException.Type type, String message, Exception cause);

Type getType();   // error classification

// HttpClientException.Type enum:
REQUEST_TIMEOUT, CONNECTION_TIMEOUT, SERVICE_UNAVAILABLE, GENERAL_IO_ERROR
```

> **Async error handling:** When `send()` fails, the returned
> `CompletionStage` completes exceptionally with a `CompletionException`
> wrapping `HttpClientException`. Use `.exceptionally()` to recover:
> ```java
> .exceptionally(cause -> {
>     if (cause instanceof CompletionException ce &&
>         ce.getCause() instanceof HttpClientException) {
>         logger.warn("HTTP call failed", cause);
>         return Outcome.RETURN;  // graceful fallback
>     }
>     throw new IllegalStateException("Unexpected", cause);
> });
> ```

---

## 20. Complete SPI Inventory & Sources

> **Tags:** `SPI`, `matrix`, `services`, `artefacts`, `Java`, `coverage`

### Sync vs Async Decision

> **When to use Async:** Use async variants (`AsyncRuleInterceptor`,
> `AsyncSiteAuthenticatorInterceptor`, etc.) **only when** the plugin logic
> requires integrating with a third-party service via `HttpClient`. Async base
> classes provide `getHttpClient()`. If no `HttpClient` is needed, use the
> sync variant.

### Extension Point Matrix

| Extension Point | Variant | SPI Interface | Base Class | Annotation |
|----------------|---------|---------------|------------|------------|
| **Rule** | Sync | `RuleInterceptor<T>` | `RuleInterceptorBase<T>` | `@Rule` |
| | Async | `AsyncRuleInterceptor<T>` | `AsyncRuleInterceptorBase<T>` | `@Rule` |
| **Site Authenticator** | Sync | `SiteAuthenticatorInterceptor<T>` | `SiteAuthenticatorInterceptorBase<T>` | `@SiteAuthenticator` |
| | Async | `AsyncSiteAuthenticatorInterceptor<T>` | `AsyncSiteAuthenticatorInterceptorBase<T>` | `@SiteAuthenticator` |
| **Identity Mapping** | Sync | `IdentityMappingPlugin<T>` | `IdentityMappingPluginBase<T>` | `@IdentityMapping` |
| | Async | `AsyncIdentityMappingPlugin<T>` | `AsyncIdentityMappingPluginBase<T>` | `@IdentityMapping` |
| **Load Balancing** | Sync | `LoadBalancingPlugin<H,T>` | `LoadBalancingPluginBase<H,T>` | `@LoadBalancingStrategy` |
| | Async | `AsyncLoadBalancingPlugin<H,T>` | `AsyncLoadBalancingPluginBase<H,T>` | `@LoadBalancingStrategy` |
| **Locale Override** | Sync only | `LocaleOverrideService` | *(none)* | *(none)* |

> ⚠️ **LocaleOverrideService limitation:** The LocaleOverrideService **cannot**
> use third-party service integration. There is no async variant and no base
> class with `HttpClient` access. This is the only extension point with this
> restriction.

#### Additional base class: `HeaderIdentityMappingPlugin`

For Identity Mapping plugins that need to set headers from identity attributes,
the SDK provides `HeaderIdentityMappingPlugin` as a convenience base class
(extends `IdentityMappingPluginBase`). It handles the common pattern of mapping
identity attributes to request headers via `AttributeHeaderPair` configuration.

### SPI Registration Files (`META-INF/services/`)

Each extension type requires its own provider-configuration file:

| Extension Type | Provider-Configuration File |
|---------------|----------------------------|
| Sync Rule | `META-INF/services/com.pingidentity.pa.sdk.policy.RuleInterceptor` |
| Async Rule | `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor` |
| Sync Site Authenticator | `META-INF/services/com.pingidentity.pa.sdk.siteauthenticator.SiteAuthenticatorInterceptor` |
| Async Site Authenticator | `META-INF/services/com.pingidentity.pa.sdk.siteauthenticator.AsyncSiteAuthenticatorInterceptor` |
| Sync Identity Mapping | `META-INF/services/com.pingidentity.pa.sdk.identitymapping.IdentityMappingPlugin` |
| Async Identity Mapping | `META-INF/services/com.pingidentity.pa.sdk.identitymapping.AsyncIdentityMappingPlugin` |
| Sync Load Balancing | `META-INF/services/com.pingidentity.pa.sdk.ha.lb.LoadBalancingPlugin` |
| Async Load Balancing | `META-INF/services/com.pingidentity.pa.sdk.ha.lb.AsyncLoadBalancingPlugin` |
| Locale Override | `META-INF/services/com.pingidentity.pa.sdk.localization.LocaleOverrideService` |

### Java Version Compatibility

PingAccess 9.0 officially supports **Java 17 and 21** (Amazon Corretto, OpenJDK,
Oracle JDK — all 64-bit). The adapter module compiles with **Java 21**, matching
the core module. No cross-compilation needed.

> Source: PingAccess 9.0 docs § "Java runtime environments" (page 78)

### SDK Class Coverage

Total SDK classes (excluding inner): **112** • Documented: **112** (100%)

All publicly exported classes in `pingaccess-sdk-9.0.1.0.jar` are documented in
this guide. The 5 previously undocumented classes were added in this revision:
`DependantFieldAccessor`, `DependantFieldAccessorFactory`,
`ConfigurationDependentFieldOption`, `DynamicConfigurationParentField`,
`DynamicOptionsConfigurationField`.

### SDK Artefact Locations

| Artefact | Path |
|----------|------|
| SDK JAR (binary) | `binaries/pingaccess/dist/pingaccess-9.0.1/sdk/` |
| SDK Javadoc (HTML) | `binaries/pingaccess/dist/pingaccess-9.0.1/sdk/apidocs/` |
| SDK sample rules | `binaries/pingaccess/dist/pingaccess-9.0.1/sdk/samples/` |
| Local SDK JAR copy | `docs/reference/vendor/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` |
| Engine JAR (vendor plugins) | `binaries/pingaccess/dist/pingaccess-9.0.1/lib/pingaccess-engine-9.0.1.0.jar` |
