import {
  monthShort,
  toAmount,
  type CheckoutQuote,
  type CheckoutQuoteRequest,
  type FacilityFeeBasis,
  type PeriodPurchase,
  type PurchaseScope,
  type QuoteCourseCharge,
  type QuoteItemRequest,
  type QuoteLine,
  type RequiredPurchase,
} from "@/lib/api/klass-student";
import { isApiClientError } from "@/lib/api/error";

/**
 * Pure model helpers for the PAY composer: tabs ↔ API scopes, selection keys
 * (identity of a purchasable item is course + scope + period/module/lesson,
 * never a title), the multi-course cart (items + per-course postage choices)
 * and entry-point presets. No pricing or access logic lives here - amounts
 * always come from the server quote.
 */

export type ComposerTab = "COURSE" | "MONTHLY" | "DAILY" | "MODULE" | "SESSION" | "INSTALLMENT";

export const COMPOSER_TABS: ComposerTab[] = ["COURSE", "MONTHLY", "DAILY", "MODULE", "SESSION", "INSTALLMENT"];

export const TAB_HEADINGS: Record<ComposerTab, string> = {
  COURSE: "Course payments",
  MONTHLY: "Monthly payments",
  DAILY: "Daily payments",
  MODULE: "Module payments",
  SESSION: "Session payments",
  INSTALLMENT: "Installment payments",
};

/** SESSION tab ↔ LESSON scope (plan §4.4). */
export function scopeForTab(tab: ComposerTab): PurchaseScope {
  return tab === "SESSION" ? "LESSON" : tab;
}

export function tabForScope(scope: PurchaseScope): ComposerTab {
  return scope === "LESSON" ? "SESSION" : scope;
}

export const SCOPE_LINE_LABELS: Partial<Record<PurchaseScope, string>> = {
  MONTHLY: "Monthly payment",
  MODULE: "Module payment",
  LESSON: "Session payment",
  COURSE: "Course payment",
};

export interface SelectedItem {
  key: string;
  /** Always carries `courseId`: the cart may span several courses. */
  request: QuoteItemRequest;
  /** Display text until the server quote answers (offer label or period). */
  label: string;
  /** Display only (grouping before the quote answers); the quote's `courseTitle` wins once priced. */
  courseTitle?: string | null;
}

/**
 * Identity of a purchasable item: course + scope + period/module/lesson.
 * Including the course keeps "2026 Jan" of course A and "2026 Jan" of course
 * B apart, while a second click on the same item is a duplicate.
 */
export function itemKey(item: {
  courseId: string | null;
  scope: PurchaseScope;
  year?: number | null;
  month?: number | null;
  moduleId?: string | null;
  lessonId?: string | null;
}): string {
  const course = item.courseId ?? "";
  switch (item.scope) {
    case "MONTHLY":
      return `${course}|MONTHLY:${item.year}-${item.month}`;
    case "MODULE":
      return `${course}|MODULE:${item.moduleId}`;
    case "LESSON":
      return `${course}|LESSON:${item.lessonId}`;
    default:
      return `${course}|${item.scope}:${item.year ?? ""}-${item.month ?? ""}:${item.moduleId ?? ""}:${item.lessonId ?? ""}`;
  }
}

/** Key of a quote line, matching the `itemKey` of the cart item it prices. */
export function lineKey(line: QuoteLine, fallbackCourseId: string | null = null): string {
  return itemKey({ ...line, courseId: line.courseId ?? fallbackCourseId });
}

// ---------------------------------------------------------------------------
// Cart (items across courses + per-course postage choices)
// ---------------------------------------------------------------------------

export interface ComposerCart {
  items: SelectedItem[];
  /** Courses whose postage the student ticked. Default: none. */
  postageCourseIds: string[];
}

export const EMPTY_CART: ComposerCart = { items: [], postageCourseIds: [] };

/** Distinct course ids of the cart, in the order they were first added. */
export function cartCourseIds(cart: ComposerCart): string[] {
  const ids: string[] = [];
  for (const item of cart.items) {
    if (!ids.includes(item.request.courseId)) ids.push(item.request.courseId);
  }
  return ids;
}

/** Postage is only ever kept for a course that still has items in the cart. */
function prunePostage(items: SelectedItem[], postageCourseIds: string[]): string[] {
  const withItems = new Set(items.map((item) => item.request.courseId));
  return postageCourseIds.filter((courseId) => withItems.has(courseId));
}

/** Adds an item unless an item with the same key is already in the cart (duplicates are prevented). */
export function addCartItem(cart: ComposerCart, item: SelectedItem): ComposerCart {
  if (cart.items.some((current) => current.key === item.key)) return cart;
  return { ...cart, items: [...cart.items, item] };
}

