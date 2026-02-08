# PingAccess Docker Image & SDK Samples — Research Notes

> Researched: 2026-02-08
> Context: Investigating the PingAccess Docker image and installer zip for adapter development

---

## Docker Image

**Image:** `pingidentity/pingaccess:latest`

| Property | Value |
|----------|-------|
| Version | **9.0.1** |
| Build date | 2026-02-04 |
| Base OS | Alpine 3.23.3 |
| Java | Amazon Corretto 17 (path: `/opt/java`) |
| Image size | 370 MB |
| SDK JAR | `/opt/server/lib/pingaccess-sdk-9.0.1.0.jar` (131 KB, 166 classes) |

### Exposed Ports

| Port | Purpose |
|------|---------|
| `1443` | HTTPS (TLS) |
| `3000` | Engine (PA_ENGINE_PORT) |
| `9000` | Admin API (PA_ADMIN_PORT) |

### Key Environment Variables

| Variable | Default | Notes |
|----------|---------|-------|
| `PING_IDENTITY_ACCEPT_EULA` | `NO` | **Must set to `YES` to start** |
| `PA_ADMIN_PASSWORD_INITIAL` | `2Access` | Default admin password |
| `OPERATIONAL_MODE` | `STANDALONE` | Can be `STANDALONE` or `CLUSTERED` |
| `FIPS_MODE_ON` | `false` | FIPS compliance toggle |
| `MAX_HEAP_SIZE` | `384m` | JVM heap |
| `JAVA_RAM_PERCENTAGE` | `60.0` | Alternative memory sizing |
| `STARTUP_COMMAND` | `/opt/out/instance/bin/run.sh` | Main entrypoint |
| `LICENSE_DIR` | `/opt/out/instance/conf` | Where to mount the `.lic` file |

### Container Structure

```
/opt/
├── server/
│   ├── lib/
│   │   ├── pingaccess-sdk-9.0.1.0.jar    ← SDK JAR (same as installer)
│   │   ├── pingaccess-engine-9.0.1.0.jar
│   │   ├── pingaccess-admin-9.0.1.0.jar
│   │   └── (many third-party JARs)
│   └── deploy/                             ← EMPTY — custom plugin JARs go here
├── staging/
├── out/
│   └── instance/
│       ├── bin/run.sh
│       ├── conf/                           ← config + license mount point
│       └── log/pingaccess.log
└── java/                                   ← JDK 17
```

### What's NOT in the Docker Image

- **No SDK samples** — the `sdk/samples/` directory from the installer zip is stripped
- **No SDK Javadocs** — the `sdk/apidocs/` directory is stripped
- **No SDK `README.md`**
- Only the runtime JAR (`pingaccess-sdk-9.0.1.0.jar`) is included in `/opt/server/lib/`

### Tagging Strategy (from Ping Identity docs)

- `latest` — most recent stable release, slides monthly
- `edge` — semi-weekly updates, re-assigned to `latest` at month start
- **Recommended for production:** use full sprint tag digest, e.g., `pingidentity/pingaccess:2206-11.1.0@sha256:...`
- Images retained for **one year** — store in private registry for longer

---

## Installer Zip

**File:** `binaries/pingaccess-9.0.1.zip` (163 MB)

The full installer contains everything the Docker image has, **plus** the SDK directory with samples, Javadocs, and build files.

### SDK Directory Structure (`sdk/`)

```
sdk/
├── apidocs/                              ← Full Javadocs (HTML)
│   └── com/pingidentity/pa/sdk/...
├── samples/
│   ├── Rules/                            ← ★ Most relevant for message-xform
│   │   ├── pom.xml                       ← Maven build (Java 17)
│   │   ├── src/main/java/.../
│   │   │   ├── HeaderRule.java           ← Header injection
│   │   │   ├── ParameterRule.java        ← Query/cookie inspection (full impl)
│   │   │   ├── Clobber404ResponseRule.java ← Response rewriting (Site-only)
│   │   │   ├── WriteResponseRule.java    ← Error response via template
│   │   │   ├── ExternalAuthorizationRule.java ← Session state + DI
│   │   │   ├── RiskAuthorizationRule.java ← AsyncRuleInterceptor + HttpClient
│   │   │   ├── AllUITypesAnnotationRule.java ← Config UI via annotations
│   │   │   └── AllUITypesBuilderRule.java  ← Config UI via builder
│   │   └── src/test/java/.../
│   │       ├── TestUtil.java             ← Mock Exchange/Headers helpers
│   │       └── (test classes for each rule)
│   ├── SiteAuthenticator/                ← URI/query manipulation
│   ├── IdentityMappings/                 ← Identity header mapping
│   ├── LoadBalancingStrategies/           ← Async handler patterns
│   └── LocaleOverrideService/            ← Locale resolution
```

