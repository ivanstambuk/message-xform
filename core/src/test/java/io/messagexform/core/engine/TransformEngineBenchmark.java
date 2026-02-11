package io.messagexform.core.engine;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.messagexform.core.engine.jslt.JsltExpressionEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.Message;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformResult;
import io.messagexform.core.spec.SpecParser;
import io.messagexform.core.testkit.TestMessages;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight opt-in benchmark for NFR-001-03: core transform latency MUST be
 * < 5ms for typical messages (< 50KB body).
 *
 * <p>
 * <b>Pattern:</b> follows the openauth-sim benchmark convention (ADR-0028):
 * <ul>
 * <li>Guarded by {@code assumeTrue(isBenchmarkEnabled())} — skipped in normal
 * builds.</li>
 * <li>Enable via {@code -Dio.messagexform.benchmark=true} or env var
 * {@code IO_MESSAGEXFORM_BENCHMARK=true}.</li>
 * <li>Uses {@code System.nanoTime()} with a warmup phase and measured
 * phase.</li>
 * <li>Computes p50, p90, p95, p99, max latency and throughput.</li>
 * <li>Soft-asserts NFR-001-03 target — logs warnings, does not fail the
 * build.</li>
 * </ul>
 *
 * <p>
 * <b>Scenarios:</b>
 * <ul>
 * <li>S-001-79: Identity JSLT transform, ~1KB body</li>
 * <li>S-001-80: 5-field mapping JSLT transform, ~10KB body</li>
 * <li>S-001-81: Complex nested/array transform, ~50KB body</li>
 * </ul>
 *
 * <p>
 * <b>Run:</b>
 * {@code IO_MESSAGEXFORM_BENCHMARK=true ./gradlew :core:test --tests "*TransformEngineBenchmark*" --info}
 *
 * @see <a href=
 *      "../../../../../../docs/decisions/ADR-0028-performance-testing-strategy.md">ADR-0028</a>
 */
class TransformEngineBenchmark {

