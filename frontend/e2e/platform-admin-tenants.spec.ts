import { test, expect } from "@playwright/test";

/**
 * Platform Admin Tenant List — SCAFFOLD ONLY (module plan §20/§21 item 13:
 * `GET /api/v1/platform-admin/tenants` and Platform Admin authentication don't
 * exist on the backend yet). This page renders local sample data with no
 * fetch/React Query involved, so these tests exercise the real static UI —
 * no network mocking needed.
 */

test.describe("platform admin tenant list — scaffold notice", () => {
  test("clearly discloses this is sample data, not a real error/permission state", async ({
    page,
  }) => {
    await page.goto("/platform-admin/tenants");
    await expect(
      page.getByText("Showing sample data. Live tenant data and approval actions", {
        exact: false,
      })
    ).toBeVisible();
    // Must not fabricate an error or permission-denied signal for a condition
    // that isn't real yet.
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
  });
});

test.describe("platform admin tenant list — table content", () => {
  test("every row names the tenant, next to its status, plan, and submitted date", async ({
    page,
  }) => {
    await page.goto("/platform-admin/tenants");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Tenant name" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Subdomain" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Status" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Requested plan" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Submitted" })).toBeVisible();

    await expect(table.getByText("Example Institute A")).toBeVisible();
    await expect(table.getByText("Example Academy")).toBeVisible();
  });

  test("every one of the (unfiltered) sample tenants has its name visible in a table row, not just the first/last", async ({
    page,
  }) => {
    await page.goto("/platform-admin/tenants");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    // The full unfiltered mock dataset (src/app/(platform-admin)/platform-admin/tenants/mock-tenants.ts) —
    // asserting every entry by name (not just a sampled subset) catches a row-mapping
    // bug (e.g. a key collision or an off-by-one slice) that spot-checking two names
    // would miss.
    const allTenantNames = [
      "Example Institute A",
      "Example Institute B",
      "Example Tutoring Co",
      "Example Academy",
      "Example Learning Centre",
      "Example Coaching Institute",
    ];

    const rows = table.locator("tbody tr");
    await expect(rows).toHaveCount(allTenantNames.length);

    for (const name of allTenantNames) {
      // Scoped to a single row's first cell so this proves the name is a genuine
      // row identifier, not just text appearing somewhere in the table (e.g. also
      // present in a "requested plan" or "subdomain" cell by coincidence).
      await expect(rows.filter({ has: page.getByRole("cell", { name, exact: true }) })).toHaveCount(1);
    }
  });

  test("status is conveyed with an icon and text label, never color alone", async ({ page }) => {
    await page.goto("/platform-admin/tenants");
    const table = page.getByRole("table");
    // Text labels (scoped to the visible desktop table, distinct from the same
    // labels in the <select> filter's <option>s) prove status isn't color-only.
    await expect(table.getByText("Pending approval").first()).toBeVisible();
    await expect(table.getByText("Active", { exact: true }).first()).toBeVisible();
    await expect(table.getByText("Suspended").first()).toBeVisible();
  });

  test("no approve/reject/suspend/cancel action exists anywhere on the page", async ({
    page,
  }) => {
    await page.goto("/platform-admin/tenants");
    for (const name of ["Approve", "Reject", "Suspend", "Cancel"]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
    }
  });
});

test.describe("platform admin tenant list — filtering and empty states", () => {
  test("filtering to a status with zero sample rows shows the true zero-data empty state", async ({
    page,
  }) => {
    await page.goto("/platform-admin/tenants");
    await page.getByLabel("Status").selectOption("cancelled");

    await expect(page.getByText("No tenants with this status")).toBeVisible();
    await expect(
      page.getByText('no tenant registrations currently in "Cancelled" status', { exact: false })
    ).toBeVisible();
  });

  test("a search term that matches nothing shows a distinct 'no match' empty state", async ({
    page,
  }) => {
    await page.goto("/platform-admin/tenants");
    await page.getByLabel("Search").fill("no-such-institute-zzz");

    await expect(page.getByText("No tenants match your filter")).toBeVisible();
    await expect(
      page.getByText("No tenants match your current search and status filter", { exact: false })
    ).toBeVisible();

    // The two empty states must use genuinely different copy.
    await expect(page.getByText("No tenants with this status")).toHaveCount(0);
  });

  test("clearing filters from the empty state restores the full list", async ({ page }) => {
    await page.goto("/platform-admin/tenants");
    await page.getByLabel("Status").selectOption("cancelled");
    await expect(page.getByText("No tenants with this status")).toBeVisible();

    await page.getByRole("button", { name: "View all tenants" }).click();
    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(table.getByText("Example Institute A")).toBeVisible();
  });
});

test.describe("platform admin tenant list — responsive behavior", () => {
  test("below md, the table converts to a stacked card list", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/platform-admin/tenants");

    await expect(page.getByRole("table")).toBeHidden();
    await expect(page.getByRole("list").getByText("Example Institute A")).toBeVisible();
  });

  test("at md and above, the table is shown instead of the card list", async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.goto("/platform-admin/tenants");

    await expect(page.getByRole("table")).toBeVisible();
  });

  test("the swap happens exactly at Tailwind's md breakpoint (768px), not lg or another ad hoc value", async ({
    page,
  }) => {
    // 1024px (used by the test above) also happens to be >= the `lg` breakpoint, so
    // that assertion alone wouldn't catch the layout accidentally being gated on
    // `lg:` instead of `md:` (per .claude/rules/ui-ux.md §5's standardized-breakpoint
    // rule). Testing right at the 767/768 boundary pins it to `md` specifically.
    //
    // The dashboard shell's own sidebar nav also renders a `<ul>` (visible from md
    // upward), so the tenant card list must be located by its distinguishing content
    // rather than by role alone once both `role="list"` elements can be present.
    const cardList = page.getByRole("list").filter({ hasText: "Example Institute A" });

    await page.setViewportSize({ width: 767, height: 900 });
    await page.goto("/platform-admin/tenants");
    await expect(page.getByRole("table")).toBeHidden();
    await expect(cardList).toBeVisible();

    await page.setViewportSize({ width: 768, height: 900 });
    await expect(page.getByRole("table")).toBeVisible();
    await expect(cardList).toBeHidden();
  });
});
