import { z } from "zod";

/**
 * Zod schemas for the Staff Management dialogs (STAFF-MGMT-02). They mirror
 * `StaffCreateRequest` / `StaffUpdateRequest` (`@NotBlank`, `@Email`,
 * `@Size`, the shared phone `@Pattern`); the backend remains authoritative.
 * `roleCode` is only required here - the selectable set comes from
 * `GET /v1/staff/assignable-roles`, and the server rejects anything else.
 */

/** `StaffUpdateRequest.PHONE_PATTERN` minus the empty alternative. */
const PHONE = /^\+?[0-9][0-9 ()-]{5,30}$/;

const firstName = z
  .string()
  .trim()
  .min(1, "First name is required.")
  .max(120, "First name must be 120 characters or fewer.");

export const staffCreateSchema = z.object({
  firstName,
  lastName: z
    .string()
    .trim()
    .min(1, "Last name is required.")
    .max(120, "Last name must be 120 characters or fewer."),
  email: z
    .string()
    .trim()
    .min(1, "Email is required.")
    .email("Enter a valid email address.")
    .max(255, "Email must be 255 characters or fewer."),
  mobileNumber: z
    .string()
    .trim()
    .min(1, "Mobile number is required.")
    .max(32, "Mobile number must be 32 characters or fewer.")
    .regex(PHONE, "Enter a valid phone number."),
  roleCode: z.string().min(1, "Select a role."),
});

export type StaffCreateFormValues = z.infer<typeof staffCreateSchema>;

export const staffUpdateSchema = z.object({
  firstName,
  lastName: z.string().trim().max(120, "Last name must be 120 characters or fewer."),
  contactNumber: z
    .string()
    .trim()
    .max(32, "Contact number must be 32 characters or fewer.")
    .refine((value) => value === "" || PHONE.test(value), "Enter a valid phone number."),
});

export type StaffUpdateFormValues = z.infer<typeof staffUpdateSchema>;

export const staffRoleSchema = z.object({
  roleCode: z.string().min(1, "Select a role."),
});

export type StaffRoleFormValues = z.infer<typeof staffRoleSchema>;
