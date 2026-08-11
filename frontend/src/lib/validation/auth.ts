import { z } from "zod";

/**
 * Zod schemas backing the `(auth)` route group's forms. `loginSchema` is wired
 * to a real, submitting form (`components/auth/login-form.tsx`, POSTing to
 * identity-access-service); `registerSchema`/`forgotPasswordSchema` remain
 * client-side UX structure only for their still-disabled placeholder forms —
 * no backend endpoint exists for either yet. In every case, this is a UX
 * convenience only; the backend's own validation is authoritative.
 */

export const loginSchema = z.object({
  email: z.string().min(1, "Email is required.").email("Enter a valid email address."),
  password: z.string().min(1, "Password is required."),
});
export type LoginFormValues = z.infer<typeof loginSchema>;

export const registerSchema = z
  .object({
    fullName: z.string().min(1, "Full name is required."),
    email: z.string().min(1, "Email is required.").email("Enter a valid email address."),
    password: z.string().min(8, "Password must be at least 8 characters."),
    confirmPassword: z.string().min(1, "Confirm your password."),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: "Passwords do not match.",
    path: ["confirmPassword"],
  });
export type RegisterFormValues = z.infer<typeof registerSchema>;

export const forgotPasswordSchema = z.object({
  email: z.string().min(1, "Email is required.").email("Enter a valid email address."),
});
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
