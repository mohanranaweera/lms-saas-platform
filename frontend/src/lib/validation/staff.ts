import { z } from "zod";

/**
 * Zod schema for the "Add staff" form (`staff/create-staff-sheet.tsx`).
 * Mirrors `StaffCreateRequest`'s `@NotBlank`/`@Email`/`@Size` constraints for
 * `name`/`email`/`password` field-for-field (same shape as
 * `lib/validation/students.ts#studentCreateSchema`).
 *
 * `roleCode` deliberately is NOT re-validated against the backend's own
 * `@Pattern(regexp = "FINANCE_STAFF|COURSE_COORDINATOR|...")` allowlist here
 * — hardcoding that same 7-code list client-side would drift the moment the
 * backend's assignable-role set changes. Instead, `roleCode` is just
 * required-non-blank; the actual constrained choice set comes from the
 * live `GET /v1/roles` catalog (`lib/api/roles.ts#useAssignableRoles`) via
 * this form's `<select>` options, so a user can only ever pick a role the
 * backend currently considers assignable — the backend's own `@Pattern`
 * remains the authoritative check regardless.
 */
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
  password: z
    .string()
    .min(8, "Password must be at least 8 characters.")
    .max(255, "Password must be 255 characters or fewer."),
  roleCode: z.string().min(1, "Select a role."),
});

export type StaffCreateFormValues = z.infer<typeof staffCreateSchema>;
