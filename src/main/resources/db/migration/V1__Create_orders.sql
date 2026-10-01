CREATE TABLE orders (
    id             BIGSERIAL PRIMARY KEY,
    customer_email VARCHAR(255)   NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    channel        VARCHAR(20)    NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    failure_reason VARCHAR(30),
    created_at     TIMESTAMPTZ    NOT NULL,
    processed_at   TIMESTAMPTZ
);

CREATE INDEX idx_orders_status ON orders (status);
