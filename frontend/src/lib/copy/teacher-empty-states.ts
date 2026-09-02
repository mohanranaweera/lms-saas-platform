/**
 * Shared empty-state copy for the Teacher portal's "zero assigned courses"
 * case, used by both `/teacher/dashboard` (Overview) and `/teacher/courses`
 * (My Courses) so the two screens agree verbatim — see
 * `docs/plans/MVP-014 Teacher Dashboard.md` §4.1 step 4 and §11. Distinct
 * from the Student "no active enrollments" copy per
 * `.claude/rules/ui-ux.md` §3.
 */
export const TEACHER_NO_ASSIGNED_COURSES_TITLE = "No assigned courses yet";

export const TEACHER_NO_ASSIGNED_COURSES_DESCRIPTION =
  "You don't own any courses yet. Create your first course to get started, or contact your tenant admin if you expected to see courses assigned here already.";

export const TEACHER_NO_ASSIGNED_COURSES_ACTION_LABEL = "Create your first course";
