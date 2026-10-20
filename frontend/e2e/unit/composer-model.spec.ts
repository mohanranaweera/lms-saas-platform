import { test, expect } from "@playwright/test";
import {
  EMPTY_CART,
  addCartItem,
  cartCourseIds,
  composerErrorMessage,
  facilityFeeSourceText,
  groupQuoteByCourse,
  hasDeliveryAddress,
  hasPostageCharge,
  itemKey,
  lineKey,
  quoteFromQuoteChanged,
  removeCartItem,
  setCartPostage,
  toQuoteRequest,
  toggleCartItem,
  type ComposerCart,
  type SelectedItem,
} from "../../src/components/payments/composer/composer-model";
import { ApiClientError } from "../../src/lib/api/error";
import type { CheckoutQuote, QuoteCourseCharge, QuoteLine } from "../../src/lib/api/klass-student";

/**
 * Unit tests (no browser, no server) for the multi-course composer model.
 * Letters follow the multi-course checkout acceptance list
 * (docs/plans/multi-course-checkout-fees-postage.md): A multi-course cart,
 * B no collision / no duplicates across courses, C per-course display,
 * H per-course postage, J QUOTE_CHANGED / currency mismatch.
 */

const ICT = "11111111-1111-1111-1111-111111111111";
const BIO = "22222222-2222-2222-2222-222222222222";

function month(courseId: string, year: number, m: number, courseTitle = "Course"): SelectedItem {
  const request = { courseId, scope: "MONTHLY" as const, year, month: m };
  return { key: itemKey(request), request, label: `${year}-${m}`, courseTitle };
}

function lesson(courseId: string, lessonId: string): SelectedItem {
  const request = { courseId, scope: "LESSON" as const, lessonId };
  return { key: itemKey(request), request, label: lessonId };
}

function line(overrides: Partial<QuoteLine>): QuoteLine {
  return {
    lineNo: 1,
    scope: "MONTHLY",
    label: "2026 Oct",
    year: 2026,
    month: 10,
    moduleId: null,
    lessonId: null,
    unitAmount: "3500.00",
    facilityFee: "0.00",
    lineTotal: "3500.00",
    courseId: ICT,
    courseTitle: "ICT",
    ...overrides,
  };
}

function charge(overrides: Partial<QuoteCourseCharge>): QuoteCourseCharge {
  return {
    courseId: ICT,
    courseTitle: "ICT",
    currency: "LKR",
    subtotal: "3500.00",
    facilityFee: "100.00",
    effectiveFacilityFee: "100.00",
    facilityFeeSource: "TENANT_DEFAULT",
    postageFee: "0.00",
    postageSelected: false,
    postageCharge: "0.00",
    ...overrides,
  };
}

function quote(overrides: Partial<CheckoutQuote>): CheckoutQuote {
  return {
    courseId: null,
    currency: "LKR",
    lines: [],
    courses: [],
    subtotal: "0.00",
    facilityFeeTotal: "0.00",
    postageTotal: "0.00",
    discountTotal: "0.00",
    total: "0.00",
    feeBasis: "PER_COURSE",
    feeNote: null,
    deliveryRequired: false,
    delivery: null,
    quoteToken: "token-1",
    methods: { online: true, slip: true },
    bankDetails: null,
    ...overrides,
  };
}

