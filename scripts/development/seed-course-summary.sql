-- LOCAL DEVELOPMENT ONLY. Synthetic Course Summary fixture for lms_dev.
-- Marker tenant: course-summary-seed. This script never touches another tenant.
-- Rerun-safe: inserts carry stable UUIDs and use ON CONFLICT DO NOTHING.

\set ON_ERROR_STOP on
BEGIN;

DO $$
BEGIN
  IF current_database() <> 'lms_dev' THEN
    RAISE EXCEPTION 'Refusing to seed database %, expected lms_dev', current_database();
  END IF;
END $$;

CREATE TEMP TABLE course_summary_seed_ids (
  tenant_id uuid,
  admin_id uuid,
  teacher_a uuid,
  teacher_b uuid,
  course_a uuid,
  course_b uuid,
  course_c uuid,
  course_d uuid
) ON COMMIT DROP;

INSERT INTO tenant (id, name, subdomain, status, requested_plan, contact_name, contact_email, contact_phone, created_at, updated_at)
VALUES ('c1000000-0000-4000-8000-000000000001', 'Course Summary Seed Institute', 'course-summary-seed', 'active', 'local-seed', 'Synthetic Admin', 'course-summary-seed@example.test', '+94-000-000-000', now(), now())
ON CONFLICT (subdomain) DO NOTHING;

INSERT INTO course_summary_seed_ids
SELECT t.id, 'c1000000-0000-4000-8000-000000000010', 'c1000000-0000-4000-8000-000000000011',
       'c1000000-0000-4000-8000-000000000012', 'c1000000-0000-4000-8000-000000000101',
       'c1000000-0000-4000-8000-000000000102', 'c1000000-0000-4000-8000-000000000103',
       'c1000000-0000-4000-8000-000000000104'
FROM tenant t WHERE t.subdomain = 'course-summary-seed';

DO $$
BEGIN
  IF (SELECT count(*) FROM course_summary_seed_ids) <> 1 THEN
    RAISE EXCEPTION 'Seed marker tenant is missing or ambiguous';
  END IF;
END $$;

-- Hash copied from a local synthetic account only. It grants no production access.
INSERT INTO tenant_user (id, tenant_id, email, password_hash, role, status, must_change_password, created_at, updated_at)
SELECT ids.admin_id, ids.tenant_id, 'admin@course-summary-seed.example.test',
       (SELECT password_hash FROM tenant_user WHERE role = 'TEACHER' LIMIT 1), 'TENANT_ADMIN', 'active', false, now(), now()
FROM course_summary_seed_ids ids
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO tenant_user (id, tenant_id, email, password_hash, role, status, must_change_password, created_at, updated_at)
SELECT user_id, ids.tenant_id, email,
       (SELECT password_hash FROM tenant_user WHERE role = 'TEACHER' LIMIT 1), role, 'active', false, now(), now()
FROM course_summary_seed_ids ids
CROSS JOIN LATERAL (VALUES
  (ids.teacher_a, 'teacher-a@course-summary-seed.example.test', 'TEACHER'),
  (ids.teacher_b, 'teacher-b@course-summary-seed.example.test', 'TEACHER')
) AS users(user_id, email, role)
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO teacher_profile (id, tenant_id, user_id, name, approval_status, approved_by, approved_at, created_at, updated_at, created_by, updated_by)
SELECT profile_id, ids.tenant_id, teacher_id, name, 'APPROVED', ids.admin_id, now(), now(), now(), ids.admin_id, ids.admin_id
FROM course_summary_seed_ids ids
CROSS JOIN LATERAL (VALUES
  ('c1000000-0000-4000-8000-000000000021'::uuid, ids.teacher_a, 'Synthetic Teacher A'),
  ('c1000000-0000-4000-8000-000000000022'::uuid, ids.teacher_b, 'Synthetic Teacher B')
) AS teachers(profile_id, teacher_id, name)
ON CONFLICT (tenant_id, user_id) DO NOTHING;

INSERT INTO teacher_revenue_share_rate (id, tenant_id, teacher_id, share_percent, effective_from, created_by, created_at)
SELECT rate_id, ids.tenant_id, teacher_id, share, DATE '2026-10-01', ids.admin_id, now()
FROM course_summary_seed_ids ids
CROSS JOIN LATERAL (VALUES
  ('c1000000-0000-4000-8000-000000000031'::uuid, ids.teacher_a, 75.00::numeric),
  ('c1000000-0000-4000-8000-000000000032'::uuid, ids.teacher_b, 70.00::numeric)
) AS rates(rate_id, teacher_id, share)
ON CONFLICT (tenant_id, teacher_id, effective_from) DO NOTHING;

