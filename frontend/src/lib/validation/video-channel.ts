import { z } from "zod";

/**
 * VIDEO Phase 5b - the institute channel's settings form. Mirrors
 * `TenantVideoChannelService` (name ≤ 100, description ≤ 500, no `<`/`>`,
 * only the institute's teachers). UX convenience only - the backend
 * re-validates and its field errors are shown on the form.
 */

const NO_ANGLE_BRACKETS = /^[^<>]*$/;

export const channelSettingsSchema = z
  .object({
    displayName: z
      .string()
      .trim()
      .max(100, "Use at most 100 characters.")
      .regex(NO_ANGLE_BRACKETS, "The name can't contain < or >."),
    description: z
      .string()
      .trim()
      .max(500, "Use at most 500 characters.")
      .regex(NO_ANGLE_BRACKETS, "The description can't contain < or >."),
    uploaders: z.enum(["ALL", "SELECTED"]),
    teacherIds: z.array(z.string()),
  })
  .refine((values) => values.uploaders === "ALL" || values.teacherIds.length > 0, {
    path: ["teacherIds"],
    message: "Choose at least one teacher, or allow all teachers.",
  });

export type ChannelSettingsFormValues = z.infer<typeof channelSettingsSchema>;
