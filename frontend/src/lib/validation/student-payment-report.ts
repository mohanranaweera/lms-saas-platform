import { z } from "zod";
import type { ReportPaymentMethod } from "@/lib/api/reports";
import { STUDENT_PAYMENT_LIMITS, type StudentPaymentRunRequest } from "@/lib/api/student-payment-report";
import { ALL_METHODS } from "./reports";

/**
 * RPT-02 Student Payment filter (spec §2). Mirrors the server's
 * `StudentPaymentRequestValidator` limits for fast feedback only - the server
 * re-validates every field and its 422 field errors are always rendered.
 * Courses are optional (empty = all courses). DATES = one received date, or
 * a range when the optional "to" date is set. Only the active mode is sent.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

export const studentPaymentFilterSchema = z
  .object({
    courseIds: z.array(z.string()),
    paymentMethod: z.string(),
    periodMode: z.enum(["MONTHS", "DATES"]),
    months: z.array(z.string()),
    startDate: z.string(),
    endDate: z.string(),
  })
  .superRefine((values, ctx) => {
    if (values.courseIds.length > STUDENT_PAYMENT_LIMITS.maxCourses) {
      ctx.addIssue({
        code: "custom",
        path: ["courseIds"],
        message: `Select at most ${STUDENT_PAYMENT_LIMITS.maxCourses} courses.`,
      });
    }
    if (values.periodMode === "MONTHS") {
      if (values.months.length === 0) {
        ctx.addIssue({ code: "custom", path: ["months"], message: "Select at least one month." });
      } else if (values.months.length > STUDENT_PAYMENT_LIMITS.maxMonths) {
        ctx.addIssue({
          code: "custom",
          path: ["months"],
          message: `Select at most ${STUDENT_PAYMENT_LIMITS.maxMonths} months.`,
        });
      }
      return;
    }
    if (!values.startDate) {
      ctx.addIssue({ code: "custom", path: ["startDate"], message: "Choose a date." });
      return;
    }
    if (values.endDate) {
      const start = Date.parse(`${values.startDate}T00:00:00Z`);
      const end = Date.parse(`${values.endDate}T00:00:00Z`);
      if (start > end) {
        ctx.addIssue({ code: "custom", path: ["endDate"], message: "The end date must be on or after the date." });
      } else if (Math.round((end - start) / DAY_MS) + 1 > STUDENT_PAYMENT_LIMITS.maxSpanDays) {
        ctx.addIssue({
          code: "custom",
          path: ["endDate"],
          message: `The period must not exceed ${STUDENT_PAYMENT_LIMITS.maxSpanDays} days.`,
        });
      }
    }
  });

export type StudentPaymentFilterValues = z.infer<typeof studentPaymentFilterSchema>;

export const DEFAULT_STUDENT_PAYMENT_FILTER: StudentPaymentFilterValues = {
  courseIds: [],
  paymentMethod: ALL_METHODS,
  periodMode: "MONTHS",
  months: [],
  startDate: "",
  endDate: "",
};

/**
 * Builds the `POST /runs` body: only the active mode's fields, no "all"
 * method, no course list when "all courses", never a tenant id. A single date
 * is sent as `startDate == endDate`.
 */
export function toStudentPaymentRunRequest(values: StudentPaymentFilterValues): StudentPaymentRunRequest {
  const request: StudentPaymentRunRequest = { periodMode: values.periodMode };
  if (values.courseIds.length > 0) {
    request.courseIds = [...values.courseIds];
  }
  if (values.paymentMethod && values.paymentMethod !== ALL_METHODS) {
    request.paymentMethod = values.paymentMethod as ReportPaymentMethod;
  }
  if (values.periodMode === "MONTHS") {
    request.months = [...values.months].sort();
  } else {
    request.startDate = values.startDate;
    request.endDate = values.endDate || values.startDate;
  }
  return request;
}

/** Stable comparison key for "has the filter changed since the last run". */
export function studentPaymentRequestKey(request: StudentPaymentRunRequest): string {
  return JSON.stringify({
    ...request,
    courseIds: request.courseIds ? [...request.courseIds].sort() : undefined,
    months: request.months ? [...request.months].sort() : undefined,
  });
}

/** Maps a server field key (`courseIds[0]`, `months[3]`) to the form field it belongs to. */
export function studentPaymentFieldFor(serverField: string): keyof StudentPaymentFilterValues | null {
  const base = serverField.replace(/\[\d+\]$/, "");
  const known: (keyof StudentPaymentFilterValues)[] = [
    "courseIds",
    "paymentMethod",
    "periodMode",
    "months",
    "startDate",
    "endDate",
  ];
  return (known as string[]).includes(base) ? (base as keyof StudentPaymentFilterValues) : null;
}
