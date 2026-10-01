package com.gkcontas.metrics.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Publishes the health of each component as a gauge, so it has a history.
 *
 * <p>One is the obvious value for UP and zero for DOWN, which makes the alert rule read
 * naturally: {@code min_over_time(component_health[5m]) == 0}.
 *
 * <p>The gauge reads the indicator bean, which is a singleton and therefore strongly
 * reachable — the same requirement that {@code GaugeReferenceDemo} exists to illustrate.
 */
@Component
public class HealthMetrics {

    static final String COMPONENT_HEALTH = "component.health";

    public HealthMetrics(MeterRegistry registry, PaymentGatewayHealth paymentGateway) {
        Gauge.builder(COMPONENT_HEALTH, paymentGateway, health -> health.isReachable() ? 1 : 0)
                .description("1 when the component is reachable, 0 when it is not")
                .tag("component", "paymentGateway")
                .register(registry);
    }
}
