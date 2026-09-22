package com.project.paymentservice.webhook.stripe;

import com.project.paymentservice.config.StripeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookVerifierTest {

    @Mock
    private StripeProperties stripeProperties;

    private StripeWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new StripeWebhookVerifier(stripeProperties);
    }

    @Test
    void shouldRejectInvalidSignature() {
        
        when(stripeProperties.getWebhookSecret()).thenReturn("whsec_test");

        assertThrows(
                InvalidStripeWebhookException.class,
                () -> verifier.verify(
                        "{\"id\":\"evt_fake\"}",
                        "t=123,v1=invalid-signature"
                )
        );
    }
    
    @Test
    void shouldRejectEmptyPayload() {
        assertThrows(
                InvalidStripeWebhookException.class,
                () -> verifier.verify(
                        "",
                        "t=123,v1=invalid-signature"
                )
        );
    }
    
    @Test
    void shouldRejectMissingSignature() {
        assertThrows(
                InvalidStripeWebhookException.class,
                () -> verifier.verify(
                        "{\"id\":\"evt_fake\"}",
                        ""
                )
        );
    }
}
