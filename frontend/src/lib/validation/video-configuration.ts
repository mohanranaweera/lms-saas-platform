import { z } from "zod";
import type { TenantVideoSettings, WatermarkField } from "@/lib/api/video-providers";

/**
 * VIDEO Phase 3 - tenant video settings form. Mirrors the backend
 * `TenantVideoConfigurationService` rules and the V71 CHECK ranges. UX
 * convenience only - the backend re-validates every field.
 */

export const WATERMARK_FIELDS: { value: WatermarkField; label: string }[] = [
  { value: "STUDENT_DISPLAY_ID", label: "Student ID" },
  { value: "STUDENT_FULL_NAME", label: "Student full name" },
  { value: "MASKED_EMAIL", label: "Masked email" },
  { value: "MASKED_PHONE", label: "Masked phone number" },
  { value: "SESSION_ID", label: "Session code" },
  { value: "TIMESTAMP", label: "Date and time" },
];

function wholeInRange(min: number, max: number, label: string) {
  return z
    .string()
    .trim()
    .min(1, `${label} is required.`)
    .refine((value) => /^\d+$/.test(value), `${label} must be a whole number.`)
    .refine((value) => Number(value) >= min && Number(value) <= max, `${label} must be between ${min} and ${max}.`);
}

function decimalInRange(min: number, max: number, label: string) {
  return z
    .string()
    .trim()
    .min(1, `${label} is required.`)
    .refine((value) => /^\d+(\.\d{1,2})?$/.test(value), `${label} must be a number with up to 2 decimals.`)
    .refine((value) => Number(value) >= min && Number(value) <= max, `${label} must be between ${min} and ${max}.`);
}

export const videoConfigurationSchema = z
  .object({
    defaultProvider: z.enum(["INTERNAL", "YOUTUBE"]),
    defaultPrivacyStatus: z.enum(["UNLISTED", "PRIVATE"]),
    uploadsEnabled: z.boolean(),
    nativePlayerRequired: z.boolean(),
    watermarkEnabled: z.boolean(),
    watermarkOpacity: decimalInRange(0.05, 0.6, "Watermark opacity"),
    watermarkMovementIntervalSeconds: wholeInRange(5, 120, "Movement interval"),
    watermarkFields: z
      .array(
        z.enum(["STUDENT_DISPLAY_ID", "STUDENT_FULL_NAME", "MASKED_EMAIL", "MASKED_PHONE", "SESSION_ID", "TIMESTAMP"])
      )
      .min(1, "Choose at least one thing to show in the watermark."),
    captureProtectionEnabled: z.boolean(),
    captureProtectionRequired: z.boolean(),
    maxConcurrentSessions: wholeInRange(1, 5, "Simultaneous playback"),
    watchTimePolicy: z.enum(["NONE", "VIDEO_DURATION_MULTIPLIER", "FIXED_MINUTES"]),
    watchTimeMultiplier: z.string().trim(),
    watchTimeFixedMinutes: z.string().trim(),
    launchCodeTtlSeconds: wholeInRange(15, 60, "Player link lifetime"),
    heartbeatIntervalSeconds: wholeInRange(10, 60, "Check-in interval"),
  })
  .superRefine((values, ctx) => {
    if (values.captureProtectionRequired && !values.captureProtectionEnabled) {
      ctx.addIssue({
        code: "custom",
        path: ["captureProtectionRequired"],
        message: "Turn on screen-capture protection first.",
      });
    }
    if (values.watchTimePolicy === "VIDEO_DURATION_MULTIPLIER") {
      const result = decimalInRange(1, 10, "Multiplier").safeParse(values.watchTimeMultiplier);
      if (!result.success) {
        ctx.addIssue({ code: "custom", path: ["watchTimeMultiplier"], message: result.error.issues[0].message });
      }
    }
    if (values.watchTimePolicy === "FIXED_MINUTES") {
      const result = wholeInRange(1, 10000, "Minutes").safeParse(values.watchTimeFixedMinutes);
      if (!result.success) {
        ctx.addIssue({ code: "custom", path: ["watchTimeFixedMinutes"], message: result.error.issues[0].message });
      }
    }
  });

export type VideoConfigurationFormValues = z.infer<typeof videoConfigurationSchema>;

export function toVideoConfigurationFormValues(settings: TenantVideoSettings): VideoConfigurationFormValues {
  return {
    defaultProvider: settings.defaultProvider,
    defaultPrivacyStatus: settings.defaultPrivacyStatus,
    uploadsEnabled: settings.uploadsEnabled,
    nativePlayerRequired: settings.nativePlayerRequired,
    watermarkEnabled: settings.watermarkEnabled,
    watermarkOpacity: String(settings.watermarkOpacity),
    watermarkMovementIntervalSeconds: String(settings.watermarkMovementIntervalSeconds),
    watermarkFields: [...settings.watermarkFields],
    captureProtectionEnabled: settings.captureProtectionEnabled,
    captureProtectionRequired: settings.captureProtectionRequired,
    maxConcurrentSessions: String(settings.maxConcurrentSessions),
    watchTimePolicy: settings.watchTimePolicy,
    watchTimeMultiplier: settings.watchTimeMultiplier === null ? "" : String(settings.watchTimeMultiplier),
    watchTimeFixedMinutes: settings.watchTimeFixedMinutes === null ? "" : String(settings.watchTimeFixedMinutes),
    launchCodeTtlSeconds: String(settings.launchCodeTtlSeconds),
    heartbeatIntervalSeconds: String(settings.heartbeatIntervalSeconds),
  };
}

/** Only the watch-time value belonging to the chosen policy is sent. */
export function toTenantVideoSettings(values: VideoConfigurationFormValues): TenantVideoSettings {
  return {
    defaultProvider: values.defaultProvider,
    defaultPrivacyStatus: values.defaultPrivacyStatus,
    uploadsEnabled: values.uploadsEnabled,
    nativePlayerRequired: values.nativePlayerRequired,
    watermarkEnabled: values.watermarkEnabled,
    watermarkOpacity: Number(values.watermarkOpacity),
    watermarkMovementIntervalSeconds: Number(values.watermarkMovementIntervalSeconds),
    watermarkFields: values.watermarkFields,
    captureProtectionEnabled: values.captureProtectionEnabled,
    captureProtectionRequired: values.captureProtectionEnabled && values.captureProtectionRequired,
    maxConcurrentSessions: Number(values.maxConcurrentSessions),
    watchTimePolicy: values.watchTimePolicy,
    watchTimeMultiplier:
      values.watchTimePolicy === "VIDEO_DURATION_MULTIPLIER" ? Number(values.watchTimeMultiplier) : null,
    watchTimeFixedMinutes: values.watchTimePolicy === "FIXED_MINUTES" ? Number(values.watchTimeFixedMinutes) : null,
    launchCodeTtlSeconds: Number(values.launchCodeTtlSeconds),
    heartbeatIntervalSeconds: Number(values.heartbeatIntervalSeconds),
  };
}
