import { z } from "zod";

/**
 * Zod schema for the Teacher "Schedule Live Class" / "Edit Live Class" forms
 * (Wave 4, PAR-19-03). UX convenience only, per `.claude/rules/frontend.md` —
 * `ClassSessionSchedulingService`/`ClassSessionService` independently and
 * authoritatively re-validate the window (`scheduledEnd` after
 * `scheduledStart`) and every other server-only rule (course ownership,
 * lesson-belongs-to-course cross-check) server-side regardless of what this
 * schema accepts.
 */
export const classSessionFormSchema = z
  .object({
    courseId: z.string().min(1, "Course is required."),
    /** Empty string means "no lesson selected" — converted to `undefined` at submit time, never sent as `""`. */
    lessonId: z.string(),
    title: z
      .string()
      .trim()
      .min(1, "Title is required.")
      .max(255, "Title must be 255 characters or fewer."),
    description: z.string().trim().max(5000, "Description must be 5000 characters or fewer."),
    /** `datetime-local` input value, e.g. `2026-09-06T14:30`. */
    scheduledStart: z.string().min(1, "Start date/time is required."),
    scheduledEnd: z.string().min(1, "End date/time is required."),
  })
  .refine(
    (values) => {
      const start = new Date(values.scheduledStart);
      return !Number.isNaN(start.getTime()) && start.getTime() > Date.now() - 60_000;
    },
    { message: "Start date/time must not be in the past.", path: ["scheduledStart"] }
  )
  .refine(
    (values) => {
      const start = new Date(values.scheduledStart);
      const end = new Date(values.scheduledEnd);
      return !Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()) && end > start;
    },
    { message: "End time must be after start time.", path: ["scheduledEnd"] }
  );

export type ClassSessionFormValues = z.infer<typeof classSessionFormSchema>;

export const CLASS_SESSION_FORM_DEFAULT_VALUES: ClassSessionFormValues = {
  courseId: "",
  lessonId: "",
  title: "",
  description: "",
  scheduledStart: "",
  scheduledEnd: "",
};
