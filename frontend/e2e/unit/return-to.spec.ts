import { test, expect } from "@playwright/test";
import { safeReturnTo, withReturnTo } from "../../src/lib/navigation/return-to";

/**
 * Unit tests (no browser) for the STORE-01 `returnTo` validator: only a
 * same-host relative path is honored (ADR-037 decision 7).
 */
test.describe("safeReturnTo", () => {
  test("accepts same-host relative paths", () => {
    expect(safeReturnTo("/")).toBe("/");
    expect(safeReturnTo("/courses")).toBe("/courses");
    expect(safeReturnTo("/courses/intro-to-biology?tab=outline#lessons")).toBe(
      "/courses/intro-to-biology?tab=outline#lessons"
    );
    expect(safeReturnTo("/student/courses")).toBe("/student/courses");
  });

  test("rejects absolute URLs, schemes and protocol-relative paths", () => {
    for (const value of [
      "https://evil.test/",
      "http://evil.test",
      "javascript:alert(1)",
      "data:text/html,hi",
      "//evil.test",
      "//evil.test/path",
      "/\\evil.test",
      "\\\\evil.test",
      "courses",
      "evil.test/path",
      "",
    ]) {
      expect(safeReturnTo(value), value).toBeNull();
    }
  });

  test("rejects whitespace, control characters and backslashes anywhere", () => {
    for (const value of ["/\t/evil.test", "/\n/evil.test", "/ /evil.test", "/a\\b", "/courses\u0000", "/x\u007f"]) {
      expect(safeReturnTo(value), JSON.stringify(value)).toBeNull();
    }
  });

  test("rejects non-strings, over-long values and auth pages", () => {
    expect(safeReturnTo(null)).toBeNull();
    expect(safeReturnTo(undefined)).toBeNull();
    expect(safeReturnTo(`/${"a".repeat(3000)}`)).toBeNull();
    expect(safeReturnTo("/login")).toBeNull();
    expect(safeReturnTo("/login?returnTo=/x")).toBeNull();
    expect(safeReturnTo("/register")).toBeNull();
    expect(safeReturnTo("/loginx")).toBe("/loginx");
  });

  test("withReturnTo encodes a safe path and drops an unsafe one", () => {
    expect(withReturnTo("/login", "/courses/a?b=1")).toBe("/login?returnTo=%2Fcourses%2Fa%3Fb%3D1");
    expect(withReturnTo("/login", "//evil.test")).toBe("/login");
    expect(withReturnTo("/register", null)).toBe("/register");
  });
});
