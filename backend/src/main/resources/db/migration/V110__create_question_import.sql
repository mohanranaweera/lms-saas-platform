-- exam-management (Klass Exam Module, Slice A - docs/plans/klass-exam-module.md "imports"
-- migration, planned as V109, shifted to V110 because V108 is a Java migration; spec QB9):
-- CSV question-import history + idempotency guard.
--
-- One row per successful (atomic) CSV import into one module bank. A rejected import
-- writes nothing at all (no row here, no questions). Purely additive; no existing row is
-- touched.
--
-- Tenant-owned: tenant_id NOT NULL FK, tenant-leading indexes, composite same-tenant FKs.
-- id is generated application-side (UUIDv7) like every other table.

CREATE TABLE question_import (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    course_id        UUID NOT NULL,
    module_id        UUID NOT NULL,
    file_name        VARCHAR(255) NOT NULL,
    file_sha256      CHAR(64) NOT NULL,
    -- Client-supplied Idempotency-Key header (<= 100 chars). A retry with the same key for
    -- the same module returns this row instead of importing the file a second time.
    idempotency_key  VARCHAR(100) NOT NULL,
    question_count   INTEGER NOT NULL,
    imported_by      UUID NOT NULL,
    imported_at      TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    created_by       UUID,
    updated_by       UUID,

    CONSTRAINT uq_question_import_tenant_id UNIQUE (tenant_id, id),
    -- Per-tenant, per-module idempotency (never global): the same key in another module or
    -- another tenant is an unrelated import.
    CONSTRAINT uq_question_import_tenant_module_idempotency UNIQUE (tenant_id, module_id, idempotency_key),

    -- The module must belong to the import's course in the same tenant (V109's
    -- uq_course_module_tenant_course_id). RESTRICT: a module with import history cannot be
    -- deleted (history is never deleted).
    CONSTRAINT fk_question_import_module FOREIGN KEY (tenant_id, course_id, module_id)
        REFERENCES course_module (tenant_id, course_id, id),
    CONSTRAINT fk_question_import_imported_by FOREIGN KEY (tenant_id, imported_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_question_import_file_name_not_blank CHECK (btrim(file_name) <> ''),
    CONSTRAINT ck_question_import_sha256_hex CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_question_import_idempotency_key_not_blank CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_question_import_question_count CHECK (question_count > 0 AND question_count <= 500)
);

-- Imports tab: one module's history, newest first (the optional file-name search filters
-- within this tenant + module slice).
CREATE INDEX idx_question_import_tenant_module_imported_at
    ON question_import (tenant_id, module_id, imported_at DESC);

-- exam_question.import_id: which import created the question (NULL = created in the editor,
-- copied, or legacy). Same-tenant composite FK; no CASCADE (history is never deleted).
ALTER TABLE exam_question
    ADD COLUMN import_id UUID;

ALTER TABLE exam_question
    ADD CONSTRAINT fk_exam_question_import FOREIGN KEY (tenant_id, import_id)
        REFERENCES question_import (tenant_id, id);

CREATE INDEX idx_exam_question_tenant_import
    ON exam_question (tenant_id, import_id)
    WHERE import_id IS NOT NULL;
