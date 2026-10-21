import { z } from "zod";

/**
 * Wave 10 - device and access-policy forms. Mirrors the backend request DTOs
 * (`DeviceResetRequest`, `DeviceLimitRequest`, `DevicePolicyRequest`,
 * `TenantAccessPolicyRequest`, `CourseAccessPolicyRequest`,
 * `AccessExtensionRequest`) and the V64/V65 CHECK bounds. UX convenience only -
 * the backend re-validates every field.
 */

const reason = z
  .string()
  .trim()
  .min(1, "A reason is required.")
  .max(500, "Reason must be 500 characters or fewer.");

/** An optional whole number from an `<input type="number">` ("" = not set). */
function optionalWhole(min: number, max: number, label: string) {
  return z
    .string()
    .trim()
    .refine((value) => value === "" || /^\d+$/.test(value), `${label} must be a whole number.`)
    .refine(
      (value) => value === "" || (Number(value) >= min && Number(value) <= max),
      `${label} must be between ${min} and ${max}.`
    );
}

function requiredWhole(min: number, max: number, label: string) {
  return z
    .string()
    .trim()
    .min(1, `${label} is required.`)
    .refine((value) => /^\d+$/.test(value), `${label} must be a whole number.`)
    .refine((value) => Number(value) >= min && Number(value) <= max, `${label} must be between ${min} and ${max}.`);
}

export const deviceResetSchema = z.object({ reason });
export type DeviceResetFormValues = z.infer<typeof deviceResetSchema>;

export const deviceLimitSchema = z.object({
  maxDevices: optionalWhole(1, 20, "Device limit"),
  reason,
});
export type DeviceLimitFormValues = z.infer<typeof deviceLimitSchema>;

export const devicePolicySchema = z.object({
  maxDevices: optionalWhole(1, 20, "Device limit"),
  resetCooldownHours: requiredWhole(0, 720, "Reset cooldown"),
  studentSelfServiceEnabled: z.boolean(),
});
export type DevicePolicyFormValues = z.infer<typeof devicePolicySchema>;

export const gracePeriodSchema = z.object({
  gracePeriodDays: requiredWhole(0, 90, "Grace period"),
});
export type GracePeriodFormValues = z.infer<typeof gracePeriodSchema>;

export const courseAccessPolicySchema = z.object({
  courseId: z.string().min(1, "Choose a course."),
  gracePeriodDays: optionalWhole(0, 90, "Grace period"),
  maxDevices: optionalWhole(1, 20, "Device limit"),
});
export type CourseAccessPolicyFormValues = z.infer<typeof courseAccessPolicySchema>;

export const extendAccessSchema = z.object({
  /** `YYYY-MM-DD` from `<input type="date">`; access runs until the end of that day (local time). */
  newExpiryDate: z
    .string()
    .min(1, "Choose the new end date.")
    .refine((value) => !Number.isNaN(Date.parse(value)), "Enter a valid date."),
  reason,
});
export type ExtendAccessFormValues = z.infer<typeof extendAccessSchema>;

/** "" -> null, "3" -> 3. */
export function toOptionalNumber(value: string): number | null {
  return value.trim() === "" ? null : Number(value.trim());
}

/** End of the chosen local calendar day, as an ISO instant. */
export function endOfLocalDayIso(date: string): string {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(year, month - 1, day, 23, 59, 59).toISOString();
}
