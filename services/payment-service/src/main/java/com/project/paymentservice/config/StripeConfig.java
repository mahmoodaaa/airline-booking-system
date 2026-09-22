package com.project.paymentservice.config;

import com.stripe.StripeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class StripeConfig {

    private final StripeProperties stripeProperties;

    @Bean
    public StripeClient stripeClient() {

        String secretKey = stripeProperties.getSecretKey();

        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("Stripe secret key is not configured");
        }

        return new StripeClient(secretKey);
    }
}