package com.gkcontas.metrics.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.springframework.stereotype.Component;

/**
 * Cardinality explosion, reproduced on demand and then contained.
 *
 * <p>The failure is not subtle once it happens: a metric tagged by something unbounded
 * produces one time series per distinct value, in the application's memory and again in
 * Prometheus, and the number grows with traffic forever. What makes it dangerous is that
 * it looks completely reasonable in a code review — "count orders by customer" is a
 * sentence a product manager would say.
 *
 * <p>Two meters here, same data, different tag:
 * <ul>
 *   <li>{@code orders.by_customer} tagged by e-mail — unbounded, and capped at ten
 *       distinct values by the {@code MeterFilter} in {@link MetricsConfig}.
 *   <li>{@code orders.by_channel} tagged by channel — three values, forever.
 * </ul>
 *
 * <p>The identity that matters: <em>series = metric × product of the distinct values of
 * every tag</em>. Two tags of a hundred values each is ten thousand series from one line
 * of instrumentation.
 */
@Component
public class CardinalityDemo {

    public static final String BY_CUSTOMER = "orders.by_customer";
    public static final String BY_CHANNEL = "orders.by_channel";

    private final MeterRegistry registry;

    public CardinalityDemo(MeterRegistry registry) {
        this.registry = registry;
    }

    /** The dangerous one: one new time series per customer the service has ever seen. */
    public void countByCustomer(String customerEmail) {
        Counter.builder(BY_CUSTOMER)
                .description("DO NOT COPY: tagged by an unbounded value, on purpose")
                .tag("customer", customerEmail)
                .register(registry)
                .increment();
    }

    /** The same count, tagged by something closed. */
    public void countByChannel(String channel) {
        Counter.builder(BY_CHANNEL)
                .description("The same count with a bounded dimension")
                .tag("channel", channel)
                .register(registry)
                .increment();
    }

    public int seriesFor(String meterName) {
        return Search.in(registry).name(meterName).meters().size();
    }

    /** Total series currently held by the registry, across every meter. */
    public long totalSeries() {
        return registry.getMeters().stream().map(Meter::getId).distinct().count();
    }
}
