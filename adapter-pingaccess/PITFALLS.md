# adapter-pingaccess — Known Pitfalls

SDK quirks and gotchas discovered during development. Read this before working
on anything in the `adapter-pingaccess` module.

## HeaderField API
PA SDK's `HeaderField` uses `getHeaderName()` (returns `HeaderName` object, use
`.toString()` for the string), **not** `getName()`. In tests, use real instances
`new HeaderField("Content-Type", "application/json")` rather than mocking —
`HeaderField` is a concrete class with a `(String, String)` constructor.

## ErrorHandlerConfiguration Not on Config
`MessageTransformConfig` (or any custom `PluginConfiguration`) does NOT
implement `ErrorHandlerConfiguration`. When constructing
`RuleInterceptorErrorHandlingCallback`, create an inline anonymous
`ErrorHandlerConfiguration` with sensible defaults (500, `text/html`,
`general.error.page`).

## getConfigurationFields() Required
`AsyncRuleInterceptorBase` inherits `DescribesUIConfigurable`, which requires
implementing `getConfigurationFields()`. Use the SDK builder pattern:
```java
@Override
public List<ConfigurationField> getConfigurationFields() {
    return ConfigurationBuilder.from(MyConfig.class).toConfigurationFields();
}
```

## Test Spec YAML — Schema Blocks Required
Even minimal test specs used in `@TempDir` tests need `input.schema` and
`output.schema` blocks (FR-001-09 mandates them). A minimal valid spec:
```yaml
id: test-spec
version: '1'
input:
  schema:
    type: object
output:
  schema:
    type: object
transform:
  lang: jslt
  expr: "."
```

## JMX MBean Test Isolation
JMX uses the platform-wide `MBeanServer` singleton. Tests registering MBeans
**must** use unique `ObjectName`s (include a UUID or test-method name) and
unregister in `@AfterEach`. Otherwise, parallel test execution or re-runs
will fail with `InstanceAlreadyExistsException`.

## PA Creates Multiple Rule Instances (Critical)
PingAccess creates **multiple Java objects** per rule configuration — one per
engine/application binding. A single admin-defined rule can produce ~7
instances. This means:

- **Shared state must use static registries** — any per-rule state (like
  JMX metrics counters) that needs to be consistent across instances must
  use a `static ConcurrentHashMap` keyed by instance name, not instance
  fields.
- **MBean registration must be idempotent** — multiple `configure()` calls
  will try to register the same ObjectName. Use `mbs.isRegistered()` guard.
- **Unit tests are affected** — `forInstance("default")` returns the same
  shared metrics object across tests. Reset counters in `@BeforeEach`.

See `pingaccess-operations-guide.md` §"JMX Pitfalls" for the full diagnosis
with log trace evidence.

## Gradle Build Cache and Shadow JARs
When debugging adapter Java code and re-running E2E tests, Gradle's build
cache may return a **stale** shadow JAR even after `./gradlew clean`:

```
> Task :adapter-pingaccess:shadowJar FROM-CACHE   ← stale!
```

**Always use `--no-build-cache`** when iterating on Java changes:
```bash
./gradlew clean :adapter-pingaccess:shadowJar --no-build-cache
```

Verify the task output says `EXECUTED`, not `FROM-CACHE` or `UP-TO-DATE`.

## Gradle Test Working Directory
Gradle may run tests from the **project root** or the **module directory**
depending on the version and configuration cache state. Any test that resolves
file paths relative to the project (e.g., `build/libs/*.jar`) must try both
`adapter-pingaccess/build/libs` (from root) and `build/libs` (from module).
