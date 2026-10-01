package com.gkcontas.metrics.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filters, applied to a registry built by hand.
 *
 * <p>A {@code MeterFilter} runs at registration time, so these assertions are about what
 * ends up in the registry rather than about what the code intended to put there — which is
 * the only way to catch a filter whose name prefix does not match the meter it was meant
 * to govern.
 */
class MeterFilterTest {

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        MetricsConfig config = new MetricsConfig();
        registry = new SimpleMeterRegistry();
        registry.config()
                .meterFilter(config.commonTags("metrics-observability", "local"))
                .meterFilter(config.sloHistograms())
                .meterFilter(config.cardinalityLimit())
                .meterFilter(config.denyNoisyMeters());
    }

    @Test
    void shouldAddCommonTagsToEveryMeter() {
        Counter.builder("anything.at.all").register(registry).increment();

        assertThat(registry.get("anything.at.all")
                .tags("application", "metrics-observability", "region", "local")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void shouldDropDeniedMeters() {
        Counter.builder("jvm.buffer.count").register(registry).increment();

        // Denied at registration: the counter object still works, it is simply not in the
        // registry and never reaches the exposition output.
        assertThat(registry.find("jvm.buffer.count").counter()).isNull();
    }

    @Test
    void shouldPublishSloBucketsForTheProcessingTimer() {
        Timer timer = Timer.builder(OrderMetrics.ORDERS_PROCESSING)
                .tag("outcome", "paid").register(registry);
        timer.record(java.time.Duration.ofMillis(120));

        var buckets = timer.takeSnapshot().histogramCounts();
        assertThat(buckets).isNotEmpty();
        // The boundary that matters is the objective itself. Without an explicit SLO the
        // nearest default bucket to 300 ms is far enough away to make the question
        // "did we meet the objective" unanswerable.
        assertThat(buckets)
                .anyMatch(bucket -> bucket.bucket(java.util.concurrent.TimeUnit.MILLISECONDS) == 300.0);
    }

    @Test
    void shouldNotTouchTheHistogramOfUnrelatedTimers() {
        Timer other = Timer.builder("some.other.timer").register(registry);
        other.record(java.time.Duration.ofMillis(10));

        assertThat(other.takeSnapshot().histogramCounts()).isEmpty();
    }

    @Test
    void shouldStopRegisteringNewSeriesOnceTheCardinalityCapIsReached() {
        for (int index = 0; index < 50; index++) {
            Counter.builder(CardinalityDemo.BY_CUSTOMER)
                    .tag("customer", "customer%d@example.com".formatted(index))
                    .register(registry)
                    .increment();
        }

        // Fifty distinct customers, ten time series. Without the cap this is fifty, and on
        // a real system it is however many customers the service has ever seen.
        assertThat(registry.find(CardinalityDemo.BY_CUSTOMER).counters()).hasSize(10);
    }

    @Test
    void shouldLeaveBoundedDimensionsAlone() {
        for (String channel : new String[]{"WEB", "MOBILE", "PARTNER_API", "WEB"}) {
            Counter.builder(CardinalityDemo.BY_CHANNEL).tag("channel", channel)
                    .register(registry).increment();
        }

        assertThat(registry.find(CardinalityDemo.BY_CHANNEL).counters()).hasSize(3);
    }
}
