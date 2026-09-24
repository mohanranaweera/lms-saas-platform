import { z } from "zod";

/**
 * Zod schemas for the Wave 3 Student Detail "Actions" area
 * (`app/(tenant-admin)/tenant-admin/students/[studentId]/**`). Mirrors the
 * backend's request DTOs field-for-field: `StudentEnrollRequest`
 * (`courseId`, `reason` — both required, `reason` `@Size(max = 1000)`) and
 * `RevokeEnrollmentRequest` (`reason` only, `@Size(max = 1000)`) — both
 * mandatory non-blank reasons per ADR-016. UX convenience only — the backend
 * independently and authoritatively re-validates every field.
 */

export const enrollStudentSchema = z.object({
  courseId: z.string().min(1, "Choose a course."),
  reason: z
    .string()
    .min(1, "A reason is required.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
export type EnrollStudentFormValues = z.infer<typeof enrollStudentSchema>;

export const revokeEnrollmentSchema = z.object({
  reason: z
    .string()
    .min(1, "A reason is required.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
export type RevokeEnrollmentFormValues = z.infer<typeof revokeEnrollmentSchema>;
