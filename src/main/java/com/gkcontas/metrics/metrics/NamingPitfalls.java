package com.gkcontas.metrics.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Two counters that differ by one word, and only one of them is scrapeable under the name
 * it was given.
 *
 * <p>OpenMetrics reserves a handful of name suffixes, {@code _created} among them: it
 * marks the series carrying a counter's creation timestamp. The Prometheus client library
 * Micrometer delegates to strips a reserved suffix from the base name before appending
 * {@code _total}. So a counter called {@code demo.orders.created} is published as
 * {@code demo_orders_total} — the word disappears.
 *
 * <p>It fails in the worst possible way: the application starts, the metric is recorded,
 * the value is correct, and every dashboard query written as {@code orders_created_total}
 * matches nothing and draws a flat line at zero. Which reads exactly like "no orders".
 *
 * <p>This is why the real business counter in {@link OrderMetrics} is called
 * {@code orders.accepted}. These two exist so the collision can be seen in the exposition
 * output instead of taken on faith.
 */
@Component
public class NamingPitfalls {

    static final String RESERVED_SUFFIX = "demo.orders.created";
    static final String SAFE_NAME = "demo.orders.accepted";

    public NamingPitfalls(MeterRegistry registry) {
        Counter.builder(RESERVED_SUFFIX)
                .description("Published as demo_orders_total: the _created suffix is reserved")
                .register(registry)
                .increment();
        Counter.builder(SAFE_NAME)
                .description("Published as demo_orders_accepted_total, as written")
                .register(registry)
                .increment();
    }
}
