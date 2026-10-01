package com.gkcontas.metrics.health;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * A custom health indicator, and the bridge from health to metrics.
 *
 * <p>Actuator's {@code /actuator/health} is designed for a load balancer or an orchestrator
 * asking a yes/no question right now. It keeps no history, so "the gateway was down for
 * four minutes last Tuesday" is a question it cannot answer. Publishing the same state as
 * a gauge gives it a time series, which is what makes it alertable and comparable against
 * the error rate on the same dashboard.
 *
 * <p>The indicator is named by its bean name minus the {@code HealthIndicator} suffix, so
 * this one appears as {@code paymentGateway} under {@code /actuator/health}.
 */
@Component("paymentGatewayHealthIndicator")
public class PaymentGatewayHealth implements HealthIndicator {

    private final AtomicBoolean reachable = new AtomicBoolean(true);

    @Override
    public Health health() {
        return reachable.get()
                ? Health.up().withDetail("endpoint", "https://payments.example.com").build()
                : Health.down()
                        .withDetail("endpoint", "https://payments.example.com")
                        .withDetail("reason", "connection refused")
                        .build();
    }

    public void setReachable(boolean value) {
        reachable.set(value);
    }

    public boolean isReachable() {
        return reachable.get();
    }
}
