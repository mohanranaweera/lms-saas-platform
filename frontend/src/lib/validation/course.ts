import { z } from "zod";
import type {
  CourseCreateRequest,
  CoursePricingModel,
  CoursePricingModelChangeRequest,
  CourseResponse,
  CourseStatus,
  CourseUpdateRequest,
} from "@/lib/api/courses";
import type {
  CourseBillingConfigurationRequest,
  CourseBillingPeriodRequest,
} from "@/lib/api/course-billing";

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

const CURRENCY_PATTERN = /^[A-Za-z]{3}$/;
export const CURRENCY_HELPER_TEXT = "A 3-letter currency code (e.g. USD, LKR, INR).";

export const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

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

/**
 * Wave 2's pricing-model selector (`CoursePricingModel`: `FREE`, `ONE_TIME`,
 * `MONTHLY`, `SESSION`, `CUSTOM`). Same "plain string + refine" pattern as
 * `courseVisibilitySchema.status` below (not a native `z.enum`), for the
 * identical reason: keeps `""` assignable as a not-yet-selected default.
 */
export const COURSE_PRICING_MODEL_VALUES: CoursePricingModel[] = [
  "FREE",
  "ONE_TIME",
  "MONTHLY",
  "SESSION",
  "CUSTOM",
];

export const coursePricingModelFieldSchema = z.object({
  pricingModel: z.string().refine(
    (value) => COURSE_PRICING_MODEL_VALUES.includes(value as CoursePricingModel),
    { message: "Select a pricing model." }
  ),
});
export type CoursePricingModelFormValues = z.infer<typeof coursePricingModelFieldSchema>;

/**
 * The Course Builder's combined pricing step: pricing-model selector plus a
 * price input required only when `ONE_TIME` is selected — `price` is
 * meaningless for every other model server-side (`CoursePricingModel`'s
 * javadoc), so `CoursePricingFields`/`CoursePricingModelFields` only render
 * the price input in that case. There is no `pricingModel` field on the
 * backend's `CourseCreateRequest` at all (every course is created `ONE_TIME`
 * and, if a different model was chosen here, the create form follows up with
 * a separate `PATCH .../pricing-model` call after `POST /v1/courses`
 * succeeds — see `course-create-form.tsx`), so this schema's `pricingModel`
 * value is consumed by the form component directly, never by
 * `toCourseCreateRequest` below.
 */
export const coursePricingStepSchema = coursePricingModelFieldSchema.extend({
  price: z.string().optional().or(z.literal("")),
});
export type CoursePricingStepFormValues = z.infer<typeof coursePricingStepSchema>;

function applyPricingModelPriceRefinement(
  values: { pricingModel: string; price?: string },
  ctx: z.RefinementCtx
) {
  if (values.pricingModel !== "ONE_TIME") {
    return;
  }
  if (!values.price) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["price"], message: "Price is required." });
    return;
  }
  if (!PRICE_PATTERN.test(values.price)) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["price"], message: PRICE_HELPER_TEXT });
  }
}

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

// --- Step: staff-only teacher assignment (create only) -----------------------

/**
 * PAR-05-02: the staff-facing create flow (`tenant-admin/courses/new`) adds
 * this one extra required field on top of every other Course Builder step —
 * `CourseCreateRequest.teacherId` is mandatory server-side for a staff
 * caller (`CourseService#createCourse`). Deliberately a plain UUID text
 * input, not a searchable picker, for the identical "no list-teachers
 * endpoint exposed to the frontend" reason `CourseTeacherReassignForm`
 * documents.
 */
export const courseStaffTeacherFieldSchema = z.object({
  teacherId: z
    .string()
    .min(1, "Teacher ID is required.")
    .regex(UUID_PATTERN, "Enter a valid teacher ID (UUID format)."),
});
export type CourseStaffTeacherFormValues = z.infer<typeof courseStaffTeacherFieldSchema>;

// --- Combined schemas --------------------------------------------------------

