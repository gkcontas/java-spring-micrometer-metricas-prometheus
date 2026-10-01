package com.gkcontas.metrics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cardinality containment, against the real registry rather than a hand-built one.
 *
 * <p>The unit test proves the filter works. This proves it is actually installed in the
 * registry the application publishes from — which is a different claim, and the one that
 * fails when a {@code MeterFilter} bean is declared somewhere Spring never sees it.
 */
class CardinalityIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCapTheUnboundedDimensionAndLeaveTheBoundedOneAlone() throws Exception {
        JsonNode result = objectMapper.readTree(mockMvc
                .perform(post("/demo/cardinality").param("customers", "200"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(result.get("distinctCustomers").asInt()).isEqualTo(200);
        // Two hundred customers, ten time series. Uncapped this is two hundred, and in
        // production it is however many customers the service has ever seen — one new
        // series each, in the application's heap and again in Prometheus.
        assertThat(result.get("byCustomerSeries").asInt()).isEqualTo(10);
        assertThat(result.get("byChannelSeries").asInt()).isEqualTo(3);
    }

    @Test
    void shouldKeepTheCappedSeriesOutOfTheScrapeToo() throws Exception {
        mockMvc.perform(post("/demo/cardinality").param("customers", "200"))
                .andExpect(status().isOk());

        long customerSeries = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString()
                .lines()
                .filter(line -> line.startsWith("orders_by_customer_total"))
                .count();

        assertThat(customerSeries).isEqualTo(10);
    }
}
