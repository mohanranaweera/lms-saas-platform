"use client";

import { Info } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { formatMoney } from "@/lib/format";
import type { CoursePricingModel } from "@/lib/api/courses";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { useLocaleFormat } from "@/i18n/use-locale-format";

/**
 * Shared pricing-model-aware display shape — matches the fields every
 * course-shaped API response now carries (`PublicCourseResponse`,
 * `CourseResponse`, mirroring the backend's `PublicCourseView`/`CourseView`
 * javadoc): `resolvedAmount` is `0` for `FREE`, the flat `price` for
 * `ONE_TIME`, the current open billing period's amount for `MONTHLY`/
 * `SESSION` (`null` if no billing period has been configured yet — "not yet
 * available for checkout", never a silent $0), and always `null` for
 * `CUSTOM` (`requiresManualQuote: true` signals that case instead).
 *
 * Every consumer of this file (storefront listing, storefront detail,
 * student checkout) must render pricing FROM these fields, never re-derive
 * "is this free/configured/custom" from `pricingModel` alone — the backend's
 * `requiresManualQuote`/`resolvedAmount` pair is the authoritative signal, and
 * this file is the one place that interprets it, per `.claude/rules/frontend.md`'s
 * single-typed-API-client-layer rule.
 */
export interface CoursePricingInfo {
  pricingModel: CoursePricingModel;
  resolvedAmount: number | null;
  currency: string;
  requiresManualQuote: boolean;
}

function pricingSuffix(pricingModel: CoursePricingModel): string {
  if (pricingModel === "MONTHLY") return "/month";
  if (pricingModel === "SESSION") return "/session";
  return "";
}

/**
 * The single price string to render anywhere a course's price appears
 * (catalog card, storefront detail, checkout summary). Never throws/returns
 * a blank string — every branch has explicit copy.
 */
export function getCoursePriceLabel(course: CoursePricingInfo): string {
  if (course.requiresManualQuote) return "Contact us for pricing";
  if (course.pricingModel === "FREE") return "Free";
  if (course.resolvedAmount == null) return "Pricing not yet available";
  return `${formatMoney(course.resolvedAmount, course.currency)}${pricingSuffix(course.pricingModel)}`;
}

/**
 * Secondary explanatory copy for the two "can't check out" states. `null`
 * when there is nothing extra to say (FREE, ONE_TIME, and a fully configured
 * MONTHLY/SESSION course all render the price alone).
 */
export function getCoursePriceNote(course: CoursePricingInfo): string | null {
  if (course.requiresManualQuote) {
    return "This course's pricing and enrollment are arranged manually by our staff — there is no self-serve checkout for it.";
  }
  if ((course.pricingModel === "MONTHLY" || course.pricingModel === "SESSION") && course.resolvedAmount == null) {
    return "Pricing for this course hasn't been configured yet. Please check back soon.";
  }
  return null;
}

/**
 * UX-only gate for whether the enroll/checkout action should be offered at
 * all. This is never authorization — the backend independently rejects any
 * checkout attempt for a course it resolves as manual-quote/unpriced
 * regardless of what this returns; it exists purely so this frontend doesn't
 * walk a student into a checkout flow that would 409.
 */
export function isCourseCheckoutAvailable(course: CoursePricingInfo): boolean {
  return !course.requiresManualQuote && course.resolvedAmount != null;
}

/** Inline price text — used on the catalog card and detail/checkout summaries alike. */
/** Localized variant of `getCoursePriceLabel` (same branches; display only). */
export function localizedCoursePriceLabel(
  course: CoursePricingInfo,
  t: TranslateFn,
  money: (amount: number, currency?: string | null) => string
): string {
  if (course.requiresManualQuote) return t("courses.price.contactUs");
  if (course.pricingModel === "FREE") return t("courses.price.free");
  if (course.resolvedAmount == null) return t("courses.price.notAvailable");
  const price = money(course.resolvedAmount, course.currency);
  if (course.pricingModel === "MONTHLY") return t("courses.price.perMonth", { price });
  if (course.pricingModel === "SESSION") return t("courses.price.perSession", { price });
  return price;
}

export function CoursePriceText({ course, className }: { course: CoursePricingInfo; className?: string }) {
  const t = useT();
  const { formatMoney } = useLocaleFormat();
  return <span className={className}>{localizedCoursePriceLabel(course, t, formatMoney)}</span>;
}

/**
 * Renders `getCoursePriceNote`'s copy as an accessible status notice
 * (`role="status"`, matching `course-billing-panel.tsx`'s existing pattern
 * for the same "custom pricing has no self-serve checkout" message). Renders
 * nothing when there's no note to show.
 */
export function CoursePricingNotice({ course }: { course: CoursePricingInfo }) {
  const t = useT();
  const english = getCoursePriceNote(course);
  const note = !english
    ? null
    : course.requiresManualQuote
      ? t("courses.price.manualNote")
      : t("courses.price.notConfiguredNote")
  if (!note) return null;
  return (
    <Alert role="status">
      <Info aria-hidden="true" />
      <AlertDescription>{note}</AlertDescription>
    </Alert>
  );
}
