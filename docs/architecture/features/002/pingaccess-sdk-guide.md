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
| Source | Official SDK Javadoc (`binaries/pingaccess-9.0.1/sdk/apidocs/`) + sample rules + official docs chapter |
| Related spec | `docs/architecture/features/002/spec.md` (co-located) |

---

## Topic Index

| # | Section | One-liner | Tags |
|---|---------|-----------|------|
| 1 | [AsyncRuleInterceptor SPI](#1-asyncruleinterceptor-spi) | The plugin interface your adapter implements | `plugin`, `SPI`, `interceptor`, `lifecycle` |
| 2 | [Exchange](#2-exchange) | The request/response envelope passed to every handler | `exchange`, `request`, `response`, `identity` |
| 3 | [Message / Request / Response](#3-message--request--response) | Reading and writing HTTP bodies, headers, status | `message`, `body`, `headers`, `request`, `response` |
| 4 | [Body](#4-body) | HTTP message body — stateful, streamable, memory-aware | `body`, `streaming`, `memory`, `scalability` |
| 5 | [Headers](#5-headers) | Full header API — generic, typed, and CORS accessors | `headers`, `CORS`, `cookies`, `content-type` |
| 6 | [Identity & Session Context](#6-identity--session-context) | Authentication context: user, claims, OAuth, session state | `identity`, `session`, `OAuth`, `OIDC` |
| 7 | [Plugin Configuration & UI](#7-plugin-configuration--ui) | @UIElement, ConfigurationBuilder, Bean Validation | `configuration`, `UIElement`, `admin`, `validation` |
| 8 | [ResponseBuilder & Error Handling](#8-responsebuilder--error-handling) | Building responses, DENY mode, ErrorHandlingCallback | `response`, `error`, `DENY`, `ResponseBuilder` |
| 9 | [Deployment & Classloading](#9-deployment--classloading) | Shadow JAR, Jackson relocation, SPI registration | `shadow`, `JAR`, `Jackson`, `relocation` |
| 10 | [Thread Safety](#10-thread-safety) | Init-time vs per-request state, concurrency model | `thread`, `concurrency`, `mutable` |
| 11 | [Testing Patterns](#11-testing-patterns) | Mockito mock chains, config validation, dependency alignment | `test`, `Mockito`, `mock`, `Validator` |
| 12 | [Supporting Types](#12-supporting-types) | Outcome, HttpStatus, Method, ExchangeProperty, ServiceFactory | `Outcome`, `HttpStatus`, `ServiceFactory` |
| 13 | [SSL & Network Types](#13-ssl--network-types) | SslData, TargetHost, SetCookie | `SSL`, `TLS`, `cookie`, `network` |
| 14 | [OAuth SDK](#14-oauth-sdk) | OAuthConstants, OAuthUtilities, WWW-Authenticate building | `OAuth`, `DPoP`, `RFC6750`, `constants` |
| 15 | [Other Extension Points](#15-other-extension-points) | IdentityMapping, SiteAuthenticator, LoadBalancing, LocaleOverride, MasterKeyEncryptor | `extension`, `plugin`, `identity`, `encryption`, `locale` |
| 16 | [Vendor-Built Plugin Analysis](#16-vendor-built-plugin-analysis) | Patterns from PA engine's built-in plugins | `vendor`, `internal`, `engine`, `built-in` |
| 17 | [Development Setup & Plugin Creation](#17-development-setup--plugin-creation) | SDK directory structure, 7-step creation procedure, Maven/Gradle | `setup`, `Maven`, `Gradle`, `create`, `deploy` |
| 18 | [Behavioral Notes](#18-behavioral-notes) | Request immutability in response phase, logging | `behavior`, `constraints`, `logging` |

---

## 1. AsyncRuleInterceptor SPI

> **Tags:** `plugin`, `SPI`, `interceptor`, `lifecycle`, `AsyncRuleInterceptorBase`

A `AsyncRuleInterceptor` is the runtime instantiation of a Rule defined in the
Administrative API that supports applying access control logic as an asynchronous
computation. Your adapter extends `AsyncRuleInterceptorBase<T>` which provides
sensible defaults.

> **When to use Async vs Sync:** Per the SDK Javadoc, `AsyncRuleInterceptor`
> should only be used when the logic of the Rule requires using a `HttpClient`.
> If no `HttpClient` is needed, use `RuleInterceptor` instead. Our adapter uses
> async because future features may require HTTP callouts.

### Interceptor (tagging interface)

```java
// com.pingidentity.pa.sdk.interceptor.Interceptor
// An Interceptor is the "receiving object" for an Exchange, following the
// Chain-of-responsibility pattern. This is a tagging interface to facilitate
// organizing Interceptors in collections. Implementations should implement
// a sub-interface (e.g., AsyncRuleInterceptor, RuleInterceptor).
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
// Interface for describing the expected schema for a plugin's configuration,
// for use by the Administrative API and UI.
List<ConfigurationField> getConfigurationFields();
// Returns an ordered set of ConfigurationFields (most easily built with ConfigurationBuilder)
```

### `ConfigurablePlugin<T>` interface

```java
// com.pingidentity.pa.sdk.plugins.ConfigurablePlugin<T extends PluginConfiguration>
void configure(T configuration) throws ValidationException;
// Called to provide the configuration from the UI/Administrative API.
// Throws jakarta.validation.ValidationException for config validation errors.

T getConfiguration();
// Retrieves the configuration provided to this ConfigurablePlugin.
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
5. **`configure()` call:** `ConfigurablePlugin.configure(PluginConfiguration)` is
   called, giving the plugin a chance to post-process configuration.
6. **Validation:** `Validator.validate(Object, Class[])` is invoked on the
   `PluginConfiguration`. Since PA 5.0, validation runs **before** `configure()`
   was called in the old model. PingAccess now validates **before** `configure()`
   is called, so constraint annotations on PluginConfiguration fields are
   guaranteed to be enforced before the plugin receives them.
7. **Available:** The instance is made available to service end-user requests via
   `handleRequest(Exchange)` and `handleResponse(Exchange)`.

**Teardown:** No explicit destroy lifecycle. Plugins are garbage-collected on PA
restart. Clean up resources (if any) using `@PreDestroy` or finalizers.

> **Logging:** Use the **SLF4j API** for all logging in your plugin module.
> PingAccess ships its own SLF4J provider — do NOT bundle a second SLF4J
> implementation (causes classpath conflict). See [§9 Deployment](#9-deployment--classloading).

### Injection

Before plugins are put into use, rules, SiteAuthenticators, and their
`PluginConfiguration` instances are passed through Spring's Autowiring and
initialization (see Lifecycle step 2 above).

> ⚠️ **Future-proofing:** To protect your code against changes in PingAccess
> internals, **do not use Spring as a direct dependency**. Use the annotation
> `javax.inject.Inject` for any injection instead of Spring-specific annotations
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
// Site-only rule (our adapter uses this — we need handleResponse + body access)
@Rule(destination = { RuleInterceptorSupportedDestination.Site }, ...)

// Agent-only rule
@Rule(destination = { RuleInterceptorSupportedDestination.Agent }, ...)

// Both (default if destination is omitted)
@Rule(destination = { RuleInterceptorSupportedDestination.Site,
                      RuleInterceptorSupportedDestination.Agent }, ...)
```

> **Our adapter's choice:** We use `destination = {Site}` because we require:
> (1) `handleResponse()` for response-phase transforms, (2) request body access
> for body-based transforms, and (3) destination host information. All three are
> unavailable to Agent rules.

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

## 2. Exchange

> **Tags:** `exchange`, `request`, `response`, `identity`, `PolicyConfiguration`, `ExchangeProperty`

The `Exchange` is the central envelope passed to `handleRequest` and `handleResponse`.
It holds the request, response, identity, and exchange-scoped properties.

```java
// com.pingidentity.pa.sdk.http.Exchange
Request  getRequest();           void setRequest(Request);
Response getResponse();          void setResponse(Response);  // null until site responds
Identity getIdentity();          // null for unauthenticated requests
SslData  getSslData();           // TLS/SSL connection data (certs, SNI)
String   getOriginalRequestUri();
String   getSourceIp();
String   getUserAgentHost();
String   getUserAgentProtocol();
String   getTargetScheme();
List<TargetHost> getTargetHosts();    // backend target host(s)
Instant  getCreationTime();
Locale   getResolvedLocale();
PolicyConfiguration getPolicyConfiguration();   // application + resource metadata

// Exchange properties — strongly-typed cross-rule state
<T> Optional<T> getProperty(ExchangeProperty<T> property);
<T> T getRequiredProperty(ExchangeProperty<T> property);    // throws if absent
boolean isPropertyTrue(ExchangeProperty<Boolean> property); // safe boolean check
default void removeProperty(ExchangeProperty<?> property);  // equivalent to set(null)
<T> void setProperty(ExchangeProperty<T> property, T value);
```

### Critical: `getResponse()` is null during request phase

During `handleRequest()`, the backend has not yet responded, so
`exchange.getResponse()` returns **`null`**. Any attempt to call methods on it
will throw `NullPointerException`. See [§8 ResponseBuilder](#8-responsebuilder--error-handling)
for how to handle this.

### ExchangeProperty<T>

A strongly-typed key for get/set properties on an Exchange. Created via a
static factory method with a namespace (typically reverse domain name) and identifier.

```java
// com.pingidentity.pa.sdk.http.ExchangeProperty<T>
static <T> ExchangeProperty<T> create(String namespace, String identifier, Class<T> type);
String getNamespace();
String getIdentifier();
String getKey();          // "[namespace]:[identifier]"
Class<T> getType();

// Equality: two ExchangeProperty instances are equal if they have the same
// namespace and identifier, REGARDLESS of their type. This allows differently
// typed properties to refer to the same value if types are compatible.
```

**Usage example:**

```java
// Define a typed exchange property
private static final ExchangeProperty<TransformResult> TRANSFORM_RESULT =
    ExchangeProperty.create("io.messagexform", "transformResult", TransformResult.class);

// Store in handleRequest
exchange.setProperty(TRANSFORM_RESULT, result);

// Retrieve in handleResponse
Optional<TransformResult> cached = exchange.getProperty(TRANSFORM_RESULT);
```

### PolicyConfiguration

Provides details about the application and resource for the current Exchange.

```java
// com.pingidentity.pa.sdk.policy.PolicyConfiguration
Application getApplication();
Resource    getResource();
```

### Application

```java
// com.pingidentity.pa.sdk.policy.Application
int             getId();
String          getName();
String          getDescription();
String          getContextRoot();
ApplicationType getApplicationType();
boolean         isCaseSensitivePath();
String          getDefaultAuthType();
String          getSpaSupportEnabled();
String          getWebStorageType();
int             getSiteId();
int             getVirtualHostId();
```

### ApplicationType (enum)

```java
// Available values:
Web, API, SinglePageApplication
```

### Resource

```java
// com.pingidentity.pa.sdk.policy.Resource
int     getId();
String  getName();
PathPattern getPathPattern();
List<Method> getMethods();
```

### PathPattern

```java
// com.pingidentity.pa.sdk.policy.PathPattern
String getPattern();
PathPattern.Type getType();

// PathPattern.Type enum:
WILDCARD, REGEX
```

> Useful for context-aware transforms (e.g., different behavior per application).
> Not exposed in v1 `$session` but available for future `$context` enrichment.

---

## 3. Message / Request / Response

> **Tags:** `message`, `body`, `headers`, `request`, `response`, `Body`, `Headers`

### Message (parent of Request and Response)

`Message` defines the representation for an HTTP message. Both `Request` and
`Response` extend this interface.

```java
// com.pingidentity.pa.sdk.http.Message
Body    getBody();                 // never null — returns the body of this Message
void    setBody(Body body);        // ⚠️ does NOT update Content-Length
void    setBodyContent(byte[] c);  // replaces body + auto-updates Content-Length (copies array)
Headers getHeaders();              void setHeaders(Headers srcHeaders);
String  getStartLine();            // HTTP start line
String  getVersion();              // "1.0" or "1.1"
boolean isKeepAlive();
```

> **⚠️ `setBody()` vs `setBodyContent()`:** Per the Javadoc, `setBody()` does
> **NOT** update the `Content-Length` header. Use `setBodyContent(byte[])` instead
> — it updates `Content-Length` automatically. The provided byte array is copied.
>
> **`setHeaders()` validation:** Throws `IllegalArgumentException` if the
> specified `Headers` object was not created with the provided `HeadersFactory`
> (see `ServiceFactory.headersFactory()`).
>
> **Agent request caveat:** When processing an Agent Request from a PingAccess
> agent, the headers of the Message might indicate a non-empty body should be
> returned, but an empty body will be returned.

### Request

```java
// com.pingidentity.pa.sdk.http.Request extends Message
Method getMethod();              void setMethod(Method method);
String getUri();                 void setUri(String uri);

// URI is the path + query component of the URL, e.g.:
//   /application/resource?key1=value1&key2=value2

Map<String, String[]> getQueryStringParams() throws URISyntaxException;
// Parses and returns query string parameters.
// Throws URISyntaxException if the URI is badly formed.
```

> **Note:** `getUri()` returns a `String` (not `java.net.URI`). It is the path
> and query component only, not the full URL.

### Response

```java
// com.pingidentity.pa.sdk.http.Response extends Message
HttpStatus getStatus();           void setStatus(HttpStatus status);
int        getStatusCode();       // convenience: getStatus().getCode()
String     getStatusMessage();    // convenience: getStatus().getMessage()
```

---

## 4. Body

> **Tags:** `body`, `streaming`, `memory`, `scalability`, `read`, `InputStream`

A representation of the body content of an HTTP message. **Body is a stateful
object** — understanding its state model is critical for correct adapter operation.

```java
// com.pingidentity.pa.sdk.http.Body
void         read() throws AccessException, IOException;
boolean      isRead();          // true if body content has been read (streaming or in-memory)
boolean      isInMemory();      // true if body content is available in memory
byte[]       getContent();      // returns COPY of body bytes (requires isInMemory() == true)
InputStream  newInputStream();  // returns InputStream (requires isInMemory() == true)
int          getLength();       // body length in bytes (requires isInMemory() == true)
Map<String, String[]> parseFormParams();  // parses application/x-www-form-urlencoded body
String       toString();        // debugging: UTF-8 string of content, or "" if not in memory
```

### ⚠️ Scalability Warning (from Javadoc)

> **"Care needs to be taken when reading a HTTP message body into memory because
> doing so could impact the scalability of PingAccess. If possible, HTTP message
> bodies should not be read into memory."**

### State Model

PingAccess by default **streams** a message body from client to server (and
vice versa) **without** reading the complete body into memory.

| State | `isRead()` | `isInMemory()` | Methods available |
|-------|-----------|-----------------|-------------------|
| Not yet consumed | `false` | `false` | Only `read()` |
| After streaming (default) | `true` | `false` | None — body content consumed |
| After `read()` | `true` | `true` | `getContent()`, `newInputStream()`, `getLength()`, `parseFormParams()` |

- **`read()`** — Reads body content into memory. If the body size exceeds PA's
  configured maximum, throws `AccessException`. If already read, returns
  immediately (no-op). This is the **only** way to make the body available for
  `getContent()`.
- **`getContent()`** — Returns a **copy** of the body as `byte[]`. Throws
  `IllegalStateException` if `isInMemory()` is `false`. To avoid the copy
  overhead, use `newInputStream()`.
- **`newInputStream()`** — Returns an `InputStream` over the in-memory body.
  Throws `IllegalStateException` if `isInMemory()` is `false`.
- **`getLength()`** — Returns body length in bytes. Throws
  `IllegalStateException` if `isInMemory()` is `false`.
- **`parseFormParams()`** — Parses `application/x-www-form-urlencoded` content.
  Assumes UTF-8 encoding. Throws `IllegalStateException` if body is not in
  memory, `IllegalArgumentException` if content is not valid form-encoded.

> **Adapter implication:** The adapter MUST call `body.read()` before calling
> `body.getContent()`. The adapter does not impose additional limits beyond the
> core engine's `maxOutputSizeBytes`.

---

## 5. Headers

> **Tags:** `headers`, `CORS`, `cookies`, `content-type`, `HeaderField`, `HeaderName`

The `Headers` interface provides a comprehensive API for HTTP header
manipulation. Headers are case-insensitive per RFC 7230.

### Generic Header Access

```java
// com.pingidentity.pa.sdk.http.Headers

// Read
List<HeaderField> getHeaderFields();           // all header fields in this object
String   getFirstValue(String name);           // first value, comma-separated split
String   getLastValue(String name);            // last value of last field
String   getValue(String name);                // first value (alias)
String[] getValues(String name);               // all values for a name
Map<String, String[]> asMap();                 // all headers (name → values[])
List<String> getModifiedHeaderNames();         // names added/modified since creation

// Write
void     add(String name, String value);       // add a header field
void     add(HeaderName name, String value);   // add with HeaderName
void     setValue(String name, String value);   // set single value (replaces all)
void     setValue(String name, List<String> values); // alias for setValues
void     setValues(String name, List<String> values); // replace all values
boolean  removeFields(String name);            // remove all fields with name
```

### Typed Header Accessors

The SDK provides getter/setter pairs for common HTTP headers. Each getter
returns `null` (for single-value) or empty `List` (for multi-value) if absent.
Multi-value setters with an empty `List` remove the header.

| Header | Getter | Setter | Type |
|--------|--------|--------|------|
| Accept | `getAccept()` | `setAccept(List<String>)` | `List<String>` |
| Authorization | `getAuthorization()` | `setAuthorization(String)` | `String` |
| Cache-Control | `getCacheControl()` | `setCacheControl(List<String>)` | `List<String>` |
| Content-Disposition | `getContentDisposition()` | `setContentDisposition(String)` | `String` |
| Content-Encoding | `getContentEncoding()` | `setContentEncoding(List<String>)` | `List<String>` |
| Content-Length | `getContentLength()` | `setContentLength(long)` | `long` |
| Content-Location | `getContentLocation()` | `setContentLocation(String)` | `String` |
| Content-Type | `getContentType()` | `setContentType(String)` | `MediaType` / `String` |
| Date | `getDate()` | — | `Instant` |
| Host | `getHost()` | — | `String` |
| Last-Modified | `getLastModified()` | — | `Instant` |
| Location | `getLocation()` | `setLocation(String)` | `String` |
| Origin | `getOrigin()` | `setOrigin(String)` | `String` |
| Pragma | `getPragma()` | `setPragma(List<String>)` | `List<String>` |
| Set-Cookie | `getSetCookies()` | — | `List<SetCookie>` |
| Transfer-Encoding | `getTransferEncoding()` | `setTransferEncoding(List<String>)` | `List<String>` |
| Vary | `getVary()` | `setVary(List<String>)` | `List<String>` |
| WWW-Authenticate | `getWwwAuthenticate()` | — | `String` |
| X-Forwarded-For | `getXForwardedFor()` | — | `String` |
| X-Forwarded-Host | `getXForwardedHost()` | — | `String` |
| X-Forwarded-Port | `getXForwardedPort()` | — | `String` |
| X-Forwarded-Proto | `getXForwardedProto()` | — | `String` |

### CORS Header Accessors

Full CORS support with dedicated getter/setter pairs:

```java
// CORS headers — all have get/set pairs
String getAccessControlAllowCredentials();   void setAccessControlAllowCredentials(String);
List<String> getAccessControlAllowHeaders(); void setAccessControlAllowHeaders(List<String>);
List<String> getAccessControlAllowMethods(); void setAccessControlAllowMethods(List<String>);
String getAccessControlAllowOrigin();        void setAccessControlAllowOrigin(String);
List<String> getAccessControlExposeHeaders();void setAccessControlExposeHeaders(List<String>);
String getAccessControlMaxAge();             void setAccessControlMaxAge(String);
List<String> getAccessControlRequestHeaders(); void setAccessControlRequestHeaders(List<String>);
String getAccessControlRequestMethod();      void setAccessControlRequestMethod(String);
```

### Cookie Access

```java
Map<String, String[]> getCookies();          // all cookies (from Cookie header fields)
String getFirstCookieValue(String name);     // first cookie value by name
Charset getCharset();                        // charset from Content-Type header
```

### HeaderField and HeaderName

```java
// com.pingidentity.pa.sdk.http.HeaderField
HeaderName getName();
String     getValue();

// com.pingidentity.pa.sdk.http.HeaderName
// An immutable, case-insensitive HTTP header name per RFC 7230.
HeaderName(String name);   // trims whitespace, throws if null or empty
boolean equals(String s);  // case-insensitive comparison with String
```

---

## 6. Identity & Session Context

> **Tags:** `identity`, `session`, `OAuth`, `OIDC`, `claims`, `SessionStateSupport`, `OAuthTokenMetadata`, `JsonNode`

### Identity

An `Identity` object contains attributes and associated values derived from an
authentication token issued by a token provider. The actual token can be a PA
JWT cookie or an OAuth token.

```java
// com.pingidentity.pa.sdk.identity.Identity
String  getSubject();             // authenticated user principal
String  getMappedSubject();       // subject set by IdentityMappingPlugin (may be null/empty)
void    setMappedSubject(String); // used by IdentityMapping — adapter should NOT call this
String  getTrackingId();          // PA tracking ID (for logging only, NOT globally unique)
String  getTokenId();             // unique ID presented in the authentication token
Instant getTokenExpiration();     // token expiry (java.time.Instant, NOT Date)
JsonNode getAttributes();        // JSON identity attributes (OIDC/token claims)
SessionStateSupport getSessionStateSupport();  // persistent session key-value store
OAuthTokenMetadata  getOAuthTokenMetadata();   // null if OAuth is not used
```

> **`exchange.getIdentity()` may return `null`** for unauthenticated/anonymous
> resources. Always null-check before accessing identity fields.
>
> **`getAttributes()` returns an immutable snapshot:** Modifications to the
> returned `JsonNode` will NOT update the identity's attributes.
>
> **`getMappedSubject()`** may be `null` if identity mapping was not configured
> for the request, or an empty string if the `IdentityMappingPlugin` was unable
> to find a mapped subject.

### SessionStateSupport

Provides access to persistent data across exchanges. Session state data is stored
in a cookie. Requires a web application with a web session configured.

```java
// com.pingidentity.pa.sdk.identity.SessionStateSupport
Map<String, JsonNode> getAttributes();         // unmodifiable map (snapshot)
Set<String>           getAttributeNames();     // unmodifiable set (snapshot)
JsonNode              getAttributeValue(String name);  // single attribute or null
void                  setAttribute(String name, JsonNode value);
void                  removeAttribute(String name);
```

> **Caveats from SDK Javadoc:**
> - `getAttributes()` and `getAttributeNames()` return **unmodifiable snapshots**.
>   Later `setAttribute`/`removeAttribute` calls are NOT reflected in already-returned maps.
>   Modifying the returned Map/Set throws `UnsupportedOperationException`.
> - Server-side attribute caching may cause cache entries to be deleted if cache
>   limits are exceeded. Implementers should be prepared to re-acquire data.
> - In a cluster, requests to other nodes may result in a **cache miss**.
> - API-configured applications may **not support** persistent session state.
>
> **Adapter usage:** The adapter reads session attributes as read-only context
> for JSLT transforms. Writing to session state is out of scope for v1.

### OAuthTokenMetadata

The `OAuthTokenMetadata` provides access to additional details about an OAuth
token apart from the attributes on `Identity`.

```java
// com.pingidentity.pa.sdk.identity.OAuthTokenMetadata
String      getClientId();     // client_id of the OAuth client (null if not present)
Set<String> getScopes();       // granted OAuth scopes (empty if not present)
String      getTokenType();    // e.g., "Bearer"
String      getRealm();        // OAuth realm associated with the application (may be null)
Instant     getExpiresAt();    // calculated expiration from expires_in (null if not present)
Instant     getRetrievedAt();  // when PingAccess received the access token response
```

> **Null handling:** `getOAuthTokenMetadata()` returns `null` if OAuth is not
> used for authentication. Individual fields may also be null.

### Building $session — Flat Merge Pattern

The adapter builds a flat `$session` JsonNode by merging four layers. Later
layers override earlier layers on key collision:

| Layer | Source | Contents | Precedence |
|-------|--------|----------|------------|
| 1 (base) | `Identity` getters | `subject`, `mappedSubject`, `trackingId`, `tokenId`, `tokenExpiration` | Lowest |
| 2 | `OAuthTokenMetadata` | `clientId`, `scopes`, `tokenType`, `realm`, `tokenExpiresAt`, `tokenRetrievedAt` | |
| 3 | `Identity.getAttributes()` | OIDC claims / token introspection (spread into flat object) | |
| 4 (top) | `SessionStateSupport.getAttributes()` | Dynamic session state (spread into flat object) | Highest |

**Why this precedence?** Session state (L4) is the most dynamic — rules can
write to it during the session. OIDC claims (L3) override PA identity fields
(L1) because claims are the authoritative token data.

```java
// Build flat $session JsonNode from Exchange.getIdentity()
private JsonNode buildSessionContext(Identity identity) {
    if (identity == null) return null;

    ObjectNode session = objectMapper.createObjectNode();

    // Layer 1: PA identity fields (base)
    session.put("subject", identity.getSubject());
    session.put("mappedSubject", identity.getMappedSubject());
    session.put("trackingId", identity.getTrackingId());
    session.put("tokenId", identity.getTokenId());
    if (identity.getTokenExpiration() != null) {
        session.put("tokenExpiration", identity.getTokenExpiration().toString());
    }

    // Layer 2: OAuth metadata
    OAuthTokenMetadata oauth = identity.getOAuthTokenMetadata();
    if (oauth != null) {
        session.put("clientId", oauth.getClientId());
        session.put("tokenType", oauth.getTokenType());
        session.put("realm", oauth.getRealm());
        if (oauth.getExpiresAt() != null) {
            session.put("tokenExpiresAt", oauth.getExpiresAt().toString());
        }
        if (oauth.getRetrievedAt() != null) {
            session.put("tokenRetrievedAt", oauth.getRetrievedAt().toString());
        }
        ArrayNode scopesNode = objectMapper.createArrayNode();
        if (oauth.getScopes() != null) {
            oauth.getScopes().forEach(scopesNode::add);
        }
        session.set("scopes", scopesNode);
    }

    // Layer 3: OIDC claims / token attributes (SPREAD into flat object)
    JsonNode attributes = identity.getAttributes();
    if (attributes != null && attributes.isObject()) {
        attributes.fields().forEachRemaining(entry ->
            session.set(entry.getKey(), entry.getValue())  // overrides layers 1-2 on collision
        );
    }

    // Layer 4: Session state (SPREAD into flat object — highest precedence)
    SessionStateSupport sss = identity.getSessionStateSupport();
    if (sss != null && sss.getAttributes() != null) {
        sss.getAttributes().forEach((key, value) ->
            session.set(key, value)  // overrides all previous layers on collision
        );
    }

    return session;
}
```

### Boundary Conversion (Jackson relocation)

When Jackson is relocated in the shadow JAR (which is mandatory — see §9),
`Identity.getAttributes()` returns a PA-classloader `JsonNode` while the adapter
uses a shaded `JsonNode`. These are different classes. Convert at the boundary:

```java
// Boundary conversion: PA JsonNode → shaded JsonNode
byte[] raw = paObjectMapper.writeValueAsBytes(identity.getAttributes());
JsonNode shadedNode = ourObjectMapper.readTree(raw);
```

This conversion is done once per request in `buildSessionContext()` and is
negligible in cost (<1ms for typical OIDC claim payloads).

---

## 7. Plugin Configuration & UI

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
// The HeaderRule sample extends this for its Configuration inner class.
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
// From AllUITypesAnnotationRuleConfiguration sample
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
// Used by Clobber404ResponseRule, RiskAuthorizationRule
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
    // Creates a SELECT with modelAccessor=KeyPairAccessor.class
trustedCertificatesConfigurationField(String name, String label);
    // Creates a SELECT with modelAccessor=TrustedCertificateGroupAccessor.class

// Help
help();                                  // create empty Help
helpContent(String);
helpTitle(String);
helpURL(String);

// Output
List<ConfigurationField> toConfigurationFields();
clear();
```

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
// Factory loaded via ServiceLoader; engine provides DependantFieldAccessorFactoryImpl.

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

> **Warning from Javadoc:** "You must provide a `configurationField` to perform
> operations upon. Ex. Calling `required()` before calling
> `configurationField(...)` will cause an NPE."

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

## 8. ResponseBuilder & Error Handling

> **Tags:** `response`, `error`, `DENY`, `ResponseBuilder`, `NPE`, `AccessException`, `ErrorHandlingCallback`

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

### ⚠️ CRITICAL: DENY Mode in handleRequest — Response is NULL

During `handleRequest()`, `exchange.getResponse()` is **`null`** because the
backend has not yet responded. You MUST use `ResponseBuilder` to construct a new
`Response` and set it on the exchange:

```java
// DENY mode — handleRequest error path
byte[] errorBody = result.errorResponse().getBytes(StandardCharsets.UTF_8);
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
byte[] errorBody = result.errorResponse().getBytes(StandardCharsets.UTF_8);
exchange.getResponse().setBodyContent(errorBody);  // auto-updates Content-Length
exchange.getResponse().setStatus(HttpStatus.BAD_GATEWAY);
exchange.getResponse().getHeaders().setContentType("application/problem+json");
```

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
// Information about an error used to generate an HTTP response.
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
// The exchange is both read from and modified.
```

### RuleInterceptorErrorHandlingCallback

The SDK's built-in error callback implementation. Reads `ErrorHandlerConfiguration`
to determine the HTTP status, template file, content type, and status message.

```java
// com.pingidentity.pa.sdk.policy.error.RuleInterceptorErrorHandlingCallback
// Constructors:
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

### SDK Sample References

- `Clobber404ResponseRule.handleResponse()` uses `ResponseBuilder.notFound()` +
  `getTemplateRenderer().renderResponse(exchange, ...)` to construct a new response.
- `RiskAuthorizationRule.handleRequest()` uses `Outcome.RETURN` with
  `POLICY_ERROR_INFO` to delegate to ErrorHandlingCallback.
- `WriteResponseRule` throws `AccessException` to trigger
  `getErrorHandlingCallback().writeErrorResponse(exchange)`.

---

## 9. Deployment & Classloading

> **Tags:** `shadow`, `JAR`, `Jackson`, `relocation`, `classloader`, `SPI`, `SLF4J`, `ServiceLoader`

### Shadow JAR Contents

The adapter shadow JAR must contain:
- Adapter classes (`io.messagexform.pingaccess.*`)
- Core engine (`io.messagexform.core.*`)
- `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor`
- All runtime dependencies (Jackson, JSLT, SnakeYAML, JSON Schema Validator)

**Excluded** (provided by PA runtime):
- PingAccess SDK classes
- SLF4J API (PA ships its own SLF4J provider — bundling a second causes classpath conflict)

### Jackson Relocation (MANDATORY)

PingAccess uses Jackson internally (`Identity.getAttributes()` returns `JsonNode`,
`SessionStateSupport.setAttribute()` takes `JsonNode`). If the adapter bundles an
un-relocated Jackson version:

1. PA's `Identity.getAttributes()` returns a `JsonNode` from PA's classloader
2. The adapter's code expects a `JsonNode` from its own bundled Jackson
3. These are **different classes** from different classloaders
4. **`ClassCastException` at runtime** — silent deployment failure that only
   manifests when processing requests with identity context

**Solution:** Use Gradle Shadow's `relocate` to shade Jackson into a private
package (e.g., `io.messagexform.shaded.jackson`).

See [§6 Boundary Conversion](#boundary-conversion-jackson-relocation) for the
serialization-based conversion pattern at the PA/adapter boundary.

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

1. Build the shadow JAR (see §9 Shadow JAR Contents above).
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

## 10. Thread Safety

> **Tags:** `thread`, `concurrency`, `mutable`, `instance`, `shared`

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

---

## 11. Testing Patterns

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
    // Verify our plugin is loadable via ServiceFactory
    assertTrue(ServiceFactory.isValidImplName(
        RuleInterceptor.class, MyCustomRule.class.getName()));

    // Verify non-plugins are not loadable
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
// Spring context: uses LocalValidatorFactoryBean (mock-config.xml)
@ContextConfiguration("/mock-config.xml")
@RunWith(SpringJUnit4ClassRunner.class)
public class ConfigurationTest {
    @Autowired private Validator validator;

    @Test
    public void testValidConfiguration() throws IOException {
        // Build a Map<String, String> simulating admin UI form data
        Map<String, String> map = new HashMap<>();
        map.put("aText", "value");
        map.put("aSelect", "OptionOne");
        map.put("aTable", "[{\"col1\":\"val\",\"col2\":\"2\"}]");
        map.put("errorResponseCode", "403");
        // ...

        // Deserialize into configuration using readerForUpdating()
        MyPlugin.Configuration cfg = new MyPlugin.Configuration();
        cfg.setName("testRule");
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.readerForUpdating(cfg).readValue(TestUtil.getJsonForMap(map));

        // Validate using Jakarta Bean Validation
        Set<ConstraintViolation<?>> violations =
            new HashSet<>(validator.validate(cfg));
        assertThat(violations.size(), is(0));
    }

    @Test
    public void testInvalidConfiguration() throws IOException {
        // Intentionally omit required fields or provide invalid values
        Map<String, String> map = new HashMap<>();
        map.put("aConcealed", "123"); // @Size(min=5) violated
        // ...missing required field "aText"

        MyPlugin.Configuration cfg = new MyPlugin.Configuration();
        cfg.setName("testRule");
        getMapper().readerForUpdating(cfg).readValue(TestUtil.getJsonForMap(map));

        Set<ConstraintViolation<?>> violations =
            new HashSet<>(validator.validate(cfg));
        assertThat(violations.size(), is(4)); // exact count of violations
    }
}
```

> **Key pattern:** Jackson's `readerForUpdating()` merges JSON into an existing
> object without replacing defaults. This simulates how PingAccess admin
> deserializes config posted from the UI. The `TestUtil.getJsonForMap()` helper
> converts `Map<String,String>` to JSON, auto-detecting array values for TABLE.

### Test Infrastructure Helpers (from SDK samples)

The SDK tests use a consistent set of mock implementations:

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

## 12. Supporting Types

> **Tags:** `Outcome`, `HttpStatus`, `Method`, `ExchangeProperty`, `ResponseBuilder`, `ServiceFactory`, `MediaType`

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
// (For common content-type checks without string-matching)
```

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

## 13. SSL & Network Types

> **Tags:** `SSL`, `TLS`, `cookie`, `network`, `certificate`, `SNI`

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
// Represents a backend target host (returned by exchange.getTargetHosts()).
String getHost();
int    getPort();
boolean isSecure();
```

### SetCookie

```java
// com.pingidentity.pa.sdk.http.SetCookie
// Represents a parsed Set-Cookie header value (from Headers.getSetCookies()).
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

## 14. OAuth SDK

> **Tags:** `OAuth`, `DPoP`, `RFC6750`, `constants`, `WWW-Authenticate`

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

## 15. Other Extension Points

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
// Parse existing URI, merge with configured param, rebuild
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

### Complete SPI Reference (All Extension Points)

The following is the definitive reference for all 5 plugin extension points,
covering sync and async variants, SPI interfaces, base classes, annotations,
and `META-INF/services/` provider-configuration files.

#### Sync vs Async Decision

> **When to use Async:** Use async variants (`AsyncRuleInterceptor`,
> `AsyncSiteAuthenticatorInterceptor`, etc.) **only when** the plugin logic
> requires integrating with a third-party service via `HttpClient`. Async base
> classes provide `getHttpClient()`. If no `HttpClient` is needed, use the
> sync variant.

#### Extension Point Matrix

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

#### SPI Registration Files (`META-INF/services/`)

Each extension type requires its own provider-configuration file:

| Extension Type | Provider-Configuration File |
|---------------|-----------------------------|
| Sync Rule | `META-INF/services/com.pingidentity.pa.sdk.policy.RuleInterceptor` |
| Async Rule | `META-INF/services/com.pingidentity.pa.sdk.policy.AsyncRuleInterceptor` |
| Sync Site Authenticator | `META-INF/services/com.pingidentity.pa.sdk.siteauthenticator.SiteAuthenticatorInterceptor` |
| Async Site Authenticator | `META-INF/services/com.pingidentity.pa.sdk.siteauthenticator.AsyncSiteAuthenticatorInterceptor` |
| Sync Identity Mapping | `META-INF/services/com.pingidentity.pa.sdk.identitymapping.IdentityMappingPlugin` |
| Async Identity Mapping | `META-INF/services/com.pingidentity.pa.sdk.identitymapping.AsyncIdentityMappingPlugin` |
| Sync Load Balancing | `META-INF/services/com.pingidentity.pa.sdk.ha.lb.LoadBalancingPlugin` |
| Async Load Balancing | `META-INF/services/com.pingidentity.pa.sdk.ha.lb.AsyncLoadBalancingPlugin` |
| Locale Override | `META-INF/services/com.pingidentity.pa.sdk.localization.LocaleOverrideService` |

#### SiteAuthenticatorInterceptor Interface Change (post-5.0)

The `SiteAuthenticatorInterceptor` interface no longer extends
`RequestInterceptor` and `ResponseInterceptor` (as it did pre-5.0), but it
still defines `handleRequest(Exchange)` and `handleResponse(Exchange)` directly.
Both methods now throw only `AccessException` (not `IOException` or
`InterruptedException` as in earlier versions).

---

## 16. Vendor-Built Plugin Analysis

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
  the service target dropdown — **not** `@Inject @OAuthAuthorizationServer`.
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
- `getConfigurationFields()` returns fields generated from annotation scan.

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

## 17. Development Setup & Plugin Creation

> **Tags:** `setup`, `Maven`, `Gradle`, `create`, `deploy`, `directory`

### SDK Directory Structure

The PingAccess SDK is located at `<PA_HOME>/sdk` alongside the `<PA_HOME>/deploy`
directory. The following files and directories are provided:

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

### Creating a Plugin From Scratch (Official 7-Step Procedure)

The following is the official procedure from the PingAccess documentation:

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
   This builds the plugin, runs tests, and installs the JAR to the local
   Maven repository.

7. **Deploy the JAR** to `<PA_HOME>/deploy/` and **restart PingAccess**
   (see [§9 Deployment Path](#deployment-path)).

### Installing the SDK Samples

To install and test the provided SDK samples:

```bash
# From the sample directory, e.g.:
cd <PA_HOME>/sdk/samples/Rules
mvn install
# This builds, tests, and copies the JAR to <PA_HOME>/lib
```

### SDK Prerequisites

The following prerequisites must be met before using the Add-on SDK:

**Required tools:**
- **Java SDK** — 17 or 21 (see Java Version Compatibility below)
- **Apache Maven** (for official samples) or **Gradle** (for our project)

**Ping Identity Maven Repository:**

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
<dependency>
    <groupId>javax.validation</groupId>
    <artifactId>validation-api</artifactId>
    <version>1.0.0.GA</version>
    <scope>system</scope>
    <systemPath><PA_HOME>/lib/validation-api-1.0.0.GA.jar</systemPath>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>1.7.4</version>
    <scope>system</scope>
    <systemPath><PA_HOME>/lib/slf4j-api-1.7.4.jar</systemPath>
</dependency>
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-log4j12</artifactId>
    <version>1.7.4</version>
    <scope>system</scope>
    <systemPath><PA_HOME>/lib/slf4j-log4j12-1.7.4.jar</systemPath>
</dependency>
```

> **Version note:** The `systemPath` versions above are from the official docs
> (PA 4.x era). For PA 9.0.1.0, the actual JAR filenames in `<PA_HOME>/lib/`
> may differ. Always verify the exact filenames.

### Gradle Adaptation (Our Project)

Our message-xform adapter uses **Gradle** instead of Maven. The core steps are
analogous but differ in tooling:

| Maven Step | Gradle Equivalent |
|-----------|-------------------|
| `pom.xml` with SDK dependency | `build.gradle.kts` with `compileOnly` SDK dependency |
| `mvn install` | `./gradlew shadowJar` (produces shadow JAR) |
| Copy JAR to `deploy/` | CI/CD copies shadow JAR to `<PA_HOME>/deploy/` |
| Ping Maven repo in `<repositories>` | `maven { url = "http://maven.pingidentity.com/release" }` in `repositories {}` |
| System-scope local JAR | `compileOnly(files("<PA_HOME>/lib/pingaccess-sdk-9.0.1.0.jar"))` |

See [§9 Deployment](#9-deployment--classloading) for shadow JAR contents and
Jackson relocation requirements.

---

## 18. Behavioral Notes

> **Tags:** `behavior`, `constraints`, `logging`

### Request Immutability During Response Phase

Any modifications to `exchange.getRequest()` or its headers inside
`handleResponse()` are **silently ignored** by PingAccess (a warning is logged).
Only `exchange.getResponse()` may be modified during response processing.

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

### Logging

Use the **SLF4j API** for all logging in plugin modules:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger LOG = LoggerFactory.getLogger(MyPlugin.class);
```

PingAccess ships its own SLF4J provider — do **not** bundle SLF4J in your
plugin JAR (see [§9 Deployment](#9-deployment--classloading)).

---

## Java Version Compatibility

PingAccess 9.0 officially supports **Java 17 and 21** (Amazon Corretto, OpenJDK,
Oracle JDK — all 64-bit). The adapter module compiles with **Java 21**, matching
the core module. No cross-compilation needed.

> Source: PingAccess 9.0 docs § "Java runtime environments" (page 78)

---

## SDK Class Coverage

Total SDK classes (excluding inner): **112** • Documented: **112** (100%)

All publicly exported classes in `pingaccess-sdk-9.0.1.0.jar` are documented in
this guide. The 5 previously undocumented classes were added in this revision:
`DependantFieldAccessor`, `DependantFieldAccessorFactory`,
`ConfigurationDependentFieldOption`, `DynamicConfigurationParentField`,
`DynamicOptionsConfigurationField`.

---

## SDK Artefact Locations

| Artefact | Path |
|----------|------|
| SDK JAR (binary) | `binaries/pingaccess-9.0.1/sdk/` |
| SDK Javadoc (HTML) | `binaries/pingaccess-9.0.1/sdk/apidocs/` |
| SDK sample rules | `binaries/pingaccess-9.0.1/sdk/samples/` |
| Local SDK JAR copy | `docs/reference/pingaccess-sdk/pingaccess-sdk-9.0.1.0.jar` |
| Engine JAR (vendor plugins) | `binaries/pingaccess-9.0.1.zip → lib/pingaccess-engine-9.0.1.0.jar` |