test.describe("composer model", () => {
  test("A: items from two courses share one cart; every quote item carries its courseId", () => {
    let cart: ComposerCart = EMPTY_CART;
    cart = addCartItem(cart, month(ICT, 2026, 10));
    cart = addCartItem(cart, month(BIO, 2026, 11));
    cart = addCartItem(cart, lesson(BIO, "lesson-1"));

    expect(cartCourseIds(cart)).toEqual([ICT, BIO]);
    const body = toQuoteRequest(cart);
    expect(body.items).toHaveLength(3);
    for (const item of body.items) expect(item.courseId).toBeTruthy();
    expect(body.items.map((item) => item.courseId)).toEqual([ICT, BIO, BIO]);
    expect(body.postageCourseIds).toEqual([]);
    // Identifiers only - never an amount.
    expect(JSON.stringify(body)).not.toMatch(/amount|price|total/i);
  });

  test("B: the same period in two courses does not collide; the same item twice is a duplicate", () => {
    const ictOct = month(ICT, 2026, 10);
    const bioOct = month(BIO, 2026, 10);
    expect(ictOct.key).not.toBe(bioOct.key);

    let cart = addCartItem(addCartItem(EMPTY_CART, ictOct), bioOct);
    expect(cart.items).toHaveLength(2);

    // Duplicate add is a no-op (same reference: no re-quote, no new idempotency key).
    const again = addCartItem(cart, month(ICT, 2026, 10));
    expect(again).toBe(cart);
    expect(again.items).toHaveLength(2);

    // Toggle removes only the course it belongs to.
    cart = toggleCartItem(cart, month(BIO, 2026, 10));
    expect(cart.items.map((item) => item.key)).toEqual([ictOct.key]);

    // A quote line's key matches the cart item of its own course.
    expect(lineKey(line({ courseId: ICT }))).toBe(ictOct.key);
    expect(lineKey(line({ courseId: BIO }))).toBe(bioOct.key);
  });

  test("C-display: quote lines are grouped by course with the server's per-course charges", () => {
    const q = quote({
      lines: [
        line({ lineNo: 1, courseId: ICT, courseTitle: "ICT" }),
        line({ lineNo: 2, courseId: BIO, courseTitle: "Biology", month: 11 }),
        line({ lineNo: 3, courseId: ICT, courseTitle: "ICT", month: 11 }),
      ],
      courses: [
        charge({ courseId: ICT, courseTitle: "ICT", facilityFeeSource: "COURSE_OVERRIDE", facilityFee: "0.00" }),
        charge({ courseId: BIO, courseTitle: "Biology", postageFee: "350.00" }),
      ],
    });
    const groups = groupQuoteByCourse(q);
    expect(groups.map((group) => group.courseTitle)).toEqual(["ICT", "Biology"]);
    expect(groups[0].lines.map((entry) => entry.lineNo)).toEqual([1, 3]);
    expect(groups[1].lines.map((entry) => entry.lineNo)).toEqual([2]);
    expect(groups[0].charge?.facilityFeeSource).toBe("COURSE_OVERRIDE");

    expect(facilityFeeSourceText(groups[0].charge!, "PER_COURSE")).toBe("Course facility fee, once per course");
    expect(facilityFeeSourceText(groups[1].charge!, "PER_LINE")).toBe("Institute facility fee, per item");
    expect(facilityFeeSourceText(groups[1].charge!, "PER_ORDER")).toBe("Charged once per payment");
    expect(facilityFeeSourceText(groups[1].charge!, "NONE")).toBeNull();

    // 0 postage is "No postage charge", not missing.
    expect(hasPostageCharge(groups[0].charge!)).toBe(false);
    expect(hasPostageCharge(groups[1].charge!)).toBe(true);
  });

  test("H: postage is per course, unselected by default, kept while other courses change, never sent without items", () => {
    let cart = addCartItem(EMPTY_CART, month(ICT, 2026, 10));
    expect(cart.postageCourseIds).toEqual([]);

    // Can't tick postage for a course that has no items.
    expect(setCartPostage(cart, BIO, true)).toBe(cart);

    cart = setCartPostage(cart, ICT, true);
    expect(cart.postageCourseIds).toEqual([ICT]);

    // Working on another course keeps the ICT postage choice.
    cart = addCartItem(cart, month(BIO, 2026, 10));
    cart = toggleCartItem(cart, month(BIO, 2026, 11));
    expect(cart.postageCourseIds).toEqual([ICT]);
    expect(toQuoteRequest(cart).postageCourseIds).toEqual([ICT]);

    cart = setCartPostage(cart, ICT, false);
    expect(cart.postageCourseIds).toEqual([]);
  });

  test("postage is dropped automatically when a course's last item is removed", () => {
    let cart = addCartItem(EMPTY_CART, month(ICT, 2026, 10));
    cart = addCartItem(cart, month(ICT, 2026, 11));
    cart = addCartItem(cart, month(BIO, 2026, 10));
    cart = setCartPostage(setCartPostage(cart, ICT, true), BIO, true);

    cart = removeCartItem(cart, month(ICT, 2026, 10).key);
    expect(cart.postageCourseIds).toEqual([ICT, BIO]);

    cart = removeCartItem(cart, month(ICT, 2026, 11).key);
    expect(cart.postageCourseIds).toEqual([BIO]);
    expect(toQuoteRequest(cart).postageCourseIds).toEqual([BIO]);

    cart = toggleCartItem(cart, month(BIO, 2026, 10));
    expect(cart.items).toHaveLength(0);
    expect(cart.postageCourseIds).toEqual([]);
  });

  test("J: QUOTE_CHANGED yields the fresh quote; CURRENCY_MISMATCH shows the server message", () => {
    const fresh = quote({ total: "4100.00", quoteToken: "token-2", lines: [line({})] });
    const changed = new ApiClientError(
      { code: "QUOTE_CHANGED", message: "Prices changed", fieldErrors: [], details: { quote: fresh } },
      409
    );
    expect(quoteFromQuoteChanged(changed)?.quoteToken).toBe("token-2");
    expect(composerErrorMessage(changed)).toBe("Prices changed — review the updated total, then confirm again.");

    // Other errors / malformed details never produce a quote.
    expect(quoteFromQuoteChanged(new ApiClientError({ code: "ITEM_PENDING", message: "x", fieldErrors: [] }, 422))).toBeNull();
    expect(
      quoteFromQuoteChanged(new ApiClientError({ code: "QUOTE_CHANGED", message: "x", fieldErrors: [], details: {} }, 409))
    ).toBeNull();

    const mismatch = new ApiClientError(
      { code: "CURRENCY_MISMATCH", message: "ICT is priced in LKR but Biology is priced in USD.", fieldErrors: [] },
      422
    );
    expect(composerErrorMessage(mismatch)).toBe("ICT is priced in LKR but Biology is priced in USD.");
  });

  test("delivery address needs an address line and a city", () => {
    expect(hasDeliveryAddress(quote({ delivery: null }))).toBe(false);
    expect(
      hasDeliveryAddress(quote({ delivery: { recipientName: "A", addressLine: "1 Main St", city: " ", phone: null } }))
    ).toBe(false);
    expect(
      hasDeliveryAddress(quote({ delivery: { recipientName: null, addressLine: "1 Main St", city: "Kandy", phone: null } }))
    ).toBe(true);
  });
});
