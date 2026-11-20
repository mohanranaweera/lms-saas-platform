-- user-management (PAY-GW-01 follow-up): a student's own billing address.
--
-- PayHere's Checkout API requires the payer's address and city. Until now no
-- student address was held, so placeholders were sent. The student maintains
-- these on their own profile (GET/PUT /api/v1/students/me/billing-address);
-- online payment requires them.
--
-- Purely additive: two nullable columns on an existing tenant-owned table
-- (student_profile already carries tenant_id NOT NULL + its tenant-leading
-- indexes). No existing row is modified.
ALTER TABLE student_profile
    ADD COLUMN address_line VARCHAR(255),
    ADD COLUMN city         VARCHAR(100);
