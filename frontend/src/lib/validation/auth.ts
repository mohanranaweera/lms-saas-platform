import { z } from "zod";

/**
 * Zod schemas backing the `(auth)` route group's forms. `loginSchema` is wired
 * to a real, submitting form (`components/auth/login-form.tsx`, POSTing to
 * identity-access-service); `forgotPasswordSchema` remains client-side UX
 * structure only for its still-disabled placeholder form — no backend
 * endpoint exists for it yet. In every case, this is a UX convenience only;
 * the backend's own validation is authoritative.
 *
 * The real student self-registration form's schema
 * (`app/(auth)/register/page.tsx`, Wave 3) lives in
 * `lib/validation/student-registration.ts` instead — it isn't a small
 * `fullName`/`email`/`password` shape anymore (guardian/school/grade/stream/
 * mobile fields, tenant-config-driven required-ness), so it earned its own
 * file rather than growing this one.
 */

export const loginSchema = z.object({
  email: z.string().min(1, "Email is required.").email("Enter a valid email address."),
  password: z.string().min(1, "Password is required."),
});
export type LoginFormValues = z.infer<typeof loginSchema>;

/**
 * ADR-031 mobile sign-in. Only a light shape check here (digits, spaces,
 * dashes, brackets, a leading +); the backend normalises the number with the
 * institute's country and answers a wrong number exactly like a wrong password.
 */
export const mobileLoginSchema = z.object({
  mobile: z
    .string()
    .trim()
    .min(1, "Mobile number is required.")
    .max(40, "Enter a valid mobile number.")
    .regex(/^\+?[\d\s\-()]{6,}$/, "Enter a valid mobile number."),
  password: z.string().min(1, "Password is required."),
});
export type MobileLoginFormValues = z.infer<typeof mobileLoginSchema>;

/**
 * The tenant login form's fields for the selected tab: only that tab's
 * identifier is validated; the other one is ignored (never sent).
 */
export function tenantLoginSchema(method: "email" | "mobile") {
  return z.object({
    email: method === "email" ? loginSchema.shape.email : z.string(),
    mobile: method === "mobile" ? mobileLoginSchema.shape.mobile : z.string(),
    password: loginSchema.shape.password,
  });
}

export const forgotPasswordSchema = z.object({
  email: z.string().min(1, "Email is required.").email("Enter a valid email address."),
});
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;

/**
 * AUTH-PWD-01 — a password chosen by its owner. Mirrors the backend's
 * PasswordPolicy length rule (8-128) for fast feedback; the backend stays
 * authoritative (it also rejects the email and the current password).
 */
const newPasswordField = z
  .string()
  .min(8, "Use at least 8 characters.")
  .max(128, "Use at most 128 characters.");

export const setPasswordSchema = z
  .object({
    newPassword: newPasswordField,
    confirmPassword: z.string().min(1, "Confirm your new password."),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    path: ["confirmPassword"],
    message: "The passwords don't match.",
  });

export type SetPasswordFormValues = z.infer<typeof setPasswordSchema>;

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, "Enter your current password."),
    newPassword: newPasswordField,
    confirmPassword: z.string().min(1, "Confirm your new password."),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    path: ["confirmPassword"],
    message: "The passwords don't match.",
  });

export type ChangePasswordFormValues = z.infer<typeof changePasswordSchema>;
