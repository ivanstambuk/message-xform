package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

/** Tests for {@link TransformResult} (DO-001-05). */
class TransformResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void successResultHasTransformedMessage() {
        var msg = new Message(NullNode.getInstance(), null, null, 200, null, null, null);
        var result = TransformResult.success(msg);

        assertThat(result.type()).isEqualTo(TransformResult.Type.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(result.isPassthrough()).isFalse();
        assertThat(result.message()).isSameAs(msg);
        assertThat(result.errorResponse()).isNull();
        assertThat(result.errorStatusCode()).isNull();
    }

    @Test
    void errorResultHasErrorResponseAndStatus() throws Exception {
        var errorBody = MAPPER.readTree("{\"type\":\"urn:message-xform:error:transform-failed\"}");
        var result = TransformResult.error(errorBody, 502);

        assertThat(result.type()).isEqualTo(TransformResult.Type.ERROR);
        assertThat(result.isError()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isPassthrough()).isFalse();
        assertThat(result.message()).isNull();
        assertThat(result.errorResponse()).isSameAs(errorBody);
        assertThat(result.errorStatusCode()).isEqualTo(502);
    }

    @Test
    void passthroughResultHasNoMessageOrError() {
        var result = TransformResult.passthrough();

        assertThat(result.type()).isEqualTo(TransformResult.Type.PASSTHROUGH);
        assertThat(result.isPassthrough()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isError()).isFalse();
        assertThat(result.message()).isNull();
        assertThat(result.errorResponse()).isNull();
        assertThat(result.errorStatusCode()).isNull();
    }

    @Test
    void successRequiresNonNullMessage() {
        assertThatThrownBy(() -> TransformResult.success(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transformedMessage must not be null");
    }

    @Test
    void errorRequiresNonNullErrorResponse() {
        assertThatThrownBy(() -> TransformResult.error(null, 500))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorResponse must not be null");
    }

    @Test
    void toStringContainsType() {
        assertThat(TransformResult.passthrough().toString()).contains("PASSTHROUGH");
        assertThat(TransformResult.error(NullNode.getInstance(), 502).toString())
                .contains("ERROR")
                .contains("502");
    }
}
