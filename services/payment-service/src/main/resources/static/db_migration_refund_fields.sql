-- =============================================================================
-- Migration: Add refund fields to payments table
--
-- Context:
--   Adds minimal refund tracking for the booking-rejection compensation path.
--   Triggered ONLY when:
--     Payment.status            = SUCCEEDED
--     BookingConfirmationStatus = REJECTED
--
-- PaymentStatus remains SUCCEEDED throughout (financial truth preserved).
-- RefundStatus independently tracks the return of captured funds.
--
-- NOTE: In this project ddl-auto=update handles schema changes automatically.
--       This file is provided for production reference / rollback purposes.
-- =============================================================================

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS refund_status           VARCHAR(30)  NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN IF NOT EXISTS provider_refund_id      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS refunded_at             TIMESTAMP,
    ADD COLUMN IF NOT EXISTS refund_last_error       VARCHAR(500);

-- Index to support reconciliation queries on refund_status
CREATE INDEX IF NOT EXISTS idx_payment_refund_status
    ON payments (refund_status)
    WHERE refund_status IN ('PENDING', 'UNKNOWN', 'FAILED');

-- =============================================================================
-- Rollback (if needed):
-- =============================================================================
-- ALTER TABLE payments
--     DROP COLUMN IF EXISTS refund_status,
--     DROP COLUMN IF EXISTS provider_refund_id,
--     DROP COLUMN IF EXISTS refunded_at,
--     DROP COLUMN IF EXISTS refund_last_error;
-- DROP INDEX IF EXISTS idx_payment_refund_status;