### License File

**File:** `binaries/PingAccess-9.0-Development.lic`

Required to start PingAccess. Must be mounted into the container at the `LICENSE_DIR` path (`/opt/out/instance/conf/`) and referenced by `LICENSE_FILE_NAME=pingaccess.lic`.

---

## SDK Samples Analysis

### Sample Relevance Matrix

| Sample | SPI | Pattern | Relevance to message-xform |
|--------|-----|---------|---------------------------|
| **HeaderRule** | `RuleInterceptorBase` | `handleRequest` + header add | **High** — direct reference for header transforms |
| **Clobber404ResponseRule** | `RuleInterceptorBase` | `handleResponse` + `@Rule(destination = Site)` | **Critical** — only sample showing response handling; confirms Site-only constraint |
| **ParameterRule** | `RuleInterceptor` (full) | `handleRequest` + query params + cookies | **High** — shows full `RuleInterceptor` impl (not Base), query string access |
| **ExternalAuthorizationRule** | `RuleInterceptorBase` | Session state + `@Inject ObjectMapper` | **Medium** — DI pattern, session state caching |
| **RiskAuthorizationRule** | `AsyncRuleInterceptorBase` | `CompletionStage<Outcome>` + `HttpClient` | **Medium** — async pattern for external HTTP calls |
| **WriteResponseRule** | `RuleInterceptorBase` | `TemplateRenderer` | **Low** — error template rendering, less relevant |
| **AddQueryStringSiteAuthenticator** | `SiteAuthenticatorInterceptorBase` | URI manipulation via `setUri()` | **Medium** — reference for URL rewriting adapter logic |

### Key Patterns Observed

#### 1. Two Implementation Styles

- **Extending `RuleInterceptorBase<T>`** — recommended, handles boilerplate (configure, getConfiguration)
- **Implementing `RuleInterceptor<T>` directly** — full control, must implement `configure()`, `getConfiguration()`, `handleRequest()`, `handleResponse()` yourself

#### 2. Configuration Model

```java
// Annotation-based (preferred for simple configs)
public static class Configuration extends SimplePluginConfiguration {
    @UIElement(label = "...", order = 10, type = ConfigurationType.TEXT, required = true)
    @NotNull
    public String myField;
}

// Builder-based (for complex/dynamic configs like tables)
public List<ConfigurationField> getConfigurationFields() {
    return new ConfigurationBuilder()
            .configurationField("name", "Label", ConfigurationType.TABLE)
            .subFields(...)
            .toConfigurationFields();
}
```

#### 3. @Rule Annotation

```java
@Rule(
    category = RuleInterceptorCategory.Processing,  // or AccessControl
    type = "UniqueTypeName",                          // must be unique across all plugins
    label = "Human-Readable Label",
    expectedConfiguration = MyRule.Configuration.class,
    destination = Site                                // CRITICAL for body access
)
```

#### 4. Dependency Injection

```java
@Inject
public void setObjectMapper(ObjectMapper objectMapper) { ... }

@Inject
public void setRenderer(TemplateRenderer renderer) { ... }
```

Uses `jakarta.inject.Inject` (not Spring annotations).

#### 5. Error Handling

```java
@Override
public ErrorHandlingCallback getErrorHandlingCallback() {
    return new RuleInterceptorErrorHandlingCallback(
        getRenderer(), getConfiguration().errorHandlerPolicyConfig);
}
```

Requires `@JsonUnwrapped ErrorHandlerConfigurationImpl errorHandlerPolicyConfig` in the Configuration class.

#### 6. META-INF/services Registration

Custom plugins are discovered via Java SPI:
```
META-INF/services/com.pingidentity.pa.sdk.policy.RuleInterceptor
```
containing the fully qualified class name of the plugin.

---

## Maven Dependency Structure

From the SDK sample `pom.xml`:

