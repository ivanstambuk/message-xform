package io.messagexform.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.messagexform.core.model.StatusSpec;
import io.messagexform.core.model.TransformContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies declarative status code transformations to a message
 * (FR-001-11, ADR-0003, T-001-37).
 *
 * <p>
 * Processing rules:
 * <ul>
 * <li>If no {@code when} predicate → unconditionally return {@code set}
 * value.</li>
 * <li>If {@code when} predicate evaluates to {@code true} → return {@code set}
 * value.</li>
 * <li>If {@code when} predicate evaluates to {@code false} → return original
 * status.</li>
 * <li>If {@code when} predicate evaluation fails → log warning, return original
 * status.</li>
 * </ul>
 *
 * <p>
 * The {@code when} predicate is evaluated against the
 * <strong>transformed</strong> body
 * (consistent with header {@code add.expr}, per ADR-0003).
 */
public final class StatusTransformer {

    private static final Logger LOG = LoggerFactory.getLogger(StatusTransformer.class);

    private StatusTransformer() {
        // utility class
    }

    /**
     * Applies the status spec to determine the new status code.
     *
     * @param originalStatus  the current HTTP status code
     * @param statusSpec      the status transformation spec
     * @param transformedBody the body after JSLT transform (for when predicate
     *                        evaluation)
     * @return the resolved status code (may be unchanged if when predicate is false
     *         or errors)
     */
    public static Integer apply(Integer originalStatus, StatusSpec statusSpec, JsonNode transformedBody) {
        if (statusSpec == null) {
            return originalStatus;
        }

        // No when predicate → unconditional set
        if (statusSpec.when() == null) {
            LOG.debug("Unconditional status set: {} → {}", originalStatus, statusSpec.set());
            return statusSpec.set();
        }

        // Evaluate when predicate against transformed body
        try {
            JsonNode predicateResult = statusSpec.when().evaluate(transformedBody, TransformContext.empty());

            if (JsonNodeUtils.isTruthy(predicateResult)) {
                LOG.debug("Status when predicate matched: {} → {}", originalStatus, statusSpec.set());
                return statusSpec.set();
            } else {
                LOG.debug("Status when predicate not matched — keeping original status {}", originalStatus);
                return originalStatus;
            }
        } catch (Exception e) {
            // When predicate evaluation error → abort, keep original status (T-001-37)
            LOG.warn(
                    "Status when predicate evaluation failed — keeping original status {}: {}",
                    originalStatus,
                    e.getMessage());
            return originalStatus;
        }
    }
}
