import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Wave 10 - device controls and access policy UI. No real backend runs here:
 * every endpoint is intercepted with `page.route()`. The backend remains the
 * only authority; these specs check the UI reflects its answers, sends the
 * right requests, and hides staff-only actions from roles without them.
 */

const STUDENT = {
  id: "11111111-1111-1111-1111-111111111111",
  name: "Ada Lovelace",
  email: "ada@example-institute.test",
  roleCode: "STUDENT",
  status: "ACTIVE",
};

const NOW = Date.now();
const iso = (offsetMs: number) => new Date(NOW + offsetMs).toISOString();
const DAY = 24 * 60 * 60 * 1000;

function device(overrides: Record<string, unknown>) {
  return {
    id: "d0000000-0000-0000-0000-000000000001",
    label: "Chrome on Windows",
    status: "ACTIVE",
    firstSeenAt: iso(-10 * DAY),
    lastSeenAt: iso(-DAY),
    lastIp: "203.0.113.10",
    revokedAt: null,
    revocationSource: null,
    revokeReason: null,
    slotReusableAt: null,
    current: false,
    ...overrides,
  };
}

function summary(overrides: Record<string, unknown> = {}) {
  return {
    devices: [
      device({ id: "d1", label: "Chrome on Windows", current: true }),
      device({ id: "d2", label: "Safari on iOS" }),
    ],
    limit: 2,
    limitSource: "TENANT",
    occupiedSlots: 2,
    nextSlotFreeAt: null,
    studentOverride: null,
    studentOverrideReason: null,
    selfServiceEnabled: false,
    resetCooldownHours: 24,
    ...overrides,
  };
}

async function mockTenantSession(page: Page, role: string): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/notifications/unread-count", 200, apiSuccess({ unreadCount: 0 }));
}

// ---------------------------------------------------------------------------
// Login
// ---------------------------------------------------------------------------

test.describe("login - device limit", () => {
  test("a refused new device shows the backend message under a device-limit title", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/auth/login",
      403,
      apiError(
        "DEVICE_LIMIT_REACHED",
        "You have reached the maximum of 2 devices for your account. Remove a device you no longer use or ask your institute to reset one."
      )
    );
    await page.goto("/login");
    await page.getByLabel("Email").fill("student@example.com");
    await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Sign in" }).click();

    const alert = page.getByRole("alert").filter({ hasText: "Device limit reached" });
    await expect(alert).toBeVisible();
    await expect(alert).toContainText("maximum of 2 devices");
    await expect(page).toHaveURL(/\/login$/);
  });
});

// ---------------------------------------------------------------------------
// Student - My Devices
// ---------------------------------------------------------------------------

