import { z } from "zod";
import {
  REPORT_LIMITS,
  type ReportPaymentMethod,
  type TeacherIncomeRunRequest,
} from "@/lib/api/reports";

/**
 * RPT-01 Teacher Income filter (spec §3/§4). Mirrors the server's
 * `TeacherIncomeRequestValidator` limits for fast feedback only - the server
 * re-validates every field and its 422 field errors are always rendered.
 * Only the active period mode's fields are validated (and sent).
 */

export const ALL_METHODS = "ALL";
const DAY_MS = 24 * 60 * 60 * 1000;

export const teacherIncomeFilterSchema = z
  .object({
    teacherIds: z.array(z.string()),
    paymentMethod: z.string(),
    periodMode: z.enum(["MONTHS", "DATES"]),
    months: z.array(z.string()),
    startDate: z.string(),
    endDate: z.string(),
  })
  .superRefine((values, ctx) => {
    if (values.teacherIds.length === 0) {
      ctx.addIssue({ code: "custom", path: ["teacherIds"], message: "Select at least one teacher." });
    } else if (values.teacherIds.length > REPORT_LIMITS.maxTeachers) {
      ctx.addIssue({
        code: "custom",
        path: ["teacherIds"],
        message: `Select at most ${REPORT_LIMITS.maxTeachers} teachers.`,
      });
    }
    if (values.periodMode === "MONTHS") {
      if (values.months.length === 0) {
        ctx.addIssue({ code: "custom", path: ["months"], message: "Select at least one month." });
      } else if (values.months.length > REPORT_LIMITS.maxMonths) {
        ctx.addIssue({
          code: "custom",
          path: ["months"],
          message: `Select at most ${REPORT_LIMITS.maxMonths} months.`,
        });
      }
      return;
    }
    if (!values.startDate) {
      ctx.addIssue({ code: "custom", path: ["startDate"], message: "Choose a start date." });
    }
    if (!values.endDate) {
      ctx.addIssue({ code: "custom", path: ["endDate"], message: "Choose an end date." });
    }
    if (values.startDate && values.endDate) {
      const start = Date.parse(`${values.startDate}T00:00:00Z`);
      const end = Date.parse(`${values.endDate}T00:00:00Z`);
      if (start > end) {
        ctx.addIssue({ code: "custom", path: ["startDate"], message: "Start date must be on or before the end date." });
      } else if (Math.round((end - start) / DAY_MS) + 1 > REPORT_LIMITS.maxSpanDays) {
        ctx.addIssue({
          code: "custom",
          path: ["endDate"],
          message: `The period must not exceed ${REPORT_LIMITS.maxSpanDays} days.`,
        });
      }
    }
  });

export type TeacherIncomeFilterValues = z.infer<typeof teacherIncomeFilterSchema>;

export const DEFAULT_TEACHER_INCOME_FILTER: TeacherIncomeFilterValues = {
  teacherIds: [],
  paymentMethod: ALL_METHODS,
  periodMode: "MONTHS",
  months: [],
  startDate: "",
  endDate: "",
};

/** Builds the `POST /runs` body: only the active mode's fields, no "all" method, never a tenant id. */
export function toTeacherIncomeRunRequest(values: TeacherIncomeFilterValues): TeacherIncomeRunRequest {
  const request: TeacherIncomeRunRequest = {
    teacherIds: [...values.teacherIds],
    periodMode: values.periodMode,
  };
  if (values.paymentMethod && values.paymentMethod !== ALL_METHODS) {
    request.paymentMethod = values.paymentMethod as ReportPaymentMethod;
  }
  if (values.periodMode === "MONTHS") {
    request.months = [...values.months].sort();
  } else {
    request.startDate = values.startDate;
    request.endDate = values.endDate;
  }
  return request;
}

/** Stable comparison key for "has the filter changed since the last run". */
export function runRequestKey(request: TeacherIncomeRunRequest): string {
  return JSON.stringify({
    ...request,
    teacherIds: [...request.teacherIds].sort(),
    months: request.months ? [...request.months].sort() : undefined,
  });
}

/** Maps a server field key (`teacherIds[0]`, `months[3]`) to the form field it belongs to. */
export function filterFieldFor(serverField: string): keyof TeacherIncomeFilterValues | null {
  const base = serverField.replace(/\[\d+\]$/, "");
  const known: (keyof TeacherIncomeFilterValues)[] = [
    "teacherIds",
    "paymentMethod",
    "periodMode",
    "months",
    "startDate",
    "endDate",
  ];
  return (known as string[]).includes(base) ? (base as keyof TeacherIncomeFilterValues) : null;
}

export const MONTH_SHORT_NAMES = [
  "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
] as const;

export const MONTH_LONG_NAMES = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
] as const;

export function monthKey(year: number, monthIndex: number): string {
  return `${year}-${String(monthIndex + 1).padStart(2, "0")}`;
}

/** "2024-12" -> "Dec 2024". */
export function monthKeyLabel(key: string): string {
  const [year, month] = key.split("-");
  return `${MONTH_SHORT_NAMES[Number(month) - 1] ?? month} ${year}`;
}