```xml
<repositories>
    <repository>
        <id>PingIdentityMaven</id>
        <name>PingIdentity Release</name>
        <url>https://maven.pingidentity.com/release/</url>
    </repository>
</repositories>

<dependencies>
    <!-- Core SDK -->
    <dependency>
        <groupId>com.pingidentity.pingaccess</groupId>
        <artifactId>pingaccess-sdk</artifactId>
        <version>9.0.1.0</version>
    </dependency>

    <!-- Required runtime deps -->
    <dependency>
        <groupId>jakarta.validation</groupId>
        <artifactId>jakarta.validation-api</artifactId>
        <version>3.1.1</version>
    </dependency>
    <dependency>
        <groupId>jakarta.inject</groupId>
        <artifactId>jakarta.inject-api</artifactId>
        <version>2.0.1</version>
    </dependency>
</dependencies>
```

**Java version:** 17 (not 21 — PA 9.0.1 targets Java 17)

> **Important:** The SDK sample `pom.xml` sets `javac.source=17` and `javac.target=17`.
> Our project uses Java 21. We need to confirm that PA 9.0.1 runtime supports Java 21
> class files, or else the adapter module must target Java 17.

---

## Decompiled SDK API Surface

### Core Interfaces (from `pingaccess-sdk-9.0.1.0.jar`)

```
Exchange
├── getRequest() → Request
├── setRequest(Request)
├── getResponse() → Response
├── setResponse(Response)
├── getIdentity() → Identity
├── getOriginalRequestUri() → String
├── getSourceIp() → String
├── getUserAgentHost() → String
├── getUserAgentProtocol() → String
└── getProperty/setProperty(ExchangeProperty<T>, T)

Message (parent of Request and Response)
├── getBody() → Body
├── setBody(Body)
├── setBodyContent(byte[])           ← PRIMARY BODY MUTATION METHOD
├── getHeaders() → Headers
├── setHeaders(Headers)
├── getStartLine() → String
└── getVersion() → String

Request extends Message
├── getMethod() → Method
├── setMethod(Method)
├── getUri() → String
├── setUri(String)
└── getQueryStringParams() → Map<String, String[]>

Response extends Message
├── getStatus() → HttpStatus
├── setStatus(HttpStatus)
├── getStatusCode() → int
└── getStatusMessage() → String

Body
├── getContent() → byte[]            ← READ BODY BYTES
├── read()                            ← force-read (may be deferred)
├── isRead() → boolean
├── getLength() → int
├── newInputStream() → InputStream
└── parseFormParams() → Map<String, String[]>

Outcome (enum)
├── CONTINUE                          ← pass to next rule / backend
└── RETURN                            ← halt pipeline, return current response
```

### Plugin Deployment

1. Build plugin JAR (contains your classes + `META-INF/services/` SPI registration)
2. Place JAR in `<PA_HOME>/deploy/` (Docker: `/opt/server/deploy/`)
3. **Restart PingAccess** — plugins are loaded at startup only

---

## Open Items for Future Sessions

1. **Java version compatibility** — PA SDK targets Java 17; our core is Java 21. Need to verify if PA 9.0.1 runtime can load Java 21 class files, or if the adapter module must compile to Java 17 target while core stays at 21.

2. **Maven repo accessibility** — Need to verify that `https://maven.pingidentity.com/release/` is actually reachable and resolves `pingaccess-sdk:9.0.1.0`. If not, we fall back to the local JAR from the installer.

3. **Gradle vs. Maven for adapter module** — Our project uses Gradle, but PingAccess SDK samples use Maven. The adapter module will use Gradle (consistency) but reference the Ping Maven repo.

4. **Docker-based E2E test setup** — How to configure the container with: license file, custom plugin JAR, and a test site/application for integration testing.

5. **Body access constraints** — Confirmed: request body only available in **Site** rules (not Agent rules). Response body access in `handleResponse` is available for Site rules. Need to verify if `setBodyContent` works in `handleResponse` (docs say response modifications during response processing are "ignored" since PA 6.1 — but that refers to *request* modifications during response processing, not response body modifications).

6. **Async vs. sync adapter** — `RuleInterceptorBase` (sync) vs. `AsyncRuleInterceptorBase` (async). For pure transformation (no external calls), sync is simpler and sufficient. Async only needed if the adapter calls external services mid-transform.

7. **SDK Javadocs exploration** — Full HTML Javadocs available at `binaries/pingaccess-9.0.1/sdk/apidocs/`. Not yet read in detail.

8. **Plugin marketplace samples** — User mentioned availability of marketplace plugin samples. Could provide additional real-world patterns.

---

*Status: COMPLETE — Docker image and SDK samples fully analyzed and documented*
