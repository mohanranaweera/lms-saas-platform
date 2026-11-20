import { test, expect } from "@playwright/test";
import {
  EMPTY_SELECTION,
  buildLabelsRequest,
  confirmDispatchIds,
  explicitCount,
  isRowSelected,
  labelTargetCount,
  missingAddressTargetCount,
  pageSelectionState,
  printButtonLabel,
  selectAllFiltered,
  togglePage,
  toggleRow,
} from "../../src/lib/tutes/dispatch-selection";
import {
  formatTutePrice,
  fulfillmentStatusLabel,
  toTuteRequest,
  tuteFormSchema,
  type TuteFormValues,
} from "../../src/lib/tutes/tute-form";
import type { DispatchRowView } from "../../src/lib/api/tutes";

/**
 * TUTE-01 pure logic (no browser, no server): the dispatch selection model
 * and print/confirm request bodies, and the tute form rules mirrored from the
 * server (spec §3, §7). Synthetic data only.
 */

function row(key: string, itemIds: string[], addressMissing = false): DispatchRowView {
  return {
    groupKey: key,
    studentId: `student-${key}`,
    studentProfileId: `profile-${key}`,
    indexNumber: `00${key}`,
    studentName: `Student ${key}`,
    city: addressMissing ? null : "Kandy",
    addressMissing,
    tuteCount: itemIds.length,
    items: itemIds.map((id) => ({
      fulfillmentId: id,
      tuteId: "t1",
      tuteName: "Unit 1",
      courseId: "c1",
      courseName: "Physics",
      label: "PHYSICS – Unit 1",
    })),
  };
}

const PAGE_1 = [row("1", ["f1"]), row("2", ["f2", "f3"], true)];
const PAGE_2 = [row("3", ["f4"])];
const FILTERS = { search: "  kan ", courseId: "c1", tuteId: null, twoPlus: true };

test.describe("dispatch selection model", () => {
  test("header toggles the page and reports all / some / none", () => {
    expect(pageSelectionState(EMPTY_SELECTION, PAGE_1)).toBe("none");
    let selection = toggleRow(EMPTY_SELECTION, PAGE_1[0], PAGE_1);
    expect(pageSelectionState(selection, PAGE_1)).toBe("some");
    selection = togglePage(selection, PAGE_1);
    expect(pageSelectionState(selection, PAGE_1)).toBe("all");
    selection = togglePage(selection, PAGE_1);
    expect(pageSelectionState(selection, PAGE_1)).toBe("none");
  });

  test("selection persists across pages by group key", () => {
    let selection = togglePage(EMPTY_SELECTION, PAGE_1);
    selection = toggleRow(selection, PAGE_2[0], PAGE_2);
    expect(explicitCount(selection)).toBe(3);
    expect(isRowSelected(selection, PAGE_1[1])).toBe(true);
    expect(confirmDispatchIds(selection)).toEqual(["f1", "f2", "f3", "f4"]);
  });

  test("all-filtered mode selects every row; unticking narrows to the rest of the page", () => {
    const all = selectAllFiltered();
    expect(isRowSelected(all, PAGE_2[0])).toBe(true);
    expect(confirmDispatchIds(all)).toBeNull();
    const narrowed = toggleRow(all, PAGE_1[0], PAGE_1);
    expect(narrowed.mode).toBe("explicit");
    expect(explicitCount(narrowed)).toBe(1);
    expect(isRowSelected(narrowed, PAGE_1[1])).toBe(true);
  });

  test("print button label and label target count", () => {
    expect(printButtonLabel(EMPTY_SELECTION, 6)).toBe("Print all 6 labels (PDF)");
    expect(printButtonLabel(EMPTY_SELECTION, 1)).toBe("Print all 1 label (PDF)");
    expect(printButtonLabel(selectAllFiltered(), 40)).toBe("Print all 40 labels (PDF)");
    const one = toggleRow(EMPTY_SELECTION, PAGE_1[1], PAGE_1);
    expect(printButtonLabel(one, 40)).toBe("Print 1 selected label (PDF)");
    expect(printButtonLabel(togglePage(EMPTY_SELECTION, PAGE_1), 40)).toBe("Print 2 selected labels (PDF)");
    expect(labelTargetCount(one, 40)).toBe(1);
    expect(labelTargetCount(selectAllFiltered(), 40)).toBe(40);
  });

  test("labels request: explicit ids, or filters + expected count", () => {
    const explicit = togglePage(EMPTY_SELECTION, PAGE_1);
    expect(buildLabelsRequest(explicit, FILTERS, 40)).toEqual({ fulfillmentIds: ["f1", "f2", "f3"] });
    const expected = {
      allFiltered: true,
      filters: { search: "kan", courseId: "c1", tuteId: null, twoPlus: true },
      expectedLabelCount: 40,
    };
    expect(buildLabelsRequest(selectAllFiltered(), FILTERS, 40)).toEqual(expected);
    expect(buildLabelsRequest(EMPTY_SELECTION, FILTERS, 40)).toEqual(expected);
    expect(buildLabelsRequest(EMPTY_SELECTION, { ...FILTERS, search: "" }, 3)).toMatchObject({ filters: { search: null } });
  });

  test("missing-address count uses the selection, else the server's filtered count", () => {
    expect(missingAddressTargetCount(togglePage(EMPTY_SELECTION, PAGE_1), 9)).toBe(1);
    expect(missingAddressTargetCount(toggleRow(EMPTY_SELECTION, PAGE_1[0], PAGE_1), 9)).toBe(0);
    expect(missingAddressTargetCount(selectAllFiltered(), 9)).toBe(9);
    expect(missingAddressTargetCount(EMPTY_SELECTION, 2)).toBe(2);
  });
});

