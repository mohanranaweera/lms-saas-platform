import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * TUTE-01 Tute Dispatch queue (`/tenant-admin/tute-dispatch`): sort/filter
 * params, the page / all-matching selection model, label printing (both
 * request shapes, missing-address confirm, stale 409) and Confirm dispatch.
 * No real backend: `/v1/tute-dispatch/**` and `/v1/me/permissions` are an
 * in-memory fake shaped like `DispatchQueueView` etc. Synthetic data only.
 */

const COURSE_A = "c0000000-0000-0000-0000-00000000000a";
const COURSE_B = "c0000000-0000-0000-0000-00000000000b";
const TUTE_A1 = "70000000-0000-0000-0000-0000000000a1";
const TUTE_B1 = "70000000-0000-0000-0000-0000000000b1";

const ALL_CAPS = ["TUTE_DISPATCH_VIEW", "TUTE_DISPATCH_PRINT_LABELS", "TUTE_UPDATE_STATUS", "TUTE_VIEW_TUTES"];

const FILTER_OPTIONS = {
  courses: [
    { id: COURSE_A, name: "Physics 2027" },
    { id: COURSE_B, name: "Chemistry 2027" },
  ],
  tutes: [
    { id: TUTE_A1, name: "Unit 1 – Mechanics", courseId: COURSE_A },
    { id: TUTE_B1, name: "Unit 1 – Atoms", courseId: COURSE_B },
  ],
};

/** 30 recipient groups; every 10th has no address; row 2 has two tutes. */
function rows() {
  return Array.from({ length: 30 }, (_, i) => {
    const n = i + 1;
    const index = String(n).padStart(4, "0");
    const items = [
      { fulfillmentId: `f-${n}-a`, tuteId: TUTE_A1, tuteName: "Unit 1 – Mechanics", courseId: COURSE_A, courseName: "Physics 2027", label: "PHYSICS 2027 – Unit 1 – Mechanics" },
    ];
    if (n === 2) {
      items.push({ fulfillmentId: "f-2-b", tuteId: TUTE_B1, tuteName: "Unit 1 – Atoms", courseId: COURSE_B, courseName: "Chemistry 2027", label: "CHEMISTRY 2027 – Unit 1 – Atoms" });
    }
    const addressMissing = n % 10 === 0;
    return {
      groupKey: `g-${n}`,
      studentId: `s-${n}`,
      studentProfileId: `p-${n}`,
      indexNumber: index,
      studentName: `Sample Student ${index}`,
      city: addressMissing ? null : "Galle",
      addressMissing,
      tuteCount: items.length,
      items,
    };
  });
}

interface Options {
  role?: string;
  capabilities?: string[];
  queueStatus?: number;
  labelResponses?: Array<{ status: number; body?: unknown }>;
  confirmResponses?: Array<{ status: number; body: unknown }>;
}

interface Log {
  queueQueries: URLSearchParams[];
  labelBodies: unknown[];
  confirmBodies: unknown[];
}

