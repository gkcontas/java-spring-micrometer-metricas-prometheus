package com.gkcontas.metrics.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    long countByStatus(OrderStatus status);

    List<Order> findTop50ByStatusOrderByCreatedAtAsc(OrderStatus status);
}