test.describe("student - My Devices", () => {
  test("lists own devices, marks this device, and explains institute-managed removal", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/devices/my", 200, apiSuccess(summary()));
    await page.goto("/student/devices");

    await expect(page.getByRole("heading", { name: "My devices" })).toBeVisible();
    await expect(page.getByText("2 of 2 device slots in use")).toBeVisible();
    await expect(page.getByText("This device").first()).toBeVisible();
    await expect(page.getByText("Your institute manages your devices.")).toBeVisible();
    await expect(page.getByRole("button", { name: /Remove/ })).toHaveCount(0);
  });

  test("with self-service on, another device can be removed; the current one cannot", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/devices/my", 200, apiSuccess(summary({ selfServiceEnabled: true })));
    let revoked = "";
    await page.route("**/v1/devices/my/*/revoke", async (route) => {
      revoked = route.request().url();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess(
            summary({
              selfServiceEnabled: true,
              devices: [
                device({ id: "d1", current: true }),
                device({
                  id: "d2",
                  label: "Safari on iOS",
                  status: "REVOKED",
                  revokedAt: iso(0),
                  revocationSource: "STUDENT",
                  slotReusableAt: iso(DAY),
                }),
              ],
            })
          )
        ),
      });
    });
    await page.goto("/student/devices");

    await expect(page.getByRole("button", { name: "Remove Chrome on Windows" })).toHaveCount(0);
    await page.getByRole("button", { name: "Remove Safari on iOS" }).first().click();
    await page.getByRole("button", { name: "Remove device" }).click();

    await expect(page.getByText("Safari on iOS was removed.")).toBeVisible();
    expect(revoked).toContain("/v1/devices/my/d2/revoke");
    await expect(page.getByText("Removed - slot cooling down").first()).toBeVisible();
  });

  test("an error state when the device list cannot load", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/devices/my", 500, apiError("INTERNAL_ERROR", "Something went wrong."));
    await page.goto("/student/devices");
    await expect(page.getByRole("alert").first()).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Tenant Admin - Student detail Devices tab
// ---------------------------------------------------------------------------

async function gotoDevicesTab(page: Page, role: string) {
  await mockTenantSession(page, role);
  await mockJson(page, `**/v1/students/${STUDENT.id}`, 200, apiSuccess(STUDENT));
  await mockJson(page, `**/v1/students/${STUDENT.id}/devices`, 200, apiSuccess(summary()));
  // VIDEO Phase 8: the tab also lists the student's AOC Players.
  await mockJson(page, `**/v1/students/${STUDENT.id}/player-installations`, 200, apiSuccess([]));
  await page.goto(`/tenant-admin/students/${STUDENT.id}?tab=devices`);
  await expect(page.getByRole("heading", { name: STUDENT.name })).toBeVisible();
}

test.describe("tenant admin - student Devices tab", () => {
  test("a reset needs a reason and sends it to the backend", async ({ page }) => {
    await gotoDevicesTab(page, "TENANT_ADMIN");
    let body: unknown = null;
    await page.route(`**/v1/students/${STUDENT.id}/devices/d2/reset`, async (route) => {
      body = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(device({ id: "d2", status: "REVOKED", revokedAt: iso(0) }))),
      });
    });

    await expect(page.getByText("Last IP").first()).toBeVisible();
    await page.getByRole("button", { name: "Reset Safari on iOS" }).first().click();
    await page.getByRole("button", { name: "Reset device" }).click();
    await expect(page.getByText("A reason is required.")).toBeVisible();

    await page.getByRole("alertdialog").getByLabel(/Reason/).fill("Student replaced the phone");
    await page.getByRole("button", { name: "Reset device" }).click();
    await expect(page.getByText("Safari on iOS was reset.")).toBeVisible();
    expect(body).toEqual({ reason: "Student replaced the phone" });
  });

  test("a device-limit exception is saved with a reason", async ({ page }) => {
    await gotoDevicesTab(page, "TENANT_ADMIN");
    let body: unknown = null;
    await page.route(`**/v1/students/${STUDENT.id}/device-limit`, async (route) => {
      body = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(summary({ limit: 3, limitSource: "STUDENT", studentOverride: 3 }))),
      });
    });

    await page.getByLabel("Devices allowed").fill("3");
    await page.getByLabel(/Reason/).last().fill("Shared family computer");
    await page.getByRole("button", { name: "Save exception" }).click();
    await expect(page.getByText("This student may now use 3 devices.")).toBeVisible();
    expect(body).toEqual({ maxDevices: 3, reason: "Shared family computer" });
  });

  test("a read-only auditor sees devices but no reset or exception controls", async ({ page }) => {
    await gotoDevicesTab(page, "READ_ONLY_AUDITOR");
    await expect(page.getByText("Safari on iOS").first()).toBeVisible();
    await expect(page.getByRole("button", { name: /Reset/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Save exception" })).toHaveCount(0);
  });

  test("a finance user has no Devices tab", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockJson(page, `**/v1/students/${STUDENT.id}`, 200, apiSuccess(STUDENT));
    await page.goto(`/tenant-admin/students/${STUDENT.id}`);
    await expect(page.getByRole("heading", { name: STUDENT.name })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Devices" })).toHaveCount(0);
  });
});

// ---------------------------------------------------------------------------
// Tenant Admin - Extend access
// ---------------------------------------------------------------------------

test.describe("tenant admin - extend access", () => {
  const expiredRow = {
    enrollmentId: "e0000000-0000-0000-0000-000000000001",
    courseId: "c0000000-0000-0000-0000-000000000001",
    current: true,
    activatedAt: iso(-60 * DAY),
    accessExpiresAt: iso(-2 * DAY),
    supersededAt: null,
    revokedAt: null,
    revokeReason: null,
    reactivatedFromEnrollmentId: null,
    effectiveExpiresAt: iso(-2 * DAY),
    accessEndsAt: iso(-2 * DAY),
    extended: false,
    accessActive: false,
  };

  test("an expired enrollment can be extended with a date and reason", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${STUDENT.id}`, 200, apiSuccess(STUDENT));
    await mockJson(page, `**/v1/students/${STUDENT.id}/enrollments`, 200, apiSuccess([expiredRow]));
    let sent: { newExpiresAt: string; reason: string } | null = null;
    await page.route(`**/v1/enrollments/${expiredRow.enrollmentId}/extensions`, async (route) => {
      if (route.request().method() === "GET") {
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
        return;
      }
      const request = route.request().postDataJSON() as { newExpiresAt: string; reason: string };
      sent = request;
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            id: "x1",
            enrollmentId: expiredRow.enrollmentId,
            previousExpiresAt: expiredRow.accessExpiresAt,
            newExpiresAt: request.newExpiresAt,
            reason: request.reason,
            grantedBy: "admin",
            grantedAt: iso(0),
          })
        ),
      });
    });
    await page.goto(`/tenant-admin/students/${STUDENT.id}?tab=enrollments`);

    await expect(page.getByText("Access ended").first()).toBeVisible();
    await page.getByRole("button", { name: /Extend access to/ }).first().click();
    await expect(page.getByText(/restores access now, without a new payment/)).toBeVisible();
    const future = new Date(NOW + 30 * DAY).toISOString().slice(0, 10);
    await page.getByLabel("New end date").fill(future);
    await page.getByLabel(/Reason/).fill("Medical leave");
    await page.getByRole("button", { name: "Extend access", exact: true }).click();

    await expect(page.getByText(/now runs until/)).toBeVisible();
    expect(sent).not.toBeNull();
    expect(sent!.reason).toBe("Medical leave");
    expect(new Date(sent!.newExpiresAt).getTime()).toBeGreaterThan(NOW);
  });

  test("Student Support sees the enrollment but not the Extend action", async ({ page }) => {
    await mockTenantSession(page, "STUDENT_SUPPORT");
    await mockJson(page, `**/v1/students/${STUDENT.id}`, 200, apiSuccess(STUDENT));
    await mockJson(page, `**/v1/students/${STUDENT.id}/enrollments`, 200, apiSuccess([expiredRow]));
    await page.goto(`/tenant-admin/students/${STUDENT.id}?tab=enrollments`);
    await expect(page.getByText("Access ended").first()).toBeVisible();
    await expect(page.getByRole("button", { name: /Extend access to/ })).toHaveCount(0);
  });
});

// ---------------------------------------------------------------------------
// Tenant Admin - Settings, Devices & access
// ---------------------------------------------------------------------------

test.describe("tenant admin - Devices & access settings", () => {
  async function gotoSettings(page: Page, role = "TENANT_ADMIN") {
    await mockTenantSession(page, role);
    await mockJson(
      page,
      "**/v1/device-policy",
      200,
      apiSuccess({
        maxDevices: null,
        effectiveMaxDevices: 2,
        planDefaultLimit: 2,
        resetCooldownHours: 24,
        studentSelfServiceEnabled: false,
        updatedAt: null,
      })
    );
    await mockJson(page, "**/v1/access-policy", 200, apiSuccess({ gracePeriodDays: 0, updatedAt: null }));
    await mockJson(page, "**/v1/access-policy/courses", 200, apiSuccess([]));
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));
    await page.goto("/tenant-admin/settings/devices-access");
    await expect(page.getByRole("heading", { name: "Devices & access" })).toBeVisible();
  }

  test("shows the plan default, validates, and saves the device policy", async ({ page }) => {
    await gotoSettings(page);
    let body: unknown = null;
    await page.route("**/v1/device-policy", async (route) => {
      if (route.request().method() !== "PUT") {
        await route.fallback();
        return;
      }
      body = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            maxDevices: 3,
            effectiveMaxDevices: 3,
            planDefaultLimit: 2,
            resetCooldownHours: 12,
            studentSelfServiceEnabled: true,
            updatedAt: iso(0),
          })
        ),
      });
    });

    await expect(page.getByText(/2 devices per\s+student \(plan default\)/)).toBeVisible();
    await page.getByLabel("Devices per student").fill("0");
    await page.getByRole("button", { name: "Save device policy" }).click();
    await expect(page.getByText("Device limit must be between 1 and 20.")).toBeVisible();

    await page.getByLabel("Devices per student").fill("3");
    await page.getByLabel("Reset cooldown (hours)").fill("12");
    await page.getByLabel(/Let students remove their own devices/).check();
    await page.getByRole("button", { name: "Save device policy" }).click();
    await expect(page.getByText("Device policy saved.")).toBeVisible();
    expect(body).toEqual({ maxDevices: 3, resetCooldownHours: 12, studentSelfServiceEnabled: true });
  });

  test("an empty course-override list explains what an override is for", async ({ page }) => {
    await gotoSettings(page);
    await expect(page.getByText("No course overrides")).toBeVisible();
    await expect(page.getByRole("button", { name: "Save override" })).toBeVisible();
  });

  test("a read-only auditor sees the policy without save buttons", async ({ page }) => {
    await gotoSettings(page, "READ_ONLY_AUDITOR");
    await expect(page.getByLabel("Devices per student")).toBeDisabled();
    await expect(page.getByRole("button", { name: /^Save/ })).toHaveCount(0);
  });
});
