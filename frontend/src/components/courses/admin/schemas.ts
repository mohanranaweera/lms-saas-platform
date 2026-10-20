import { z } from "zod";
import { CREATABLE_PRICING_MODELS, LESSON_PAYMENT_TYPES, pricingModelTakesPrice } from "@/lib/api/klass-admin";
import { zonedDateTimeToIso } from "@/components/courses/admin/tenant-time";

/**
 * Zod schemas for the Klass admin forms. Client validation is a UX aid only;
 * the backend re-validates everything and its field errors are mapped back
 * onto these fields.
 */

export const MONEY_PATTERN = /^\d+(\.\d{1,2})?$/;

/** The one "missing required value" message used by every Klass admin form. */
export const REQUIRED_MESSAGE = "This field is required.";

function isHttpsUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && url.hostname.length > 0;
  } catch {
    return false;
  }
}

const optionalHttps = z
  .string()
  .refine((value) => value.trim() === "" || isHttpsUrl(value.trim()), { message: "Enter a full https:// link" });

// ---------------------------------------------------------------------------
// Add New Course
// ---------------------------------------------------------------------------

const courseSettingsObject = z.object({
  name: z
    .string()
    .refine((value) => value.trim().length > 0, { message: REQUIRED_MESSAGE })
    .refine((value) => value.trim().length <= 255, { message: "Name must be 255 characters or fewer" }),
  teacherIds: z.array(z.string()).min(1, { message: REQUIRED_MESSAGE }),
  pricingModel: z
    .string()
    .refine((value) => (CREATABLE_PRICING_MODELS as string[]).includes(value), {
      message: REQUIRED_MESSAGE,
    }),
  price: z.string(),
  enrollmentFee: z
    .string()
    .refine((value) => value.trim() === "" || MONEY_PATTERN.test(value.trim()), {
      message: "Enter an amount of 0 or more with up to 2 decimals",
    }),
  paymentValidDays: z
    .string()
    .refine((value) => value.trim() === "" || /^\d+$/.test(value.trim()), {
      message: "Enter a whole number of days (0 or more)",
    }),
  commissionRate: z
    .string()
    .refine(
      (value) =>
        value.trim() === "" || (MONEY_PATTERN.test(value.trim()) && Number(value.trim()) <= 100),
      { message: "Enter a percentage from 0 to 100" }
    ),
  whatsappInviteUrl: optionalHttps,
  telegramInviteUrl: optionalHttps,
  description: z.string(),
  tagNames: z.array(z.string()),
  linkedCourseIds: z.array(z.string()),
});

export const courseCreateSchema = courseSettingsObject.superRefine((values, ctx) => {
  if (!pricingModelTakesPrice(values.pricingModel)) return;
  const price = values.price.trim();
  if (!price) {
    ctx.addIssue({ code: "custom", path: ["price"], message: REQUIRED_MESSAGE });
  } else if (!MONEY_PATTERN.test(price) || Number(price) <= 0) {
    ctx.addIssue({
      code: "custom",
      path: ["price"],
      message: "Enter an amount greater than 0 with up to 2 decimals",
    });
  }
});

export type CourseCreateFormValues = z.infer<typeof courseCreateSchema>;

export const COURSE_CREATE_DEFAULTS: CourseCreateFormValues = {
  name: "",
  teacherIds: [],
  pricingModel: "",
  price: "",
  enrollmentFee: "",
  paymentValidDays: "",
  commissionRate: "",
  whatsappInviteUrl: "",
  telegramInviteUrl: "",
  description: "",
  tagNames: [],
  linkedCourseIds: [],
};

// ---------------------------------------------------------------------------
// Course edit (G3)
// ---------------------------------------------------------------------------

/**
 * Edit form schema, built per loaded course. Same fields as Add New Course,
 * except: a legacy payment type (SESSION/CUSTOM) is accepted as-is (it is
 * shown read-only and never sent), the price is only checked when it or the
 * payment type changed (an untouched legacy price must not block other
 * edits), and a commission rate can't be blanked once set (the API treats a
 * missing rate as "unchanged", so blank would silently do nothing).
 */
export function makeCourseEditSchema(original: { pricingModel: string; price: string; commissionRate: string }) {
  return courseSettingsObject
    .extend({
      pricingModel: z.string().refine((value) => value.trim().length > 0, { message: REQUIRED_MESSAGE }),
    })
    .superRefine((values, ctx) => {
      const modelChanged = values.pricingModel !== original.pricingModel;
      if (!(CREATABLE_PRICING_MODELS as string[]).includes(values.pricingModel) && modelChanged) {
        ctx.addIssue({ code: "custom", path: ["pricingModel"], message: REQUIRED_MESSAGE });
      }
      if (pricingModelTakesPrice(values.pricingModel) && (modelChanged || values.price.trim() !== original.price)) {
        const price = values.price.trim();
        if (!price) {
          ctx.addIssue({ code: "custom", path: ["price"], message: REQUIRED_MESSAGE });
        } else if (!MONEY_PATTERN.test(price) || Number(price) <= 0) {
          ctx.addIssue({
            code: "custom",
            path: ["price"],
            message: "Enter an amount greater than 0 with up to 2 decimals",
          });
        }
      }
      if (original.commissionRate !== "" && values.commissionRate.trim() === "") {
        ctx.addIssue({
          code: "custom",
          path: ["commissionRate"],
          message: "Enter a percentage from 0 to 100 (use 0 for no commission)",
        });
      }
    });
}

