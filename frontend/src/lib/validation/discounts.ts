import { z } from "zod";
import type { DiscountRequest, DiscountRule } from "@/lib/api/discounts";

/**
 * DISC-01 discount form schema. Mirrors `DiscountAdminService.validate` per
 * discount type - UX convenience only; the backend re-validates
 * authoritatively (422 `DISCOUNT_VALIDATION`) and its field errors are mapped
 * back onto these same field names.
 */

const MONEY = /^\d{1,10}(\.\d{1,2})?$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const INTEGER = /^\d{1,9}$/;
/** Same shape the server accepts after trimming/upper-casing (`isWellFormedCode`). */
const CODE = /^[A-Za-z0-9_-]{6,40}$/;
export const MAX_USES = 1_000_000;

export const discountSchema = z
  .object({
    name: z.string().trim().min(1, "Discount name is required.").max(120, "Discount name must be at most 120 characters."),
    description: z.string().max(1000, "Description must be at most 1000 characters."),
    discountType: z.enum(["ADVANCE_PAYMENT", "BUNDLE_AMOUNT_OFF", "BUNDLE_FREE_COURSE", "DISCOUNT_CODE"], {
      message: "Choose a discount type.",
    }),
    courseIds: z.array(z.string()).min(1, "Select at least one required course."),
    selectionMode: z.enum(["ALL", "ANY", "MIN_COUNT"]),
    minCount: z.string(),
    valueType: z.enum(["FIXED", "PERCENTAGE"]),
    value: z.string(),
    rewardCourseIds: z.array(z.string()),
    freeMonths: z.string(),
    code: z.string(),
    validFrom: z.string().regex(ISO_DATE, "Valid from is required."),
    validTo: z.string().regex(ISO_DATE, "Valid to is required."),
    maxUses: z.string(),
    minPurchaseAmount: z.string(),
    active: z.boolean(),
  })
  .superRefine((values, ctx) => {
    const issue = (path: string, message: string) => ctx.addIssue({ code: "custom", path: [path], message });

    if (values.selectionMode === "MIN_COUNT") {
      const count = Number(values.minCount);
      if (!INTEGER.test(values.minCount.trim())) {
        issue("minCount", "Minimum count is required.");
      } else if (count < 1 || count > values.courseIds.length) {
        issue("minCount", "Minimum count must be between 1 and the number of required courses.");
      }
    }

    if (values.discountType !== "BUNDLE_FREE_COURSE") {
      const value = values.value.trim();
      if (!value) {
        issue("value", "Discount value is required.");
      } else if (!MONEY.test(value) || Number(value) <= 0) {
        issue("value", "Discount value must be more than zero with at most 2 decimals.");
      } else if (values.valueType === "PERCENTAGE" && Number(value) > 100) {
        issue("value", "A percentage cannot be more than 100.");
      }
    } else {
      if (values.rewardCourseIds.length === 0) {
        issue("rewardCourseIds", "Select at least one free course.");
      } else if (values.rewardCourseIds.some((id) => values.courseIds.includes(id))) {
        issue("rewardCourseIds", "A free course cannot also be a required course.");
      }
      const months = Number(values.freeMonths);
      if (!INTEGER.test(values.freeMonths.trim()) || months < 1 || months > 24) {
        issue("freeMonths", "Free for (months) must be between 1 and 24.");
      }
    }

    if (values.discountType === "DISCOUNT_CODE") {
      const code = values.code.trim();
      if (!code) issue("code", "Discount code is required.");
      else if (!CODE.test(code)) issue("code", "Use 6-40 letters, digits, '-' or '_'.");
    }

    if (ISO_DATE.test(values.validFrom) && ISO_DATE.test(values.validTo) && values.validTo < values.validFrom) {
      issue("validTo", "Valid to must be on or after valid from.");
    }

    const maxUses = values.maxUses.trim();
    if (maxUses && (!INTEGER.test(maxUses) || Number(maxUses) < 1 || Number(maxUses) > MAX_USES)) {
      issue("maxUses", `Maximum uses must be between 1 and ${MAX_USES.toLocaleString("en-US")}.`);
    }
    const minPurchase = values.minPurchaseAmount.trim();
    if (minPurchase && !MONEY.test(minPurchase)) {
      issue("minPurchaseAmount", "Minimum purchase amount must be zero or more with at most 2 decimals.");
    }
  });

export type DiscountFormValues = z.infer<typeof discountSchema>;

export const DISCOUNT_FORM_FIELDS = [
  "name",
  "description",
  "discountType",
  "courseIds",
  "selectionMode",
  "minCount",
  "valueType",
  "value",
  "rewardCourseIds",
  "freeMonths",
  "code",
  "validFrom",
  "validTo",
  "maxUses",
  "minPurchaseAmount",
  "active",
] as const satisfies readonly (keyof DiscountFormValues)[];

export function emptyDiscountForm(): DiscountFormValues {
  return {
    name: "",
    description: "",
    discountType: "BUNDLE_AMOUNT_OFF",
    courseIds: [],
    selectionMode: "ALL",
    minCount: "2",
    valueType: "FIXED",
    value: "",
    rewardCourseIds: [],
    freeMonths: "1",
    code: "",
    validFrom: "",
    validTo: "",
    maxUses: "",
    minPurchaseAmount: "",
    active: true,
  };
}

function text(value: number | string | null | undefined): string {
  return value === null || value === undefined ? "" : String(value);
}

export function discountFormFromRule(rule: DiscountRule): DiscountFormValues {
  return {
    name: rule.name,
    description: rule.description ?? "",
    discountType: rule.discountType,
    courseIds: rule.courseIds,
    selectionMode: rule.selectionMode,
    minCount: text(rule.minCount ?? 2),
    valueType: rule.valueType ?? "FIXED",
    value: rule.value === null ? "" : Number(rule.value).toFixed(2),
    rewardCourseIds: rule.rewardCourseIds,
    freeMonths: text(rule.freeMonths ?? 1),
    // A masked code ("******") is never shown as an editable value.
    code: rule.code && !/^\*+$/.test(rule.code) ? rule.code : "",
    validFrom: rule.validFrom,
    validTo: rule.validTo,
    maxUses: text(rule.maxUses),
    minPurchaseAmount: rule.minPurchaseAmount === null ? "" : Number(rule.minPurchaseAmount).toFixed(2),
    active: rule.active,
  };
}

/** Builds the request body, sending null for every field the chosen type doesn't use. */
export function toDiscountRequest(values: DiscountFormValues): DiscountRequest {
  const type = values.discountType;
  const freeCourse = type === "BUNDLE_FREE_COURSE";
  return {
    name: values.name.trim(),
    description: values.description.trim() || null,
    discountType: type,
    courseIds: values.courseIds,
    selectionMode: values.selectionMode,
    minCount: values.selectionMode === "MIN_COUNT" ? Number(values.minCount) : null,
    paymentType: type === "ADVANCE_PAYMENT" ? "INSTALLMENTS" : null,
    valueType: freeCourse ? null : values.valueType,
    value: freeCourse ? null : values.value.trim(),
    rewardCourseIds: freeCourse ? values.rewardCourseIds : [],
    freeMonths: freeCourse ? Number(values.freeMonths) : null,
    code: type === "DISCOUNT_CODE" ? values.code.trim().toUpperCase() : null,
    validFrom: values.validFrom,
    validTo: values.validTo,
    maxUses: values.maxUses.trim() ? Number(values.maxUses) : null,
    minPurchaseAmount: values.minPurchaseAmount.trim() || null,
    active: values.active,
  };
}
