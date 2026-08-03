-- Disposable, test-only fixture table used solely to exercise
-- TenantAwareRepository end-to-end (real Postgres via Testcontainers,
-- not a mock, so a missing tenant_id filter fails the test). Version 9001
-- keeps it visibly separate from the real V1.. migration history in
-- src/main/resources/db/migration - this file is never applied to any
-- real dev/prod database (see backend/src/test/resources/application.yml,
-- which adds classpath:db/migration-test as a test-only Flyway location).
--
-- Kept permanently as a fast regression guard for the base repository
-- mechanism, independent of whatever real tenant-owned tables
-- tenant-management eventually ships.
CREATE TABLE test_fixture_widget (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by UUID,
    updated_by UUID
);

CREATE INDEX idx_test_fixture_widget_tenant ON test_fixture_widget (tenant_id, id);
