import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  mockJson,
  mockStudentRegistrationPolicy,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * SET-CFG-01 — Settings → Configs (Klass "Configs"): GA4 measurement id,
 * self-enrollment and SMS switches, the gtag loader and the hidden Enroll
 * button. Mock-only: validation, BRANDING_SETTINGS enforcement, the 409
 * SELF_ENROLLMENT_DISABLED gate, the SMS skip and host-tenant scoping are
 * proven by the backend integration tests (`TenantAppearanceIntegrationTest`,
 * `ParticipationAndLessonAccessIntegrationTest`,
 * `CommunicationsBroadcastIntegrationTest`). All institute data is invented.
 */

type Property = { key: string; value: unknown; type: string; defaultValue: unknown; sensitive: boolean };

const GA_ID = "G-ABC123XYZ9";

const HOME = {
  publicPageEnabled: true,
  instituteName: "Northwind Academy",
  logoUrl: null,
  faviconUrl: null,
  homepageUrl: null,
  subHeroEnabled: false,
  ctaEnabled: false,
  supportVideoUrl: null,
  desktopBannerUrls: [],
  mobileBannerUrls: [],
  featuredCourses: [],
  theme: null,
  logoDisplaySize: "STANDARD",
  teacherAutoRotate: false,
  supportEmail: null,
  supportPhone: null,
  institute: null,
  policies: { termsOfService: false, refundPolicy: false, privacyPolicy: false },
  analytics: { gaMeasurementId: null as string | null },
  studentOptions: { selfEnrollmentEnabled: true },
};

/** Never let a test reach Google; the request itself is what the gtag tests assert. */
async function blockGoogle(page: Page): Promise<string[]> {
  const requested: string[] = [];
  await page.route("https://www.googletagmanager.com/**", async (route) => {
    requested.push(route.request().url());
    await route.fulfill({ status: 200, contentType: "application/javascript", body: "" });
  });
  return requested;
}

async function mockHome(page: Page, overrides: Partial<typeof HOME> = {}) {
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess({ ...HOME, ...overrides }));
}

function domainMock(page: Page, domain: string, initial: Property[], puts: Record<string, unknown>[]) {
  let properties = initial;
  return page.route(`**/api/v1/tenant-config/${domain}`, async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      puts.push({ domain, ...body });
      properties = properties.map((entry) => (entry.key in body ? { ...entry, value: body[entry.key] } : entry));
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(properties)) });
  });
}

async function mockAdmin(page: Page, role = "TENANT_ADMIN", gaId: string | null = null) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  const puts: Record<string, unknown>[] = [];
  await domainMock(
    page,
    "GENERAL",
    [
      { key: "institute_name", value: "Northwind Academy", type: "STRING", defaultValue: null, sensitive: false },
      { key: "ga_measurement_id", value: gaId, type: "STRING", defaultValue: null, sensitive: false },
    ],
    puts
  );
  await domainMock(
    page,
    "STUDENT",
    [{ key: "self_enrollment_enabled", value: null, type: "BOOLEAN", defaultValue: true, sensitive: false }],
    puts
  );
  await domainMock(
    page,
    "NOTIFICATION",
    [{ key: "sms_enabled", value: null, type: "BOOLEAN", defaultValue: true, sensitive: false }],
    puts
  );
  return puts;
}

