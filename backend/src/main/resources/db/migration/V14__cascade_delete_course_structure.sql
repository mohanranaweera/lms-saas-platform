-- Fixes an MVP-008 backend-review finding (severity Low): CourseService
-- #deleteCourse and CourseModuleService#deleteModule previously sequenced
-- three/two separate DELETE statements by hand (lessons, then modules, then
-- the course) purely to work around fk_course_module_course/
-- fk_course_lesson_module (V11) having no ON DELETE behavior. That is
-- exactly the "invariant enforced only by service-layer discipline" pattern
-- .claude/rules/backend.md and database-architecture.md §4 warn against for
-- schema-critical operations - a future direct SQL fix, admin script, or new
-- code path that deletes a course/module without going through those two
-- specific service methods would hit a raw FK violation (or, if DB
-- privileges were ever widened, silently orphan rows).
--
-- Fix: declare ON DELETE CASCADE on both FKs, so the database enforces the
-- structural cascade itself, regardless of which application code path
-- triggers the delete. This is safe for course_module/course_lesson
-- specifically - unlike course_price_history (V12), a module/lesson row has
-- no independent audit/compliance value once its parent course is gone, and
-- no root CLAUDE.md rule treats course structure as data that must survive
-- its owning course's deletion.
--
-- course_price_history is deliberately NOT touched here - V12 already
-- dropped its FK to course entirely (rather than cascading), specifically so
-- a course's price-change history survives the course's own deletion.

ALTER TABLE course_module DROP CONSTRAINT fk_course_module_course;
ALTER TABLE course_module ADD CONSTRAINT fk_course_module_course
    FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE course_lesson DROP CONSTRAINT fk_course_lesson_module;
ALTER TABLE course_lesson ADD CONSTRAINT fk_course_lesson_module
    FOREIGN KEY (tenant_id, module_id) REFERENCES course_module (tenant_id, id) ON DELETE CASCADE;
