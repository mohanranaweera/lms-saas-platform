-- course-management (Wave 2 - Course/Class model + billing foundation):
-- extends the existing `course` aggregate (V11) with a `pricing_model`
-- discriminator and an `archived_at` soft-lifecycle flag. This migration
-- does NOT create a new course/class table - per the approved Wave 2 plan,
-- `course` remains the single aggregate root; billing configuration and
-- billing-period history are added as new child tables in V38/V39.
--
-- `pricing_model` follows `course.status`'s own established convention
-- exactly (V11): a CHECK-constrained VARCHAR, not a native Postgres enum
-- type - this schema has never used a native enum type for a status-like
-- column, so introducing one here would be an inconsistent, unreviewed
-- pattern change. `NOT NULL DEFAULT 'ONE_TIME'` backfills every existing
-- row safely, since every course created before this migration was
-- effectively a flat one-time price (V11's own header comment: "bare
-- NUMERIC price", "no currency column... a single implicit currency").
--
-- `archived_at` mirrors `enrollment.superseded_at`'s (V22) nullable-
-- timestamp-flag pattern: NULL means "not archived", a non-null timestamp
-- records when the course was archived, without ever deleting the row or
-- losing its price/order/enrollment history. Neither `price` nor `status`
-- semantics are touched by this migration.

ALTER TABLE course
    ADD COLUMN pricing_model VARCHAR(20) NOT NULL DEFAULT 'ONE_TIME',
    ADD COLUMN archived_at   TIMESTAMPTZ;

ALTER TABLE course
    ADD CONSTRAINT ck_course_pricing_model CHECK (
        pricing_model IN ('FREE', 'ONE_TIME', 'MONTHLY', 'SESSION', 'CUSTOM')
    );