INSERT INTO tenant_user (id, tenant_id, email, password_hash, role, status, must_change_password, created_at, updated_at)
SELECT ('c1000000-0000-4000-9100-' || lpad(student_no::text, 12, '0'))::uuid, ids.tenant_id,
       'student-' || lpad(student_no::text, 2, '0') || '@course-summary-seed.example.test',
       (SELECT password_hash FROM tenant_user WHERE role = 'TEACHER' LIMIT 1), 'STUDENT', 'active', false, now(), now()
FROM course_summary_seed_ids ids CROSS JOIN generate_series(1, 20) AS student_no
ON CONFLICT (tenant_id, email) DO NOTHING;

INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at, created_by, updated_by)
SELECT ('c1000000-0000-4000-8100-' || lpad(student_no::text, 12, '0'))::uuid, ids.tenant_id,
       ('c1000000-0000-4000-9100-' || lpad(student_no::text, 12, '0'))::uuid,
       'Synthetic Student ' || lpad(student_no::text, 2, '0'), now(), now(), ids.admin_id, ids.admin_id
FROM course_summary_seed_ids ids CROSS JOIN generate_series(1, 20) AS student_no
ON CONFLICT (tenant_id, user_id) DO NOTHING;

INSERT INTO course (id, tenant_id, teacher_id, name, slug, category, price, status, created_at, updated_at,
                    created_by, updated_by, pricing_model, archived_at, registration_enabled, enrollment_fee,
                    display_order, facility_fee_override, postage_fee)
SELECT course_id, ids.tenant_id, teacher_id, name, slug, 'Synthetic Reporting', price, 'PUBLIC',
       now(), now(), ids.admin_id, ids.admin_id, 'MONTHLY', NULL, true, 0, display_order, 0, 0
FROM course_summary_seed_ids ids
CROSS JOIN LATERAL (VALUES
  (ids.course_a, ids.teacher_a, 'Synthetic Biology Theory', 'seed-biology-theory', 4000.00::numeric, 1),
  (ids.course_b, ids.teacher_a, 'Synthetic Biology Revision', 'seed-biology-revision', 3500.00::numeric, 2),
  (ids.course_c, ids.teacher_b, 'Synthetic Chemistry Theory', 'seed-chemistry-theory', 3000.00::numeric, 3),
  (ids.course_d, ids.teacher_b, 'Synthetic Physics Theory', 'seed-physics-theory', 4500.00::numeric, 4)
) AS courses(course_id, teacher_id, name, slug, price, display_order)
ON CONFLICT (tenant_id, slug) DO NOTHING;

INSERT INTO course_teacher_assignment (id, tenant_id, course_id, teacher_id, is_primary, created_at)
SELECT ('c1000000-0000-4000-8200-' || lpad(row_no::text, 12, '0'))::uuid, ids.tenant_id, course_id, teacher_id, true, now()
FROM course_summary_seed_ids ids
CROSS JOIN LATERAL (VALUES
  (1, ids.course_a, ids.teacher_a), (2, ids.course_b, ids.teacher_a),
  (3, ids.course_c, ids.teacher_b), (4, ids.course_d, ids.teacher_b)
) AS assignments(row_no, course_id, teacher_id)
ON CONFLICT (tenant_id, course_id, teacher_id) DO NOTHING;

-- Free participation: registration counts for October report windows.
INSERT INTO course_participation (id, tenant_id, student_id, course_id, enrolled_at, source)
SELECT ('c1000000-0000-4000-8300-' || lpad(student_no::text, 12, '0'))::uuid, ids.tenant_id,
       ('c1000000-0000-4000-9100-' || lpad(student_no::text, 12, '0'))::uuid,
       CASE (student_no - 1) % 4 WHEN 0 THEN ids.course_a WHEN 1 THEN ids.course_b WHEN 2 THEN ids.course_c ELSE ids.course_d END,
       ('2026-10-01'::date + ((student_no - 1) % 28))::timestamp AT TIME ZONE 'Asia/Colombo', 'SELF_ENROLL'
