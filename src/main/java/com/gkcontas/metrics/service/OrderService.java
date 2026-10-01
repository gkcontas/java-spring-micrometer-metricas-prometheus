package com.gkcontas.metrics.service;

import com.gkcontas.metrics.domain.Order;
import com.gkcontas.metrics.domain.OrderChannel;
import com.gkcontas.metrics.domain.OrderRepository;
import com.gkcontas.metrics.domain.OrderStatus;
import com.gkcontas.metrics.metrics.CardinalityDemo;
import com.gkcontas.metrics.metrics.OrderMetrics;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where business metrics are recorded, which is the half nobody ships for you.
 *
 * <p>Actuator gives heap, threads, GC pauses, connection pool and HTTP latency for free,
 * and all of it answers one question: is the process healthy. None of it answers whether
 * the company is making money. "Paid orders per minute fell to zero" is an outage that a
 * perfectly green JVM dashboard shows no sign of — which is why this class exists.
 */
@Service
public class OrderService {

    private final OrderRepository repository;
    private final OrderMetrics metrics;
    private final CardinalityDemo cardinalityDemo;

    public OrderService(OrderRepository repository, OrderMetrics metrics,
                        CardinalityDemo cardinalityDemo) {
        this.repository = repository;
        this.metrics = metrics;
        this.cardinalityDemo = cardinalityDemo;
    }

    @Transactional
    public Order create(String customerEmail, BigDecimal amount, OrderChannel channel) {
        Order order = repository.save(Order.builder()
                .customerEmail(customerEmail)
                .amount(amount)
                .channel(channel)
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .build());

        metrics.orderCreated(channel, OrderStatus.PENDING);
        metrics.orderAmount(channel, amount);
        cardinalityDemo.countByChannel(channel.name());
        refreshPendingGauge();
        return order;
    }

    /**
     * Keeps the pending-orders gauge current, off the scrape path.
     *
     * <p>The tempting alternative is to register the gauge directly on the repository —
     * {@code Gauge.builder("orders.pending", repository, r -> r.countByStatus(PENDING))}.
     * It reads beautifully and it means a database query runs <em>on every scrape</em>, on
     * the scrape thread. At a fifteen-second interval across a dozen instances that is a
     * steady load nobody asked for, and a slow query turns into a scrape timeout, which
     * shows up as the whole instance disappearing from the dashboard rather than as a slow
     * query.
     */
    void refreshPendingGauge() {
        metrics.setPendingOrders(repository.countByStatus(OrderStatus.PENDING));
    }
}
