package io.messagexform.core.model;

/**
 * Direction of a message transformation.
 *
 * <ul>
 *   <li>{@link #REQUEST} — transform is applied to an inbound request (before it reaches the
 *       backend).
 *   <li>{@link #RESPONSE} — transform is applied to an outbound response (before it reaches the
 *       client).
 * </ul>
 */
public enum Direction {
    REQUEST,
    RESPONSE
}
