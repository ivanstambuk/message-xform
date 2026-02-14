package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pingidentity.pa.sdk.http.*;
import com.pingidentity.pa.sdk.interceptor.Outcome;
import io.messagexform.core.engine.TransformEngine;
import io.messagexform.core.model.Direction;
import io.messagexform.core.model.HttpHeaders;
import io.messagexform.core.model.MessageBody;
import io.messagexform.core.model.SessionContext;
import io.messagexform.core.model.TransformContext;
import io.messagexform.core.model.TransformResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for request/response orchestration in {@link MessageTransformRule}
 * (T-002-20 through T-002-24, Phase 5 — Error Mode Dispatch).
 *
 * <p>
 * Uses a real {@link PingAccessAdapter} but a mocked {@link TransformEngine}
 * to control transform outcomes. Exchange objects are fully mocked.
 */
class TransformFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageTransformRule rule;
    private TransformEngine engine;
    private PingAccessAdapter adapter;

    private Exchange exchange;
    private Request request;
    private Response response;
    private Body requestBody;
    private Body responseBody;
    private Headers requestHeaders;
    private Headers responseHeaders;

    @BeforeEach
    void setUp() {
        engine = mock(TransformEngine.class);
        adapter = new PingAccessAdapter(MAPPER);

        rule = new MessageTransformRule();
        rule.setEngine(engine);
        rule.setAdapter(adapter);

        exchange = mock(Exchange.class);
        request = mock(Request.class);
        response = mock(Response.class);
        requestBody = mock(Body.class);
        responseBody = mock(Body.class);
        requestHeaders = mock(Headers.class);
        responseHeaders = mock(Headers.class);

        // Wire exchange defaults
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);
        when(request.getBody()).thenReturn(requestBody);
        when(request.getHeaders()).thenReturn(requestHeaders);
        when(request.getMethod()).thenReturn(Method.POST);
        when(request.getUri()).thenReturn("/api/test");
        when(requestBody.isRead()).thenReturn(true);
        when(requestBody.getContent()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(requestHeaders.getHeaderFields()).thenReturn(List.of());
        when(response.getBody()).thenReturn(responseBody);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(response.getStatus()).thenReturn(HttpStatus.OK);
        when(responseBody.isRead()).thenReturn(true);
        when(responseBody.getContent()).thenReturn("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        when(responseHeaders.getHeaderFields()).thenReturn(List.of());
    }

    // ── T-002-20: Request SUCCESS / PASSTHROUGH / bodyParseFailed ──

    @Nested
    class RequestSuccessAndPassthrough {

        @Test
        void successAppliesChangesAndContinues() throws Exception {
            // Engine returns SUCCESS with a transformed message
            io.messagexform.core.model.Message transformed = new io.messagexform.core.model.Message(
                    MessageBody.json("{\"wrapped\":true}"),
                    HttpHeaders.empty(),
                    null,
                    "/api/test",
                    "POST",
                    null,
                    SessionContext.empty());
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.REQUEST),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.success(transformed));

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Outcome> result = rule.handleRequest(exchange);

            assertThat(result.toCompletableFuture().get()).isEqualTo(Outcome.CONTINUE);
            // Verify URI was applied to request
            verify(request).setUri("/api/test");
        }

        @Test
        void passthroughSkipsApplyAndContinues() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.REQUEST),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.passthrough());

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Outcome> result = rule.handleRequest(exchange);

            assertThat(result.toCompletableFuture().get()).isEqualTo(Outcome.CONTINUE);
            // No changes applied — request URI not touched after wrap
            verify(request, never()).setUri(anyString());
        }
    }

    @Nested
    class BodyParseFailedSkipGuard {

        @Test
        void bodyParseFailedSkipsBodyTransformButContinues() throws Exception {
            // Non-JSON body → parse failure
            when(requestBody.getContent()).thenReturn("hello world".getBytes(StandardCharsets.UTF_8));

            // Engine returns SUCCESS (header transform works even with empty body)
            io.messagexform.core.model.Message transformed = new io.messagexform.core.model.Message(
                    MessageBody.empty(), // body transforms would produce empty
                    HttpHeaders.empty(),
                    null,
                    "/api/test",
                    "POST",
                    null,
                    SessionContext.empty());
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.REQUEST),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.success(transformed));

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Outcome> result = rule.handleRequest(exchange);

            assertThat(result.toCompletableFuture().get()).isEqualTo(Outcome.CONTINUE);
            // Body NOT applied (skip guard): setBodyContent not called on request
            verify(request, never()).setBodyContent(any(byte[].class));
            // But URI changes ARE applied (skip body variant still applies URI/headers)
            verify(request).setUri("/api/test");
        }
    }

    // ── T-002-21: Response SUCCESS / PASSTHROUGH ──

    @Nested
    class ResponseSuccessAndPassthrough {

        @Test
        void responseSuccessAppliesChanges() throws Exception {
            io.messagexform.core.model.Message transformed = new io.messagexform.core.model.Message(
                    MessageBody.json("{\"result\":\"ok\"}"),
                    HttpHeaders.empty(),
                    200,
                    "/api/test",
                    "POST",
                    null,
                    SessionContext.empty());
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.RESPONSE),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.success(transformed));

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Void> result = rule.handleResponse(exchange);

            result.toCompletableFuture().get();
            // Status should be applied
            verify(response).setStatus(HttpStatus.OK);
        }

        @Test
        void responsePassthroughSkipsApply() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.RESPONSE),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.passthrough());

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Void> result = rule.handleResponse(exchange);

            result.toCompletableFuture().get();
            // No changes applied — status not modified after wrap
            verify(response, never()).setStatus(any(HttpStatus.class));
        }
    }

    // ── T-002-22: PASS_THROUGH error mode ──

    @Nested
    class PassThroughOnError {

        @Test
        void requestErrorWithPassThroughContinues() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.REQUEST),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.error(MessageBody.json("{\"error\":true}"), 502));

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Outcome> result = rule.handleRequest(exchange);

            assertThat(result.toCompletableFuture().get()).isEqualTo(Outcome.CONTINUE);
            // Original body preserved — no setUri calls
            verify(request, never()).setUri(anyString());
        }

        @Test
        void responseErrorWithPassThroughPreservesResponse() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.RESPONSE),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.error(MessageBody.json("{\"error\":true}"), 502));

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Void> result = rule.handleResponse(exchange);

            result.toCompletableFuture().get();
            // Original response preserved
            verify(response, never()).setStatus(any(HttpStatus.class));
        }
    }

    // ── T-002-23: DENY error mode ──

    @Nested
    class DenyOnError {

        private Response mockErrorResponse;

        @BeforeEach
        void setUpDeny() {
            // ResponseBuilder requires PA ServiceFactory — inject a mock factory
            mockErrorResponse = mock(Response.class);
            when(mockErrorResponse.getHeaders()).thenReturn(mock(Headers.class));
            rule.setResponseFactory((status, body) -> mockErrorResponse);
        }

        @Test
        void requestErrorWithDenyReturnsOutcomeReturn() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.REQUEST),
                            any(TransformContext.class)))
                    .thenReturn(
                            TransformResult.error(MessageBody.json("{\"type\":\"about:blank\",\"status\":502}"), 502));

            rule.setErrorMode(ErrorMode.DENY);
            CompletionStage<Outcome> result = rule.handleRequest(exchange);

            assertThat(result.toCompletableFuture().get()).isEqualTo(Outcome.RETURN);
        }

        @Test
        void requestErrorWithDenySetsResponse() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.REQUEST),
                            any(TransformContext.class)))
                    .thenReturn(
                            TransformResult.error(MessageBody.json("{\"type\":\"about:blank\",\"status\":502}"), 502));

            rule.setErrorMode(ErrorMode.DENY);
            rule.handleRequest(exchange).toCompletableFuture().get();

            // ResponseBuilder → exchange.setResponse() called
            verify(exchange).setResponse(any(Response.class));
        }

        @Test
        void requestErrorWithDenySetsTransformDeniedProperty() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.REQUEST),
                            any(TransformContext.class)))
                    .thenReturn(
                            TransformResult.error(MessageBody.json("{\"type\":\"about:blank\",\"status\":502}"), 502));

            rule.setErrorMode(ErrorMode.DENY);
            rule.handleRequest(exchange).toCompletableFuture().get();

            verify(exchange).setProperty(eq(MessageTransformRule.TRANSFORM_DENIED), eq(Boolean.TRUE));
        }

        @Test
        void responseErrorWithDenyRewritesInPlace() throws Exception {
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.RESPONSE),
                            any(TransformContext.class)))
                    .thenReturn(
                            TransformResult.error(MessageBody.json("{\"type\":\"about:blank\",\"status\":502}"), 502));

            rule.setErrorMode(ErrorMode.DENY);
            rule.handleResponse(exchange).toCompletableFuture().get();

            // Response rewritten in-place to 502
            verify(response).setStatus(HttpStatus.BAD_GATEWAY);
            verify(response).setBodyContent(any(byte[].class));
        }

        @Test
        void wrapResponseBodyParseFailedDenySkipsBody() throws Exception {
            // Non-JSON response body → parse failure
            when(responseBody.getContent()).thenReturn("not json".getBytes(StandardCharsets.UTF_8));

            // Engine returns SUCCESS for the response (body is empty due to parse failure)
            io.messagexform.core.model.Message transformed = new io.messagexform.core.model.Message(
                    MessageBody.empty(), HttpHeaders.empty(), 200, "/api/test", "POST", null, SessionContext.empty());
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.RESPONSE),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.success(transformed));

            rule.setErrorMode(ErrorMode.DENY);
            rule.handleResponse(exchange).toCompletableFuture().get();

            // Body parse failed → skip body replacement, keep original bytes
            verify(response, never()).setBodyContent(any(byte[].class));
        }
    }

    // ── T-002-24: DENY guard in handleResponse ──

    @Nested
    class DenyGuard {

        @Test
        void denyGuardSkipsResponseProcessing() throws Exception {
            // Simulate: handleRequest already set TRANSFORM_DENIED
            when(exchange.isPropertyTrue(MessageTransformRule.TRANSFORM_DENIED)).thenReturn(true);

            rule.setErrorMode(ErrorMode.DENY);
            CompletionStage<Void> result = rule.handleResponse(exchange);

            result.toCompletableFuture().get();
            // Engine should NOT be called
            verify(engine, never())
                    .transform(
                            any(io.messagexform.core.model.Message.class),
                            any(Direction.class),
                            any(TransformContext.class));
        }

        @Test
        void noDenyAllowsNormalResponseProcessing() throws Exception {
            when(exchange.isPropertyTrue(MessageTransformRule.TRANSFORM_DENIED)).thenReturn(false);
            when(engine.transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.RESPONSE),
                            any(TransformContext.class)))
                    .thenReturn(TransformResult.passthrough());

            rule.setErrorMode(ErrorMode.PASS_THROUGH);
            CompletionStage<Void> result = rule.handleResponse(exchange);

            result.toCompletableFuture().get();
            // Engine IS called
            verify(engine)
                    .transform(
                            any(io.messagexform.core.model.Message.class),
                            eq(Direction.RESPONSE),
                            any(TransformContext.class));
        }
    }
}
