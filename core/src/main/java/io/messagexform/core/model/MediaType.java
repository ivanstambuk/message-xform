package io.messagexform.core.model;

import java.util.Locale;

/**
 * Supported media types for message bodies (DO-001-08, FR-001-14a).
 *
 * <p>
 * Core uses this to determine parsing strategy (JSON, XML, etc.). This is a
 * core-owned type with
 * zero third-party dependencies — it replaces raw {@code String contentType}
 * and prevents leaking
 * Jackson or Jakarta types into the public API (ADR-0032, ADR-0033).
 */
public enum MediaType {
    /** {@code application/json} and structured suffixes ({@code +json}). */
    JSON("application/json"),

    /** {@code application/xml} and structured suffixes ({@code +xml}). */
    XML("application/xml"),

    /** {@code application/x-www-form-urlencoded}. */
    FORM("application/x-www-form-urlencoded"),

    /** {@code text/plain}. */
    TEXT("text/plain"),

    /**
     * {@code application/octet-stream} — also used as fallback for unrecognized
     * types.
     */
    BINARY("application/octet-stream"),

    /** No content type (body absent or not specified). */
    NONE(null);

    private final String value;

    MediaType(String value) {
        this.value = value;
    }

    /** Returns the MIME type string, or {@code null} for {@link #NONE}. */
    public String value() {
        return value;
    }

    /**
     * Resolves a Content-Type header value to a {@link MediaType}.
     *
     * <ul>
     * <li>Ignores parameters (charset, boundary, etc.) — only the MIME type is
     * considered.
     * <li>Recognizes structured suffixes (RFC 6838): {@code +json} → {@link #JSON},
     * {@code +xml}
     * → {@link #XML}.
     * <li>Returns {@link #NONE} for {@code null} or blank input.
     * <li>Returns {@link #BINARY} for unrecognized types.
     * </ul>
     *
     * @param contentType the Content-Type header value (e.g.,
     *                    {@code "application/json;
     *     charset=utf-8"})
     * @return the resolved {@code MediaType}
     */
    public static MediaType fromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return NONE;
        }

        // Strip parameters (everything after ';')
        String mime = contentType;
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon);
        }
        mime = mime.strip().toLowerCase(Locale.ROOT);

        // Exact match
        for (MediaType type : values()) {
            if (type.value != null && type.value.equals(mime)) {
                return type;
            }
        }

        // Structured suffix match (RFC 6838)
        if (mime.endsWith("+json")) {
            return JSON;
        }
        if (mime.endsWith("+xml")) {
            return XML;
        }

        return BINARY;
    }
}
