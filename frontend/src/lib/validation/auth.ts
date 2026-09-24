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

export const forgotPasswordSchema = z.object({
  email: z.string().min(1, "Email is required.").email("Enter a valid email address."),
});
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
