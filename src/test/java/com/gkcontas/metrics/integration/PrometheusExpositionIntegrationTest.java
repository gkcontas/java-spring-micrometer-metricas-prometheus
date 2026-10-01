package com.gkcontas.metrics.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What Prometheus will actually scrape.
 *
 * <p>The unit tests assert that the meters hold the right values; this asserts that they
 * survive the trip to the exposition format with the names and labels a PromQL query will
 * use. The two are genuinely different: a dot becomes an underscore, a base unit becomes a
 * name suffix, a counter gains {@code _total}, and a query written against the Java name
 * matches nothing.
 */
class PrometheusExpositionIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void generateSomeTraffic() throws Exception {
        String body = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerEmail": "ana@example.com", "amount": 199.90, "channel": "WEB"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/orders/{id}/process", id)).andExpect(status().isOk());
    }

    @Test
    void shouldExposeTheBusinessCountersWithTheirPrometheusNames() throws Exception {
        String scrape = scrape();

        // orders.accepted -> orders_accepted_total: dots become underscores and a counter
        // gains the _total suffix. A query written as `orders.accepted` matches nothing.
        assertThat(scrape).contains("orders_accepted_total{");
        assertThat(scrape).contains("channel=\"WEB\"");
    }

    @Test
    void shouldAddTheCommonTagsToEveryMeter() throws Exception {
        assertThat(scrape())
                .contains("application=\"metrics-observability\"")
                .contains("region=\"local\"");
    }

    @Test
    void shouldPublishTheProcessingTimerAsExactlyTheObjectiveBuckets() throws Exception {
        List<String> boundaries = scrape().lines()
                .filter(line -> line.startsWith("orders_processing_seconds_bucket"))
                .map(line -> line.replaceAll(".*le=\"([^\"]*)\".*", "$1"))
                .distinct()
                .toList();

        // Seven series per outcome, and the boundaries are the objectives — in seconds,
        // which is the unit Prometheus uses whatever the Java API was called with.
        // `percentilesHistogram(true)` would publish 75 instead, for the same one timer.
        assertThat(boundaries)
                .containsExactly("0.05", "0.1", "0.2", "0.3", "0.5", "1.0", "+Inf");
        assertThat(scrape()).contains("orders_processing_seconds_count{");
    }

    @Test
    void shouldPublishClientSidePercentilesForTheAnnotatedTimerInstead() throws Exception {
        String scrape = scrape();

        // The contrast the two timers exist for. This one carries a p95 computed inside
        // this JVM — convenient on one instance, and arithmetically meaningless to
        // average across several. The histogram above is what Prometheus can aggregate.
        assertThat(scrape).contains("orders_processing_annotated_seconds{")
                .contains("quantile=\"0.95\"");
        assertThat(scrape).doesNotContain("orders_processing_annotated_seconds_bucket");
    }

    @Test
    void shouldStripTheReservedCreatedSuffixFromACounterName() throws Exception {
        String scrape = scrape();

        // `_created` is reserved by OpenMetrics, so the client library drops it from the
        // base name: demo.orders.created is published as demo_orders_total. The metric
        // works, the value is right, and every dashboard querying the written name draws
        // a flat zero — which reads exactly like "no orders".
        assertThat(scrape).contains("demo_orders_total{");
        assertThat(scrape).doesNotContain("demo_orders_created_total");
        // The same counter named without the reserved word keeps its name.
        assertThat(scrape).contains("demo_orders_accepted_total{");
    }

    @Test
    void shouldPublishTheOrderValueDistributionWithItsBaseUnitInTheName() throws Exception {
        // The base unit is part of the metric name in the Prometheus convention, which is
        // why `orders.amount` with baseUnit BRL is not `orders_amount`.
        assertThat(scrape()).contains("orders_amount_BRL_bucket{");
    }

    @Test
    void shouldPublishThePendingOrdersGauge() throws Exception {
        assertThat(scrape()).contains("orders_pending_orders{");
    }

    @Test
    void shouldStillPublishTheMetricsNobodyHadToWrite() throws Exception {
        String scrape = scrape();

        // Half of observability arrives for free, and forgetting that is how projects end
        // up with a hand-rolled counter next to the one Actuator already publishes.
        assertThat(scrape)
                .contains("jvm_memory_used_bytes")
                .contains("hikaricp_connections")
                .contains("http_server_requests_seconds_bucket")
                .contains("system_cpu_usage");
    }

    @Test
    void shouldNotPublishTheMetersThatWereDeniedOnPurpose() throws Exception {
        assertThat(scrape()).doesNotContain("jvm_buffer");
    }

    @Test
    void shouldTagHttpMetricsWithTheTemplatedUriRatherThanTheResolvedOne() throws Exception {
        // `/orders/{id}` and not `/orders/4711`: the templated path is what keeps the
        // built-in HTTP timer from growing a time series per order.
        assertThat(scrape()).contains("uri=\"/orders/{id}/process\"");
    }

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