/** Removes one item; a course's postage choice is dropped together with its last item. */
export function removeCartItem(cart: ComposerCart, key: string): ComposerCart {
  const items = cart.items.filter((item) => item.key !== key);
  if (items.length === cart.items.length) return cart;
  return { items, postageCourseIds: prunePostage(items, cart.postageCourseIds) };
}

export function toggleCartItem(cart: ComposerCart, item: SelectedItem): ComposerCart {
  return cart.items.some((current) => current.key === item.key) ? removeCartItem(cart, item.key) : addCartItem(cart, item);
}

/** Ticks/unticks postage for one course; ticking is ignored for a course with no items. */
export function setCartPostage(cart: ComposerCart, courseId: string, selected: boolean): ComposerCart {
  const has = cart.postageCourseIds.includes(courseId);
  if (selected) {
    if (has || !cart.items.some((item) => item.request.courseId === courseId)) return cart;
    return { ...cart, postageCourseIds: [...cart.postageCourseIds, courseId] };
  }
  return has ? { ...cart, postageCourseIds: cart.postageCourseIds.filter((id) => id !== courseId) } : cart;
}

/** Number of cart items for one course (course picker hint). */
export function cartItemCount(cart: ComposerCart, courseId: string): number {
  return cart.items.filter((item) => item.request.courseId === courseId).length;
}

/** The quote/order body: identifiers only, `courseId` on every item, postage only for courses in the cart. */
export function toQuoteRequest(cart: ComposerCart): CheckoutQuoteRequest {
  return {
    items: cart.items.map((item) => item.request),
    postageCourseIds: prunePostage(cart.items, cart.postageCourseIds),
  };
}

// ---------------------------------------------------------------------------
// Quote display (server values only)
// ---------------------------------------------------------------------------

export interface QuoteCourseGroup {
  courseId: string;
  courseTitle: string;
  lines: QuoteLine[];
  /** The server's per-course charges (`null` only if the server sent none for this course). */
  charge: QuoteCourseCharge | null;
}

/** Quote lines grouped by course, in line order, with the server's per-course charges attached. */
export function groupQuoteByCourse(
  quote: CheckoutQuote,
  titleFallback?: (courseId: string) => string | null | undefined
): QuoteCourseGroup[] {
  const groups: QuoteCourseGroup[] = [];
  const courses = quote.courses ?? [];
  for (const line of quote.lines) {
    const courseId = line.courseId ?? quote.courseId ?? "";
    let group = groups.find((entry) => entry.courseId === courseId);
    if (!group) {
      const charge = courses.find((entry) => entry.courseId === courseId) ?? null;
      group = {
        courseId,
        courseTitle: line.courseTitle ?? charge?.courseTitle ?? titleFallback?.(courseId) ?? "Course",
        lines: [],
        charge,
      };
      groups.push(group);
    }
    group.lines.push(line);
  }
  return groups;
}

export interface CartCourseGroup {
  courseId: string;
  courseTitle: string;
  items: SelectedItem[];
}

