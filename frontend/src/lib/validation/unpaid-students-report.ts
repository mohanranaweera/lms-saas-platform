import { z } from "zod";
import type { UnpaidStudentsRunRequest } from "@/lib/api/unpaid-students-report";

export const unpaidStudentsFilterSchema = z.object({
  courseId: z.string().min(1, "Select a course."),
  billingMonth: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/, "Choose one billing month."),
  atLeastOneAttended: z.boolean(),
});

export type UnpaidStudentsFilterValues = z.infer<typeof unpaidStudentsFilterSchema>;

export const DEFAULT_UNPAID_STUDENTS_FILTER: UnpaidStudentsFilterValues = {
  courseId: "",
  billingMonth: "",
  atLeastOneAttended: false,
};

export function toUnpaidStudentsRunRequest(values: UnpaidStudentsFilterValues): UnpaidStudentsRunRequest {
  return {
    courseId: values.courseId,
    billingMonth: values.billingMonth,
    atLeastOneAttended: values.atLeastOneAttended,
  };
}

export function unpaidStudentsRequestKey(request: UnpaidStudentsRunRequest): string {
  return JSON.stringify(request);
}

export function unpaidStudentsFieldFor(field: string): keyof UnpaidStudentsFilterValues | null {
  if (field === "courseId" || field === "billingMonth" || field === "atLeastOneAttended") {
    return field;
  }
  return null;
}