FROM course_summary_seed_ids ids CROSS JOIN generate_series(1, 20) AS student_no
ON CONFLICT (tenant_id, student_id, course_id) DO NOTHING;

-- One synthetic fixed-amount discount definition used by every sixth order.
-- Header/version FK is deferred, matching the application write transaction.
INSERT INTO discount_rule (id, tenant_id, discount_type, code_normalized, current_version_id, active, archived_at,
                           created_at, created_by, updated_at)
SELECT 'c1000000-0000-4000-9300-000000000001', ids.tenant_id, 'DISCOUNT_CODE', 'SEED500',
       'c1000000-0000-4000-9300-000000000002', true, NULL, now(), ids.admin_id, now()
FROM course_summary_seed_ids ids
ON CONFLICT (id) DO NOTHING;

INSERT INTO discount_rule_version (id, tenant_id, rule_id, version_no, name, description, discount_type,
                                   selection_mode, min_count, payment_type, value_type, value, free_months, code,
                                   valid_from, valid_to, max_uses, min_purchase_amount, currency, created_at, created_by)
SELECT 'c1000000-0000-4000-9300-000000000002', ids.tenant_id,
       'c1000000-0000-4000-9300-000000000001', 1, 'Synthetic LKR 500 discount',
       'Local Course Summary report fixture', 'DISCOUNT_CODE', 'ANY', NULL, NULL, 'FIXED', 500.00, NULL,
       'SEED500', DATE '2026-01-01', DATE '2026-12-31', NULL, NULL, 'LKR', now(), ids.admin_id
FROM course_summary_seed_ids ids
ON CONFLICT (id) DO NOTHING;

-- 60 immutable monthly orders/payment confirmations/ledger entries. 15 per course.
WITH seed AS (
  SELECT n, ids.tenant_id, ids.admin_id,
         ('c1000000-0000-4000-8400-' || lpad(n::text, 12, '0'))::uuid AS order_id,
         ('c1000000-0000-4000-8500-' || lpad(n::text, 12, '0'))::uuid AS line_id,
         ('c1000000-0000-4000-8600-' || lpad(n::text, 12, '0'))::uuid AS payment_id,
         ('c1000000-0000-4000-8700-' || lpad(n::text, 12, '0'))::uuid AS ledger_id,
         ('c1000000-0000-4000-9400-' || lpad(n::text, 12, '0'))::uuid AS order_discount_id,
         ('c1000000-0000-4000-9100-' || lpad((((n - 1) % 20) + 1)::text, 12, '0'))::uuid AS student_id,
         CASE (n - 1) / 15 WHEN 0 THEN ids.course_a WHEN 1 THEN ids.course_b WHEN 2 THEN ids.course_c ELSE ids.course_d END AS course_id,
         CASE (n - 1) / 15 WHEN 0 THEN 4000.00::numeric WHEN 1 THEN 3500.00::numeric WHEN 2 THEN 3000.00::numeric ELSE 4500.00::numeric END AS unit_amount,
         CASE WHEN n % 6 = 0 THEN 500.00::numeric ELSE 0.00::numeric END AS discount_amount,
         ('2026-10-01'::date + ((n - 1) % 28))::timestamp AT TIME ZONE 'Asia/Colombo' AS paid_at
  FROM course_summary_seed_ids ids CROSS JOIN generate_series(1, 60) AS n
), orders AS (
  INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, created_at, updated_at,
                             created_by, updated_by, subtotal_amount, facility_fee_amount, discount_amount, postage_amount,
                             fee_policy_snapshot, idempotency_key)
  SELECT order_id, tenant_id, student_id, course_id, unit_amount - discount_amount, 'LKR', 'PLACED', paid_at, paid_at,
         admin_id, admin_id, unit_amount, 0, discount_amount, NULL, 'NONE',
         ('c1000000-0000-4000-8800-' || lpad(n::text, 12, '0'))::uuid
  FROM seed ON CONFLICT (id) DO NOTHING RETURNING id
), discounts AS (
  INSERT INTO student_order_discount (id, tenant_id, order_id, rule_id, rule_version_id, discount_type, name,
                                     code, value_type, value, amount, qualification, created_at)
  SELECT order_discount_id, tenant_id, order_id, 'c1000000-0000-4000-9300-000000000001',
         'c1000000-0000-4000-9300-000000000002', 'DISCOUNT_CODE', 'Synthetic LKR 500 discount', 'SEED500',
         'FIXED', 500.00, 500.00, '{"seed":true}', now()
  FROM seed WHERE discount_amount > 0
  ON CONFLICT (id) DO NOTHING RETURNING id
), lines AS (
  INSERT INTO student_order_line (id, tenant_id, order_id, line_no, scope, course_id, period_year, period_month,
                                  label, unit_amount, facility_fee_amount, discount_amount, line_total, currency,
                                  coverage_key, created_at, order_discount_id, reward)
  SELECT line_id, tenant_id, order_id, 1, 'MONTHLY', course_id, 2026, 10, 'October 2026 course fee', unit_amount, 0,
         discount_amount, unit_amount - discount_amount, 'LKR', 'MONTHLY:' || course_id::text || ':2026-10', paid_at,
         CASE WHEN discount_amount > 0 THEN order_discount_id ELSE NULL END, false
  FROM seed ON CONFLICT (id) DO NOTHING RETURNING id
), payments AS (
  INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, confirmed_at, created_at,
                       updated_at, idempotency_key)
  SELECT payment_id, tenant_id, order_id, unit_amount - discount_amount, 'LKR', 'CONFIRMED',
         'SEED-GW-' || payment_id::text, paid_at, paid_at, paid_at,
         ('c1000000-0000-4000-8900-' || lpad(n::text, 12, '0'))::uuid
  FROM seed ON CONFLICT (id) DO NOTHING RETURNING id
)
INSERT INTO ledger_entry (id, tenant_id, order_id, payment_id, entry_type, amount, created_at)
SELECT ledger_id, tenant_id, order_id, payment_id, 'PAYMENT_CONFIRMED', unit_amount - discount_amount, paid_at
FROM seed ON CONFLICT (id) DO NOTHING;

