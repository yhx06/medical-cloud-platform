-- Apply only through the protected 006 upgrader to an exact 005 baseline.
-- Additive only: every existing row keeps its amount, paid_at and clinical record.
-- Legacy PAID rows receive a one-time unique reference; UNPAID rows stay NULL.
ALTER TABLE payment_order
    ADD COLUMN payment_reference VARCHAR(25) NULL AFTER paid_at;

UPDATE payment_order
SET payment_reference = CONCAT('PAY-', COALESCE(DATE_FORMAT(paid_at, '%Y%m%d'), '00000000'), '-',
        UPPER(SUBSTRING(REPLACE(UUID(), '-', ''), 1, 12)))
WHERE status = 'PAID' AND (payment_reference IS NULL OR payment_reference = '');

ALTER TABLE payment_order
    ADD CONSTRAINT uk_course_order_payment_reference UNIQUE (payment_reference);
