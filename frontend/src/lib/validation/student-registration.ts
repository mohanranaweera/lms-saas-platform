import { z } from "zod";
import type { ProfileFieldDescriptor } from "@/lib/api/student-profile-fields";
import { buildProfileFieldsSchema, emptyFormValues } from "./student-profile-fields";

/**
 * Zod schema builder for the public student self-registration form
 * (`app/(auth)/register/page.tsx`). `name`/`email`/`password` are always
 * required; the `profile` group holds the tenant-configured fields asked at
 * registration (STU-PROF-01 — the policy's `registrationFields`), validated
 * with the same per-type rules as the profile page. UX convenience only —
 * the backend independently and authoritatively re-validates every field,
 * per `.claude/rules/frontend.md`.
 */
export function buildStudentRegistrationSchema(registrationFields: ProfileFieldDescriptor[]) {
  return z
    .object({
      name: z.string().min(1, "Name is required.").max(255, "Name must be 255 characters or fewer."),
      email: z
        .string()
        .min(1, "Email is required.")
        .email("Enter a valid email address.")
        .max(255, "Email must be 255 characters or fewer."),
      password: z
        .string()
        .min(8, "Password must be at least 8 characters.")
        .max(255, "Password must be 255 characters or fewer."),
      confirmPassword: z.string().min(1, "Confirm your password."),
      profile: buildProfileFieldsSchema(registrationFields, { enforceRequired: true }),
    })
    .superRefine((data, ctx) => {
      if (data.password !== data.confirmPassword) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Passwords do not match.",
          path: ["confirmPassword"],
        });
      }
    });
}

export type StudentRegistrationFormValues = z.infer<ReturnType<typeof buildStudentRegistrationSchema>>;

export function studentRegistrationDefaultValues(
  registrationFields: ProfileFieldDescriptor[]
): StudentRegistrationFormValues {
  return {
    name: "",
    email: "",
    password: "",
    confirmPassword: "",
    profile: emptyFormValues(registrationFields),
  };
}

/** OTP code entry step — email-only delivery (Wave 3 judgment call, see wave-03-plan.md §10 item 3). */
export const registrationOtpSchema = z.object({
  otp: z.string().min(1, "Enter the verification code.").max(20, "Enter a valid verification code."),
});

export type RegistrationOtpFormValues = z.infer<typeof registrationOtpSchema>;
