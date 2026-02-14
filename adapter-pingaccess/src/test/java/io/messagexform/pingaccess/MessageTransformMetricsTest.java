package io.messagexform.pingaccess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MessageTransformMetrics} (T-002-27, FR-002-14).
 *
 * <p>
 * Verifies LongAdder-backed counters, latency tracking, reset behavior, and
 * the MXBean interface contract.
 */
class MessageTransformMetricsTest {

    private MessageTransformMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new MessageTransformMetrics();
    }

    @Nested
    class InitialState {

        @Test
        void allCountersStartAtZero() {
            assertThat(metrics.getTransformSuccessCount()).isZero();
            assertThat(metrics.getTransformPassthroughCount()).isZero();
            assertThat(metrics.getTransformErrorCount()).isZero();
            assertThat(metrics.getTransformDenyCount()).isZero();
            assertThat(metrics.getTransformTotalCount()).isZero();
            assertThat(metrics.getReloadSuccessCount()).isZero();
            assertThat(metrics.getReloadFailureCount()).isZero();
            assertThat(metrics.getActiveSpecCount()).isZero();
        }

        @Test
        void latencyStartsAtZero() {
            assertThat(metrics.getAverageTransformTimeMs()).isZero();
            assertThat(metrics.getMaxTransformTimeMs()).isZero();
            assertThat(metrics.getLastTransformTimeMs()).isZero();
        }
    }

    @Nested
    class TransformCounters {

        @Test
        void recordSuccessIncrementsCounter() {
            metrics.recordSuccess(10);
            metrics.recordSuccess(20);

            assertThat(metrics.getTransformSuccessCount()).isEqualTo(2);
            assertThat(metrics.getTransformTotalCount()).isEqualTo(2);
        }

        @Test
        void recordPassthroughIncrementsCounter() {
            metrics.recordPassthrough(5);

            assertThat(metrics.getTransformPassthroughCount()).isEqualTo(1);
            assertThat(metrics.getTransformTotalCount()).isEqualTo(1);
        }

        @Test
        void recordErrorIncrementsCounter() {
            metrics.recordError(15);

            assertThat(metrics.getTransformErrorCount()).isEqualTo(1);
            assertThat(metrics.getTransformTotalCount()).isEqualTo(1);
        }

        @Test
        void recordDenyIncrementsCounter() {
            metrics.recordDeny(12);

            assertThat(metrics.getTransformDenyCount()).isEqualTo(1);
            assertThat(metrics.getTransformTotalCount()).isEqualTo(1);
        }

        @Test
        void totalCountIsSumOfAllOutcomes() {
            metrics.recordSuccess(10);
            metrics.recordPassthrough(5);
            metrics.recordError(15);
            metrics.recordDeny(8);

            assertThat(metrics.getTransformTotalCount()).isEqualTo(4);
            assertThat(metrics.getTransformSuccessCount()).isEqualTo(1);
            assertThat(metrics.getTransformPassthroughCount()).isEqualTo(1);
            assertThat(metrics.getTransformErrorCount()).isEqualTo(1);
            assertThat(metrics.getTransformDenyCount()).isEqualTo(1);
        }
    }

    @Nested
    class ReloadCounters {

        @Test
        void recordReloadSuccessIncrements() {
            metrics.recordReloadSuccess();
            metrics.recordReloadSuccess();

            assertThat(metrics.getReloadSuccessCount()).isEqualTo(2);
        }

        @Test
        void recordReloadFailureIncrements() {
            metrics.recordReloadFailure();

            assertThat(metrics.getReloadFailureCount()).isEqualTo(1);
        }

        @Test
        void activeSpecCountIsSettable() {
            metrics.setActiveSpecCount(5);

            assertThat(metrics.getActiveSpecCount()).isEqualTo(5);

            metrics.setActiveSpecCount(3);

            assertThat(metrics.getActiveSpecCount()).isEqualTo(3);
        }
    }

    @Nested
    class LatencyTracking {

        @Test
        void averageTransformTimeTracksRollingAverage() {
            metrics.recordSuccess(10);
            metrics.recordSuccess(20);
            metrics.recordSuccess(30);

            assertThat(metrics.getAverageTransformTimeMs()).isEqualTo(20.0);
        }

        @Test
        void maxTransformTimeTracksMaximum() {
            metrics.recordSuccess(10);
            metrics.recordError(50);
            metrics.recordPassthrough(30);

            assertThat(metrics.getMaxTransformTimeMs()).isEqualTo(50);
        }

        @Test
        void lastTransformTimeTracksMostRecent() {
            metrics.recordSuccess(10);
            metrics.recordError(50);
            metrics.recordPassthrough(30);

            assertThat(metrics.getLastTransformTimeMs()).isEqualTo(30);
        }

        @Test
        void latencyTrackedAcrossAllOutcomeTypes() {
            metrics.recordSuccess(10);
            metrics.recordPassthrough(20);
            metrics.recordError(30);
            metrics.recordDeny(40);

            assertThat(metrics.getAverageTransformTimeMs()).isEqualTo(25.0);
            assertThat(metrics.getMaxTransformTimeMs()).isEqualTo(40);
            assertThat(metrics.getLastTransformTimeMs()).isEqualTo(40);
        }
    }

    @Nested
    class ResetBehavior {

        @Test
        void resetZerosAllCounters() {
            metrics.recordSuccess(10);
            metrics.recordPassthrough(5);
            metrics.recordError(15);
            metrics.recordDeny(8);
            metrics.recordReloadSuccess();
            metrics.recordReloadFailure();

            metrics.resetMetrics();

            assertThat(metrics.getTransformSuccessCount()).isZero();
            assertThat(metrics.getTransformPassthroughCount()).isZero();
            assertThat(metrics.getTransformErrorCount()).isZero();
            assertThat(metrics.getTransformDenyCount()).isZero();
            assertThat(metrics.getTransformTotalCount()).isZero();
            assertThat(metrics.getReloadSuccessCount()).isZero();
            assertThat(metrics.getReloadFailureCount()).isZero();
        }

        @Test
        void resetZerosLatencyStats() {
            metrics.recordSuccess(50);

            metrics.resetMetrics();

            assertThat(metrics.getAverageTransformTimeMs()).isZero();
            assertThat(metrics.getMaxTransformTimeMs()).isZero();
            assertThat(metrics.getLastTransformTimeMs()).isZero();
        }

        @Test
        void resetPreservesActiveSpecCount() {
            metrics.setActiveSpecCount(5);

            metrics.resetMetrics();

            assertThat(metrics.getActiveSpecCount()).isEqualTo(5);
        }

        @Test
        void resetIsIdempotent() {
            metrics.resetMetrics();
            metrics.resetMetrics(); // should not throw

            assertThat(metrics.getTransformTotalCount()).isZero();
        }

        @Test
        void countersWorkAfterReset() {
            metrics.recordSuccess(10);
            metrics.resetMetrics();
            metrics.recordSuccess(20);

            assertThat(metrics.getTransformSuccessCount()).isEqualTo(1);
            assertThat(metrics.getLastTransformTimeMs()).isEqualTo(20);
        }
    }

    @Nested
    class MXBeanContract {

        @Test
        void implementsMXBeanInterface() {
            assertThat(metrics).isInstanceOf(MessageTransformMetricsMXBean.class);
        }
    }
}
