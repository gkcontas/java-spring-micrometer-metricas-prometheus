package com.gkcontas.metrics.api;

import com.gkcontas.metrics.domain.OrderChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateOrderRequest(

        @Email(message = "must be a valid e-mail address")
        @NotNull(message = "must not be null")
        String customerEmail,

        @NotNull(message = "must not be null")
        @DecimalMin(value = "0.01", message = "must be greater than zero")
        BigDecimal amount,

        @NotNull(message = "must not be null")
        OrderChannel channel) {
}
