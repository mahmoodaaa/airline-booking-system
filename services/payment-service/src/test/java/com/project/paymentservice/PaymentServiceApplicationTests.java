package com.project.paymentservice;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — verifies the Spring context wires up correctly.
 *
 * Uses the "test" profile which must supply any required
 * infrastructure configuration (e.g. in-memory DB or Testcontainers).
 *
 * For DB-layer tests use @DataJpaTest or Testcontainers directly.
 *
 * Currently disabled: requires PostgreSQL + Stripe credentials.
 * Re-enable with Testcontainers or a dedicated test profile.
 */
@Disabled("Requires live PostgreSQL and Stripe credentials — re-enable with Testcontainers")
@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
        // Spring context must boot successfully
    }
}
