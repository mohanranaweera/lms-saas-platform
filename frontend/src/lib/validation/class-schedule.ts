import { z } from "zod";
import { WEEKDAYS } from "@/lib/api/class-schedule";
import { validateSchedule } from "@/lib/calendar/schedule-validation";

/**
 * "Edit Class Schedule" form schema (CAL-01). The cross-row rules (overnight,
 * duplicates, same-day overlap, From ≤ To, max rows) live in
 * `validateSchedule` and are mapped onto field paths here. UX only — the
 * server re-validates and its `VALIDATION_ERROR` messages are shown as well.
 * Row-level messages (duplicate/overlap) are attached to `dayOfWeek`.
 */
export const classScheduleFormSchema = z
  .object({
    effectiveFrom: z.string(),
    effectiveTo: z.string(),
    slots: z.array(
      z.object({
        key: z.string(),
        dayOfWeek: z.enum(WEEKDAYS),
        startTime: z.string(),
        endTime: z.string(),
      })
    ),
  })
  .superRefine((values, ctx) => {
    const result = validateSchedule(values.slots, values.effectiveFrom, values.effectiveTo);
    values.slots.forEach((slot, index) => {
      const errors = result.rows[slot.key];
      if (!errors) return;
      if (errors.startTime) ctx.addIssue({ code: "custom", path: ["slots", index, "startTime"], message: errors.startTime });
      if (errors.endTime) ctx.addIssue({ code: "custom", path: ["slots", index, "endTime"], message: errors.endTime });
      if (errors.row) ctx.addIssue({ code: "custom", path: ["slots", index, "dayOfWeek"], message: errors.row });
    });
    if (result.effectiveFrom) ctx.addIssue({ code: "custom", path: ["effectiveFrom"], message: result.effectiveFrom });
    if (result.effectiveTo) ctx.addIssue({ code: "custom", path: ["effectiveTo"], message: result.effectiveTo });
    if (result.form) ctx.addIssue({ code: "custom", path: ["slots"], message: result.form });
  });

export type ClassScheduleFormValues = z.infer<typeof classScheduleFormSchema>;