async function fulfill(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function errorWithDetails(code: string, message: string, details: Record<string, unknown>) {
  return { ...apiError(code, message), error: { code, message, fieldErrors: [], details } };
}

async function setUp(pw: Page, options: Options = {}): Promise<Log> {
  const role = options.role ?? "TENANT_ADMIN";
  const log: Log = { queueQueries: [], labelBodies: [], confirmBodies: [] };
  const labelResponses = [...(options.labelResponses ?? [])];
  const confirmResponses = [...(options.confirmResponses ?? [])];
  const all = rows();

  await pw.route(/\/v1\/(me\/permissions|tute-dispatch)(\/|\?|$)/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/v1\//, "/");
    if (path === "/me/permissions") {
      return fulfill(route, 200, apiSuccess({ role, capabilities: options.capabilities ?? ALL_CAPS, permissionVersion: 1 }));
    }
    if (path === "/tute-dispatch/filter-options") return fulfill(route, 200, apiSuccess(FILTER_OPTIONS));
    if (path === "/tute-dispatch/queue") {
      log.queueQueries.push(url.searchParams);
      if (options.queueStatus === 403) {
        return fulfill(route, 403, apiError("FORBIDDEN", "You do not have permission to view the dispatch queue."));
      }
      const q = (url.searchParams.get("search") ?? "").toLowerCase();
      const twoPlus = url.searchParams.get("twoPlus") === "true";
      let filtered = all.filter((row) => (!q || row.studentName.toLowerCase().includes(q)) && (!twoPlus || row.tuteCount >= 2));
      if (url.searchParams.get("direction") === "desc") filtered = [...filtered].reverse();
      const size = Number(url.searchParams.get("size") ?? "25");
      const pageIndex = Number(url.searchParams.get("page") ?? "0");
      return fulfill(
        route,
        200,
        apiSuccess({
          content: filtered.slice(pageIndex * size, pageIndex * size + size),
          page: pageIndex,
          size,
          totalElements: filtered.length,
          totalPages: Math.ceil(filtered.length / size),
          missingAddressCount: filtered.filter((row) => row.addressMissing).length,
          itemCount: filtered.reduce((sum, row) => sum + row.tuteCount, 0),
        })
      );
    }
    if (path === "/tute-dispatch/labels") {
      log.labelBodies.push(request.postDataJSON());
      const next = labelResponses.shift();
      if (next && next.status !== 200) return fulfill(route, next.status, next.body);
      return route.fulfill({
        status: 200,
        contentType: "application/pdf",
        headers: { "X-Label-Count": "1", "Content-Disposition": 'attachment; filename="tute-dispatch-labels.pdf"' },
        body: "%PDF-1.4 fake",
      });
    }
    if (path === "/tute-dispatch/confirm") {
      const body = request.postDataJSON();
      log.confirmBodies.push(body);
      const next = confirmResponses.shift();
      if (next) return fulfill(route, next.status, next.body);
      return fulfill(route, 200, apiSuccess({ dispatched: body.fulfillmentIds.length, alreadyDispatched: 0, dispatchedIds: body.fulfillmentIds, alreadyDispatchedIds: [] }));
    }
    return route.fallback();
  });
  await mockJson(pw, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  return log;
}

async function openQueue(pw: Page) {
  await pw.goto("/tenant-admin/tute-dispatch");
  await expect(pw.getByRole("heading", { level: 1, name: "Tute Dispatch" })).toBeVisible();
  await expect(pw.getByTestId("dispatch-row").first()).toBeVisible();
}

const rowByIndex = (pw: Page, index: string) => pw.getByTestId("dispatch-row").filter({ hasText: `Sample Student ${index}` });

test.describe("Tute Dispatch queue", () => {
  test("renders Klass columns, chips, profile links and missing-address warnings", async ({ page: pw }) => {
    await setUp(pw);
    await openQueue(pw);
    const table = pw.getByTestId("dispatch-table");
    for (const header of ["Index Number", "Student Name", "Tutes", "To Send", "City"]) {
      await expect(table.getByRole("columnheader", { name: header })).toBeVisible();
    }
    await expect(table.getByRole("columnheader", { name: "Index Number" })).toHaveAttribute("aria-sort", "ascending");
    const second = rowByIndex(pw, "0002");
    await expect(second.getByRole("link", { name: "0002" })).toHaveAttribute("href", "/tenant-admin/students/p-2");
    await expect(second.getByTestId("dispatch-chip")).toHaveText(["PHYSICS 2027 – Unit 1 – Mechanics", "CHEMISTRY 2027 – Unit 1 – Atoms"]);
    await expect(rowByIndex(pw, "0010").getByTestId("dispatch-no-address")).toHaveText("No address");
    await expect(pw.getByRole("button", { name: "Print all 30 labels (PDF)" })).toBeVisible();
    await expect(pw.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Tute Dispatch" })).toBeVisible();
  });

  test("sort and filter params are sent; tute options narrow by course; clearing course clears tute", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openQueue(pw);
    expect(Object.fromEntries(log.queueQueries[0])).toMatchObject({ sort: "indexNumber", direction: "asc", page: "0", size: "25" });

    await pw.getByTestId("dispatch-sort-studentName").click();
    await expect(pw.getByRole("columnheader", { name: "Student Name" })).toHaveAttribute("aria-sort", "ascending");
    await expect.poll(() => log.queueQueries.at(-1)?.get("sort")).toBe("studentName");
    await pw.getByTestId("dispatch-sort-studentName").click();
    await expect(pw.getByRole("columnheader", { name: "Student Name" })).toHaveAttribute("aria-sort", "descending");
    await expect.poll(() => log.queueQueries.at(-1)?.get("direction")).toBe("desc");
    await expect(pw.getByRole("columnheader", { name: "Index Number" })).toHaveAttribute("aria-sort", "none");

    const course = pw.getByLabel("Course");
    const tuteSelect = pw.getByLabel("Tute", { exact: true });
    await expect(tuteSelect.locator("option")).toHaveCount(3);
    await course.selectOption(COURSE_A);
    await expect(tuteSelect.locator("option")).toHaveText(["All tutes of this course", "Unit 1 – Mechanics"]);
    await tuteSelect.selectOption(TUTE_A1);
    await expect.poll(() => Object.fromEntries(log.queueQueries.at(-1) ?? [])).toMatchObject({ courseId: COURSE_A, tuteId: TUTE_A1 });
    await course.selectOption("");
    await expect(tuteSelect).toHaveValue("");
    await expect(tuteSelect.locator("option")).toHaveCount(3);

    // The next request (2+ only) carries neither course nor tute.
    await pw.getByRole("switch", { name: "2+ tutes only" }).click();
    await expect.poll(() => log.queueQueries.at(-1)?.get("twoPlus")).toBe("true");
    expect(log.queueQueries.at(-1)?.has("courseId")).toBe(false);
    expect(log.queueQueries.at(-1)?.has("tuteId")).toBe(false);
    await expect(pw.getByTestId("dispatch-row")).toHaveCount(1);

    await pw.getByLabel("Search").fill("zzz");
    await expect(pw.getByTestId("dispatch-empty")).toContainText("No matches for these filters");
    expect(log.queueQueries.at(-1)?.get("search")).toBe("zzz");
  });

  test("page selection, select all matching, and clearing on filter change", async ({ page: pw }) => {
    await setUp(pw);
    await openQueue(pw);
    const header = pw.getByRole("checkbox", { name: "Select all on this page" });
    await rowByIndex(pw, "0001").getByRole("checkbox", { name: "Select Sample Student 0001 (0001)" }).check();
    expect(await header.evaluate((el) => (el as HTMLInputElement).indeterminate)).toBe(true);
    await expect(pw.getByRole("button", { name: "Print 1 selected label (PDF)" })).toBeVisible();

    await header.check();
    const banner = pw.getByTestId("dispatch-selection-banner");
    await expect(banner).toContainText("All 25 on this page selected.");
    await banner.getByRole("button", { name: "Select all 30 matching" }).click();
    await expect(banner).toContainText("All 30 matching selected.");
    await expect(pw.getByRole("button", { name: "Print all 30 labels (PDF)" })).toBeVisible();
    await expect(pw.getByRole("button", { name: "Confirm dispatch" })).toBeDisabled();
    await banner.getByRole("button", { name: "Clear selection" }).click();
    await expect(banner).toHaveCount(0);

    // Selection persists across pages.
    await rowByIndex(pw, "0003").getByRole("checkbox").check();
    await pw.getByRole("button", { name: "Next page" }).click();
    await rowByIndex(pw, "0026").getByRole("checkbox").check();
    await expect(pw.getByRole("button", { name: "Print 2 selected labels (PDF)" })).toBeVisible();
    await pw.getByRole("button", { name: "Previous page" }).click();
    await expect(rowByIndex(pw, "0003").getByRole("checkbox")).toBeChecked();

    // Any filter change clears it and says so.
    await pw.getByRole("switch", { name: "2+ tutes only" }).click();
    await expect(pw.getByRole("status").filter({ hasText: "Selection cleared because the filters changed." })).toHaveCount(1);
    await expect(pw.getByRole("button", { name: "Print all 1 label (PDF)" })).toBeVisible();
  });

  test("print selected rows: missing-address confirm, fulfillment ids body and PDF download", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openQueue(pw);
    await rowByIndex(pw, "0002").getByRole("checkbox").check();
    await rowByIndex(pw, "0010").getByRole("checkbox").check();
    await pw.getByRole("button", { name: "Print 2 selected labels (PDF)" }).click();
    const warn = pw.getByTestId("dispatch-missing-address-dialog");
    await expect(warn).toContainText("1 label has no address and will print “No address provided”");
    const download = pw.waitForEvent("download");
    await warn.getByRole("button", { name: "Print anyway" }).click();
    expect((await download).suggestedFilename()).toBe("tute-dispatch-labels.pdf");
    expect(log.labelBodies[0]).toEqual({ fulfillmentIds: ["f-2-a", "f-2-b", "f-10-a"] });
  });

  test("print all matching sends the filters and the expected label count", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openQueue(pw);
    await pw.getByLabel("Search").fill("Sample");
    await expect.poll(() => log.queueQueries.at(-1)?.get("search")).toBe("Sample");
    await pw.getByRole("checkbox", { name: "Select all on this page" }).check();
    await pw.getByRole("button", { name: "Select all 30 matching" }).click();
    await pw.getByRole("button", { name: "Print all 30 labels (PDF)" }).click();
    await expect(pw.getByTestId("dispatch-missing-address-dialog")).toContainText("3 labels have no address");
    await pw.getByRole("button", { name: "Print anyway" }).click();
    await expect.poll(() => log.labelBodies.length).toBe(1);
    expect(log.labelBodies[0]).toEqual({
      allFiltered: true,
      filters: { search: "Sample", courseId: null, tuteId: null, twoPlus: false },
      expectedLabelCount: 30,
    });
  });

  test("a stale selection (409) refreshes the list and clears the selection", async ({ page: pw }) => {
    const log = await setUp(pw, {
      labelResponses: [{ status: 409, body: apiError("TUTE_SELECTION_STALE", "Selection is stale.") }],
    });
    await openQueue(pw);
    await rowByIndex(pw, "0001").getByRole("checkbox").check();
    const before = log.queueQueries.length;
    await pw.getByRole("button", { name: "Print 1 selected label (PDF)" }).click();
    await expect(pw.getByTestId("admin-toast")).toContainText("The list changed — refreshed, please review your selection");
    await expect(rowByIndex(pw, "0001").getByRole("checkbox")).not.toBeChecked();
    await expect(pw.getByRole("button", { name: "Print all 30 labels (PDF)" })).toBeVisible();
    await expect.poll(() => log.queueQueries.length).toBeGreaterThan(before);
  });

  test("confirm dispatch: explains printing, sends ids, reports results and missing addresses", async ({ page: pw }) => {
    const log = await setUp(pw, {
      confirmResponses: [
        { status: 422, body: errorWithDetails("TUTE_ADDRESS_MISSING", "Address missing.", { fulfillmentIds: ["f-10-a"] }) },
      ],
    });
    await openQueue(pw);
    await expect(pw.getByRole("button", { name: "Confirm dispatch" })).toBeDisabled();
    await rowByIndex(pw, "0002").getByRole("checkbox").check();
    await rowByIndex(pw, "0010").getByRole("checkbox").check();
    await pw.getByRole("button", { name: "Confirm dispatch" }).click();
    const dialog = pw.getByTestId("dispatch-confirm-dialog");
    await expect(dialog).toContainText("Printing does not mark anything dispatched. Confirm that these 3 tute copies have been sent.");
    await dialog.getByRole("button", { name: "Confirm dispatch" }).click();
    await expect(dialog.getByTestId("dispatch-confirm-error")).toContainText("1 selected copy has no delivery address");

    await dialog.getByRole("button", { name: "Cancel" }).click();
    await rowByIndex(pw, "0010").getByRole("checkbox").uncheck();
    await pw.getByRole("button", { name: "Confirm dispatch" }).click();
    await dialog.getByRole("button", { name: "Confirm dispatch" }).click();
    await expect(pw.getByTestId("admin-toast")).toContainText("2 copies marked as dispatched");
    expect(log.confirmBodies[1]).toEqual({ fulfillmentIds: ["f-2-a", "f-2-b"] });
    await expect(pw.getByRole("button", { name: "Print all 30 labels (PDF)" })).toBeVisible();
  });

  test("view-only: no Print or Confirm buttons", async ({ page: pw }) => {
    await setUp(pw, { role: "OPERATOR", capabilities: ["TUTE_DISPATCH_VIEW"] });
    await openQueue(pw);
    await expect(pw.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Tute Dispatch" })).toBeVisible();
    await expect(pw.getByTestId("dispatch-print")).toHaveCount(0);
    await expect(pw.getByTestId("dispatch-confirm")).toHaveCount(0);
  });

  test("without TUTE_DISPATCH_VIEW the nav item is hidden and a 403 shows permission denied", async ({ page: pw }) => {
    await setUp(pw, { role: "OPERATOR", capabilities: ["STUDENTS_VIEW"], queueStatus: 403 });
    await pw.goto("/tenant-admin/tute-dispatch");
    await expect(pw.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(pw.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Students", exact: true })).toBeVisible();
    await expect(pw.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Tute Dispatch" })).toHaveCount(0);
  });

  test("at 375px the table scrolls inside its card without page horizontal scroll", async ({ page: pw }) => {
    await pw.setViewportSize({ width: 375, height: 800 });
    await setUp(pw);
    await openQueue(pw);
    const overflow = await pw.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    const scroller = pw.getByTestId("dispatch-table-scroll");
    expect(await scroller.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
  });

  test("teacher portal: Tute Dispatch only when granted, index numbers are not profile links", async ({ page: pw }) => {
    await setUp(pw, { role: "TEACHER", capabilities: ["TUTE_DISPATCH_VIEW"] });
    await pw.goto("/teacher/tute-dispatch");
    await expect(pw.getByRole("heading", { level: 1, name: "Tute Dispatch" })).toBeVisible();
    await expect(pw.getByRole("link", { name: "Tute Dispatch" })).toBeVisible();
    await expect(rowByIndex(pw, "0002").getByRole("link", { name: "0002" })).toHaveCount(0);
    await expect(rowByIndex(pw, "0002")).toContainText("0002");
    await expect(pw.getByTestId("dispatch-print")).toHaveCount(0);
  });

  test("teacher portal without a grant has no Tute Dispatch link", async ({ page: pw }) => {
    await setUp(pw, { role: "TEACHER", capabilities: [] });
    await pw.goto("/teacher/tute-dispatch");
    await expect(pw.getByRole("link", { name: "Courses", exact: true }).first()).toBeVisible();
    await expect(pw.getByRole("link", { name: "Tute Dispatch" })).toHaveCount(0);
  });
});
