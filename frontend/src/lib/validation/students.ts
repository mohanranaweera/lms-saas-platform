import { z } from "zod";

/**
 * Zod schemas for Student Management forms (MVP-006). Mirror the backend's
 * actual, narrow request DTOs field-for-field:
 * `StudentCreateRequest` (`name`, `email`, `password` — `@NotBlank`,
 * `email` `@Email`, `password` `@Size(min = 8, max = 255)`, both `name`/
 * `email` `@Size(max = 255)`) and `StudentUpdateRequest` (`name` only —
 * `email`/`password`/`status` are not editable through either
 * `PATCH /v1/students/{id}` or `PATCH /v1/students/me`).
 *
 * Client-side validation is a UX convenience only — the backend independently
 * and authoritatively re-validates every field, per `.claude/rules/frontend.md`.
 */

export const studentCreateSchema = z.object({
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

export type StudentCreateFormValues = z.infer<typeof studentCreateSchema>;

export const studentUpdateSchema = z.object({
  name: z
    .string()
    .min(1, "Name is required.")
    .max(255, "Name must be 255 characters or fewer."),
});

export type StudentUpdateFormValues = z.infer<typeof studentUpdateSchema>;