const courseCreateObjectSchema = courseBasicsSchema
  .extend(courseClassificationSchema.shape)
  .extend(coursePricingStepSchema.shape)
  .extend(courseEnrollmentAccessSchema.shape)
  .extend(courseVisibilitySchema.shape);

export const courseCreateSchema = courseCreateObjectSchema.superRefine(applyPricingModelPriceRefinement);
export type CourseCreateFormValues = z.infer<typeof courseCreateSchema>;

/** Staff variant (`tenant-admin/courses/new`) — adds the required `teacherId` field. */
export const courseStaffCreateSchema = courseCreateObjectSchema
  .extend(courseStaffTeacherFieldSchema.shape)
  .superRefine(applyPricingModelPriceRefinement);
export type CourseStaffCreateFormValues = z.infer<typeof courseStaffCreateSchema>;

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
  // Defaults to ONE_TIME, matching the backend's own column default for
  // every newly created course (`CoursePricingModel`'s javadoc) — still an
  // explicit, changeable choice on the Pricing step, never hidden.
  pricingModel: "ONE_TIME",
  price: "",
  enrollmentRule: "",
  accessDurationDays: "",
  // Deliberately no default selection — the builder requires an explicit
  // choice, never a silent default to PUBLIC (or any other status).
  status: "",
};

export const COURSE_STAFF_CREATE_DEFAULT_VALUES: CourseStaffCreateFormValues = {
  ...COURSE_CREATE_DEFAULT_VALUES,
  teacherId: "",
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

/**
 * `CourseCreateRequest.price` has no `pricingModel` counterpart on the
 * backend (see `coursePricingStepSchema`'s doc comment) and is `@NotNull`
 * regardless of which pricing model the caller intends — for every model
 * other than `ONE_TIME`, `price` is meaningless, so this sends `0` rather
 * than the (hidden, possibly stale) form value. The caller is responsible
 * for following up with `PATCH .../pricing-model` after creation when
 * `values.pricingModel !== "ONE_TIME"` — see `course-create-form.tsx`.
 */
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
    price: values.pricingModel === "ONE_TIME" ? Number(values.price) : 0,
    accessDurationDays: values.accessDurationDays ? Number(values.accessDurationDays) : undefined,
    enrollmentRule: trimmedOrUndefined(values.enrollmentRule),
    status: values.status as CourseStatus,
  };
}

