-- Tenant-local, human-readable order number for support communication.
-- UUID remains the immutable internal order identity.

LOCK TABLE student_order IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE student_order_number_counter (
    tenant_id  UUID PRIMARY KEY REFERENCES tenant (id),
    last_value BIGINT NOT NULL,
    CONSTRAINT ck_student_order_number_counter_positive CHECK (last_value >= 0)
);

CREATE INDEX idx_student_order_number_counter_tenant
    ON student_order_number_counter (tenant_id);

ALTER TABLE student_order
    ADD COLUMN order_number BIGINT;

-- Deterministic backfill: oldest order first, UUID tie-breaker.
WITH numbered AS (
    SELECT id, tenant_id,
           ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY created_at, id) AS number
    FROM student_order
)
UPDATE student_order o
SET order_number = numbered.number
FROM numbered
WHERE o.id = numbered.id;

INSERT INTO student_order_number_counter (tenant_id, last_value)
SELECT tenant_id, MAX(order_number)
FROM student_order
GROUP BY tenant_id;

ALTER TABLE student_order
    ALTER COLUMN order_number SET NOT NULL;

ALTER TABLE student_order
    ADD CONSTRAINT uq_student_order_tenant_order_number
    UNIQUE (tenant_id, order_number);

CREATE INDEX idx_student_order_tenant_order_number
    ON student_order (tenant_id, order_number);

CREATE FUNCTION assign_student_order_number() RETURNS trigger AS $$
DECLARE
    next_value BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.order_number IS NULL THEN
            INSERT INTO student_order_number_counter AS c (tenant_id, last_value)
            VALUES (NEW.tenant_id, 1)
            ON CONFLICT (tenant_id) DO UPDATE SET last_value = c.last_value + 1
            RETURNING c.last_value INTO next_value;
            NEW.order_number := next_value;
        END IF;
    ELSIF NEW.order_number IS DISTINCT FROM OLD.order_number THEN
        RAISE EXCEPTION 'student_order.order_number is immutable once assigned';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_student_order_number
    BEFORE INSERT OR UPDATE OF order_number ON student_order
    FOR EACH ROW EXECUTE FUNCTION assign_student_order_number();

COMMENT ON COLUMN student_order.order_number IS
    'Tenant-local human-readable sequence, displayed as ORD- plus zero-padded value; immutable communication ID.';
