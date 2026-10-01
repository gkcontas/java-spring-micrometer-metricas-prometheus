package com.gkcontas.metrics.integration;

import com.gkcontas.metrics.health.PaymentGatewayHealth;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The custom health indicator, and the same state as a time series.
 *
 * <p>{@code /actuator/health} answers "is it up, right now" for a load balancer and keeps
 * no history. The gauge is what turns the same fact into something a dashboard can show
 * over a week and an alert rule can fire on.
 */
class HealthMetricsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PaymentGatewayHealth paymentGatewayHealth;

    @Autowired
    private MeterRegistry registry;

    @AfterEach
    void restore() {
        paymentGatewayHealth.setReachable(true);
    }

    @Test
    void shouldReportTheGatewayUnderActuatorHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.paymentGateway.status").value("UP"))
                .andExpect(jsonPath("$.components.paymentGateway.details.endpoint").exists());
    }

    @Test
    void shouldTurnTheWholeApplicationDownWhenOneComponentIsDown() throws Exception {
        paymentGatewayHealth.setReachable(false);

        // A custom indicator contributes to the aggregate by default. That is usually
        // right and occasionally catastrophic: an indicator that checks a non-essential
        // dependency will take the pod out of the load balancer when that dependency
        // blinks. Worth deciding on purpose, per indicator.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.paymentGateway.details.reason")
                        .value("connection refused"));
    }

    @Test
    void shouldMirrorTheHealthStateIntoAGauge() {
        assertThat(gauge()).isEqualTo(1.0);

        paymentGatewayHealth.setReachable(false);

        // Same reading, now with a history and an alert rule:
        //     min_over_time(component_health{component="paymentGateway"}[5m]) == 0
        assertThat(gauge()).isZero();
    }

    private double gauge() {
        return registry.get("component.health").tag("component", "paymentGateway").gauge().value();
    }
}
