-- user-management (RPT-02 security review L3/L4): hardening of the V95
-- student index number trigger. V95 is NOT edited (already applied).
--
-- L3: the trigger function now runs with a fixed search_path and refers to
--     the counter table schema-qualified, so a session search_path (or an
--     object of the same name in another schema) can never redirect the
--     counter upsert.
-- L4: the index number is per tenant, so moving an existing student_profile
--     row to another tenant would carry a number that was never allocated in
--     that tenant. A tenant_id change on a row that has an index number is
--     rejected (the trigger now also fires on UPDATE OF tenant_id).
--
-- Behaviour on INSERT is unchanged: a NULL index_number gets the next
-- per-tenant number from public.student_index_counter in the same
-- transaction; an explicitly supplied value is kept.

CREATE OR REPLACE FUNCTION assign_student_index_number() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = public, pg_temp
AS $$
DECLARE
    next_value BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.index_number IS NULL THEN
            INSERT INTO public.student_index_counter AS c (tenant_id, last_value)
            VALUES (NEW.tenant_id, 1)
            ON CONFLICT (tenant_id) DO UPDATE SET last_value = c.last_value + 1
            RETURNING c.last_value INTO next_value;

            NEW.index_number := CASE
                                    WHEN next_value < 10000 THEN lpad(next_value::text, 4, '0')
                                    ELSE next_value::text
                                END;
        END IF;
    ELSE
        IF NEW.index_number IS DISTINCT FROM OLD.index_number THEN
            RAISE EXCEPTION 'student_profile.index_number is immutable once assigned';
        END IF;
        IF OLD.index_number IS NOT NULL AND NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
            RAISE EXCEPTION 'student_profile.tenant_id cannot change once an index number is assigned';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER trg_student_profile_index_number ON student_profile;

CREATE TRIGGER trg_student_profile_index_number
    BEFORE INSERT OR UPDATE OF index_number, tenant_id ON student_profile
    FOR EACH ROW EXECUTE FUNCTION assign_student_index_number();