-- Four partial refunds. Each reverses one confirmed synthetic ledger entry.
INSERT INTO payment_refund (id, tenant_id, original_payment_id, amount, reason, created_at, idempotency_key)
SELECT ('c1000000-0000-4000-8a00-' || lpad(n::text, 12, '0'))::uuid, ids.tenant_id,
       ('c1000000-0000-4000-8600-' || lpad(n::text, 12, '0'))::uuid, 500.00, 'Synthetic partial refund',
       '2026-10-25T12:00:00+05:30'::timestamptz, ('c1000000-0000-4000-8b00-' || lpad(n::text, 12, '0'))::uuid
FROM course_summary_seed_ids ids CROSS JOIN (VALUES (5), (17), (31), (46)) AS refunded(n)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ledger_entry (id, tenant_id, order_id, payment_id, entry_type, amount, reverses_entry_id, created_at)
SELECT ('c1000000-0000-4000-8c00-' || lpad(n::text, 12, '0'))::uuid, ids.tenant_id,
       ('c1000000-0000-4000-8400-' || lpad(n::text, 12, '0'))::uuid,
       ('c1000000-0000-4000-8600-' || lpad(n::text, 12, '0'))::uuid, 'REFUND', -500.00,
       ('c1000000-0000-4000-8700-' || lpad(n::text, 12, '0'))::uuid,
       '2026-10-25T12:00:00+05:30'::timestamptz
FROM course_summary_seed_ids ids CROSS JOIN (VALUES (5), (17), (31), (46)) AS refunded(n)
ON CONFLICT (id) DO NOTHING;

-- Four non-confirmed attempts: visible in raw payment data, never ledger-derived Course Summary income.
WITH attempts AS (
  SELECT n, ids.tenant_id, ids.admin_id,
         ('c1000000-0000-4000-8d00-' || lpad(n::text, 12, '0'))::uuid AS order_id,
         ('c1000000-0000-4000-8e00-' || lpad(n::text, 12, '0'))::uuid AS payment_id,
         ('c1000000-0000-4000-9100-' || lpad(n::text, 12, '0'))::uuid AS student_id,
         ids.course_a AS course_id,
         CASE WHEN n % 2 = 0 THEN 'PENDING' ELSE 'REJECTED' END AS payment_status
  FROM course_summary_seed_ids ids CROSS JOIN generate_series(1, 4) AS n
)
INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, created_at, updated_at,
                           created_by, updated_by, subtotal_amount, facility_fee_amount, discount_amount, postage_amount,
                           fee_policy_snapshot)