const VALID: TuteFormValues = {
  name: "Unit 1 notes",
  quantity: "100",
  paidLessonId: "",
  paymentType: "FREE",
  price: "",
  description: "",
};

function fieldsWithErrors(values: TuteFormValues): string[] {
  const result = tuteFormSchema.safeParse(values);
  return result.success ? [] : result.error.issues.map((issue) => issue.path.join("."));
}

test.describe("tute form rules", () => {
  test("requires name, whole-number quantity and a payment type", () => {
    expect(fieldsWithErrors(VALID)).toEqual([]);
    expect(fieldsWithErrors({ ...VALID, name: "   " })).toEqual(["name"]);
    expect(fieldsWithErrors({ ...VALID, name: "x".repeat(201) })).toEqual(["name"]);
    expect(fieldsWithErrors({ ...VALID, quantity: "" })).toEqual(["quantity"]);
    expect(fieldsWithErrors({ ...VALID, quantity: "1.5" })).toEqual(["quantity"]);
    expect(fieldsWithErrors({ ...VALID, quantity: "-1" })).toEqual(["quantity"]);
    expect(fieldsWithErrors({ ...VALID, quantity: "1000001" })).toEqual(["quantity"]);
    expect(fieldsWithErrors({ ...VALID, quantity: "0" })).toEqual([]);
    expect(fieldsWithErrors({ ...VALID, paymentType: "" })).toEqual(["paymentType"]);
    expect(fieldsWithErrors({ ...VALID, description: "d".repeat(2001) })).toEqual(["description"]);
  });

  test("price is required only for Paid, > 0, <= 999,999.99, at most 2 decimals", () => {
    const paid = { ...VALID, paymentType: "PAID" as const };
    expect(fieldsWithErrors(paid)).toEqual(["price"]);
    expect(fieldsWithErrors({ ...paid, price: "0" })).toEqual(["price"]);
    expect(fieldsWithErrors({ ...paid, price: "1.234" })).toEqual(["price"]);
    expect(fieldsWithErrors({ ...paid, price: "1000000" })).toEqual(["price"]);
    expect(fieldsWithErrors({ ...paid, price: "999999.99" })).toEqual([]);
    expect(fieldsWithErrors({ ...VALID, price: "abc" })).toEqual([]);
  });

  test("request never sends a price or session for non-Paid tutes", () => {
    expect(toTuteRequest({ ...VALID, price: "50", paidLessonId: "l1" })).toEqual({
      name: "Unit 1 notes",
      quantity: 100,
      paidLessonId: null,
      paymentType: "FREE",
      price: null,
      description: null,
    });
    expect(toTuteRequest({ ...VALID, paymentType: "PAID", price: "1500.50", paidLessonId: "l1" }, 3)).toEqual({
      name: "Unit 1 notes",
      quantity: 100,
      paidLessonId: "l1",
      paymentType: "PAID",
      price: 1500.5,
      description: null,
      version: 3,
    });
  });

  test("labels", () => {
    expect(formatTutePrice(1500, "LKR")).toBe("LKR 1,500.00");
    expect(formatTutePrice(null, "LKR")).toBe("—");
    expect(fulfillmentStatusLabel("DISPATCHED", "COLLECTION")).toBe("Handed over");
    expect(fulfillmentStatusLabel("DISPATCHED", "POSTAL")).toBe("Dispatched");
    expect(fulfillmentStatusLabel("PENDING", "POSTAL")).toBe("Pending");
  });
});
