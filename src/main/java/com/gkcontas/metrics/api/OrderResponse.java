package com.gkcontas.metrics.api;

import com.gkcontas.metrics.domain.Order;
import com.gkcontas.metrics.domain.OrderChannel;
import com.gkcontas.metrics.domain.OrderStatus;
import com.gkcontas.metrics.domain.PaymentFailureReason;
import java.math.BigDecimal;

public record OrderResponse(Long id, String customerEmail, BigDecimal amount,
                            OrderChannel channel, OrderStatus status,
                            PaymentFailureReason failureReason) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getCustomerEmail(), order.getAmount(),
                order.getChannel(), order.getStatus(), order.getFailureReason());
    }
}
