import { test, expect } from "@playwright/test";
import { courseChargesSchema } from "../../src/components/courses/admin/schemas";
import {
  courseToChargesFormValues,
  toCourseChargesRequest,
} from "../../src/components/courses/admin/course-charges-section";
import {
  facilityFeeConfigSchema,
  facilityFeeFormValues,
  toFacilityFeeChanges,
} from "../../src/lib/validation/tenant-config";
import type { KlassCourse } from "../../src/lib/api/klass-admin";

/**
 * Unit tests (no browser, no server) for the course facility-fee / postage
 * and tenant facility-fee validation: negatives and > 2 decimals are rejected,
 * 0 is a real value (never "empty"), and the request bodies send only changes.
 */

function errorsOf(result: { success: boolean; error?: { issues: { path: PropertyKey[] }[] } }): string[] {
  return result.success ? [] : (result.error?.issues ?? []).map((issue) => issue.path.join("."));
}

test.describe("course facility fee + postage validation", () => {
  test("rejects negatives and more than 2 decimals; accepts 0", () => {
    const base = { facilityFeeInherit: false, facilityFeeOverride: "0", postageFee: "0" };
    expect(courseChargesSchema.safeParse(base).success).toBe(true);
    expect(courseChargesSchema.safeParse({ ...base, facilityFeeOverride: "0.00", postageFee: "0.00" }).success).toBe(true);
    expect(errorsOf(courseChargesSchema.safeParse({ ...base, facilityFeeOverride: "-1" }))).toEqual(["facilityFeeOverride"]);
    expect(errorsOf(courseChargesSchema.safeParse({ ...base, facilityFeeOverride: "1.234" }))).toEqual(["facilityFeeOverride"]);
    expect(errorsOf(courseChargesSchema.safeParse({ ...base, postageFee: "-5" }))).toEqual(["postageFee"]);
    expect(errorsOf(courseChargesSchema.safeParse({ ...base, postageFee: "2.555" }))).toEqual(["postageFee"]);
  });

  test("an override is required when not inheriting, ignored when inheriting", () => {
    expect(
      errorsOf(courseChargesSchema.safeParse({ facilityFeeInherit: false, facilityFeeOverride: "", postageFee: "0" }))
    ).toEqual(["facilityFeeOverride"]);
    expect(
      courseChargesSchema.safeParse({ facilityFeeInherit: true, facilityFeeOverride: "", postageFee: "0" }).success
    ).toBe(true);
    expect(
      errorsOf(courseChargesSchema.safeParse({ facilityFeeInherit: true, facilityFeeOverride: "", postageFee: "" }))
    ).toEqual(["postageFee"]);
  });

  test("an override of 0 is 'no fee', not 'inherit'; null is inherit", () => {
    const course = { facilityFeeOverride: "0.00", postageFee: "0.00" } as unknown as KlassCourse;
    expect(courseToChargesFormValues(course)).toEqual({
      facilityFeeInherit: false,
      facilityFeeOverride: "0.00",
      postageFee: "0.00",
    });
    const inheriting = { facilityFeeOverride: null, postageFee: 150 } as unknown as KlassCourse;
    expect(courseToChargesFormValues(inheriting)).toEqual({
      facilityFeeInherit: true,
      facilityFeeOverride: "",
      postageFee: "150.00",
    });
  });

  test("request sends only changes: inherit clears, override 0 is sent, postage 0 is sent", () => {
    const inheriting = { facilityFeeInherit: true, facilityFeeOverride: "", postageFee: "150.00" };
    expect(toCourseChargesRequest(inheriting, inheriting)).toEqual({});
    expect(toCourseChargesRequest({ ...inheriting, facilityFeeInherit: false, facilityFeeOverride: "0" }, inheriting)).toEqual({
      facilityFeeInherit: false,
      facilityFeeOverride: "0.00",
    });
    expect(toCourseChargesRequest({ ...inheriting, postageFee: "0" }, inheriting)).toEqual({ postageFee: "0.00" });

    const overriding = { facilityFeeInherit: false, facilityFeeOverride: "50.00", postageFee: "0.00" };
    expect(toCourseChargesRequest({ ...overriding, facilityFeeInherit: true }, overriding)).toEqual({ facilityFeeInherit: true });
    expect(toCourseChargesRequest({ ...overriding, facilityFeeOverride: "50" }, overriding)).toEqual({});
  });
});

test.describe("tenant facility fee validation", () => {
  test("mode must be one of the four; amount >= 0 with up to 2 decimals, 0 allowed", () => {
    expect(facilityFeeConfigSchema.safeParse({ facility_fee_mode: "PER_COURSE", facility_fee_amount: "0" }).success).toBe(true);
    expect(errorsOf(facilityFeeConfigSchema.safeParse({ facility_fee_mode: "WEEKLY", facility_fee_amount: "0" }))).toEqual([
      "facility_fee_mode",
    ]);
    expect(errorsOf(facilityFeeConfigSchema.safeParse({ facility_fee_mode: "NONE", facility_fee_amount: "-1" }))).toEqual([
      "facility_fee_amount",
    ]);
    expect(errorsOf(facilityFeeConfigSchema.safeParse({ facility_fee_mode: "NONE", facility_fee_amount: "1.001" }))).toEqual([
      "facility_fee_amount",
    ]);
    expect(errorsOf(facilityFeeConfigSchema.safeParse({ facility_fee_mode: "NONE", facility_fee_amount: "" }))).toEqual([
      "facility_fee_amount",
    ]);
  });

  test("reads persisted/default values and sends only changed keys, the amount as a JSON number", () => {
    const current = facilityFeeFormValues([
      { key: "facility_fee_mode", value: null, defaultValue: "NONE" },
      { key: "facility_fee_amount", value: 0, defaultValue: 0 },
    ]);
    expect(current).toEqual({ facility_fee_mode: "NONE", facility_fee_amount: "0.00" });
    expect(toFacilityFeeChanges(current, current)).toEqual({});
    expect(toFacilityFeeChanges({ facility_fee_mode: "PER_COURSE", facility_fee_amount: "0" }, current)).toEqual({
      facility_fee_mode: "PER_COURSE",
    });
    expect(toFacilityFeeChanges({ facility_fee_mode: "NONE", facility_fee_amount: "125.5" }, current)).toEqual({
      facility_fee_amount: 125.5,
    });
  });
});
