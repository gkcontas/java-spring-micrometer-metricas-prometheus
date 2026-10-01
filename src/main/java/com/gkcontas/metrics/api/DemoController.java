package com.gkcontas.metrics.api;

import com.gkcontas.metrics.health.PaymentGatewayHealth;
import com.gkcontas.metrics.metrics.CardinalityDemo;
import com.gkcontas.metrics.metrics.GaugeReferenceDemo;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two failure modes, reproducible from the command line.
 *
 * <p>Both are things that are usually described and rarely shown. Running them against a
 * live registry turns "cardinality is dangerous" and "gauges can report NaN" from advice
 * into numbers that can be put in a README.
 */
@RestController
public class DemoController {

    private final CardinalityDemo cardinalityDemo;
    private final GaugeReferenceDemo gaugeDemo;
    private final PaymentGatewayHealth paymentGatewayHealth;

    public DemoController(CardinalityDemo cardinalityDemo, GaugeReferenceDemo gaugeDemo,
                          PaymentGatewayHealth paymentGatewayHealth) {
        this.cardinalityDemo = cardinalityDemo;
        this.gaugeDemo = gaugeDemo;
        this.paymentGatewayHealth = paymentGatewayHealth;
    }

    /**
     * Records the same event twice — once tagged by customer, once by channel — and
     * reports how many time series each produced.
     */
    @PostMapping("/demo/cardinality")
    public CardinalityResult cardinality(@RequestParam(defaultValue = "50") int customers) {
        for (int index = 0; index < customers; index++) {
            cardinalityDemo.countByCustomer("customer%d@example.com".formatted(index));
            cardinalityDemo.countByChannel(index % 3 == 0 ? "WEB" : index % 3 == 1 ? "MOBILE" : "PARTNER_API");
        }
        return new CardinalityResult(
                customers,
                cardinalityDemo.seriesFor(CardinalityDemo.BY_CUSTOMER),
                cardinalityDemo.seriesFor(CardinalityDemo.BY_CHANNEL),
                cardinalityDemo.totalSeries());
    }

    @PostMapping("/demo/gauge-reference")
    public GaugeReferenceDemo.Reading gaugeReference() {
        gaugeDemo.registerBoth();
        return gaugeDemo.readAfterGarbageCollection();
    }

    /** Flips the simulated gateway, which moves both /actuator/health and component.health. */
    @PostMapping("/demo/gateway")
    public String gateway(@RequestParam boolean reachable) {
        paymentGatewayHealth.setReachable(reachable);
        return reachable ? "UP" : "DOWN";
    }

    /**
     * @param distinctCustomers how many distinct customers were recorded
     * @param byCustomerSeries  series actually kept for the unbounded metric, after the cap
     * @param byChannelSeries   series kept for the bounded one
     * @param totalSeries       every series the registry currently holds
     */
    public record CardinalityResult(int distinctCustomers, int byCustomerSeries,
                                    int byChannelSeries, long totalSeries) {
    }
}
