-- Fixes an MVP-008 backend-review finding (severity Low): V11 declared both
-- an explicit CREATE INDEX and a UNIQUE constraint covering the exact same
-- column list, in the exact same order, on course_module and course_lesson:
--   * idx_course_module_tenant_course_sequence (tenant_id, course_id, sequence)
--     duplicates the backing index of uq_course_module_sequence
--     UNIQUE (tenant_id, course_id, sequence).
--   * idx_course_lesson_tenant_module_sequence (tenant_id, module_id, sequence)
--     duplicates the backing index of uq_course_lesson_sequence
--     UNIQUE (tenant_id, module_id, sequence).
--
-- Postgres already maintains a unique btree index for each UNIQUE constraint,
-- which fully serves the "all modules/lessons for one parent within one
-- tenant, in sequence order" read pattern these explicit indexes were added
-- for (V11's own comments). The explicit indexes added no additional query
-- coverage - only extra write-time index-maintenance cost on every
-- module/lesson insert, update, and delete.

DROP INDEX idx_course_module_tenant_course_sequence;
DROP INDEX idx_course_lesson_tenant_module_sequence;
