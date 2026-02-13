# PingAccess 9.0.1.0 — Provided Dependencies

> Generated: 2026-02-11T19:34:50Z
> Source image: `pingidentity/pingaccess:latest`
> Script: `scripts/pa-extract-deps.sh`
> Total JARs: 146

This document lists all libraries shipped in `/opt/server/lib/` of the
PingAccess Docker image. Libraries on this list are available to plugins
on the flat classpath (ADR-0031) and MUST be declared as `compileOnly`
in the adapter's build configuration — they MUST NOT be bundled.

## Plugin-Relevant Dependencies

These are the libraries the adapter directly uses or must be aware of:

| Library | Group | Version | Build Scope |
|---------|-------|---------|-------------|
| `jackson-annotations` | `com.fasterxml.jackson.core` | **2.17.0** | `compileOnly` |
| `jackson-core` | `com.fasterxml.jackson.core` | **2.17.0** | `compileOnly` |
| `jackson-databind` | `com.fasterxml.jackson.core` | **2.17.0** | `compileOnly` |
| `jackson-datatype-jdk8` | `com.fasterxml.jackson.datatype` | 2.17.0 | `compileOnly` |
| `jackson-datatype-jsr310` | `com.fasterxml.jackson.datatype` | 2.17.0 | `compileOnly` |
| `guava` | `com.google.guava` | 33.1.0-jre | Available (optional) |
| `pingaccess-sdk` | `com.pingidentity.pingaccess` | **9.0.1.0** | `compileOnly` |
| `jakarta.inject-api` | `jakarta.inject` | 2.0.1 | `compileOnly` |
| `jakarta.validation-api` | `jakarta.validation` | 3.1.1 | `compileOnly` |
| `commons-lang3` | `org.apache.commons` | 3.14.0 | Available (optional) |
| `log4j-api` | `org.apache.logging.log4j` | 2.24.3 | PA internal (logging backend) |
| `log4j-core` | `org.apache.logging.log4j` | 2.24.3 | PA internal (logging backend) |
| `log4j-slf4j-impl` | `org.apache.logging.log4j` | 2.24.3 | PA internal (logging backend) |
| `hibernate-validator` | `org.hibernate.validator` | 7.0.5.Final | `compileOnly` |
| `slf4j-api` | `org.slf4j` | **1.7.36** | `compileOnly` |

### Not Shipped (MUST bundle)

These libraries are **not** in PA's `/opt/server/lib/`:

| Library | Reason |
|---------|--------|
| `snakeyaml` | Core engine needs it for YAML config parsing |
| `jslt` | Core engine's expression engine |
| `json-schema-validator` | Core engine's schema validation |
| `logback-classic` | PA uses Log4j2, not Logback |
| `jackson-dataformat-yaml` | Not in PA's Jackson bundle |

## Full Inventory

All 146 JARs in `/opt/server/lib/`:

