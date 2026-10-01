package com.gkcontas.metrics.service;

import com.gkcontas.metrics.domain.Order;
import com.gkcontas.metrics.domain.OrderRepository;
import com.gkcontas.metrics.domain.OrderStatus;
import com.gkcontas.metrics.metrics.OrderMetrics;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processing, instrumented twice over, to compare the two ways of doing it.
 *
 * <p>{@code @Timed} is one line and needs no plumbing. It is also an AOP proxy, which
 * brings the same two constraints as {@code @Transactional}: it only works on a call that
 * arrives through the proxy, and the unit of measurement is the whole method. Programmatic
 * instrumentation costs a few lines and gives the thing the annotation cannot — a tag
 * whose value is only known once the work is done.
 *
 * <p>That is the deciding question, and it is not about style. An {@code outcome} tag
 * separating success from failure is what keeps a flood of fast failures from
 * <em>improving</em> the p95. {@code @Timed} can tag a method, not a result.
 */
@Service
public class OrderProcessor {

    private final OrderRepository repository;
    private final PaymentGateway gateway;
    private final OrderMetrics metrics;
    private final OrderService orderService;

    public OrderProcessor(OrderRepository repository, PaymentGateway gateway,
                          OrderMetrics metrics, OrderService orderService) {
        this.repository = repository;
        this.gateway = gateway;
        this.metrics = metrics;
        this.orderService = orderService;
    }

    /**
     * The programmatic version, and the one the dashboards use.
     *
     * <p>The timer is stopped with the outcome already known, so {@code orders.processing}
     * can be split by it. Everything after the call to the gateway is cheap, so measuring
     * the whole method rather than only the remote call costs nothing in accuracy.
     */
    @Transactional
    public OrderStatus process(Long orderId) {
        Order order = repository.findById(orderId).orElseThrow(
                () -> new IllegalArgumentException("No order with id " + orderId));

        OrderStatus status = metrics.recordProcessing(
                () -> charge(order), result -> result.name().toLowerCase(Locale.ROOT));
        orderService.refreshPendingGauge();
        return status;
    }

    /**
     * The same work under {@code @Timed}, kept for comparison and for the tests.
     *
     * <p>{@code percentiles} here publishes client-side percentiles — a precomputed p95
     * per instance. Convenient on a single instance, and not aggregable across several:
     * see {@code MetricsConfig.sloHistograms}. It is here to make that difference visible
     * in the exposition output, next to the histogram version.
     */
    @Timed(value = "orders.processing.annotated",
            description = "Processing time measured by the AOP aspect",
            percentiles = {0.5, 0.95, 0.99})
    @Transactional
    public OrderStatus processAnnotated(Long orderId) {
        Order order = repository.findById(orderId).orElseThrow(
                () -> new IllegalArgumentException("No order with id " + orderId));
        OrderStatus status = charge(order);
        orderService.refreshPendingGauge();
        return status;
    }

    /**
     * The proxy trap, kept as executable documentation.
     *
     * <p>This method calls {@link #processAnnotated(Long)} on {@code this}, not through
     * the Spring proxy, so the aspect never runs and {@code orders.processing.annotated}
     * records nothing — while the method itself does all of its work and returns
     * perfectly normal results. The same rule that catches people with
     * {@code @Transactional}, with a worse failure mode: a missing transaction eventually
     * corrupts data and gets noticed, a missing metric just leaves a chart at zero that
     * everyone reads as "no traffic".
     *
     * <p>{@code AnnotatedTimerTest} asserts both halves of this.
     */
    @Counted(value = "orders.batch.runs", description = "Batches started, counted by the aspect")
    public int processPendingBatchViaSelfInvocation() {
        List<Order> pending = repository.findTop50ByStatusOrderByCreatedAtAsc(OrderStatus.PENDING);
        pending.forEach(order -> processAnnotated(order.getId()));
        return pending.size();
    }

    private OrderStatus charge(Order order) {
        PaymentGateway.Result result = gateway.charge(order);
        order.setProcessedAt(Instant.now());
        if (result.approved()) {
            order.setStatus(OrderStatus.PAID);
        } else {
            order.setStatus(OrderStatus.FAILED);
            order.setFailureReason(result.reason());
            metrics.paymentFailed(result.reason());
        }
        repository.save(order);
        return order.getStatus();
    }
}
