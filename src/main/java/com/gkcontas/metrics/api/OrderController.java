package com.gkcontas.metrics.api;

import com.gkcontas.metrics.domain.Order;
import com.gkcontas.metrics.domain.OrderRepository;
import com.gkcontas.metrics.domain.OrderStatus;
import com.gkcontas.metrics.service.OrderProcessor;
import com.gkcontas.metrics.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The HTTP surface.
 *
 * <p>Note what is <em>not</em> instrumented here: request count and latency per endpoint
 * already arrive from Actuator as {@code http.server.requests}, tagged by method, uri,
 * status and outcome. Adding a hand-rolled counter per controller method would duplicate
 * it with worse tags. The rule of thumb is to instrument what the framework cannot know —
 * which is the meaning of the work, not the fact that an HTTP call happened.
 *
 * <p>The {@code uri} tag on that built-in metric is the templated path
 * ({@code /orders/{id}}), not the resolved one. That is not a cosmetic choice: tagging by
 * the resolved path would create a series per order id.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderProcessor orderProcessor;
    private final OrderRepository repository;

    public OrderController(OrderService orderService, OrderProcessor orderProcessor,
                           OrderRepository repository) {
        this.orderService = orderService;
        this.orderProcessor = orderProcessor;
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return OrderResponse.from(orderService.create(
                request.customerEmail(), request.amount(), request.channel()));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return repository.findById(id).map(OrderResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No order with id " + id));
    }

    /** Processed through the programmatic timer, which tags by the outcome. */
    @PostMapping("/{id}/process")
    public OrderResponse process(@PathVariable Long id) {
        OrderStatus status = orderProcessor.process(id);
        return OrderResponse.from(reload(id));
    }

    /** The same work under {@code @Timed}, for comparison in the exposition output. */
    @PostMapping("/{id}/process-annotated")
    public OrderResponse processAnnotated(@PathVariable Long id) {
        OrderStatus status = orderProcessor.processAnnotated(id);
        return OrderResponse.from(reload(id));
    }

    private Order reload(Long id) {
        return repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No order with id " + id));
    }
}