| # | Group | Artifact | Version | Source |
|---|-------|----------|---------|--------|
| 1 | `com.fasterxml` | `classmate` | 1.5.1 | pom.properties |
| 2 | `com.fasterxml.jackson.core` | `jackson-annotations` | 2.17.0 | pom.properties |
| 3 | `com.fasterxml.jackson.core` | `jackson-core` | 2.17.0 | pom.properties |
| 4 | `com.fasterxml.jackson.core` | `jackson-databind` | 2.17.0 | pom.properties |
| 5 | `com.fasterxml.jackson.datatype` | `jackson-datatype-jdk8` | 2.17.0 | pom.properties |
| 6 | `com.fasterxml.jackson.datatype` | `jackson-datatype-jsr310` | 2.17.0 | pom.properties |
| 7 | `com.github.rwitzel.streamflyer` | `streamflyer-core` | 1.2.0 | pom.properties |
| 8 | `com.google.code.findbugs` | `jsr305` | 3.0.2 | pom.properties |
| 9 | `com.google.errorprone` | `error_prone_annotations` | 2.26.1 | pom.properties |
| 10 | `com.google.guava` | `failureaccess` | 1.0.2 | pom.properties |
| 11 | `com.google.guava` | `guava` | 33.1.0-jre | pom.properties |
| 12 | `com.google.guava` | `listenablefuture` | 9999.0-empty-to-avoid-conflict-with-guava | pom.properties |
| 13 | `com.google.j2objc` | `j2objc-annotations` | 3.0.0 | pom.properties |
| 14 | `commons-cli` | `commons-cli` | 1.9.0 | pom.properties |
| 15 | `commons-codec` | `commons-codec` | 1.18.0 | pom.properties |
| 16 | `commons-collections` | `commons-collections` | 3.2.2 | pom.properties |
| 17 | `commons-fileupload` | `commons-fileupload` | 1.5 | pom.properties |
| 18 | `commons-io` | `commons-io` | 2.16.0 | pom.properties |
| 19 | `commons-lang` | `commons-lang` | 2.6 | pom.properties |
| 20 | `commons-logging` | `commons-logging` | 1.1.1 | pom.properties |
| 21 | `com.pingidentity` | `pingcommons` | 1.1.0.4 | pom.properties |
| 22 | `com.pingidentity` | `pingcommons-bc-fips` | 2.0.0 | pom.properties |
| 23 | `com.pingidentity` | `pingcommons-notification-publisher` | 1.0.0 | pom.properties |
| 24 | `com.pingidentity.pingaccess` | `pingaccess-admin` | 9.0.1.0 | pom.properties |
| 25 | `com.pingidentity.pingaccess` | `pingaccess-admin-ui` | 9.0.1.0 | pom.properties |
| 26 | `com.pingidentity.pingaccess` | `pingaccess-api-spec` | 9.0.1.0 | pom.properties |
| 27 | `com.pingidentity.pingaccess` | `pingaccess-cli` | 9.0.1.0 | pom.properties |
| 28 | `com.pingidentity.pingaccess` | `pingaccess-database` | 9.0.1.0 | pom.properties |
| 29 | `com.pingidentity.pingaccess` | `pingaccess-engine` | 9.0.1.0 | pom.properties |
| 30 | `com.pingidentity.pingaccess` | `pingaccess-groovy-api` | 9.0.1.0 | pom.properties |
| 31 | `com.pingidentity.pingaccess` | `pingaccess-network` | 0.18.7 | pom.properties |
| 32 | `com.pingidentity.pingaccess` | `pingaccess-sdk` | 9.0.1.0 | pom.properties |
| 33 | `com.pingidentity.pingcommons.aws.key` | `aws-kms-master-key-encryptor` | 1.1.5 | pom.properties |
| 34 | `com.pingidentity.pingfederate` | `pingfederate-sdk` | 12.2.2.0 | pom.properties |
| 35 | `com.sun.activation` | `jakarta.activation` | 1.2.1 | pom.properties |
| 36 | `com.sun.istack` | `istack-commons-runtime` | 3.0.7 | pom.properties |
| 37 | `com.sun.mail` | `jakarta.mail` | 1.6.5 | pom.properties |
| 38 | `com.sun.xml.fastinfoset` | `FastInfoset` | 1.2.15 | pom.properties |
| 39 | `io.netty` | `netty-buffer` | 4.1.127.Final | pom.properties |
| 40 | `io.netty` | `netty-codec` | 4.1.127.Final | pom.properties |
| 41 | `io.netty` | `netty-codec-http` | 4.1.127.Final | pom.properties |
| 42 | `io.netty` | `netty-codec-socks` | 4.1.127.Final | pom.properties |
| 43 | `io.netty` | `netty-common` | 4.1.127.Final | pom.properties |
| 44 | `io.netty` | `netty-handler` | 4.1.127.Final | pom.properties |
| 45 | `io.netty` | `netty-handler-proxy` | 4.1.127.Final | pom.properties |
| 46 | `io.netty` | `netty-resolver` | 4.1.127.Final | pom.properties |
| 47 | `io.netty` | `netty-transport` | 4.1.127.Final | pom.properties |
| 48 | `io.netty` | `netty-transport-native-unix-common` | 4.1.127.Final | pom.properties |
| 49 | `io.smallrye` | `jandex` | 3.2.0 | pom.properties |
| 50 | `jakarta.activation` | `jakarta.activation-api` | 2.1.0 | pom.properties |
| 51 | `jakarta.annotation` | `jakarta.annotation-api` | 2.0.0 | pom.properties |
| 52 | `jakarta.el` | `jakarta.el-api` | 6.0.1 | pom.properties |
| 53 | `jakarta.inject` | `jakarta.inject-api` | 2.0.1 | pom.properties |
| 54 | `jakarta.persistence` | `jakarta.persistence-api` | 3.1.0 | pom.properties |
| 55 | `jakarta.transaction` | `jakarta.transaction-api` | 2.0.1 | pom.properties |
| 56 | `jakarta.validation` | `jakarta.validation-api` | 3.1.1 | pom.properties |
| 57 | `jakarta.xml.bind` | `jakarta.xml.bind-api` | 4.0.0 | pom.properties |
| 58 | `javax.activation` | `javax.activation-api` | 1.2.0 | pom.properties |
| 59 | `javax.annotation` | `javax.annotation-api` | 1.3.2 | pom.properties |
| 60 | `javax.cache` | `cache-api` | 1.0.0 | pom.properties |
| 61 | `javax.servlet` | `javax.servlet-api` | 3.1.0 | pom.properties |
| 62 | `javax.xml.bind` | `jaxb-api` | 2.3.1 | pom.properties |
| 63 | `net.bytebuddy` | `byte-buddy` | 1.14.9 | pom.properties |
| 64 | `org.antlr` | `antlr4-runtime` | 4.13.0 | pom.properties |
| 65 | `org.apache.commons` | `commons-compress` | 1.26.1 | pom.properties |
| 66 | `org.apache.commons` | `commons-dbcp2` | 2.13.0 | pom.properties |
| 67 | `org.apache.commons` | `commons-lang3` | 3.14.0 | pom.properties |
| 68 | `org.apache.commons` | `commons-math3` | 3.6.1 | pom.properties |
| 69 | `org.apache.commons` | `commons-pool2` | 2.12.1 | pom.properties |
| 70 | `org.apache.commons` | `commons-text` | 1.11.0 | pom.properties |
| 71 | `org.apache.httpcomponents` | `httpclient` | 4.5.14 | pom.properties |
| 72 | `org.apache.httpcomponents` | `httpcore` | 4.4.13 | pom.properties |
| 73 | `org.apache.logging.log4j` | `log4j-api` | 2.24.3 | pom.properties |
| 74 | `org.apache.logging.log4j` | `log4j-core` | 2.24.3 | pom.properties |
| 75 | `org.apache.logging.log4j` | `log4j-iostreams` | 2.24.3 | pom.properties |
| 76 | `org.apache.logging.log4j` | `log4j-jcl` | 2.24.3 | pom.properties |
| 77 | `org.apache.logging.log4j` | `log4j-jul` | 2.24.3 | pom.properties |
| 78 | `org.apache.logging.log4j` | `log4j-layout-template-json` | 2.24.3 | pom.properties |
| 79 | `org.apache.logging.log4j` | `log4j-slf4j-impl` | 2.24.3 | pom.properties |
| 80 | `org.apache.velocity` | `velocity-engine-core` | 2.3 | pom.properties |
| 81 | `org.bitbucket.b_c` | `jose4j` | 0.9.6 | pom.properties |
| 82 | `org.ehcache.modules` | `ehcache-107` | 3.10.8 | pom.properties |
| 83 | `org.glassfish` | `jakarta.el` | 4.0.2 | pom.properties |
| 84 | `org.glassfish.jaxb` | `jaxb-runtime` | 2.3.1 | pom.properties |
| 85 | `org.glassfish.jaxb` | `txw2` | 2.3.1 | pom.properties |
| 86 | `org.hibernate.validator` | `hibernate-validator` | 7.0.5.Final | pom.properties |
| 87 | `org.javassist` | `javassist` | 3.28.0-GA | pom.properties |
| 88 | `org.jboss.logging` | `jboss-logging` | 3.5.0.Final | pom.properties |
| 89 | `org.jgrapht` | `jgrapht-core` | 1.4.0 | pom.properties |
| 90 | `org.jsoup` | `jsoup` | 1.17.2 | pom.properties |
| 91 | `org.jvnet.staxex` | `stax-ex` | 1.8 | pom.properties |
| 92 | `org.owasp.encoder` | `encoder` | 1.2.3 | pom.properties |
| 93 | `org.reflections` | `reflections` | 0.10.2 | pom.properties |
| 94 | `org.shredzone.acme4j` | `acme4j-client` | 2.16 | pom.properties |
| 95 | `org.slf4j` | `slf4j-api` | 1.7.36 | pom.properties |
| 96 | `org.springframework.data` | `spring-data-commons` | 3.5.4 | pom.properties |
| 97 | `org.springframework.data` | `spring-data-jpa` | 3.5.4 | pom.properties |
| 98 | `pingfederate` | `pf-commons` | 12.2.2.0 | pom.properties |
| 99 | `pingfederate` | `pf-email-util` | 12.2.2.0 | pom.properties |
| 100 | `pingfederate` | `pf-xml` | 12.2.2.0 | pom.properties |
| 101 | `software.amazon.awssdk` | `annotations` | 2.17.131 | pom.properties |
| 102 | `software.amazon.awssdk` | `apache-client` | 2.17.131 | pom.properties |
| 103 | `software.amazon.awssdk` | `auth` | 2.17.131 | pom.properties |
| 104 | `software.amazon.awssdk` | `aws-core` | 2.17.131 | pom.properties |
| 105 | `software.amazon.awssdk` | `aws-json-protocol` | 2.17.131 | pom.properties |
| 106 | `software.amazon.awssdk` | `aws-query-protocol` | 2.17.131 | pom.properties |
| 107 | `software.amazon.awssdk` | `http-client-spi` | 2.17.131 | pom.properties |
| 108 | `software.amazon.awssdk` | `json-utils` | 2.17.131 | pom.properties |
| 109 | `software.amazon.awssdk` | `kms` | 2.17.131 | pom.properties |
| 110 | `software.amazon.awssdk` | `metrics-spi` | 2.17.131 | pom.properties |
| 111 | `software.amazon.awssdk` | `profiles` | 2.17.131 | pom.properties |
| 112 | `software.amazon.awssdk` | `protocol-core` | 2.17.131 | pom.properties |
| 113 | `software.amazon.awssdk` | `regions` | 2.17.131 | pom.properties |
| 114 | `software.amazon.awssdk` | `sdk-core` | 2.17.131 | pom.properties |
| 115 | `software.amazon.awssdk` | `sts` | 2.17.131 | pom.properties |
| 116 | `software.amazon.awssdk` | `third-party-jackson-core` | 2.17.131 | pom.properties |
| 117 | `software.amazon.awssdk` | `utils` | 2.17.131 | pom.properties |
| 118 | `software.amazon.eventstream` | `eventstream` | 1.0.1 | pom.properties |
| 119 | `sqe.pf-admin-api-clients` | `pf-admin-api-clients-v2` | 11.3.0.0 | pom.properties |
| 120 | `unknown` | `checker-qual` | 3.42.0 | MANIFEST.MF |
| 121 | `unknown` | `com.lmax.disruptor` | 3.4.4 | MANIFEST.MF |
| 122 | `unknown` | `derby` | 10.16.1000001.1901046 | MANIFEST.MF |
| 123 | `unknown` | `derbyshared` | 10.16.1.1 | filename |
| 124 | `unknown` | `derbytools` | 10.16.1.1 | filename |
| 125 | `unknown` | `Groovy: a powerful, dynamic language for the JVM` | 2.4.21 | MANIFEST.MF |
| 126 | `unknown` | `hamcrest-core` | 1.3 | MANIFEST.MF |
| 127 | `unknown` | `hibernate-core` | 6.6.29.Final | MANIFEST.MF |
| 128 | `unknown` | `hibernate-jcache` | 6.6.29.Final | MANIFEST.MF |
| 129 | `unknown` | `io.micrometer#micrometer-commons;1.14.11` | 1.14.11 | MANIFEST.MF |
| 130 | `unknown` | `io.micrometer#micrometer-observation;1.14.11` | 1.14.11 | MANIFEST.MF |
| 131 | `unknown` | `License4J` | 1.3 | MANIFEST.MF |
| 132 | `unknown` | `org.apache.xmlbeans` | 3.1.0 | MANIFEST.MF |
| 133 | `unknown` | `org.jheaps` | 0.11.0 | MANIFEST.MF |
| 134 | `unknown` | `org.reactivestreams.reactive-streams` | 1.0.3 | MANIFEST.MF |
| 135 | `unknown` | `re2j` | 1.8 | filename |
| 136 | `unknown` | `spring-aop` | 6.2.11 | MANIFEST.MF |
| 137 | `unknown` | `spring-beans` | 6.2.11 | MANIFEST.MF |
| 138 | `unknown` | `spring-context` | 6.2.11 | MANIFEST.MF |
| 139 | `unknown` | `spring-context-support` | 6.2.11 | MANIFEST.MF |
| 140 | `unknown` | `spring-core` | 6.2.11 | MANIFEST.MF |
| 141 | `unknown` | `spring-expression` | 6.2.11 | MANIFEST.MF |
| 142 | `unknown` | `spring-jcl` | 6.2.11 | MANIFEST.MF |
| 143 | `unknown` | `spring-jdbc` | 6.2.11 | MANIFEST.MF |
| 144 | `unknown` | `spring-orm` | 6.2.11 | MANIFEST.MF |
| 145 | `unknown` | `spring-tx` | 6.2.11 | MANIFEST.MF |
| 146 | `unknown` | `unknown` | 7.0.3.Final | MANIFEST.MF |

---

*Generated by `scripts/pa-extract-deps.sh` — re-run after PA upgrade.*
