package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.NullNode;
import io.messagexform.core.testkit.TestMessages;
import org.junit.jupiter.api.Test;

/**
 * Tests for spec provenance metadata on {@link TransformResult} (T-001-67,
 * DO-001-05).
 *
 * <p>
 * Verifies that {@code specId()} and {@code specVersion()} are correctly
 * populated for SUCCESS and ERROR results, and null for PASSTHROUGH.
 */
class TransformResultSpecMetadataTest {

    private static final Message SAMPLE_MSG = new Message(
            TestMessages.toBody(NullNode.getInstance(), null),
            HttpHeaders.empty(),
            200,
            null,
            null,
            null,
            SessionContext.empty());

    @Test
    void successWithSpecMetadata() {
        var result = TransformResult.success(SAMPLE_MSG, "callback-prettify", "1.0.0");

        assertThat(result.type()).isEqualTo(TransformResult.Type.SUCCESS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.specId()).isEqualTo("callback-prettify");
        assertThat(result.specVersion()).isEqualTo("1.0.0");
        assertThat(result.message()).isSameAs(SAMPLE_MSG);
    }

    @Test
    void errorWithSpecMetadata() {
        var errorBody = TestMessages.toBody(NullNode.getInstance());
        var result = TransformResult.error(errorBody, 502, "callback-prettify", "1.0.0");

        assertThat(result.type()).isEqualTo(TransformResult.Type.ERROR);
        assertThat(result.isError()).isTrue();
        assertThat(result.specId()).isEqualTo("callback-prettify");
        assertThat(result.specVersion()).isEqualTo("1.0.0");
        assertThat(result.errorResponse()).isSameAs(errorBody);
        assertThat(result.errorStatusCode()).isEqualTo(502);
    }

    @Test
    void passthroughHasNullSpecMetadata() {
        var result = TransformResult.passthrough();

        assertThat(result.type()).isEqualTo(TransformResult.Type.PASSTHROUGH);
        assertThat(result.isPassthrough()).isTrue();
        assertThat(result.specId()).isNull();
        assertThat(result.specVersion()).isNull();
    }

    @Test
    void backwardCompatSuccessHasNullSpecMetadata() {
        var result = TransformResult.success(SAMPLE_MSG);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.specId()).isNull();
        assertThat(result.specVersion()).isNull();
        assertThat(result.message()).isSameAs(SAMPLE_MSG);
    }

    @Test
    void backwardCompatErrorHasNullSpecMetadata() {
        var errorBody = TestMessages.toBody(NullNode.getInstance());
        var result = TransformResult.error(errorBody, 502);

        assertThat(result.isError()).isTrue();
        assertThat(result.specId()).isNull();
        assertThat(result.specVersion()).isNull();
    }

    @Test
    void toStringIncludesSpecIdWhenPresent() {
        var result = TransformResult.success(SAMPLE_MSG, "my-spec", "2.1.0");
        assertThat(result.toString()).contains("SUCCESS").contains("my-spec@2.1.0");
    }

    @Test
    void toStringOmitsSpecIdWhenNull() {
        var result = TransformResult.success(SAMPLE_MSG);
        assertThat(result.toString()).contains("SUCCESS").doesNotContain("@");
    }
}
