package com.project.paymentservice.webhook;

import com.project.paymentservice.enums.WebhookProcessingDecision;
import com.project.paymentservice.service.WebhookTransactionService;
import com.project.paymentservice.service.impl.StripeWebhookServiceImpl;
import com.project.paymentservice.webhook.handler.StripeCheckoutCompletedHandler;
import com.project.paymentservice.webhook.handler.StripeCheckoutExpiredHandler;
import com.project.paymentservice.webhook.stripe.StripeWebhookVerifier;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceImplTest {

    @Mock
    private StripeWebhookVerifier verifier;

    @Mock
    private WebhookTransactionService webhookTransactionService;

    @Mock
    private StripeCheckoutCompletedHandler completedHandler;

    @Mock
    private StripeCheckoutExpiredHandler expiredHandler;

    private StripeWebhookServiceImpl service;


    @BeforeEach
    void setUp() {

        service = new StripeWebhookServiceImpl(
                verifier,
                webhookTransactionService,
                completedHandler,
                expiredHandler
        );
    }


    // ============================================================
    // checkout.session.completed
    // ============================================================

    @Test
    void shouldProcessCompletedEventAndMarkProcessed() {

        byte[] body = "{\"id\":\"evt_123\"}".getBytes(StandardCharsets.UTF_8);

        Event event = mock(Event.class);

        when(event.getId()).thenReturn("evt_123");
        when(event.getType()).thenReturn("checkout.session.completed");

        when(verifier.verify(anyString(), eq("signature"))).thenReturn(event);

        when(webhookTransactionService.claimForProcessing("evt_123"))
                .thenReturn(WebhookProcessingDecision.PROCESS);


        service.processWebhook(body, "signature");


        verify(webhookTransactionService)
                .registerEvent(eq("evt_123"), eq("checkout.session.completed"), anyString());

        verify(completedHandler).handle(event);

        verify(expiredHandler, never()).handle(any());

        verify(webhookTransactionService).markProcessed("evt_123");
    }


    // ============================================================
    // checkout.session.expired
    // ============================================================

    @Test
    void shouldProcessExpiredEventAndMarkProcessed() {

        byte[] body = "{\"id\":\"evt_expired\"}".getBytes(StandardCharsets.UTF_8);

        Event event = mock(Event.class);

        when(event.getId()).thenReturn("evt_expired");
        when(event.getType()).thenReturn("checkout.session.expired");

        when(verifier.verify(anyString(), eq("signature"))).thenReturn(event);

        when(webhookTransactionService.claimForProcessing("evt_expired"))
                .thenReturn(WebhookProcessingDecision.PROCESS);


        service.processWebhook(body, "signature");


        verify(expiredHandler).handle(event);

        verify(completedHandler, never()).handle(any());

        verify(webhookTransactionService).markProcessed("evt_expired");
    }


    // ============================================================
    // Duplicate already PROCESSED
    // ============================================================

    @Test
    void shouldIgnoreAlreadyProcessedDuplicate() {

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        Event event = mock(Event.class);

        when(event.getId()).thenReturn("evt_duplicate");
        when(event.getType()).thenReturn("checkout.session.completed");

        when(verifier.verify(anyString(), anyString())).thenReturn(event);

        when(webhookTransactionService.claimForProcessing("evt_duplicate"))
                .thenReturn(WebhookProcessingDecision.ALREADY_PROCESSED);


        service.processWebhook(body, "signature");


        verify(completedHandler, never()).handle(any());
        verify(expiredHandler, never()).handle(any());
        verify(webhookTransactionService, never()).markProcessed(anyString());
        verify(webhookTransactionService, never()).markFailed(anyString(), anyString());
    }


    // ============================================================
    // Concurrent duplicate (IN_PROGRESS)
    // ============================================================

    @Test
    void shouldIgnoreEventAlreadyInProgress() {

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        Event event = mock(Event.class);

        when(event.getId()).thenReturn("evt_processing");
        when(event.getType()).thenReturn("checkout.session.completed");

        when(verifier.verify(anyString(), anyString())).thenReturn(event);

        when(webhookTransactionService.claimForProcessing("evt_processing"))
                .thenReturn(WebhookProcessingDecision.IN_PROGRESS);


        service.processWebhook(body, "signature");


        verify(completedHandler, never()).handle(any());
        verify(webhookTransactionService, never()).markProcessed(anyString());
    }


    // ============================================================
    // Handler failure -> markFailed + rethrow
    // ============================================================

    @Test
    void shouldMarkWebhookFailedWhenHandlerFails() {

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        Event event = mock(Event.class);

        when(event.getId()).thenReturn("evt_failure");
        when(event.getType()).thenReturn("checkout.session.completed");

        when(verifier.verify(anyString(), anyString())).thenReturn(event);

        when(webhookTransactionService.claimForProcessing("evt_failure"))
                .thenReturn(WebhookProcessingDecision.PROCESS);

        doThrow(new IllegalStateException("financial transaction failed"))
                .when(completedHandler).handle(event);


        assertThrows(
                IllegalStateException.class,
                () -> service.processWebhook(body, "signature")
        );


        verify(webhookTransactionService)
                .markFailed(eq("evt_failure"), contains("financial transaction failed"));

        verify(webhookTransactionService, never()).markProcessed(anyString());
    }


    // ============================================================
    // Unsupported but VALID Stripe event -> silently PROCESSED
    // ============================================================

    @Test
    void shouldMarkUnsupportedValidEventProcessed() {

        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        Event event = mock(Event.class);

        when(event.getId()).thenReturn("evt_other");
        when(event.getType()).thenReturn("customer.created");

        when(verifier.verify(anyString(), anyString())).thenReturn(event);

        when(webhookTransactionService.claimForProcessing("evt_other"))
                .thenReturn(WebhookProcessingDecision.PROCESS);


        service.processWebhook(body, "signature");


        verify(completedHandler, never()).handle(any());
        verify(expiredHandler, never()).handle(any());
        verify(webhookTransactionService).markProcessed("evt_other");
    }
}