    private static final Logger LOG = LoggerFactory.getLogger(TransformEngineBenchmark.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Configuration ---
    private static final int WARMUP_ITERATIONS = 2_000;
    private static final int MEASURED_ITERATIONS = 20_000;
    private static final double NFR_001_03_TARGET_MS = 5.0; // NFR-001-03: < 5ms p95

    // --- Fixture paths ---
    private static final String BENCH_DIR = "src/test/resources/test-vectors/benchmark/";
    private static final String SPEC_IDENTITY = BENCH_DIR + "bench-identity.yaml";
    private static final String SPEC_FIELD_MAPPING = BENCH_DIR + "bench-field-mapping.yaml";
    private static final String SPEC_COMPLEX = BENCH_DIR + "bench-complex.yaml";

    // --- Pre-built JSON payloads (generated once in @BeforeAll) ---
    private static String body1KB;
    private static String body10KB;
    private static String body50KB;

    @BeforeAll
    static void generatePayloads() {
        body1KB = generateSimplePayload(1_024);
        body10KB = generateFieldMappingPayload(10_240);
        body50KB = generateArrayPayload(50_000);
    }

    // -----------------------------------------------------------------------
    // S-001-79: Identity transform — ~1KB body
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("S-001-79: Identity JSLT transform — p95 < 5ms (1KB body)")
    void identityTransformBenchmark() throws Exception {
        assumeTrue(isBenchmarkEnabled(), "Benchmark flag not set — skipping (set -Dio.messagexform.benchmark=true)");

        TransformEngine engine = createEngine();
        engine.loadSpec(Path.of(SPEC_IDENTITY));

        // Sanity check — transform works
        JsonNode sanityBody = MAPPER.readTree(body1KB);
        Message sanityInput = new Message(
                TestMessages.toBody(sanityBody, "application/json"),
                HttpHeaders.empty(),
                200,
                "/bench",
                "GET",
                null,
                SessionContext.empty());
        TransformResult sanity = engine.transform(sanityInput, Direction.RESPONSE);
        assertTransformSuccess(sanity, "identity-sanity");

        BenchmarkReport report = runBenchmark("identity-1KB", engine, body1KB);
        logReport("identity-1KB", report);
        softAssertNfr(report, "identity-1KB");
    }

    // -----------------------------------------------------------------------
    // S-001-80: 5-field mapping — ~10KB body
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("S-001-80: 5-field mapping JSLT transform — p95 < 5ms (10KB body)")
    void fieldMappingBenchmark() throws Exception {
        assumeTrue(isBenchmarkEnabled(), "Benchmark flag not set — skipping (set -Dio.messagexform.benchmark=true)");

        TransformEngine engine = createEngine();
        engine.loadSpec(Path.of(SPEC_FIELD_MAPPING));

        BenchmarkReport report = runBenchmark("field-mapping-10KB", engine, body10KB);
        logReport("field-mapping-10KB", report);
        softAssertNfr(report, "field-mapping-10KB");
    }

    // -----------------------------------------------------------------------
    // S-001-81: Complex array transform — ~50KB body
    // -----------------------------------------------------------------------
    @Test
    @DisplayName("S-001-81: Complex nested/array transform — p95 < 5ms (50KB body)")
    void complexTransformBenchmark() throws Exception {
        assumeTrue(isBenchmarkEnabled(), "Benchmark flag not set — skipping (set -Dio.messagexform.benchmark=true)");

        TransformEngine engine = createEngine();
        engine.loadSpec(Path.of(SPEC_COMPLEX));

        BenchmarkReport report = runBenchmark("complex-50KB", engine, body50KB);
        logReport("complex-50KB", report);
        softAssertNfr(report, "complex-50KB");
    }

    // -----------------------------------------------------------------------
    // Core benchmark harness
    // -----------------------------------------------------------------------

    private BenchmarkReport runBenchmark(String label, TransformEngine engine, String bodyJson) throws Exception {
        // --- Warmup phase ---
        LOG.info("xform-bench.{} warmup={} starting...", label, WARMUP_ITERATIONS);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            // Re-parse body each iteration to avoid JIT optimizing away the work
            JsonNode freshBody = MAPPER.readTree(bodyJson);
            Message freshInput = new Message(
                    TestMessages.toBody(freshBody, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/bench",
                    "GET",
                    null,
                    SessionContext.empty());
            TransformResult result = engine.transform(freshInput, Direction.RESPONSE);
            if (!result.isSuccess()) {
                throw new AssertionError("Transform failed during warmup at iteration " + i);
            }
        }

        // --- Measured phase ---
        long[] latenciesNs = new long[MEASURED_ITERATIONS];
        long totalStartNs = System.nanoTime();

        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            JsonNode freshBody = MAPPER.readTree(bodyJson);
            Message freshInput = new Message(
                    TestMessages.toBody(freshBody, "application/json"),
                    HttpHeaders.empty(),
                    200,
                    "/bench",
                    "GET",
                    null,
                    SessionContext.empty());

            long startNs = System.nanoTime();
            TransformResult result = engine.transform(freshInput, Direction.RESPONSE);
            long endNs = System.nanoTime();

            latenciesNs[i] = endNs - startNs;

            if (!result.isSuccess()) {
                throw new AssertionError("Transform failed during measurement at iteration " + i);
            }
        }

        long totalElapsedNs = System.nanoTime() - totalStartNs;
        return new BenchmarkReport(label, latenciesNs, totalElapsedNs, WARMUP_ITERATIONS, MEASURED_ITERATIONS);
    }

    // -----------------------------------------------------------------------
    // BenchmarkReport — percentile computation + structured output
    // -----------------------------------------------------------------------

