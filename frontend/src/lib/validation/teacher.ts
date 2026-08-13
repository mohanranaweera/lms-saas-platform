import { z } from "zod";

/**
 * Zod schema for the Tenant Admin "Add teacher" form
 * (`/tenant-admin/teachers/new`).
 *
 * Mirrors `TeacherCreateRequest` (backend
 * `com.lms.usermanagement.teacher.web.dto.TeacherCreateRequest`) field-for-field:
 * `name`, `email`, `password`. This is a UX convenience only — the backend
 * independently and authoritatively re-validates every field (including
 * tenant-scoped email uniqueness, which surfaces as a `409 CONFLICT` this form
 * maps onto the `email` field), per `.claude/rules/frontend.md`.
 */
export const teacherCreateSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required.")
    .max(255, "Name must be 255 characters or fewer."),
  email: z
    .string()
    .min(1, "Email is required.")
    .email("Enter a valid email address.")
    .max(255, "Email must be 255 characters or fewer."),
  password: z
    .string()
    .min(8, "Password must be at least 8 characters.")
    .max(255, "Password must be 255 characters or fewer."),
});

export type TeacherCreateFormValues = z.infer<typeof teacherCreateSchema>;
