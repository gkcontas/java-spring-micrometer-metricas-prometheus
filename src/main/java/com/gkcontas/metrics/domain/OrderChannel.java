package com.gkcontas.metrics.domain;

/**
 * A bounded set, which is what makes it safe as a metric tag.
 *
 * <p>Three values today, maybe five next year. Every tag on a metric multiplies the number
 * of time series Prometheus stores, so the question to ask before tagging by something is
 * not "would this be useful?" but "how many distinct values can this ever have?".
 */
public enum OrderChannel {
    WEB,
    MOBILE,
    PARTNER_API
}
