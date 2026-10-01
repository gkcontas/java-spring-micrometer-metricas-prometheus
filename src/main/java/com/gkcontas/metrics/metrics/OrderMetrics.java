package com.gkcontas.metrics.metrics;

import com.gkcontas.metrics.domain.OrderChannel;
import com.gkcontas.metrics.domain.OrderStatus;
import com.gkcontas.metrics.domain.PaymentFailureReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The four primitive meter types, each on a case where it is the right one.
 *
 * <p>Picking the wrong type does not fail; it produces a chart that quietly says something
 * untrue. The distinctions that matter:
 *
 * <ul>
 *   <li><b>Counter</b> — monotonic. It only ever goes up, and Prometheus understands that
 *       a drop means the process restarted. Always query it through {@code rate()}; the
 *       raw value ("42819 orders since the last deploy") answers no question anyone asks.
 *   <li><b>Gauge</b> — an instantaneous reading, sampled at scrape time. It is <em>not</em>
 *       for counting events: a gauge you increment loses every change that happened
 *       between two scrapes, and a restart silently resets it with no way to tell.
 *   <li><b>Timer</b> — count, total time and a distribution of durations, in one meter.
 *   <li><b>DistributionSummary</b> — the same, for a quantity that is not a duration.
 * </ul>
 *
 * <p>Every meter here is registered through a builder rather than cached in a field.
 * Micrometer deduplicates by name plus tags, so repeated registration returns the same
 * instrument — and the lookup is cheap enough that pre-caching one field per tag
 * combination buys nothing but a combinatorial explosion of fields.
 */
@Component
public class OrderMetrics {

    static final String ORDERS_ACCEPTED = "orders.accepted";
    static final String ORDERS_PENDING = "orders.pending";
    static final String ORDERS_PROCESSING = "orders.processing";
    static final String ORDERS_AMOUNT = "orders.amount";
    static final String PAYMENT_FAILURES = "orders.payment.failures";

    private final MeterRegistry registry;

    /**
     * The state the gauge reads, held in a field of a singleton bean.
     *
     * <p>This is not incidental. Micrometer keeps only a <em>weak</em> reference to the
     * object a gauge measures, so that instrumenting something does not keep it alive. The
     * consequence is that a gauge registered on a local variable works until the next
     * garbage collection and then reports {@code NaN} — no exception, no log, the line on
     * the dashboard simply stops. {@code GaugeReferenceDemo} reproduces it on demand.
     */
    private final AtomicLong pendingOrders = new AtomicLong();

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder(ORDERS_PENDING, pendingOrders, AtomicLong::get)
                .description("Orders waiting to be processed, as of the last scrape")
                .baseUnit("orders")
                .register(registry);
    }

    /** One order created. Tagged by two dimensions that are both closed enums. */
    public void orderCreated(OrderChannel channel, OrderStatus status) {
        Counter.builder(ORDERS_ACCEPTED)
                .description("Orders accepted by the service")
                .tags("channel", channel.name(), "status", status.name())
                .register(registry)
                .increment();
    }

    /**
     * The order's value, as a distribution rather than a sum.
     *
     * <p>A counter of total revenue answers "how much"; this also answers "how is it
     * spread", which is what tells a thousand-real order apart from a thousand one-real
     * orders on a chart where both show the same total.
     */
    public void orderAmount(OrderChannel channel, BigDecimal amount) {
        DistributionSummary.builder(ORDERS_AMOUNT)
                .description("Distribution of order values")
                .baseUnit("BRL")
                // Buckets in the units of the thing being measured, chosen where the
                // business actually asks questions ("how much of the revenue comes from
                // orders above a thousand?"), not where a default happened to put them.
                .serviceLevelObjectives(50, 100, 250, 500, 1_000, 2_500)
                .tag("channel", channel.name())
                .register(registry)
                .record(amount.doubleValue());
    }

    /**
     * Times a processing attempt and tags it with the outcome.
     *
     * <p>The {@code outcome} tag is what makes the timer usable: without it, a flood of
     * fast failures <em>lowers</em> the p95 and the dashboard looks better precisely when
     * the service is broken.
     */
    public <T> T recordProcessing(Supplier<T> work, Function<T, String> outcomeOf) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            T result = work.get();
            outcome = outcomeOf.apply(result);
            return result;
        } finally {
            // Stopped with the outcome already known. Starting the sample and deciding the
            // tag afterwards is the one thing @Timed cannot do, and the reason this method
            // is worth its extra lines.
            sample.stop(processingTimer(outcome));
        }
    }

    public void recordProcessing(String outcome, Duration duration) {
        processingTimer(outcome).record(duration);
    }

    private Timer processingTimer(String outcome) {
        return Timer.builder(ORDERS_PROCESSING)
                .description("Time to process one order")
                .tag("outcome", outcome)
                // No percentiles here: SloMeterFilter publishes buckets instead, and the
                // reason is in its javadoc.
                .register(registry);
    }

    public void paymentFailed(PaymentFailureReason reason) {
        Counter.builder(PAYMENT_FAILURES)
                .description("Payment attempts rejected, by cause")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
    }

    public void setPendingOrders(long value) {
        pendingOrders.set(value);
    }

    public long pendingOrders() {
        return pendingOrders.get();
    }

    /** Only for the cardinality demonstration — see {@link CardinalityDemo}. */
    public MeterRegistry registry() {
        return registry;
    }
}
