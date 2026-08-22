import { z } from "zod";
import type {
  CourseCreateRequest,
  CourseResponse,
  CourseStatus,
  CourseUpdateRequest,
} from "@/lib/api/courses";

/**
 * Zod schemas for the Course Builder (Teacher `new`/`edit`) and the small
 * dedicated-action forms (price change, teacher reassignment, module/lesson
 * title). Mirrors the backend's Bean Validation constraints on
 * `CourseCreateRequest`/`CourseUpdateRequest`/`CoursePriceChangeRequest`/
 * `CourseTeacherReassignRequest`/`CourseModuleRequest`/`CourseLessonRequest`
 * field-for-field — this is UX convenience only, the backend independently
 * and authoritatively re-validates every field regardless (per
 * `.claude/rules/frontend.md`).
 *
 * `price` and `accessDurationDays` are modeled as plain form-level strings
 * (not numbers) so a controlled text `Input` always has a defined value and
 * no precision is lost while typing; `toCourse*Request` below converts to the
 * `number` shape the API client expects only at submission time.
 */

const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
export const SLUG_HELPER_TEXT =
  "Lowercase letters, numbers, and single hyphens only (e.g. \"intro-to-biology\").";

const PRICE_PATTERN = /^\d{1,10}(\.\d{1,2})?$/;
export const PRICE_HELPER_TEXT = "Up to 10 digits, with up to 2 decimal places (e.g. 1999.99).";

const POSITIVE_INT_PATTERN = /^[1-9]\d*$/;
export const ACCESS_DURATION_HELPER_TEXT =
  "A positive whole number of days, or leave blank for lifetime access.";

// --- Step 1: Basics --------------------------------------------------------

export const courseBasicsSchema = z.object({
  name: z
    .string()
    .min(1, "Course name is required.")
    .max(255, "Course name must be 255 characters or fewer."),
  slug: z
    .string()
    .min(1, "Slug is required.")
    .max(160, "Slug must be 160 characters or fewer.")
    .regex(SLUG_PATTERN, SLUG_HELPER_TEXT),
  category: z
    .string()
    .min(1, "Category is required.")
    .max(100, "Category must be 100 characters or fewer."),
  description: z
    .string()
    .max(5000, "Description must be 5000 characters or fewer.")
    .optional()
    .or(z.literal("")),
});
export type CourseBasicsFormValues = z.infer<typeof courseBasicsSchema>;

// --- Step 2: Classification -------------------------------------------------

export const courseClassificationSchema = z.object({
  subject: z.string().max(100, "Subject must be 100 characters or fewer.").optional().or(z.literal("")),
  stream: z.string().max(100, "Stream must be 100 characters or fewer.").optional().or(z.literal("")),
  grade: z.string().max(50, "Grade must be 50 characters or fewer.").optional().or(z.literal("")),
  academicYear: z
    .string()
    .max(20, "Academic year must be 20 characters or fewer.")
    .optional()
    .or(z.literal("")),
});
export type CourseClassificationFormValues = z.infer<typeof courseClassificationSchema>;

// --- Step 3: Pricing (create only) -----------------------------------------

export const coursePricingSchema = z.object({
  price: z.string().min(1, "Price is required.").regex(PRICE_PATTERN, PRICE_HELPER_TEXT),
});
export type CoursePricingFormValues = z.infer<typeof coursePricingSchema>;

// --- Step 4: Enrollment & access ---------------------------------------------

export const courseEnrollmentAccessSchema = z.object({
  enrollmentRule: z
    .string()
    .max(1000, "Enrollment rules must be 1000 characters or fewer.")
    .optional()
    .or(z.literal("")),
  accessDurationDays: z
    .string()
    .regex(POSITIVE_INT_PATTERN, ACCESS_DURATION_HELPER_TEXT)
    .optional()
    .or(z.literal("")),
});
export type CourseEnrollmentAccessFormValues = z.infer<typeof courseEnrollmentAccessSchema>;

// --- Step 5: Visibility (create only) ---------------------------------------

const COURSE_STATUS_VALUES: CourseStatus[] = ["DRAFT", "PRIVATE", "PUBLIC"];