/** Cart items grouped by course (shown while the quote is loading or failed). */
export function groupCartByCourse(cart: ComposerCart): CartCourseGroup[] {
  const groups: CartCourseGroup[] = [];
  for (const item of cart.items) {
    const courseId = item.request.courseId;
    let group = groups.find((entry) => entry.courseId === courseId);
    if (!group) {
      group = { courseId, courseTitle: item.courseTitle ?? "Course", items: [] };
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
}

/** False when the course's configured postage is 0 ("No postage charge"); 0 is a value, never "missing". */
export function hasPostageCharge(charge: QuoteCourseCharge): boolean {
  return toAmount(charge.postageFee) > 0;
}

/** Short explanation of where a course's facility fee comes from, or null when none applies. */
export function facilityFeeSourceText(charge: QuoteCourseCharge, basis: FacilityFeeBasis): string | null {
  if (basis === "NONE" || charge.facilityFeeSource === "NOT_APPLICABLE") return null;
  if (basis === "PER_ORDER") return "Charged once per payment";
  const unit = basis === "PER_LINE" ? "per item" : "once per course";
  return charge.facilityFeeSource === "COURSE_OVERRIDE" ? `Course facility fee, ${unit}` : `Institute facility fee, ${unit}`;
}

/** The on-record delivery address is usable when it has an address line and a city. */
export function hasDeliveryAddress(quote: CheckoutQuote): boolean {
  return Boolean(quote.delivery?.addressLine?.trim() && quote.delivery?.city?.trim());
}

/** The fresh quote carried by a 409 `QUOTE_CHANGED` (`error.details.quote`), or null. */
export function quoteFromQuoteChanged(error: unknown): CheckoutQuote | null {
  if (!isApiClientError(error) || error.code !== "QUOTE_CHANGED") return null;
  const quote = error.details?.quote;
  if (!quote || typeof quote !== "object" || !Array.isArray((quote as CheckoutQuote).lines)) return null;
  return quote as CheckoutQuote;
}

export function isDeliveryDetailsError(error: unknown): boolean {
  return isApiClientError(error) && error.code === "DELIVERY_DETAILS_REQUIRED";
}

export function monthlyLabel(year: number, month: number): string {
  return `${year} ${monthShort(month)}`;
}

/** Text shown for a quote line's period/item column. */
export function linePeriodText(line: QuoteLine): string {
  if (line.scope === "MONTHLY" && line.year && line.month) return monthlyLabel(line.year, line.month);
  return line.label;
}

/** How the composer opens. No preset = global PAY (no preselection). */
export interface ComposerPreset {
  courseId: string;
  courseTitle?: string;
  tab: ComposerTab;
  item?: SelectedItem;
}

function toRequest(scope: PurchaseScope, purchase: RequiredPurchase | PeriodPurchase): QuoteItemRequest | null {
  const courseId = purchase.courseId;
  if (scope === "MONTHLY" && purchase.year && purchase.month) {
    return { courseId, scope, year: purchase.year, month: purchase.month };
  }
  if (scope === "MODULE" && "moduleId" in purchase && purchase.moduleId) {
    return { courseId, scope, moduleId: purchase.moduleId };
  }
  if (scope === "LESSON" && "lessonId" in purchase && purchase.lessonId) {
    return { courseId, scope, lessonId: purchase.lessonId };
  }
  return null;
}

/**
 * Preset from a server-provided purchase requirement (period "Pay now" or a
 * lesson's `access.requiredPurchase`). The month comes from the server's
 * explicit billing association - never from today's date.
 */
export function presetFromPurchase(
  purchase: RequiredPurchase | PeriodPurchase,
  options?: { courseTitle?: string; itemLabel?: string }
): ComposerPreset {
  const scope = purchase.scope;
  const tab = tabForScope(scope);
  const request = toRequest(scope, purchase);
  const label =
    options?.itemLabel ??
    (scope === "MONTHLY" && purchase.year && purchase.month ? monthlyLabel(purchase.year, purchase.month) : "Selected item");
  return {
    courseId: purchase.courseId,
    courseTitle: options?.courseTitle,
    tab,
    item: request ? { key: itemKey(request), request, label, courseTitle: options?.courseTitle ?? null } : undefined,
  };
}

/** Clear copy for composer API failures (422 codes from §4.4). */
export function composerErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    switch (error.code) {
      case "ITEM_ALREADY_PAID":
        return "One of the selected items is already paid. Remove it to continue.";
      case "ITEM_PENDING":
        return "One of the selected items already has a payment waiting for approval. Remove it to continue.";
      case "ITEM_NOT_ELIGIBLE":
        return "One of the selected items can't be bought right now. Remove it and try again.";
      case "EMPTY_SELECTION":
        return "Select at least one item to pay for.";
      case "CURRENCY_MISMATCH":
        // The server names the courses/currencies; the cart is kept so the student can remove one.
        return error.message || "These courses are priced in different currencies. Pay for them separately.";
      case "POSTAGE_COURSE_NOT_IN_CART":
        return "Postage was selected for a course with no items. Untick it or add an item from that course.";
      case "DELIVERY_DETAILS_REQUIRED":
        return "Postage needs a delivery address. Add your address below, or untick postage.";
      case "QUOTE_REQUIRED":
        return "Your total needs to be recalculated. Review it and confirm again.";
      case "QUOTE_CHANGED":
        return "Prices changed — review the updated total, then confirm again.";
      case "NETWORK_ERROR":
        return "We couldn't reach the server. Check your connection and try again.";
      default:
        if (error.status === 404) return "This course isn't available for payment.";
        if (error.status === 403) return "You can't make this payment from your account.";
        return error.message || "Something went wrong. Please try again.";
    }
  }
  return "Something went wrong. Please try again.";
}

export function isStaleSelectionError(error: unknown): boolean {
  return isApiClientError(error) && (error.code === "ITEM_ALREADY_PAID" || error.code === "ITEM_PENDING" || error.code === "ITEM_NOT_ELIGIBLE");
}

/** Rejects if `promise` takes longer than `ms` (the request itself isn't cancelled; retries reuse the same idempotency key). */
export function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}
