-- ledger-settlement-management (multi-course checkout, ADR-029 per-course
-- revenue allocation, decision 5): settlement idempotency moves from one
-- `teacher_settlement_item` per ledger entry to one per (ledger entry,
-- course), so a multi-course order's payment can be settled once per course
-- share.
--
-- Approval: docs/adr/ADR-029-per-course-revenue-allocation.md (Accepted
-- 2026-10-19, project owner) - change to the payments.md §5 settlement area.
--
-- Constraint swap only. No row is updated or deleted, and historical
-- settlements are not recomputed: every existing item keeps its stored
-- amount and its settlement. Existing rows trivially satisfy the new key,
-- because they already satisfy the stricter (tenant_id, ledger_entry_id)
-- key it replaces. The new constraint is added before the old one is
-- dropped, so the table is never without an idempotency guard. A course's
-- allocation of a ledger entry is still settled at most once across all
-- periods/runs (payments.md §5).
--
-- Dependency check: no FK or other object references
-- uq_teacher_settlement_item_ledger_entry or its backing index (it is
-- defined only in V55; no table references teacher_settlement_item by
-- (tenant_id, ledger_entry_id)). Ledger-entry lookups stay index-backed
-- because the new unique key leads with (tenant_id, ledger_entry_id).

ALTER TABLE teacher_settlement_item
    ADD CONSTRAINT uq_teacher_settlement_item_ledger_entry_course
        UNIQUE (tenant_id, ledger_entry_id, course_id);

ALTER TABLE teacher_settlement_item
    DROP CONSTRAINT uq_teacher_settlement_item_ledger_entry;
