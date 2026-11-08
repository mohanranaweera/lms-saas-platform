-- identity-access-service (STAFF-MGMT-02): remember that an account was still INVITED (had
-- never completed account setup) when it was suspended, so reactivating it returns it to
-- 'invited' (it still has no usable password and must finish its setup link) instead of
-- 'active'.
--
-- Additive; no earlier migration is edited. tenant_user is already tenant-owned
-- (tenant_id NOT NULL, tenant-leading indexes); this adds no new table or index.
-- Existing rows default to false, i.e. reactivation keeps today's behaviour (-> active).

ALTER TABLE tenant_user
    ADD COLUMN suspended_while_invited BOOLEAN NOT NULL DEFAULT false;
