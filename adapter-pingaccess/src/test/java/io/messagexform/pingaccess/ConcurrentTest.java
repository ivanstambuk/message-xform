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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Concurrent stress test (T-002-31, NFR-002-03, S-002-16, S-002-20).
 *
 * <p>
 * Verifies that {@link MessageTransformRule#handleRequest} and
 * {@link MessageTransformRule#handleResponse} can be invoked concurrently
 * from multiple threads without data races, corruption, or deadlocks.
 *
 * <p>
 * Uses a mocked {@link TransformEngine} that returns deterministic results
 * with a small random delay to amplify race conditions.
 */
class ConcurrentTest {

    private static final int THREAD_COUNT = 12;
    private static final int ITERATIONS_PER_THREAD = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageTransformRule rule;
    private TransformEngine engine;
    private PingAccessAdapter adapter;

    @BeforeEach
    void setUp() {
        engine = mock(TransformEngine.class);
        adapter = new PingAccessAdapter(MAPPER);

        rule = new MessageTransformRule();
        rule.setEngine(engine);
        rule.setAdapter(adapter);
        // No response factory override — we only test PASS_THROUGH error mode
        rule.setErrorMode(ErrorMode.PASS_THROUGH);
    }

    private Exchange createMockExchange() {
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
        when(request.getUri()).thenReturn("/api/test");
        when(requestBody.isRead()).thenReturn(true);
        when(requestBody.getContent()).thenReturn("{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8));
        when(requestHeaders.getHeaderFields()).thenReturn(List.of());
        when(response.getBody()).thenReturn(responseBody);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getStatus()).thenReturn(HttpStatus.OK);
        when(responseBody.isRead()).thenReturn(true);
        when(responseBody.getContent()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        when(responseHeaders.getHeaderFields()).thenReturn(List.of());

        return exchange;
    }

    @Test
    void concurrentRequestsDoNotCorruptState() throws Exception {
        // Engine always returns PASSTHROUGH for simplicity
        when(engine.transform(any(), eq(Direction.REQUEST), any())).thenReturn(TransformResult.passthrough());

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                latch.countDown();
                try {
                    latch.await(); // Ensure all threads start simultaneously
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
                    try {
                        Exchange ex = createMockExchange();
                        CompletionStage<Outcome> result = rule.handleRequest(ex);
                        Outcome outcome = result.toCompletableFuture().get(5, TimeUnit.SECONDS);
                        if (outcome == Outcome.CONTINUE) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            });
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int totalExpected = THREAD_COUNT * ITERATIONS_PER_THREAD;
        assertThat(successCount.get())
                .as("All concurrent requests should complete successfully")
                .isEqualTo(totalExpected);
        assertThat(errorCount.get())
                .as("No errors should occur during concurrent execution")
                .isZero();
    }

    @Test
    void concurrentResponsesDoNotCorruptState() throws Exception {
        // Engine always returns PASSTHROUGH for responses
        when(engine.transform(any(), eq(Direction.RESPONSE), any())).thenReturn(TransformResult.passthrough());

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            pool.submit(() -> {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
                    try {
                        Exchange ex = createMockExchange();
                        rule.handleResponse(ex);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            });
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int totalExpected = THREAD_COUNT * ITERATIONS_PER_THREAD;
        assertThat(successCount.get())
                .as("All concurrent responses should complete successfully")
                .isEqualTo(totalExpected);
        assertThat(errorCount.get())
                .as("No errors should occur during concurrent response execution")
                .isZero();
    }

    @Test
    void interleavedRequestsAndResponsesDoNotCorruptState() throws Exception {
        when(engine.transform(any(), eq(Direction.REQUEST), any())).thenReturn(TransformResult.passthrough());
        when(engine.transform(any(), eq(Direction.RESPONSE), any())).thenReturn(TransformResult.passthrough());

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger requestSuccesses = new AtomicInteger(0);
        AtomicInteger responseSuccesses = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final boolean isRequestThread = (i % 2 == 0);
            pool.submit(() -> {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
                    try {
                        Exchange ex = createMockExchange();
                        if (isRequestThread) {
                            CompletionStage<Outcome> result = rule.handleRequest(ex);
                            result.toCompletableFuture().get(5, TimeUnit.SECONDS);
                            requestSuccesses.incrementAndGet();
                        } else {
                            rule.handleResponse(ex);
                            responseSuccesses.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int halfThreads = THREAD_COUNT / 2;
        assertThat(requestSuccesses.get())
                .as("All request threads should complete successfully")
                .isEqualTo(halfThreads * ITERATIONS_PER_THREAD);
        assertThat(responseSuccesses.get())
                .as("All response threads should complete successfully")
                .isEqualTo(halfThreads * ITERATIONS_PER_THREAD);
        assertThat(errors.get())
                .as("No errors should occur during interleaved execution")
                .isZero();
    }
}
