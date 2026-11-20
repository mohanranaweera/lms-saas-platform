import { z } from "zod";
import { COURSE_SUMMARY_DEFAULT_NAME, COURSE_SUMMARY_MAX_NAME_LENGTH, type CourseSummaryRunRequest } from "@/lib/api/course-summary-report";

export const courseSummarySchema = z.object({
  teacherId: z.string(), courseId: z.string(), startDate: z.string(), endDate: z.string(), month: z.string(), reportName: z.string(),
}).superRefine((value, ctx) => {
  if (!value.teacherId) ctx.addIssue({ code: "custom", path: ["teacherId"], message: "Select a teacher." });
  if (!value.courseId) ctx.addIssue({ code: "custom", path: ["courseId"], message: "Select a course." });
  if (!value.startDate) ctx.addIssue({ code: "custom", path: ["startDate"], message: "Choose a start date." });
  if (!value.endDate) ctx.addIssue({ code: "custom", path: ["endDate"], message: "Choose an end date." });
  if (!value.month) ctx.addIssue({ code: "custom", path: ["month"], message: "Choose a month." });
  if (value.startDate && value.endDate && value.startDate > value.endDate) ctx.addIssue({ code: "custom", path: ["endDate"], message: "End date must be on or after start date." });
  if (value.month && value.startDate && value.startDate.slice(0, 7) !== value.month) ctx.addIssue({ code: "custom", path: ["month"], message: "Month must match the date range." });
  if (value.month && value.endDate && value.endDate.slice(0, 7) !== value.month) ctx.addIssue({ code: "custom", path: ["month"], message: "Month must match the date range." });
  if (value.reportName.trim().length > COURSE_SUMMARY_MAX_NAME_LENGTH) ctx.addIssue({ code: "custom", path: ["reportName"], message: `Use at most ${COURSE_SUMMARY_MAX_NAME_LENGTH} characters.` });
});

export type CourseSummaryFormValues = z.infer<typeof courseSummarySchema>;
export const DEFAULT_COURSE_SUMMARY: CourseSummaryFormValues = { teacherId: "", courseId: "", startDate: "", endDate: "", month: "", reportName: COURSE_SUMMARY_DEFAULT_NAME };
export function toCourseSummaryRunRequest(value: CourseSummaryFormValues): CourseSummaryRunRequest { return { ...value, reportName: value.reportName.trim() || COURSE_SUMMARY_DEFAULT_NAME }; }
export function courseSummaryFieldFor(field: string): keyof CourseSummaryFormValues | null { const fields: Array<keyof CourseSummaryFormValues> = ["teacherId", "courseId", "startDate", "endDate", "month", "reportName"]; return fields.includes(field as keyof CourseSummaryFormValues) ? field as keyof CourseSummaryFormValues : null; }
