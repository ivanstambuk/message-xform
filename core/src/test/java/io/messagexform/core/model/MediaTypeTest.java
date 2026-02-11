package io.messagexform.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link MediaType} (FR-001-14a, DO-001-08). */
class MediaTypeTest {

    // ── value() ──

    @Test
    void jsonValueIsApplicationJson() {
        assertThat(MediaType.JSON.value()).isEqualTo("application/json");
    }

    @Test
    void xmlValueIsApplicationXml() {
        assertThat(MediaType.XML.value()).isEqualTo("application/xml");
    }

    @Test
    void formValueIsFormUrlencoded() {
        assertThat(MediaType.FORM.value()).isEqualTo("application/x-www-form-urlencoded");
    }

    @Test
    void textValueIsTextPlain() {
        assertThat(MediaType.TEXT.value()).isEqualTo("text/plain");
    }

    @Test
    void binaryValueIsOctetStream() {
        assertThat(MediaType.BINARY.value()).isEqualTo("application/octet-stream");
    }

    @Test
    void noneValueIsNull() {
        assertThat(MediaType.NONE.value()).isNull();
    }

    // ── fromContentType() — exact matches ──

    @ParameterizedTest
    @CsvSource({
        "application/json, JSON",
        "application/xml, XML",
        "application/x-www-form-urlencoded, FORM",
        "text/plain, TEXT",
        "application/octet-stream, BINARY",
    })
    void fromContentTypeExactMatch(String contentType, MediaType expected) {
        assertThat(MediaType.fromContentType(contentType)).isEqualTo(expected);
    }

    // ── fromContentType() — parameters stripped ──

    @Test
    void fromContentTypeStripsCharsetParam() {
        assertThat(MediaType.fromContentType("application/json; charset=utf-8")).isEqualTo(MediaType.JSON);
    }

    @Test
    void fromContentTypeStripsBoundaryParam() {
        assertThat(MediaType.fromContentType("application/xml; boundary=something"))
                .isEqualTo(MediaType.XML);
    }

    // ── fromContentType() — structured suffixes (RFC 6838) ──

    @Test
    void fromContentTypeRecognizesPlusJson() {
        assertThat(MediaType.fromContentType("application/vnd.api+json")).isEqualTo(MediaType.JSON);
    }

    @Test
    void fromContentTypeRecognizesPlusXml() {
        assertThat(MediaType.fromContentType("application/atom+xml")).isEqualTo(MediaType.XML);
    }

    @Test
    void fromContentTypePlusJsonWithParams() {
        assertThat(MediaType.fromContentType("application/vnd.api+json; charset=utf-8"))
                .isEqualTo(MediaType.JSON);
    }

    // ── fromContentType() — edge cases ──

    @Test
    void fromContentTypeNullReturnsNone() {
        assertThat(MediaType.fromContentType(null)).isEqualTo(MediaType.NONE);
    }

    @Test
    void fromContentTypeEmptyReturnsNone() {
        assertThat(MediaType.fromContentType("")).isEqualTo(MediaType.NONE);
    }

    @Test
    void fromContentTypeBlankReturnsNone() {
        assertThat(MediaType.fromContentType("   ")).isEqualTo(MediaType.NONE);
    }

    @Test
    void fromContentTypeUnrecognizedReturnsBinary() {
        assertThat(MediaType.fromContentType("image/png")).isEqualTo(MediaType.BINARY);
    }

    @Test
    void fromContentTypeTextHtmlReturnsBinary() {
        // text/html is not a recognized MediaType — falls through to BINARY
        assertThat(MediaType.fromContentType("text/html")).isEqualTo(MediaType.BINARY);
    }

    @Test
    void fromContentTypeCaseInsensitive() {
        assertThat(MediaType.fromContentType("APPLICATION/JSON")).isEqualTo(MediaType.JSON);
    }
}
