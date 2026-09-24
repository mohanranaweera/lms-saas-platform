import { test, expect } from "@playwright/test";
import { apiSuccess, mockJson } from "./fixtures/auth-mocks";
import { makeClassSession, mockClassSessionsApi, mockTenantSession } from "./fixtures/class-session-mocks";

/**
 * Wave 4 plan §5's explicit Tenant Admin requirement: a `providerStatus ==
 * "FAILED"` session must be visibly surfaced in the tenant-wide oversight
 * list (Fake-adapter forced-failure mode's real-world equivalent), not
 * silently hidden or indistinguishable from a healthy session — and a
 * Course Coordinator (who also holds `LIVE_CLASSES`/`CREATE_EDIT`) can
 * retry it from the same screen, while a role with no `LIVE_CLASSES` grant
 * at all never even reaches this screen's data (covered by
 * `live-class-cross-tenant.spec.ts`'s 403 case).
 */

test.describe("Tenant Admin oversight — provider failure surfacing", () => {
  test("a FAILED session is visibly, distinctly flagged in the tenant-wide list and is retryable by Tenant Admin", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/teachers*", 200, apiSuccess([{ id: "teacher-1", name: "Jane Teacher", email: "jane@example.com", approvalStatus: "APPROVED", accountStatus: "ACTIVE", approvedBy: null, approvedAt: null }]));
    const healthy = makeClassSession({ id: "s-healthy", title: "Healthy Session", providerStatus: "PROVISIONED" });
    const stuck = makeClassSession({
      id: "s-stuck",
      title: "Stuck Session",
      providerStatus: "FAILED",
      providerFailureReason: "Meeting provider request timed out",
    });
    await mockClassSessionsApi(page, { initialSessions: [healthy, stuck] });

    await page.goto("/tenant-admin/live-classes");

    const stuckRow = page.getByRole("row").filter({ hasText: "Stuck Session" }).first();
    await expect(stuckRow.getByText("Provisioning failed")).toBeVisible();

    const healthyRow = page.getByRole("row").filter({ hasText: "Healthy Session" }).first();
    await expect(healthyRow.getByText("Provisioning failed")).toHaveCount(0);
    await expect(healthyRow.getByText("Meeting ready")).toBeVisible();

    // Filtering by meeting status to "Provisioning failed" narrows the list
    // to the stuck session only — the distinct filterability plan §5
    // requires so a Tenant Admin can find a stuck provisioning without
    // digging through every Teacher's own screens.
    await page.getByLabel("Meeting status", { exact: true }).click();
    await page.getByRole("option", { name: "Provisioning failed" }).click();
    // `DataTable` renders both the desktop `<table>` and the mobile card
    // `<ul>` simultaneously (CSS-swapped per breakpoint) — scope to the
    // table to avoid an ambiguous multi-match on plain `getByText`.
    await expect(page.getByRole("table").getByText("Stuck Session")).toBeVisible();
    await expect(page.getByRole("table").getByText("Healthy Session")).toHaveCount(0);

    await page.getByLabel("Meeting status", { exact: true }).click();
    await page.getByRole("option", { name: "All meeting statuses" }).click();

    await stuckRow.getByRole("button", { name: "Retry provisioning" }).click();
    await expect(stuckRow.getByText("Meeting ready")).toBeVisible();
    await expect(stuckRow.getByRole("button", { name: "Retry provisioning" })).toHaveCount(0);
  });

  test("Course Coordinator (LIVE_CLASSES/CREATE_EDIT) sees the same Retry action Tenant Admin does", async ({
    page,
  }) => {
    await mockTenantSession(page, "COURSE_COORDINATOR");
    await mockJson(page, "**/v1/teachers*", 200, apiSuccess([]));
    const stuck = makeClassSession({ id: "s-stuck", title: "Stuck Session", providerStatus: "FAILED" });
    await mockClassSessionsApi(page, { initialSessions: [stuck] });

    await page.goto("/tenant-admin/live-classes");

    await expect(page.getByRole("button", { name: "Retry provisioning" })).toBeVisible();
  });
});
