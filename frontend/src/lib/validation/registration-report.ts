import { z } from "zod";
import type { RegistrationRunRequest } from "@/lib/api/registration-report";
export const registrationFilterSchema = z
  .object({
    courseId: z.string(),
    operatorId: z.string(),
    registrationMethod: z.string(),
    startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Choose a start date."),
    endDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Choose an end date."),
  })
  .superRefine((value, ctx) => {
    if (value.startDate > value.endDate)
      ctx.addIssue({
        code: "custom",
        path: ["endDate"],
        message: "End date must be on or after start date.",
      });
  });
export type RegistrationFilterValues = z.infer<typeof registrationFilterSchema>;
export const DEFAULT_REGISTRATION_FILTER: RegistrationFilterValues = {
  courseId: "",
  operatorId: "",
  registrationMethod: "",
  startDate: "",
  endDate: "",
};
export function toRegistrationRunRequest(
  values: RegistrationFilterValues,
): RegistrationRunRequest {
  return {
    courseIds: values.courseId ? [values.courseId] : [],
    operatorId: values.operatorId || undefined,
    registrationMethod: values.registrationMethod || undefined,
    startDate: values.startDate,
    endDate: values.endDate,
  };
}
export function registrationRequestKey(request: RegistrationRunRequest) {
  return JSON.stringify(request);
}
