package com.gkcontas.metrics.service;

import com.gkcontas.metrics.domain.Order;
import com.gkcontas.metrics.domain.PaymentFailureReason;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A stand-in for the external payment provider.
 *
 * <p>Simulated rather than real, and deliberately so: the subject of this project is the
 * instrumentation, and what it needs from a gateway is a latency distribution with a tail
 * and a mix of failures with distinguishable causes. A real HTTP client would add
 * configuration and flakiness without adding a single insight about metrics.
 *
 * <p>The latency is drawn from a long-tailed distribution on purpose. A uniform one makes
 * p50, p95 and p99 nearly equal, which is exactly the shape that makes percentiles look
 * pointless — real services have a tail, and the tail is the reason to measure percentiles
 * at all.
 */
@Component
public class PaymentGateway {

    private final boolean simulateLatency;
    private final int failurePercentage;

    public PaymentGateway(@Value("${app.payment.simulate-latency:true}") boolean simulateLatency,
                          @Value("${app.payment.failure-percentage:12}") int failurePercentage) {
        this.simulateLatency = simulateLatency;
        this.failurePercentage = failurePercentage;
    }

    public Result charge(Order order) {
        if (simulateLatency) {
            sleep(nextLatency());
        }
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll >= failurePercentage) {
            return Result.approve();
        }
        PaymentFailureReason[] reasons = PaymentFailureReason.values();
        return Result.declined(reasons[roll % reasons.length]);
    }

    /** Most calls are fast; roughly one in twenty is an order of magnitude slower. */
    private Duration nextLatency() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long millis = random.nextInt(100) < 5
                ? random.nextLong(400, 900)
                : random.nextLong(15, 120);
        return Duration.ofMillis(millis);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Result(boolean approved, PaymentFailureReason reason) {

        static Result approve() {
            // Not named approved(): that is the record's own accessor.
            return new Result(true, null);
        }

        static Result declined(PaymentFailureReason reason) {
            return new Result(false, reason);
        }
    }
}
