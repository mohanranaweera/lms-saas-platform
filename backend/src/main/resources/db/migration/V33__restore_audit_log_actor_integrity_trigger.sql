-- MVP-020 Platform Admin Dashboard follow-up (post-review Critical fix):
-- restore schema-level enforcement of `audit_log.actor_id` integrity.
--
-- V32__relax_audit_log_actor_fk_for_platform_admin_actors.sql dropped
-- `fk_audit_log_actor` because `actor_id` became polymorphic once a second
-- actor table (`platform_admin_user`) exists alongside `tenant_user` - a
-- single-table FK can no longer express "actor_id must reference a real
-- actor". V32's header comment records that, as a deliberate, reviewed
-- trade-off, enforcement moved entirely to the service layer
-- (`AuditLogService#requireKnownActor`, backed by
-- `identityaccessservice.api.UserProvisioningApi#actorExists`).
--
-- A subsequent review (database-architect, security-reviewer,
-- solution-architect - all three independently) flagged that as Critical
-- against .claude/rules/backend.md's "Schema-enforced invariants for
-- high-integrity domains" section, which requires an audit row with an
-- unidentified actor to be "rejected at the schema level (NOT NULL + FK
-- where the actor is a known user), not just discouraged in code review".
-- A service-layer-only guard is bypassable by any insert/update path that
-- does not go through `AuditLogService` (a future bug, a new call site using
-- `EntityManager.persist` or a repository `save` directly, a direct
-- data-fix script) - exactly the class of gap schema-level enforcement
-- exists to close.
--
-- Since `actor_id` is genuinely polymorphic (`tenant_user.id` OR
-- `platform_admin_user.id`, two independently-generated UUID id spaces with
-- no shared parent table), a single declarative FOREIGN KEY still cannot
-- express this invariant - the same limitation V32 already documented for
-- `target_id`/`target_entity`. A `BEFORE INSERT OR UPDATE OF actor_id`
-- trigger, backed by a PL/pgSQL function, is the schema-level mechanism that
-- CAN express an "exists in table A OR table B" check: it runs inside the
-- same transaction as the write, for every write path regardless of which
-- Java code performs it (Spring Data `save`/`saveAndFlush`,
-- `EntityManager.persist`, a raw JDBC insert, a future migration/data-fix
-- script) - unlike a service-layer guard, it cannot be bypassed by skipping
-- `AuditLogService`.
--
-- This complements, not replaces, `AuditLogService#requireKnownActor`: that
-- check still gives a clean, early, descriptive `IllegalArgumentException`
-- in the normal application code path (a better API-caller experience than
-- a raw constraint-violation exception); this trigger is the un-bypassable
-- backstop underneath it. Keeping both mirrors this codebase's existing
-- "CHECK constraint plus service-layer guard" pattern for payment/ledger
-- state machines (.claude/rules/backend.md's own example).
--
-- This is a new, additive migration - V32 is NOT edited (migration history
-- is append-only, per root CLAUDE.md and .claude/rules/tenancy.md's data
-- model enforcement rules). No existing row is affected: the function only
-- fires on INSERT or on UPDATE that touches actor_id, and every row already
-- in `audit_log` today satisfies this check already (it previously
-- satisfied the stricter single-table FK).

CREATE FUNCTION audit_log_actor_must_exist() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM tenant_user WHERE id = NEW.actor_id)
       AND NOT EXISTS (SELECT 1 FROM platform_admin_user WHERE id = NEW.actor_id) THEN
        RAISE EXCEPTION 'audit_log.actor_id % does not reference a known tenant_user or platform_admin_user row',
            NEW.actor_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_actor_must_exist
    BEFORE INSERT OR UPDATE OF actor_id ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_actor_must_exist();
