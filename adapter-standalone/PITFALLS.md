# adapter-standalone — Known Pitfalls

Library quirks and gotchas discovered during development of the standalone
HTTP proxy adapter. Read this before working on anything in the
`adapter-standalone` module.

## JDK HttpClient Restricted Headers
The JDK `HttpClient` (`java.net.http`) blocks setting the `Host` header via
`HttpRequest.Builder.header("Host", ...)` — it throws `IllegalArgumentException:
restricted header name: "Host"`. In integration tests, rely on the auto-generated
`Host` header (typically `127.0.0.1:<port>`) instead of setting a custom one.

## Javalin 6 — Unknown Methods Return 404, Not 405
Javalin does not return `405 Method Not Allowed` for HTTP methods without a
registered handler (e.g. `PROPFIND`). Instead, it returns `404 Not Found`
because no route matches. To produce a proper `405` with an RFC 9457 body,
add a `before("/<path>", ...)` filter with a method whitelist check that
calls `ctx.skipRemainingHandlers()` after writing the 405 response.

## Docker Build in Multi-Module Projects
The `adapter-standalone/Dockerfile` uses a `builder` stage that runs Gradle.
Because `settings.gradle.kts` in root references all subprojects and dual
version catalogs, the Docker build MUST copy root configuration files
(`gradle/*.toml`, `gradle.properties`), local libs (`libs/`), and ALL
included subprojects (at least their `build.gradle.kts` files) even if it
only intends to build the standalone proxy. Failing to copy
`adapter-pingaccess` or `libs/` will cause the Gradle configuration phase
to fail inside the container.
