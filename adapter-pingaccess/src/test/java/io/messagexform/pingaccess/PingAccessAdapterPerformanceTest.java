package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
import com.pingidentity.pa.sdk.interceptor.Outcome;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.TransformResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Adapter overhead performance test (T-002-31a, NFR-002-01).
 *
 * <p>
 * Measures the adapter-only overhead (wrapping, context building, dispatch)
 * by mocking the core engine to return instantly. The measured time is
 * pure adapter overhead excluding any transform computation.
 *
 * <p>
 * NFR-002-01 budget: {@code < 10 ms} for a typical JSON body {@code < 64 KB}.
 */
class PingAccessAdapterPerformanceTest {

    private static final int WARMUP_ITERATIONS = 500;
    private static final int MEASURED_ITERATIONS = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * ~64 KB JSON payload — the spec's measurement threshold.
     */
    private static final byte[] PAYLOAD_64KB = generate64KbJson();

    private MessageTransformRule rule;
    private TransformEngine engine;

    @BeforeEach
    void setUp() {
        engine = mock(TransformEngine.class);
        PingAccessAdapter adapter = new PingAccessAdapter(MAPPER);

        rule = new MessageTransformRule();
        rule.setEngine(engine);
        rule.setAdapter(adapter);
        rule.setErrorMode(ErrorMode.PASS_THROUGH);
    }

    private Exchange createExchangeWith64KbBody() {
        Exchange exchange = mock(Exchange.class);
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        Body requestBody = mock(Body.class);
        Body responseBody = mock(Body.class);
        Headers requestHeaders = mock(Headers.class);
        Headers responseHeaders = mock(Headers.class);

        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);
        when(request.getBody()).thenReturn(requestBody);
        when(request.getHeaders()).thenReturn(requestHeaders);
        when(request.getMethod()).thenReturn(Method.POST);
        when(request.getUri()).thenReturn("/api/large-payload");
        when(requestBody.isRead()).thenReturn(true);
        when(requestBody.getContent()).thenReturn(PAYLOAD_64KB);
        when(requestHeaders.getHeaderFields()).thenReturn(List.of());
        when(response.getBody()).thenReturn(responseBody);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getStatus()).thenReturn(HttpStatus.OK);
        when(responseBody.isRead()).thenReturn(true);
        when(responseBody.getContent()).thenReturn(PAYLOAD_64KB);
        when(responseHeaders.getHeaderFields()).thenReturn(List.of());

        return exchange;
    }

    @Test
    void requestAdapterOverheadUnder10ms() throws Exception {
        // Engine returns PASSTHROUGH instantly — no transform computation
        when(engine.transform(any(), eq(Direction.REQUEST), any())).thenReturn(TransformResult.passthrough());

        // Warmup — let JIT optimize
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Exchange ex = createExchangeWith64KbBody();
            rule.handleRequest(ex).toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            Exchange ex = createExchangeWith64KbBody();
            long start = System.nanoTime();
            CompletionStage<Outcome> result = rule.handleRequest(ex);
            result.toCompletableFuture().get(5, TimeUnit.SECONDS);
            totalNanos += System.nanoTime() - start;
        }

        double avgMs = (totalNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;
        System.out.printf("Request adapter overhead (64KB, %d iters): avg=%.3f ms%n", MEASURED_ITERATIONS, avgMs);

        assertThat(avgMs)
                .as("Adapter-only request overhead must be < 10ms (NFR-002-01), actual: %.3f ms", avgMs)
                .isLessThan(10.0);
    }

    @Test
    void responseAdapterOverheadUnder10ms() throws Exception {
        // Engine returns PASSTHROUGH instantly — no transform computation
        when(engine.transform(any(), eq(Direction.RESPONSE), any())).thenReturn(TransformResult.passthrough());

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Exchange ex = createExchangeWith64KbBody();
            rule.handleResponse(ex);
        }

        // Measure
        long totalNanos = 0;
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            Exchange ex = createExchangeWith64KbBody();
            long start = System.nanoTime();
            rule.handleResponse(ex);
            totalNanos += System.nanoTime() - start;
        }

        double avgMs = (totalNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;
        System.out.printf("Response adapter overhead (64KB, %d iters): avg=%.3f ms%n", MEASURED_ITERATIONS, avgMs);

        assertThat(avgMs)
                .as("Adapter-only response overhead must be < 10ms (NFR-002-01), actual: %.3f ms", avgMs)
                .isLessThan(10.0);
    }

    private static byte[] generate64KbJson() {
        // Build a ~64KB JSON object with realistic field structure
        StringBuilder sb = new StringBuilder("{");
        int fieldCount = 0;
        while (sb.length() < 64 * 1024) {
            if (fieldCount > 0) {
                sb.append(",");
            }
            sb.append(String.format("\"field_%04d\":\"value_%s\"", fieldCount, "x".repeat(100)));
            fieldCount++;
        }
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