// ---------------------------------------------------------------------------
// Course facility fee + postage (V90)
// ---------------------------------------------------------------------------

/** A required amount: 0 or more, up to 2 decimals. "0" is a valid value, never "empty". */
const requiredNonNegativeMoney = z
  .string()
  .refine((value) => value.trim().length > 0, { message: REQUIRED_MESSAGE })
  .refine((value) => value.trim().length === 0 || MONEY_PATTERN.test(value.trim()), {
    message: "Enter an amount of 0 or more with up to 2 decimals",
  });

export const courseChargesSchema = z
  .object({
    facilityFeeInherit: z.boolean(),
    facilityFeeOverride: z.string(),
    postageFee: requiredNonNegativeMoney,
  })
  .superRefine((values, ctx) => {
    if (values.facilityFeeInherit) return;
    const amount = values.facilityFeeOverride.trim();
    if (!amount) {
      ctx.addIssue({ code: "custom", path: ["facilityFeeOverride"], message: REQUIRED_MESSAGE });
    } else if (!MONEY_PATTERN.test(amount)) {
      ctx.addIssue({
        code: "custom",
        path: ["facilityFeeOverride"],
        message: "Enter an amount of 0 or more with up to 2 decimals",
      });
    }
  });

export type CourseChargesFormValues = z.infer<typeof courseChargesSchema>;

// ---------------------------------------------------------------------------
// Add New Module
// ---------------------------------------------------------------------------

export const moduleFormSchema = z.object({
  name: z.string().refine((value) => value.trim().length > 0, { message: REQUIRED_MESSAGE }),
  price: z
    .string()
    .refine(
      (value) => value.trim() === "" || (MONEY_PATTERN.test(value.trim()) && Number(value.trim()) > 0),
      { message: "Enter an amount greater than 0, or leave it blank" }
    ),
});

export type ModuleFormValues = z.infer<typeof moduleFormSchema>;

// ---------------------------------------------------------------------------
// Add New Session
// ---------------------------------------------------------------------------

export const END_OF_DAY = "23:59";

const required = z.string().refine((value) => value.trim().length > 0, { message: REQUIRED_MESSAGE });

/** Built per tenant timezone, since "end after start" compares instants in that zone. */
export function makeSessionFormSchema(timeZone: string, legacy: { allowEmptyStart?: boolean; allowEmptyType?: boolean } = {}) {
  return z
    .object({
      name: required,
      fromDate: legacy.allowEmptyStart ? z.string() : required,
      fromTime: legacy.allowEmptyStart ? z.string() : required,
      toDate: z.string(),
      toTime: z.string(),
      resourceType: (legacy.allowEmptyType ? z.string() : required).refine((value) => value !== "ZOOM_RECORDING", { message: "Zoom recording import is not available yet. Choose another type." }),
      paymentType: z
        .string()
        .refine((value) => (LESSON_PAYMENT_TYPES as string[]).includes(value), { message: REQUIRED_MESSAGE }),
      price: z.string(),
      lessonLabel: z.string(),
      description: z.string(),
    })
    .superRefine((values, ctx) => {
      if (values.paymentType === "PAID") {
        const price = values.price.trim();
        if (!price) {
          ctx.addIssue({ code: "custom", path: ["price"], message: REQUIRED_MESSAGE });
        } else if (!MONEY_PATTERN.test(price) || Number(price) <= 0) {
          ctx.addIssue({ code: "custom", path: ["price"], message: "Enter an amount greater than 0" });
        }
      }
      if ((values.fromDate && !values.fromTime) || (!values.fromDate && values.fromTime)) {
        ctx.addIssue({ code: "custom", path: [values.fromDate ? "fromTime" : "fromDate"], message: "Enter both the start date and time." });
        return;
      }
      if (values.toTime && !values.toDate) {
        ctx.addIssue({ code: "custom", path: ["toDate"], message: "Pick an end date for this end time." });
        return;
      }
      if (values.toDate && (!values.fromDate || !values.fromTime) && !legacy.allowEmptyStart) {
        ctx.addIssue({ code: "custom", path: ["fromDate"], message: "Enter a start before setting an end." });
        return;
      }
      if (!values.toDate || !values.fromDate || !values.fromTime) return;
      const start = zonedDateTimeToIso(values.fromDate, values.fromTime, timeZone);
      const end = zonedDateTimeToIso(values.toDate, values.toTime || END_OF_DAY, timeZone);
      if (start && end && new Date(end).getTime() <= new Date(start).getTime()) {
        ctx.addIssue({ code: "custom", path: ["toDate"], message: "The end must be after the start." });
      }
    });
}

export type SessionFormValues = z.infer<ReturnType<typeof makeSessionFormSchema>>;
