package com.gkcontas.metrics.api;

import com.gkcontas.metrics.domain.Order;
import com.gkcontas.metrics.domain.OrderChannel;
import com.gkcontas.metrics.service.OrderProcessor;
import com.gkcontas.metrics.service.OrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synthetic traffic, because an empty histogram teaches nothing.
 *
 * <p>A dashboard built against a service nobody is calling looks fine and proves nothing:
 * the buckets are empty, {@code rate()} is flat zero, and a mistake in a PromQL expression
 * is indistinguishable from a correct one. This endpoint produces enough orders, failures
 * and latency spread for every panel to have something to show.
 *
 * <p>Virtual threads, so that a few hundred simulated calls that are almost entirely sleep
 * do not need a pool sized for them. {@code ExecutorService} is {@code AutoCloseable} since
 * Java 19, and closing it waits for the tasks — which is what makes the measured elapsed
 * time the real one.
 */
@RestController
public class LoadController {

    private final OrderService orderService;
    private final OrderProcessor orderProcessor;

    public LoadController(OrderService orderService, OrderProcessor orderProcessor) {
        this.orderService = orderService;
        this.orderProcessor = orderProcessor;
    }

    @PostMapping("/load")
    public LoadResult generate(@RequestParam(defaultValue = "100") int orders,
                               @RequestParam(defaultValue = "true") boolean process) {
        long startedAt = System.nanoTime();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>(orders);
            for (int index = 0; index < orders; index++) {
                tasks.add(executor.submit(() -> {
                    Order order = orderService.create(randomEmail(), randomAmount(), randomChannel());
                    created.incrementAndGet();
                    if (process) {
                        orderProcessor.process(order.getId());
                        processed.incrementAndGet();
                    }
                }));
            }
            tasks.forEach(LoadController::await);
        }

        return new LoadResult(created.get(), processed.get(),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // One failed order must not abort the load run; the failure is already a metric.
        }
    }

    private static String randomEmail() {
        return "customer%d@example.com".formatted(ThreadLocalRandom.current().nextInt(1, 500));
    }

    private static BigDecimal randomAmount() {
        return BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(15, 2_500))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static OrderChannel randomChannel() {
        OrderChannel[] channels = OrderChannel.values();
        return channels[ThreadLocalRandom.current().nextInt(channels.length)];
    }

    public record LoadResult(int ordersCreated, int ordersProcessed, long elapsedMillis) {
    }
}
