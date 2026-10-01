package com.gkcontas.metrics.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The gauge that stops reporting, reproduced.
 *
 * <p>Micrometer holds a <b>weak</b> reference to the object a gauge reads. That is a
 * deliberate and correct choice — instrumenting an object must not keep it alive — but it
 * has a consequence that bites: a gauge registered on something the application no longer
 * references works perfectly until the next garbage collection, and then reports
 * {@code NaN} forever. No exception, no warning in the log; the line on the dashboard just
 * stops, and whoever is looking at it assumes the value is steady.
 *
 * <p>The fix is simply to keep the reference — a field on a singleton bean, as
 * {@link OrderMetrics} does. The reason it is worth demonstrating rather than asserting is
 * that the broken version is what you write naturally when registering a gauge inside a
 * method.
 */
@Component
public class GaugeReferenceDemo {

    static final String DANGLING = "demo.dangling.gauge";
    static final String ANCHORED = "demo.anchored.gauge";

    private final MeterRegistry registry;

    /** The anchor. Its only job is to exist for as long as the gauge does. */
    private final AtomicInteger anchoredValue = new AtomicInteger(42);

    public GaugeReferenceDemo(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Registers both gauges: one on a local object, one on the field above. */
    public void registerBoth() {
        AtomicInteger local = new AtomicInteger(42);
        Gauge.builder(DANGLING, local, AtomicInteger::get).register(registry);
        Gauge.builder(ANCHORED, anchoredValue, AtomicInteger::get).register(registry);
        // `local` goes out of scope here, and nothing else refers to it.
    }

    /**
     * Reads both gauges after asking for a collection.
     *
     * <p>{@code System.gc()} is a request, not a command, so this reads a few times before
     * giving up — the demonstration reports what actually happened rather than claiming an
     * outcome it did not observe.
     */
    public Reading readAfterGarbageCollection() {
        for (int attempt = 0; attempt < 10; attempt++) {
            System.gc();
            double dangling = valueOf(DANGLING);
            if (Double.isNaN(dangling)) {
                return new Reading(dangling, valueOf(ANCHORED), true);
            }
        }
        return new Reading(valueOf(DANGLING), valueOf(ANCHORED), false);
    }

    public double valueOf(String name) {
        Gauge gauge = Search.in(registry).name(name).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    /**
     * @param danglingGauge value of the gauge whose target was not kept alive
     * @param anchoredGauge value of the gauge whose target is a field
     * @param collected     whether the collection actually happened within the attempts
     */
    public record Reading(double danglingGauge, double anchoredGauge, boolean collected) {
    }
}