test.describe("Settings → Configs", () => {
  test("tenant admin saves a GA id (normalized) and clears it; invalid ids never reach the server", async ({ page }) => {
    const puts = await mockAdmin(page);
    await page.goto("/tenant-admin/settings/configs");

    await expect(
      page.getByRole("navigation", { name: "Settings sections" }).getByRole("link", { name: "Configs" })
    ).toHaveAttribute("aria-current", "page");

    const field = page.getByLabel("Google Analytics measurement ID");
    await field.fill("UA-12345-1");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByText('Enter a Google Analytics 4 measurement ID, e.g. "G-ABC123XYZ9".')).toBeVisible();
    expect(puts).toEqual([]);

    await field.fill("  g-abc123xyz9 ");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByText("Google Analytics measurement ID saved.")).toBeVisible();
    await expect(field).toHaveValue(GA_ID);

    await field.fill("");
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByText("Google Analytics removed.")).toBeVisible();
    expect(puts).toEqual([
      { domain: "GENERAL", ga_measurement_id: GA_ID },
      { domain: "GENERAL", ga_measurement_id: null },
    ]);
  });

  test("a backend 400 on the GA id is shown on the field", async ({ page }) => {
    await mockAdmin(page);
    await page.route("**/api/v1/tenant-config/GENERAL", async (route) => {
      if (route.request().method() === "PUT") {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify(apiError("VALIDATION_FAILED", "Invalid configuration value")),
        });
        return;
      }
      await route.fallback();
    });
    await page.goto("/tenant-admin/settings/configs");

    await page.getByLabel("Google Analytics measurement ID").fill(GA_ID);
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Enter a Google Analytics 4 measurement ID" })).toBeVisible();
  });

  test("the Disable switches store the inverted enabled flags in their own domains", async ({ page }) => {
    const puts = await mockAdmin(page);
    await page.goto("/tenant-admin/settings/configs");

    const selfEnrollment = page.getByRole("switch", { name: "Disable self-enrollment" });
    const sms = page.getByRole("switch", { name: "Disable SMS notifications" });
    await expect(selfEnrollment).not.toBeChecked();
    await expect(sms).not.toBeChecked();

    await selfEnrollment.click();
    await expect(page.getByText("Self-enrollment disabled.")).toBeVisible();
    await expect(selfEnrollment).toBeChecked();
    await sms.click();
    await expect(page.getByText("SMS notifications disabled.")).toBeVisible();
    await expect(sms).toBeChecked();

    expect(puts).toEqual([
      { domain: "STUDENT", self_enrollment_enabled: false },
      { domain: "NOTIFICATION", sms_enabled: false },
    ]);
  });

  test("read-only auditor sees the values with every control disabled", async ({ page }) => {
    await mockAdmin(page, "READ_ONLY_AUDITOR", GA_ID);
    await page.goto("/tenant-admin/settings/configs");

    await expect(page.getByLabel("Google Analytics measurement ID")).toHaveValue(GA_ID);
    await expect(page.getByLabel("Google Analytics measurement ID")).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save" })).toHaveCount(0);
    await expect(page.getByRole("switch", { name: "Disable self-enrollment" })).toBeDisabled();
    await expect(page.getByRole("switch", { name: "Disable SMS notifications" })).toBeDisabled();
  });

  test("a 403 on the read renders the permission-denied state", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
    await mockJson(page, "**/api/v1/tenant-config/GENERAL", 403, apiError("FORBIDDEN", "Not allowed"));
    await page.goto("/tenant-admin/settings/configs");

    await expect(page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })).toBeVisible();
    await expect(page.getByLabel("Google Analytics measurement ID")).toHaveCount(0);
  });
});

test.describe("Google Analytics tag", () => {
  async function mockAnonymous(page: Page, gaMeasurementId: string | null) {
    await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
    await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
    await mockHome(page, { analytics: { gaMeasurementId } });
    await mockStudentRegistrationPolicy(page);
    await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess([]));
    await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
  }

  test("the public site loads the tenant's GA4 tag when configured", async ({ page }) => {
    const requested = await blockGoogle(page);
    await mockAnonymous(page, GA_ID);
    await page.goto("/");

    await expect.poll(() => requested).toContainEqual(`https://www.googletagmanager.com/gtag/js?id=${GA_ID}`);
  });

  test("no tag without an id, and a malformed id from the server is never written into a script", async ({ page }) => {
    const requested = await blockGoogle(page);
    await mockAnonymous(page, null);
    await page.goto("/");
    await expect(page.getByTestId("public-site-footer")).toBeVisible();

    await mockHome(page, { analytics: { gaMeasurementId: "G-1');alert(1)//" } });
    await page.reload();
    await expect(page.getByTestId("public-site-footer")).toBeVisible();

    expect(requested).toEqual([]);
    await expect(page.locator("script#tenant-ga-init, script#tenant-ga")).toHaveCount(0);
  });
});

test.describe("student catalog when self-enrollment is disabled", () => {
  test("Available courses show no Enroll button and explain who enrolls students", async ({ page }) => {
    await blockGoogle(page);
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
    await mockHome(page, { studentOptions: { selfEnrollmentEnabled: false } });
    await mockJson(page, "**/api/v1/course-tags", 200, apiSuccess([]));
    await mockJson(
      page,
      "**/api/v1/enrollments/my/catalog*",
      200,
      apiSuccess({
        content: [
          {
            courseId: "course-physics-2026",
            title: "Physics 2026 Theory",
            teacherName: "Teacher One",
            coverImageUrl: null,
            priceDisplay: { amount: "4500.00", currency: "LKR", period: "MONTH" },
            pricingModel: "MONTHLY",
            tags: [],
            enrolled: false,
            registrationEnabled: true,
          },
        ],
        page: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
    );
    await page.goto("/student/courses?view=available");

    await expect(page.getByTestId("catalog-course-card")).toBeVisible();
    await expect(page.getByTestId("catalog-self-enrollment-disabled")).toContainText("Contact your institute");
    await expect(page.getByTestId("catalog-view-course")).toBeVisible();
    await expect(page.getByTestId("catalog-enroll")).toHaveCount(0);
  });
});
