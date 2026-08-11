import { z } from "zod";

/**
 * Zod schema for the tenant-admin "Add staff" form.
 *
 * Mirrors `StaffCreateRequest` (backend `com.lms.usermanagement.staff.web.dto`)
 * field-for-field: `name`, `email`, `roleCode`. The backend generates the
 * temporary password itself (returned once, as `temporaryPassword`, on the
 * create response) — this form never collects or sends a password. This is a
 * UX convenience only — the backend independently and authoritatively
 * re-validates every field (`@NotBlank`/`@Email`/`@Pattern`), per
 * `.claude/rules/frontend.md`.
 */

/**
 * Mirrors `StaffCreateRequest`'s `@Pattern` — the fixed 7 staff sub-roles
 * this flow is allowed to assign (deliberately excludes `TENANT_ADMIN` /
 * `TEACHER` / `TEACHER_ASSISTANT` / `STUDENT`, which are provisioned by
 * other flows). Client-side convenience only — the backend independently
 * re-validates and is the sole authoritative enforcement point.
 */
export const STAFF_ROLE_VALUES = [
  "FINANCE_STAFF",
  "COURSE_COORDINATOR",
  "STUDENT_SUPPORT",
  "CONTENT_MANAGER",
  "EXAM_MANAGER",
  "ATTENDANCE_OPERATOR",
  "READ_ONLY_AUDITOR",
] as const;

export type StaffRoleCode = (typeof STAFF_ROLE_VALUES)[number];

/** Human-readable labels for the role picker, in the same order as `STAFF_ROLE_VALUES`. */
export const STAFF_ROLE_OPTIONS: ReadonlyArray<{ value: StaffRoleCode; label: string }> = [
  { value: "FINANCE_STAFF", label: "Finance Staff" },
  { value: "COURSE_COORDINATOR", label: "Course Coordinator" },
  { value: "STUDENT_SUPPORT", label: "Student Support" },
  { value: "CONTENT_MANAGER", label: "Content Manager" },
  { value: "EXAM_MANAGER", label: "Exam Manager" },
  { value: "ATTENDANCE_OPERATOR", label: "Attendance Operator" },
  { value: "READ_ONLY_AUDITOR", label: "Read-only Auditor" },
];

export const staffCreateSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required.")
    .max(255, "Name must be 255 characters or fewer."),
  email: z
    .string()
    .min(1, "Email is required.")
    .email("Enter a valid email address.")
    .max(255, "Email must be 255 characters or fewer."),
  roleCode: z.enum(STAFF_ROLE_VALUES, { error: "Select a role." }),
});

export type StaffCreateFormValues = z.infer<typeof staffCreateSchema>;