export const courseVisibilitySchema = z.object({
  // Deliberately not narrowed to a `CourseStatus`-typed refine predicate —
  // that would make the inferred form-values type reject `""` as a default,
  // which is required here (no default selection allowed). `toCourseCreateRequest`
  // casts to `CourseStatus` after this validates the value is one of the three.
  status: z.string().refine((value) => COURSE_STATUS_VALUES.includes(value as CourseStatus), {
    message: "Select a visibility status.",
  }),
});
export type CourseVisibilityFormValues = z.infer<typeof courseVisibilitySchema>;

// --- Combined schemas --------------------------------------------------------

export const courseCreateSchema = courseBasicsSchema
  .extend(courseClassificationSchema.shape)
  .extend(coursePricingSchema.shape)
  .extend(courseEnrollmentAccessSchema.shape)
  .extend(courseVisibilitySchema.shape);
export type CourseCreateFormValues = z.infer<typeof courseCreateSchema>;

export const courseEditSchema = courseBasicsSchema
  .extend(courseClassificationSchema.shape)
  .extend(courseEnrollmentAccessSchema.shape);
export type CourseEditFormValues = z.infer<typeof courseEditSchema>;

export const COURSE_CREATE_DEFAULT_VALUES: CourseCreateFormValues = {
  name: "",
  slug: "",
  category: "",
  description: "",
  subject: "",
  stream: "",
  grade: "",
  academicYear: "",
  price: "",
  enrollmentRule: "",
  accessDurationDays: "",
  // Deliberately no default selection — the builder requires an explicit
  // choice, never a silent default to PUBLIC (or any other status).
  status: "",
};

export function courseToEditFormValues(course: CourseResponse): CourseEditFormValues {
  return {
    name: course.name,
    slug: course.slug,
    category: course.category,
    description: course.description ?? "",
    subject: course.subject ?? "",
    stream: course.stream ?? "",
    grade: course.grade ?? "",
    academicYear: course.academicYear ?? "",
    enrollmentRule: course.enrollmentRule ?? "",
    accessDurationDays: course.accessDurationDays != null ? String(course.accessDurationDays) : "",
  };
}

function trimmedOrUndefined(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

export function toCourseCreateRequest(values: CourseCreateFormValues): CourseCreateRequest {
  return {
    name: values.name.trim(),
    slug: values.slug.trim(),
    category: values.category.trim(),
    subject: trimmedOrUndefined(values.subject),
    stream: trimmedOrUndefined(values.stream),
    grade: trimmedOrUndefined(values.grade),
    academicYear: trimmedOrUndefined(values.academicYear),
    description: trimmedOrUndefined(values.description),
    price: Number(values.price),
    accessDurationDays: values.accessDurationDays ? Number(values.accessDurationDays) : undefined,
    enrollmentRule: trimmedOrUndefined(values.enrollmentRule),
    status: values.status as CourseStatus,
  };
}

export function toCourseUpdateRequest(values: CourseEditFormValues): CourseUpdateRequest {
  return {
    name: values.name.trim(),
    slug: values.slug.trim(),
    category: values.category.trim(),
    subject: trimmedOrUndefined(values.subject),
    stream: trimmedOrUndefined(values.stream),
    grade: trimmedOrUndefined(values.grade),
    academicYear: trimmedOrUndefined(values.academicYear),
    description: trimmedOrUndefined(values.description),
    enrollmentRule: trimmedOrUndefined(values.enrollmentRule),
    accessDurationDays: values.accessDurationDays ? Number(values.accessDurationDays) : undefined,
  };
}

// --- Dedicated-action forms --------------------------------------------------

export const priceChangeSchema = coursePricingSchema;
export type PriceChangeFormValues = z.infer<typeof priceChangeSchema>;

const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export const teacherReassignSchema = z.object({
  teacherId: z
    .string()
    .min(1, "Teacher ID is required.")
    .regex(UUID_PATTERN, "Enter a valid teacher ID (UUID format)."),
});
export type TeacherReassignFormValues = z.infer<typeof teacherReassignSchema>;

export const titleOnlySchema = z.object({
  title: z
    .string()
    .min(1, "Title is required.")
    .max(255, "Title must be 255 characters or fewer."),
});
export type TitleOnlyFormValues = z.infer<typeof titleOnlySchema>;