SELECT order_id, tenant_id, student_id, course_id, 4000.00, 'LKR', 'PLACED', now(), now(), admin_id, admin_id,
       4000.00, 0, 0, NULL, 'NONE'
FROM attempts ON CONFLICT (id) DO NOTHING;

WITH attempts AS (
  SELECT n, ids.tenant_id,
         ('c1000000-0000-4000-8d00-' || lpad(n::text, 12, '0'))::uuid AS order_id,
         ('c1000000-0000-4000-8e00-' || lpad(n::text, 12, '0'))::uuid AS payment_id,
         CASE WHEN n % 2 = 0 THEN 'PENDING' ELSE 'REJECTED' END AS payment_status
  FROM course_summary_seed_ids ids CROSS JOIN generate_series(1, 4) AS n
)
INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, confirmed_at, created_at, updated_at)
SELECT payment_id, tenant_id, order_id, 4000.00, 'LKR', payment_status,
       CASE WHEN payment_status = 'REJECTED' THEN 'SEED-REJECTED-' || payment_id::text ELSE NULL END,
       NULL, now(), now()
FROM attempts ON CONFLICT (id) DO NOTHING;

-- Ten complimentary Free Cards; no order/payment/ledger rows for these passes.
INSERT INTO free_card (id, tenant_id, student_id, course_id, card_type, valid_from, valid_to, timezone,
                       effective_from, effective_until, reason, currency, nominal_monthly_amount, nominal_value,
                       issued_by, issued_at, idempotency_key, enrollment_created)
SELECT ('c1000000-0000-4000-8f00-' || lpad(n::text, 12, '0'))::uuid, ids.tenant_id,
       ('c1000000-0000-4000-9100-' || lpad((n + 10)::text, 12, '0'))::uuid,
       CASE (n - 1) % 4 WHEN 0 THEN ids.course_a WHEN 1 THEN ids.course_b WHEN 2 THEN ids.course_c ELSE ids.course_d END,
       'FULL', DATE '2026-10-01' + (n - 1), DATE '2026-10-31', 'Asia/Colombo',
       (DATE '2026-10-01' + (n - 1))::timestamp AT TIME ZONE 'Asia/Colombo',
       (DATE '2026-11-01')::timestamp AT TIME ZONE 'Asia/Colombo', 'Synthetic Course Summary complimentary pass',
       'LKR', 4000.00, 4000.00, ids.admin_id, now(),
       ('c1000000-0000-4000-9000-' || lpad(n::text, 12, '0'))::uuid, false
FROM course_summary_seed_ids ids CROSS JOIN generate_series(1, 10) AS n
ON CONFLICT (tenant_id, idempotency_key) DO NOTHING;

COMMIT;

-- Marker-scoped verification only.
SELECT
  (SELECT count(*) FROM student_profile sp JOIN tenant t ON t.id = sp.tenant_id WHERE t.subdomain = 'course-summary-seed') AS students,
  (SELECT count(*) FROM teacher_profile tp JOIN tenant t ON t.id = tp.tenant_id WHERE t.subdomain = 'course-summary-seed') AS teachers,
  (SELECT count(*) FROM course c JOIN tenant t ON t.id = c.tenant_id WHERE t.subdomain = 'course-summary-seed') AS courses,
  (SELECT count(*) FROM payment p JOIN tenant t ON t.id = p.tenant_id WHERE t.subdomain = 'course-summary-seed' AND p.status = 'CONFIRMED') AS confirmed_payments,
  (SELECT count(*) FROM ledger_entry le JOIN tenant t ON t.id = le.tenant_id WHERE t.subdomain = 'course-summary-seed' AND le.entry_type = 'PAYMENT_CONFIRMED') AS confirmed_ledger_entries,
  (SELECT count(*) FROM ledger_entry le JOIN tenant t ON t.id = le.tenant_id WHERE t.subdomain = 'course-summary-seed' AND le.entry_type = 'REFUND') AS refund_ledger_entries,
  (SELECT count(*) FROM payment p JOIN tenant t ON t.id = p.tenant_id WHERE t.subdomain = 'course-summary-seed' AND p.status IN ('PENDING', 'REJECTED')) AS non_confirmed_attempts,
  (SELECT count(*) FROM free_card fc JOIN tenant t ON t.id = fc.tenant_id WHERE t.subdomain = 'course-summary-seed') AS free_cards;
