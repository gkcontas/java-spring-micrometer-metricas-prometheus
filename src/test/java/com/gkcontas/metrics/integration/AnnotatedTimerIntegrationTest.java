package com.gkcontas.metrics.integration;

import com.gkcontas.metrics.domain.OrderChannel;
import com.gkcontas.metrics.service.OrderProcessor;
import com.gkcontas.metrics.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Timed} through the proxy, and {@code @Timed} bypassed.
 *
 * <p>Both halves matter. The first proves the aspect is actually wired — which in Spring
 * Boot 3.2 and later it is not by default, and a project can ship with annotated methods
 * that record nothing at all. The second pins the proxy limitation, where the method does
 * its work correctly and only the measurement disappears.
 */
class AnnotatedTimerIntegrationTest extends IntegrationTestBase {

    private static final String ANNOTATED_TIMER = "orders.processing.annotated";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderProcessor orderProcessor;

    @Autowired
    private MeterRegistry registry;

    @Test
    void shouldRecordWhenTheCallArrivesThroughTheProxy() {
        long before = annotatedCount();

        orderProcessor.processAnnotated(newOrderId());

        // If management.observations.annotations.enabled were left at its default, this
        // would be 0 and nothing anywhere would say why.
        assertThat(annotatedCount()).isEqualTo(before + 1);
    }

    @Test
    void shouldTagTheAnnotatedTimerWithClassAndMethod() {
        orderProcessor.processAnnotated(newOrderId());

        Timer timer = registry.get(ANNOTATED_TIMER)
                .tags("class", OrderProcessor.class.getName(), "method", "processAnnotated")
                .timer();
        // What the aspect can tag by: where the code is. Not what the code decided — which
        // is the whole reason the programmatic timer exists alongside it.
        assertThat(timer.count()).isPositive();
        assertThat(timer.getId().getTag("exception")).isEqualTo("none");
    }

    @Test
    void shouldRecordNothingWhenTheAnnotatedMethodIsCalledOnThis() {
        newOrderId();
        newOrderId();
        long before = annotatedCount();

        int processed = orderProcessor.processPendingBatchViaSelfInvocation();

        // The work happened — orders were processed — and the measurement did not. The
        // same proxy rule as @Transactional, with a quieter failure: a missing transaction
        // eventually corrupts data, a missing metric just leaves a chart at zero.
        assertThat(processed).isPositive();
        assertThat(annotatedCount()).isEqualTo(before);
    }

    @Test
    void shouldStillCountTheBatchItselfBecauseThatCallCameThroughTheProxy() {
        double before = registry.find("orders.batch.runs").counter() == null
                ? 0 : registry.get("orders.batch.runs").counter().count();

        orderProcessor.processPendingBatchViaSelfInvocation();

        assertThat(registry.get("orders.batch.runs").counter().count()).isEqualTo(before + 1);
    }

    private long annotatedCount() {
        Timer timer = registry.find(ANNOTATED_TIMER).timer();
        return timer == null ? 0 : timer.count();
    }

    private Long newOrderId() {
        return orderService.create("batch@example.com", new BigDecimal("55.00"), OrderChannel.WEB)
                .getId();
    }
}
