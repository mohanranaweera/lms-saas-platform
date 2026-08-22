-- Fixes a defect found during MVP-009 (Lessons and Learning Materials)
-- backend planning review: course_lesson (V11, course-management) has no
-- UNIQUE (tenant_id, id) constraint, unlike its sibling parent tables
-- `course` and `course_module` in that same migration (both have
-- uq_course_tenant_id / uq_course_module_tenant_id). PostgreSQL requires a
-- composite FK's referenced column set to exactly match an existing
-- unique/PK constraint on the parent table - content-management's
-- `material` table (V16) needs
-- FOREIGN KEY (tenant_id, lesson_id) REFERENCES course_lesson (tenant_id, id),
-- which cannot be created without this constraint existing first. See
-- docs/plans/MVP-009 Lessons and Learning Materials.md §8/§21 item 10.
--
-- Added as a new migration (never an edit to V11) per this project's Flyway
-- convention of never editing an already-shared/applied migration - only
-- adding new ones.

ALTER TABLE course_lesson ADD CONSTRAINT uq_course_lesson_tenant_id UNIQUE (tenant_id, id);
