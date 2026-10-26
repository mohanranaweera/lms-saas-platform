import { z } from "zod";
import type { StudentBehaviorRunRequest } from "@/lib/api/student-behavior-report";
export const studentBehaviorSchema = z.object({ courseId: z.string().min(1, "Select a course."), billingMonth: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/, "Select a month.") });
export type StudentBehaviorFilterValues = z.infer<typeof studentBehaviorSchema>;
export const DEFAULT_STUDENT_BEHAVIOR_FILTER: StudentBehaviorFilterValues = { courseId: "", billingMonth: "" };
export const toStudentBehaviorRequest = (values: StudentBehaviorFilterValues): StudentBehaviorRunRequest => ({ ...values });
export const studentBehaviorRequestKey = (request: StudentBehaviorRunRequest) => JSON.stringify(request);