    record BenchmarkReport(
            String label, long[] latenciesNs, long totalElapsedNs, int warmupIterations, int measuredIterations) {

        double p50Ms() {
            return percentileMs(50);
        }

        double p90Ms() {
            return percentileMs(90);
        }

        double p95Ms() {
            return percentileMs(95);
        }

        double p99Ms() {
            return percentileMs(99);
        }

        double maxMs() {
            return nanosToMs(sortedLatencies()[sortedLatencies().length - 1]);
        }

        double avgMs() {
            long sum = 0;
            for (long l : latenciesNs) {
                sum += l;
            }
            return nanosToMs(sum / latenciesNs.length);
        }

        double throughputOpsPerSec() {
            return (measuredIterations * 1_000_000_000.0) / totalElapsedNs;
        }

        private double percentileMs(int percentile) {
            long[] sorted = sortedLatencies();
            int index = (int) Math.ceil((percentile / 100.0) * sorted.length) - 1;
            return nanosToMs(sorted[Math.max(0, index)]);
        }

        private long[] sortedLatencies() {
            long[] sorted = Arrays.copyOf(latenciesNs, latenciesNs.length);
            Arrays.sort(sorted);
            return sorted;
        }

        private static double nanosToMs(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    // -----------------------------------------------------------------------
    // Reporting + soft assertion
    // -----------------------------------------------------------------------

    private void logReport(String scenario, BenchmarkReport report) {
        LOG.info(
                "xform-bench.{} warmup={} measured={} totalMs={} avgMs={} p50Ms={} p90Ms={} p95Ms={} p99Ms={} maxMs={} opsPerSec={}",
                scenario,
                report.warmupIterations(),
                report.measuredIterations(),
                String.format("%.1f", report.totalElapsedNs / 1_000_000.0),
                String.format("%.3f", report.avgMs()),
                String.format("%.3f", report.p50Ms()),
                String.format("%.3f", report.p90Ms()),
                String.format("%.3f", report.p95Ms()),
                String.format("%.3f", report.p99Ms()),
                String.format("%.3f", report.maxMs()),
                String.format("%.0f", report.throughputOpsPerSec()));

        // Environment summary
        LOG.info(
                "xform-bench.env os={} arch={} java={} cpus={}",
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.runtime.version"),
                Runtime.getRuntime().availableProcessors());
    }

    private void softAssertNfr(BenchmarkReport report, String scenario) {
        if (report.p95Ms() > NFR_001_03_TARGET_MS) {
            LOG.warn(
                    "⚠ NFR-001-03 EXCEEDED — {} p95={}ms > target={}ms. "
                            + "This is a soft assertion (benchmark warning, not a build failure). "
                            + "Review transform complexity or payload size.",
                    scenario,
                    String.format("%.3f", report.p95Ms()),
                    NFR_001_03_TARGET_MS);
        } else {
            LOG.info(
                    "✓ NFR-001-03 MET — {} p95={}ms ≤ target={}ms",
                    scenario,
                    String.format("%.3f", report.p95Ms()),
                    NFR_001_03_TARGET_MS);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isBenchmarkEnabled() {
        // System property takes precedence, then env var
        String prop = System.getProperty("io.messagexform.benchmark");
        if (prop != null) {
            return "true".equalsIgnoreCase(prop);
        }
        String env = System.getenv("IO_MESSAGEXFORM_BENCHMARK");
        return "true".equalsIgnoreCase(env);
    }

    private TransformEngine createEngine() {
        EngineRegistry registry = new EngineRegistry();
        registry.register(new JsltExpressionEngine());
        SpecParser specParser = new SpecParser(registry);
        return new TransformEngine(specParser);
    }

    private void assertTransformSuccess(TransformResult result, String context) {
        if (!result.isSuccess()) {
            throw new AssertionError("Transform failed (" + context + "): " + result);
        }
    }

    // --- Payload generators ---

    /**
     * Generates a simple JSON object with enough padding to reach the target size.
     * Structure: {@code {"id":"...","name":"...","email":"...","data":"<padding>"}}
     */
    private static String generateSimplePayload(int targetBytes) {
        String prefix = """
                {"id":"usr-42","name":"Bob Jensen","email":"bjensen@example.com","data":\"""";
        String suffix = "\"}";
        int paddingNeeded = Math.max(0, targetBytes - prefix.length() - suffix.length());
        return prefix + "x".repeat(paddingNeeded) + suffix;
    }

    /**
     * Generates a JSON object with the 5 fields that the field-mapping spec
     * expects.
     * Padded with a data field to reach the target size.
     */
    private static String generateFieldMappingPayload(int targetBytes) {
        String prefix = """
                {"user_id":"usr-42","first_name":"Bob","last_name":"Jensen","email_address":"bjensen@example.com","is_active":true,"data":\"""";
        String suffix = "\"}";
        int paddingNeeded = Math.max(0, targetBytes - prefix.length() - suffix.length());
        return prefix + "x".repeat(paddingNeeded) + suffix;
    }

    /**
     * Generates a JSON array payload with nested entries for the complex transform.
     * Each entry simulates a SCIM user resource.
     */
    private static String generateArrayPayload(int targetBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"totalResults\":0,\"Resources\":[");

        // Each entry is ~250 bytes
        String entryTemplate = """
                {"id":"UUID_%04d","userName":"user%04d","displayName":"User Number %04d","emails":[{"value":"user%04d@example.com","type":"work","primary":true}],"active":true,"role":"user"}""";

        int count = 0;
        while (sb.length() < targetBytes - 100) { // leave room for closing
            if (count > 0) {
                sb.append(",");
            }
            sb.append(String.format(entryTemplate, count, count, count, count));
            count++;
        }

        sb.append("]}");

        // Fix totalResults
        String result = sb.toString();
        result = result.replaceFirst("\"totalResults\":0", "\"totalResults\":" + count);

        return result;
    }
}
