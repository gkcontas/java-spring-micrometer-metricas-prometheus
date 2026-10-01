package com.gkcontas.metrics.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GaugeReferenceDemoTest {

    @Test
    void shouldLoseTheGaugeWhoseTargetWasNotKeptAlive() {
        GaugeReferenceDemo demo = new GaugeReferenceDemo(new SimpleMeterRegistry());
        demo.registerBoth();

        // Before any collection both report the same value, which is what makes the bug so
        // easy to ship: it passes every test that runs immediately after registration.
        assertThat(demo.valueOf(GaugeReferenceDemo.DANGLING)).isEqualTo(42.0);
        assertThat(demo.valueOf(GaugeReferenceDemo.ANCHORED)).isEqualTo(42.0);

        GaugeReferenceDemo.Reading reading = demo.readAfterGarbageCollection();

        // System.gc() is a request, so the assertion is conditional on it having happened.
        // Claiming an outcome that was not observed would be worse than skipping it.
        if (reading.collected()) {
            assertThat(reading.danglingGauge()).isNaN();
            assertThat(reading.anchoredGauge()).isEqualTo(42.0);
        }
    }
}
