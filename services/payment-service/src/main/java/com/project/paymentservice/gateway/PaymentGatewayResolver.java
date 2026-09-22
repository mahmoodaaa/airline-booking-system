package com.project.paymentservice.gateway;

import com.project.paymentservice.enums.PaymentProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the correct {@link PaymentGateway} implementation for a given
 * {@link PaymentProvider}.
 *
 * All {@link PaymentGateway} beans registered in the Spring context are
 * automatically discovered at startup and indexed by their {@code provider()}.
 *
 * This means adding a new provider gateway requires:
 *   1. Creating a new {@link PaymentGateway} implementation.
 *   2. Annotating it with {@code @Component} (or equivalent).
 *
 * No changes to this resolver are needed.
 */
@Slf4j
@Component
public class PaymentGatewayResolver {

    private final Map<PaymentProvider, PaymentGateway> gatewayMap;

    public PaymentGatewayResolver(List<PaymentGateway> gateways) {

        this.gatewayMap = gateways.stream()
                .collect(Collectors
                        .toMap(PaymentGateway::provider, Function.identity()));

        log.info("Registered payment gateways: {}", gatewayMap.keySet());
    }

    /**
     * Returns the gateway for the given provider.
     *
     * @throws IllegalArgumentException if no gateway is registered
     *                                  for the requested provider
     */
    public PaymentGateway resolve(PaymentProvider provider) {

        PaymentGateway gateway = gatewayMap.get(provider);

        if (gateway == null) {
            throw new IllegalArgumentException(
                    "No payment gateway registered for provider: " + provider);
        }

        return gateway;
    }
}
