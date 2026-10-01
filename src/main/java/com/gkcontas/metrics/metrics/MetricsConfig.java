package com.gkcontas.metrics.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the rules that apply to every meter live.
 *
 * <p>A {@link MeterFilter} runs when a meter is registered, so it can rename, add tags,
 * change the distribution configuration, or refuse the registration outright. Doing these
 * things centrally is what keeps them from being forgotten at the one call site that
 * matters.
 */
@Configuration
public class MetricsConfig {

    /**
     * Tags added to every meter the application publishes.
     *
     * <p>Without them, two instances of the same service produce identical series and the
     * only way to tell a failing pod from a healthy one is to guess. With them, the same
     * dashboard filters by environment, region or instance.
     *
     * <p>They cost nothing in cardinality <em>per se</em> — one value each per process —
     * but they do multiply: a common tag with many values multiplies the entire metric
     * surface of the application, not one meter.
     */
    @Bean
    public MeterFilter commonTags(@Value("${spring.application.name}") String application,
                                  @Value("${app.region:local}") String region) {
        return MeterFilter.commonTags(io.micrometer.core.instrument.Tags.of(
                "application", application,
                "region", region));
    }

    /**
     * Buckets chosen around the service level objective, not around the defaults.
     *
     * <p>The reason to publish a histogram rather than a precomputed percentile is that
     * <b>percentiles do not aggregate</b>. The average of the p95 of three instances is
     * not the p95 of the three together — it is a number with no meaning. Publishing
     * buckets lets Prometheus compute the quantile across instances with
     * {@code histogram_quantile()}, which is the only way to get a p95 for the service as
     * a whole.
     *
     * <p>What this filter does <em>not</em> do is call {@code percentilesHistogram(true)}.
     * That switch emits Micrometer's default bucket set, which spans microseconds to
     * minutes: measured on this application, 75 buckets per tag combination against the
     * 7 produced by the objectives below. Buckets are time series, so the convenience of
     * "just turn on the histogram" is a tenfold multiplication of the metric, per tag
     * combination, forever.
     *
     * <p>The trade-off is real and worth naming: seven boundaries answer "what fraction of
     * requests met the 300 ms objective" exactly, and estimate a p99 coarsely. The default
     * set does the reverse. Choosing is the job; turning everything on is how a Prometheus
     * runs out of memory.
     *
     * <p>Note the {@code equals} rather than {@code startsWith}. A prefix match on
     * {@code orders.processing} also captures {@code orders.processing.annotated} — which
     * is how this filter silently replaced the client-side percentiles of the annotated
     * timer with its own buckets, and removed the very contrast the two meters exist to
     * show.
     */
    @Bean
    public MeterFilter sloHistograms() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!id.getName().equals(OrderMetrics.ORDERS_PROCESSING)) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(
                                Duration.ofMillis(50).toNanos(),
                                Duration.ofMillis(100).toNanos(),
                                Duration.ofMillis(200).toNanos(),
                                Duration.ofMillis(300).toNanos(),
                                Duration.ofMillis(500).toNanos(),
                                Duration.ofSeconds(1).toNanos())
                        .build()
                        .merge(config);
            }
        };
    }

    /**
     * The defence against the single most common way to take down a Prometheus.
     *
     * <p>Every distinct combination of tag values is its own time series, kept in memory
     * by both the application and the server. Tagging by customer, order id, URL with
     * parameters or an exception message turns one metric into as many series as the
     * system has values — unbounded, and growing with traffic.
     *
     * <p>This filter caps {@code orders.by_customer} at ten distinct customers and denies
     * the rest. Denying is the blunt option and it is the right default: the alternative,
     * folding the excess into an "other" bucket, keeps the series count bounded but hides
     * that the limit was reached at all.
     *
     * <p>{@code maximumAllowableMetrics} is the backstop for the case this filter does not
     * anticipate — a cap on the total number of meters, after which registration fails
     * loudly instead of the process dying of memory exhaustion.
     */
    @Bean
    public MeterFilter cardinalityLimit() {
        return MeterFilter.maximumAllowableTags(
                CardinalityDemo.BY_CUSTOMER, "customer", 10, MeterFilter.deny());
    }

    @Bean
    public MeterFilter globalMeterLimit() {
        return MeterFilter.maximumAllowableMetrics(5_000);
    }

    /**
     * A deliberate denial, kept as an example of the decision rather than of the syntax.
     *
     * <p>{@code jvm.buffer.*} describes NIO direct buffers. It is real data, it is almost
     * never actionable, and it is published for every buffer pool of every instance. The
     * point is that dropping a metric should be a decision someone made, with a reason
     * written down — not the accident of nobody having looked at the exposition output.
     */
    @Bean
    public MeterFilter denyNoisyMeters() {
        return MeterFilter.denyNameStartsWith("jvm.buffer");
    }
}
