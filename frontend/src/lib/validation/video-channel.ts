import { z } from "zod";

/**
 * VIDEO Phase 5d - the YouTube integration (card) form. Mirrors
 * `TenantMediaIntegrationService` (name required ≤ 100, description ≤ 500,
 * no `<`/`>`, only the institute's teachers). UX convenience only - the backend
 * re-validates and its field errors are shown on the form.
 */

const NO_ANGLE_BRACKETS = /^[^<>]*$/;

export const videoIntegrationSchema = z
  .object({
    name: z
      .string()
      .trim()
      .min(1, "Enter a name.")
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

export type VideoIntegrationFormValues = z.infer<typeof videoIntegrationSchema>;
