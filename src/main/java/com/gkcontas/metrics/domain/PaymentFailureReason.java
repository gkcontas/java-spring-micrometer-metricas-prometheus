package com.gkcontas.metrics.domain;

/**
 * The reason a payment failed, as a closed enum rather than the gateway's message.
 *
 * <p>Tagging by the raw message from an external system is a cardinality leak waiting to
 * happen: the provider adds a transaction id to the text and every failure becomes its own
 * time series. Mapping to a known set keeps the dimension finite and the dashboard legible.
 */
public enum PaymentFailureReason {
    INSUFFICIENT_FUNDS,
    CARD_EXPIRED,
    GATEWAY_TIMEOUT,
    FRAUD_SUSPECTED
}
