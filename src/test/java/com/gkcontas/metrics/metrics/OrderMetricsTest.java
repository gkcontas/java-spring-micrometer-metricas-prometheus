package com.gkcontas.metrics.metrics;

import com.gkcontas.metrics.domain.OrderChannel;
import com.gkcontas.metrics.domain.OrderStatus;
import com.gkcontas.metrics.domain.PaymentFailureReason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Instrumentation tested without a Prometheus, a container or an HTTP call.
 *
 * <p>{@code SimpleMeterRegistry} is an in-memory registry with the same semantics as the
 * real one, which makes "did this record what I think it did" a plain unit test. Asserting
 * on metrics only through the scraped text means a broken tag is a string-matching failure
 * three layers away from the line that caused it.
 */
class OrderMetricsTest {

    private MeterRegistry registry;
    private OrderMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OrderMetrics(registry);
    }

    @Test
    void shouldCountOrdersSeparatelyPerChannelAndStatus() {
        metrics.orderCreated(OrderChannel.WEB, OrderStatus.PENDING);
        metrics.orderCreated(OrderChannel.WEB, OrderStatus.PENDING);
        metrics.orderCreated(OrderChannel.MOBILE, OrderStatus.PENDING);

        assertThat(registry.get(OrderMetrics.ORDERS_ACCEPTED)
                .tags("channel", "WEB", "status", "PENDING").counter().count()).isEqualTo(2);
        assertThat(registry.get(OrderMetrics.ORDERS_ACCEPTED)
                .tags("channel", "MOBILE", "status", "PENDING").counter().count()).isEqualTo(1);
    }

    @Test
    void shouldReturnTheSameCounterInstanceForTheSameTags() {
        metrics.orderCreated(OrderChannel.WEB, OrderStatus.PENDING);
        metrics.orderCreated(OrderChannel.WEB, OrderStatus.PENDING);

        // Registering again does not create a second meter: Micrometer deduplicates by
        // name plus tags, which is what makes the builder-per-call style safe.
        assertThat(registry.find(OrderMetrics.ORDERS_ACCEPTED).counters()).hasSize(1);
    }

    @Test
    void shouldTagTheTimerWithTheOutcomeDecidedAfterTheWorkRan() {
        String outcome = metrics.recordProcessing(() -> "PAID", String::toLowerCase);

        assertThat(outcome).isEqualTo("PAID");
        Timer timer = registry.get(OrderMetrics.ORDERS_PROCESSING).tag("outcome", "paid").timer();
        assertThat(timer.count()).isEqualTo(1);
        // The point of the whole construct: the tag value was not known when the timer
        // started, which is exactly what @Timed cannot express.
        assertThat(registry.find(OrderMetrics.ORDERS_PROCESSING).tag("outcome", "error").timer())
                .isNull();
    }

    @Test
    void shouldTagTheTimerAsErrorWhenTheWorkThrows() {
        try {
            metrics.recordProcessing(() -> {
                throw new IllegalStateException("gateway exploded");
            }, Object::toString);
        } catch (IllegalStateException expected) {
            // The exception must propagate; the timer records regardless.
        }

        assertThat(registry.get(OrderMetrics.ORDERS_PROCESSING).tag("outcome", "error")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void shouldRecordTheProcessingDuration() {
        metrics.recordProcessing("paid", Duration.ofMillis(250));

        assertThat(registry.get(OrderMetrics.ORDERS_PROCESSING).tag("outcome", "paid")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250);
    }

    @Test
    void shouldRecordTheDistributionOfOrderValues() {
        metrics.orderAmount(OrderChannel.WEB, new BigDecimal("100.00"));
        metrics.orderAmount(OrderChannel.WEB, new BigDecimal("900.00"));

        var summary = registry.get(OrderMetrics.ORDERS_AMOUNT).tag("channel", "WEB")
                .summary();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).isEqualTo(1_000.0);
        // The reason a summary beats a counter of revenue: the same total can come from
        // two very different distributions, and only one of them is a problem.
        assertThat(summary.max()).isEqualTo(900.0);
    }

    @Test
    void shouldExposePendingOrdersAsAGaugeThatFollowsTheState() {
        metrics.setPendingOrders(7);
        assertThat(registry.get(OrderMetrics.ORDERS_PENDING).gauge().value()).isEqualTo(7);

        // A gauge is a reading, not a tally: it goes down as happily as it goes up, which
        // is exactly why it must not be used to count events.
        metrics.setPendingOrders(2);
        assertThat(registry.get(OrderMetrics.ORDERS_PENDING).gauge().value()).isEqualTo(2);
    }

    @Test
    void shouldCountPaymentFailuresByCause() {
        metrics.paymentFailed(PaymentFailureReason.CARD_EXPIRED);
        metrics.paymentFailed(PaymentFailureReason.CARD_EXPIRED);
        metrics.paymentFailed(PaymentFailureReason.FRAUD_SUSPECTED);

        assertThat(registry.get(OrderMetrics.PAYMENT_FAILURES).tag("reason", "CARD_EXPIRED")
                .counter().count()).isEqualTo(2);
        assertThat(registry.find(OrderMetrics.PAYMENT_FAILURES).counters()).hasSize(2);
    }
}
