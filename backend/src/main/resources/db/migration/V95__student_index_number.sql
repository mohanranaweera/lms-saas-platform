-- user-management (RPT student payment report, owner decision D1):
-- per-tenant student index number on `student_profile`.
--
-- Requirement: docs/specs/student-payment-report.md §6.
--   * `index_number` is a string of decimal digits, zero-padded to a
--     minimum of 4 ("0001"), natural width above 9999 ("10000").
--   * Unique per tenant: UNIQUE (tenant_id, index_number).
--   * Existing students are backfilled per tenant in (created_at, id)
--     order starting at 1; every new student gets the next number.
--   * Read-only (no edit UI); enforced below as immutable once assigned.
--
-- V17 (student_profile's origin) is NOT edited - already applied/shared.
--
-- Allocation: `student_index_counter` holds one row per tenant with the
-- last number handed out. The next number is taken with a single
-- upsert statement (INSERT ... ON CONFLICT DO UPDATE ... RETURNING),
-- which takes a row lock on the tenant's counter row until the inserting
-- transaction ends - concurrent student creations in the same tenant
-- serialize on that row and can never receive the same number; different
-- tenants never contend. Because the counter row is updated inside the
-- same transaction as the student_profile INSERT, a rolled-back creation
-- also rolls back its increment (no gaps from failed creations).
--
-- Why not one PostgreSQL SEQUENCE per tenant: sequences are
-- non-transactional (nextval is never rolled back, so failed creations
-- leave gaps), they would need DDL at tenant provisioning time and
-- dynamic SQL to pick the sequence by tenant, they are not covered by a
-- tenant FK / tenant_id discriminator (tenancy.md), and they multiply
-- catalog objects per tenant. A plain tenant-keyed counter table has none
-- of these problems.
--
-- Assignment is done by a BEFORE INSERT trigger when the inserting
-- statement leaves `index_number` NULL. This covers every insert path
-- (admin create, bulk import, self-registration, raw-SQL test fixtures)
-- without requiring each one to change, which is what makes NOT NULL
-- safe in this migration. An application may instead allocate
-- explicitly (same upsert, same transaction) and supply the value; the
-- trigger then leaves it as-is.
--
-- `student_index_counter` is tenant-owned: tenant_id is its PRIMARY KEY
-- (tenant-leading unique index) and FKs `tenant`. Not financial data.

-- Block concurrent student_profile writes for the duration of the
-- backfill (ALTER TABLE below also takes ACCESS EXCLUSIVE; explicit here
-- for clarity).
LOCK TABLE student_profile IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE student_index_counter (
    tenant_id   UUID PRIMARY KEY REFERENCES tenant (id),
    last_value  BIGINT NOT NULL,

    CONSTRAINT ck_student_index_counter_last_value_non_negative CHECK (last_value >= 0)
);

ALTER TABLE student_profile
    ADD COLUMN index_number VARCHAR(20);

-- Backfill: per tenant, (created_at, id) order, starting at 1. Writes
-- only the new column.
UPDATE student_profile sp
SET index_number = CASE
                       WHEN numbered.n < 10000 THEN lpad(numbered.n::text, 4, '0')
                       ELSE numbered.n::text
                   END
FROM (
    SELECT id,
           row_number() OVER (PARTITION BY tenant_id ORDER BY created_at, id) AS n
    FROM student_profile
) numbered
WHERE sp.id = numbered.id;

-- Seed counters from the backfill (tenants with no students get their
-- row lazily on first allocation via the upsert).
INSERT INTO student_index_counter (tenant_id, last_value)
SELECT tenant_id, count(*)
FROM student_profile
GROUP BY tenant_id;

ALTER TABLE student_profile
    ALTER COLUMN index_number SET NOT NULL;

-- Format: 4 digits (0001-9999), or 5+ digits with no leading zero.
ALTER TABLE student_profile
    ADD CONSTRAINT ck_student_profile_index_number_format
        CHECK (index_number ~ '^([0-9]{4}|[1-9][0-9]{4,19})$' AND index_number <> '0000');

-- Tenant-leading unique constraint; its backing index also serves the
-- tenant-scoped "lookup/sort by index number" query shape, so no extra
-- index is added.
ALTER TABLE student_profile
    ADD CONSTRAINT uq_student_profile_tenant_index_number UNIQUE (tenant_id, index_number);

-- ---------------------------------------------------------------------------
-- Assignment on INSERT (when NULL) and immutability on UPDATE. A full-row
-- ORM flush writing the identical value is allowed.
-- ---------------------------------------------------------------------------

CREATE FUNCTION assign_student_index_number() RETURNS trigger AS $$
DECLARE
    next_value BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.index_number IS NULL THEN
            INSERT INTO student_index_counter AS c (tenant_id, last_value)
            VALUES (NEW.tenant_id, 1)
            ON CONFLICT (tenant_id) DO UPDATE SET last_value = c.last_value + 1
            RETURNING c.last_value INTO next_value;

            NEW.index_number := CASE
                                    WHEN next_value < 10000 THEN lpad(next_value::text, 4, '0')
                                    ELSE next_value::text
                                END;
        END IF;
    ELSIF NEW.index_number IS DISTINCT FROM OLD.index_number THEN
        RAISE EXCEPTION 'student_profile.index_number is immutable once assigned';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_student_profile_index_number
    BEFORE INSERT OR UPDATE OF index_number ON student_profile
    FOR EACH ROW EXECUTE FUNCTION assign_student_index_number();
