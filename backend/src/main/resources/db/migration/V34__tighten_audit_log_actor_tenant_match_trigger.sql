-- MVP-020 Platform Admin Dashboard follow-up (post-review required
-- correction, flagged independently by database-architect and
-- security-reviewer): tighten V33's `trg_audit_log_actor_must_exist`
-- trigger so a `tenant_user` actor must belong to the SAME tenant as the
-- `audit_log` row's own `tenant_id`.
--
-- V21's original composite FK
--     CONSTRAINT fk_audit_log_actor FOREIGN KEY (tenant_id, actor_id)
--         REFERENCES tenant_user (tenant_id, id)
-- enforced two things at once: (a) `actor_id` names a real user, and (b)
-- that user belongs to the tenant recorded on the same row. V32 dropped
-- this FK because `actor_id` became polymorphic (`tenant_user` OR
-- `platform_admin_user`), and V33's replacement trigger restored only (a) -
-- it checks `actor_id` exists in `tenant_user` *anywhere*, with no
-- `tenant_id` comparison. That is a real, not cosmetic, narrowing: it would
-- silently accept an `audit_log` row with `tenant_id` = Tenant B and
-- `actor_id` = a genuine `tenant_user` belonging to Tenant A.
--
-- This is not exploitable through `AuditLogService`'s current two call
-- sites (`record()`/`recordForTenant()` always derive `tenant_id`
-- consistently with the actor's own resolved context today), but the
-- schema no longer backstops the invariant if a future call site, data-fix
-- script, or direct insert/update gets it wrong - exactly the class of gap
-- V33 itself was written to close (see that migration's own header
-- comment).
--
-- A `platform_admin_user` actor is legitimately NOT scoped to the target
-- tenant it is acting on (a Platform Admin approves/rejects/drills into any
-- tenant) - that branch is intentionally left tenant-independent, unlike
-- the `tenant_user` branch.
--
-- New, additive migration - V33 is NOT edited (migration history is
-- append-only, per root CLAUDE.md and .claude/rules/tenancy.md).
-- `CREATE OR REPLACE FUNCTION` is used because the trigger
-- (`trg_audit_log_actor_must_exist`) already exists and does not need to be
-- dropped/recreated - only the function body it invokes changes, and it
-- re-attaches automatically. No existing row is affected: every row
-- already in `audit_log` today was written by `AuditLogService`, which
-- already keeps `tenant_id`/`actor_id` consistent for `tenant_user` actors.

CREATE OR REPLACE FUNCTION audit_log_actor_must_exist() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM tenant_user WHERE id = NEW.actor_id AND tenant_id = NEW.tenant_id)
       AND NOT EXISTS (SELECT 1 FROM platform_admin_user WHERE id = NEW.actor_id) THEN
        RAISE EXCEPTION
            'audit_log.actor_id % does not reference a tenant_user belonging to tenant_id % nor a known platform_admin_user row',
            NEW.actor_id, NEW.tenant_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