/** Staff variant of {@link toCourseCreateRequest} — adds `teacherId`. */
export function toCourseStaffCreateRequest(values: CourseStaffCreateFormValues): CourseCreateRequest {
  return {
    ...toCourseCreateRequest(values),
    teacherId: values.teacherId.trim(),
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

/**
 * Builds a full `CourseUpdateRequest` for the Access tab's narrow form: the
 * two Access fields come from the submitted (possibly changed) form values,
 * every other `CourseUpdateRequest` field is carried over verbatim from the
 * already-loaded `course` — `PATCH /api/v1/courses/{id}` has no partial-patch
 * semantics (`name`/`slug`/`category` are `@NotBlank` server-side), so this
 * tab cannot submit accessDurationDays/enrollmentRule alone.
 */
export function courseAndAccessValuesToUpdateRequest(
  course: CourseResponse,
  values: CourseAccessFormValues
): CourseUpdateRequest {
  return {
    name: course.name,
    slug: course.slug,
    category: course.category,
    subject: course.subject ?? undefined,
    stream: course.stream ?? undefined,
    grade: course.grade ?? undefined,
    academicYear: course.academicYear ?? undefined,
    description: course.description ?? undefined,
    enrollmentRule: trimmedOrUndefined(values.enrollmentRule),
    accessDurationDays: values.accessDurationDays ? Number(values.accessDurationDays) : undefined,
  };
}

export function courseToAccessFormValues(course: CourseResponse): CourseAccessFormValues {
  return {
    enrollmentRule: course.enrollmentRule ?? "",
    accessDurationDays: course.accessDurationDays != null ? String(course.accessDurationDays) : "",
  };
}

// --- Dedicated-action forms --------------------------------------------------

export const priceChangeSchema = coursePricingSchema;
export type PriceChangeFormValues = z.infer<typeof priceChangeSchema>;

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

/** Standalone pricing-model change form (Fees/Billing tab, `PATCH .../pricing-model`). */
export const pricingModelChangeSchema = coursePricingModelFieldSchema;
export type PricingModelChangeFormValues = CoursePricingModelFormValues;

export function toPricingModelChangeRequest(
  values: PricingModelChangeFormValues
): CoursePricingModelChangeRequest {
  return { pricingModel: values.pricingModel as CoursePricingModel };
}

// --- Billing configuration / billing period (Fees & Billing tab) -------------

/**
 * Mirrors `CourseBillingConfigurationRequest`'s constraints. `sessionRate` is
 * optional at the schema level (only meaningful for `SESSION` — the backend
 * rejects it outright for any other pricing model, see
 * `BillingConfigurationService#validateAgainstPricingModel`); the form only
 * renders/sends it when the course's pricing model is `SESSION`.
 */
export const courseBillingConfigurationSchema = z.object({
  sessionRate: z.string().optional().or(z.literal("")).refine(
    (value) => !value || PRICE_PATTERN.test(value),
    { message: PRICE_HELPER_TEXT }
  ),
  currency: z
    .string()
    .min(1, "Currency is required.")
    .regex(CURRENCY_PATTERN, CURRENCY_HELPER_TEXT),
  requiresManualQuote: z.boolean(),
});
export type CourseBillingConfigurationFormValues = z.infer<typeof courseBillingConfigurationSchema>;

export function toCourseBillingConfigurationRequest(
  values: CourseBillingConfigurationFormValues
): CourseBillingConfigurationRequest {
  return {
    sessionRate: values.sessionRate ? Number(values.sessionRate) : undefined,
    currency: values.currency.trim().toUpperCase(),
    requiresManualQuote: values.requiresManualQuote,
  };
}

/**
 * Mirrors `CourseBillingPeriodRequest`. `effectiveFrom` uses a native
 * `datetime-local` input's own value format (`YYYY-MM-DDTHH:mm`) — converted
 * to a full ISO instant only at submission time
 * (`toCourseBillingPeriodRequest`), matching `DateInput`'s established
 * "bare form value, convert at submit" convention.
 */
export const courseBillingPeriodSchema = z.object({
  amount: z.string().min(1, "Amount is required.").regex(PRICE_PATTERN, PRICE_HELPER_TEXT),
  currency: z
    .string()
    .min(1, "Currency is required.")
    .regex(CURRENCY_PATTERN, CURRENCY_HELPER_TEXT),
  effectiveFrom: z.string().optional().or(z.literal("")),
});
export type CourseBillingPeriodFormValues = z.infer<typeof courseBillingPeriodSchema>;

export function toCourseBillingPeriodRequest(
  values: CourseBillingPeriodFormValues
): CourseBillingPeriodRequest {
  return {
    amount: Number(values.amount),
    currency: values.currency.trim().toUpperCase(),
    effectiveFrom: values.effectiveFrom ? new Date(values.effectiveFrom).toISOString() : undefined,
  };
}

// --- Access tab (accessDurationDays / enrollmentRule only) -------------------

/**
 * The Access tab's dedicated form re-shares `courseEnrollmentAccessSchema`
 * (same two fields, same constraints) rather than duplicating it — see
 * `course-access-form.tsx`, which merges the submitted values back into a
 * full `CourseUpdateRequest` using the rest of the already-loaded course's
 * own values (`CourseUpdateRequest.name`/`slug`/`category` are `@NotBlank`
 * server-side, so this tab cannot submit a partial patch).
 */
export const courseAccessSchema = courseEnrollmentAccessSchema;
export type CourseAccessFormValues = CourseEnrollmentAccessFormValues;
