import { z } from "zod";
import type {
  FulfillmentMethod,
  FulfillmentStatus,
  TutePaymentType,
  TuteRequest,
  TuteView,
} from "@/lib/api/tutes";

/**
 * TUTE-01 create/edit form rules (spec §3 "Field rules"). Client validation
 * mirrors the server for fast feedback only; the backend re-validates and its
 * field errors are mapped back onto these fields.
 */

export const TUTE_PAYMENT_TYPE_LABELS: Record<TutePaymentType, string> = {
  COVERED_BY_CLASS_FEES: "Covered by class fees",
  PAID: "Paid",
  FREE: "Free",
};

export const TUTE_PAYMENT_TYPES: TutePaymentType[] = ["COVERED_BY_CLASS_FEES", "PAID", "FREE"];

export const NAME_MAX = 200;
export const QUANTITY_MAX = 1_000_000;
export const PRICE_MAX = 999_999.99;
export const DESCRIPTION_MAX = 2000;
export const REASON_MAX = 500;

export const PAID_SESSION_HELPER = "Paid sessions apply only to Paid tutes";

export interface TuteFormValues {
  name: string;
  quantity: string;
  paidLessonId: string;
  paymentType: "" | TutePaymentType;
  price: string;
  description: string;
}

export const EMPTY_TUTE_FORM: TuteFormValues = {
  name: "",
  quantity: "",
  paidLessonId: "",
  paymentType: "",
  price: "",
  description: "",
};

export const tuteFormSchema = z
  .object({
    name: z.string(),
    quantity: z.string(),
    paidLessonId: z.string(),
    paymentType: z.enum(["", "COVERED_BY_CLASS_FEES", "PAID", "FREE"]),
    price: z.string(),
    description: z.string(),
  })
  .superRefine((values, ctx) => {
    const name = values.name.trim();
    if (!name) ctx.addIssue({ code: "custom", path: ["name"], message: "Tute name is required." });
    else if (name.length > NAME_MAX)
      ctx.addIssue({ code: "custom", path: ["name"], message: `Tute name must be ${NAME_MAX} characters or fewer.` });

    const quantity = values.quantity.trim();
    if (!quantity) ctx.addIssue({ code: "custom", path: ["quantity"], message: "Quantity is required." });
    else if (!/^\d+$/.test(quantity))
      ctx.addIssue({ code: "custom", path: ["quantity"], message: "Quantity must be a whole number (0 or more)." });
    else if (Number(quantity) > QUANTITY_MAX)
      ctx.addIssue({ code: "custom", path: ["quantity"], message: "Quantity can't be more than 1,000,000." });

    if (!(TUTE_PAYMENT_TYPES as string[]).includes(values.paymentType))
      ctx.addIssue({ code: "custom", path: ["paymentType"], message: "Select a payment type." });

    if (values.paymentType === "PAID") {
      const price = values.price.trim();
      if (!price) ctx.addIssue({ code: "custom", path: ["price"], message: "Price is required for Paid tutes." });
      else if (!/^\d+(\.\d+)?$/.test(price))
        ctx.addIssue({ code: "custom", path: ["price"], message: "Enter a valid amount." });
      else if (!/^\d+(\.\d{1,2})?$/.test(price))
        ctx.addIssue({ code: "custom", path: ["price"], message: "Price can have at most 2 decimal places." });
      else if (Number(price) <= 0)
        ctx.addIssue({ code: "custom", path: ["price"], message: "Price must be greater than 0." });
      else if (Number(price) > PRICE_MAX)
        ctx.addIssue({ code: "custom", path: ["price"], message: "Price can't be more than 999,999.99." });
    }

    if (values.description.trim().length > DESCRIPTION_MAX)
      ctx.addIssue({
        code: "custom",
        path: ["description"],
        message: `Description must be ${DESCRIPTION_MAX} characters or fewer.`,
      });
  });

/** Form values for an existing tute (edit). */
export function tuteToFormValues(tute: TuteView): TuteFormValues {
  const price = tute.price === null || tute.price === undefined || tute.price === "" ? "" : Number(tute.price).toFixed(2);
  return {
    name: tute.name,
    quantity: String(tute.quantity),
    paidLessonId: tute.paymentType === "PAID" ? tute.paidLessonId ?? "" : "",
    paymentType: tute.paymentType,
    price: tute.paymentType === "PAID" ? price : "",
    description: tute.description ?? "",
  };
}

/**
 * Request body. Price and the paid session are only ever sent for Paid tutes
 * (the server rejects them otherwise: `TUTE_PRICE_NOT_ALLOWED` /
 * `TUTE_SESSION_NOT_ELIGIBLE`).
 */
export function toTuteRequest(values: TuteFormValues, version?: number): TuteRequest {
  const paid = values.paymentType === "PAID";
  const description = values.description.trim();
  const body: TuteRequest = {
    name: values.name.trim(),
    quantity: Number(values.quantity.trim()),
    paidLessonId: paid && values.paidLessonId ? values.paidLessonId : null,
    paymentType: values.paymentType as TutePaymentType,
    price: paid ? Number(values.price.trim()) : null,
    description: description ? description : null,
  };
  if (version !== undefined) body.version = version;
  return body;
}

/** The form field a server field error / error code belongs to, or null for a form-level message. */
export function tuteFieldForServerError(field: string): keyof TuteFormValues | null {
  switch (field) {
    case "name":
    case "quantity":
    case "paymentType":
    case "price":
    case "description":
    case "paidLessonId":
      return field;
    default:
      return null;
  }
}

/** "LKR 1,500.00"; "—" when there is no price. Formats the server value; never computes one. */
export function formatTutePrice(price: number | string | null | undefined, currency: string | null | undefined): string {
  if (price === null || price === undefined || price === "") return "—";
  const n = typeof price === "number" ? price : Number(price);
  if (!Number.isFinite(n)) return "—";
  const amount = n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return currency ? `${currency} ${amount}` : amount;
}

export function fulfillmentStatusLabel(status: FulfillmentStatus, method: FulfillmentMethod): string {
  if (status === "PENDING") return "Pending";
  if (status === "CANCELLED") return "Cancelled";
  return method === "COLLECTION" ? "Handed over" : "Dispatched";
}

export const METHOD_LABELS: Record<FulfillmentMethod, string> = {
  POSTAL: "Postal",
  COLLECTION: "Collection",
};

/** Index numbers are strings: leading zeros are kept, never parsed. */
export function displayIndex(indexNumber: string | null | undefined): string {
  return indexNumber && indexNumber.trim() ? indexNumber : "—";
}

/** A positive number from an error `details` entry, or null. */
export function detailNumber(details: Record<string, unknown> | null | undefined, key: string): number | null {
  const value = details?.[key];
  const n = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN;
  return Number.isFinite(n) ? n : null;
}
