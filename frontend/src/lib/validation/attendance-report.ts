import { z } from "zod";
import {
  ATTENDANCE_REPORT_DEFAULT_NAME,
  ATTENDANCE_REPORT_MAX_NAME_LENGTH,
  type AttendanceRunRequest,
} from "@/lib/api/attendance-report";

export const attendanceReportSchema = z
  .object({
    courseId: z.string(),
    startDate: z.string(),
    endDate: z.string(),
    reportName: z.string(),
  })
  .superRefine((values, ctx) => {
    if (!values.courseId) ctx.addIssue({ code: "custom", path: ["courseId"], message: "Select one course." });
    if (!values.startDate) ctx.addIssue({ code: "custom", path: ["startDate"], message: "Choose a start date." });
    if (!values.endDate) ctx.addIssue({ code: "custom", path: ["endDate"], message: "Choose an end date." });
    if (values.startDate && values.endDate && values.startDate > values.endDate) {
      ctx.addIssue({ code: "custom", path: ["endDate"], message: "The end date must be on or after the start date." });
    }
    if (values.reportName.trim().length > ATTENDANCE_REPORT_MAX_NAME_LENGTH) {
      ctx.addIssue({
        code: "custom",
        path: ["reportName"],
        message: `Use at most ${ATTENDANCE_REPORT_MAX_NAME_LENGTH} characters.`,
      });
    }
  });

export type AttendanceReportFormValues = z.infer<typeof attendanceReportSchema>;

export const DEFAULT_ATTENDANCE_REPORT: AttendanceReportFormValues = {
  courseId: "",
  startDate: "",
  endDate: "",
  reportName: ATTENDANCE_REPORT_DEFAULT_NAME,
};

export function toAttendanceRunRequest(values: AttendanceReportFormValues): AttendanceRunRequest {
  return {
    courseId: values.courseId,
    startDate: values.startDate,
    endDate: values.endDate,
    reportName: values.reportName.trim() || ATTENDANCE_REPORT_DEFAULT_NAME,
  };
}

export function attendanceFieldFor(serverField: string): keyof AttendanceReportFormValues | null {
  const known: (keyof AttendanceReportFormValues)[] = ["courseId", "startDate", "endDate", "reportName"];
  return known.includes(serverField as keyof AttendanceReportFormValues)
    ? (serverField as keyof AttendanceReportFormValues)
    : null;
}
