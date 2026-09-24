import { z } from "zod";
import type { StudentRegistrationPolicy } from "@/lib/api/student-registration";

/**
 * Zod schema builder for the public student self-registration form
 * (`app/(auth)/register/page.tsx`). Mirrors `StudentRegistrationRequest`
 * (backend `usermanagement.student.web.dto.StudentRegistrationRequest`)
 * field-for-field: `name`/`email`/`password` are always required; every
 * other field's required-ness is driven by the tenant's prefetched
 * `StudentRegistrationPolicy` (`useStudentRegistrationPolicy`,
 * `lib/api/student-registration.ts`) rather than a hardcoded guess. UX
 * convenience only — the backend independently and authoritatively
 * re-validates every field regardless of what this schema allows through,
 * per `.claude/rules/frontend.md`.
 */

type RequiredFieldsPolicy = Pick<
  StudentRegistrationPolicy,
  "requireGuardianInfo" | "requireSchool" | "requireGrade" | "requireStream" | "requireMobile"
>;

/**
 * Every optional field is typed identically (`z.string().max(...).optional()`)
 * regardless of the tenant's policy — only the runtime *behavior* (which
 * fields actually reject an empty value) varies, via the `superRefine`
 * below. This keeps `StudentRegistrationFormValues` a single stable type
 * across every policy permutation, which `zodResolver`'s generic signature
 * requires (a resolver's input/output types can't vary per call).
 */
function optionalStringField(maxLength: number) {
  return z.string().max(maxLength, `Must be ${maxLength} characters or fewer.`).optional();
}

export function buildStudentRegistrationSchema(policy: RequiredFieldsPolicy) {
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
      guardianName: optionalStringField(255),
      guardianPhone: optionalStringField(50),
      school: optionalStringField(255),
      grade: optionalStringField(50),
      stream: optionalStringField(50),
      mobile: optionalStringField(50),
    })
    .superRefine((data, ctx) => {
      if (data.password !== data.confirmPassword) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Passwords do not match.",
          path: ["confirmPassword"],
        });
      }

      const requiredFields: Array<[boolean, "guardianName" | "guardianPhone" | "school" | "grade" | "stream" | "mobile", string]> = [
        [policy.requireGuardianInfo, "guardianName", "Guardian name is required."],
        [policy.requireGuardianInfo, "guardianPhone", "Guardian phone is required."],
        [policy.requireSchool, "school", "School is required."],
        [policy.requireGrade, "grade", "Grade is required."],
        [policy.requireStream, "stream", "Stream is required."],
        [policy.requireMobile, "mobile", "Mobile number is required."],
      ];
      for (const [required, field, message] of requiredFields) {
        if (required && !data[field]?.trim()) {
          ctx.addIssue({ code: z.ZodIssueCode.custom, message, path: [field] });
        }
      }
    });
}

export type StudentRegistrationFormValues = z.infer<ReturnType<typeof buildStudentRegistrationSchema>>;

export const STUDENT_REGISTRATION_DEFAULT_VALUES: StudentRegistrationFormValues = {
  name: "",
  email: "",
  password: "",
  confirmPassword: "",
  guardianName: "",
  guardianPhone: "",
  school: "",
  grade: "",
  stream: "",
  mobile: "",
};

/** OTP code entry step — email-only delivery (Wave 3 judgment call, see wave-03-plan.md §10 item 3). */
export const registrationOtpSchema = z.object({
  otp: z.string().min(1, "Enter the verification code.").max(20, "Enter a valid verification code."),
});

export type RegistrationOtpFormValues = z.infer<typeof registrationOtpSchema>;
