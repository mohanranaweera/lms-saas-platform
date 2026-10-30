import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Tenant Admin → Settings → Appearance (`/tenant-admin/settings/appearance`),
 * backed by `GET/PUT /v1/tenant-config/BRANDING` and `/v1/tenant-assets`.
 * Every backend call is intercepted. Client-side checks here are UX only —
 * the backend re-validates and authorizes every write.
 */

function property(key: string, value: unknown, type = "STRING") {
  return { key, value, type, defaultValue: null, sensitive: false };
}

const BRANDING = [
  property("homepage_url", null),
  property("public_page_enabled", true, "BOOLEAN"),
  property("sub_hero_enabled", true, "BOOLEAN"),
  property("cta_enabled", true, "BOOLEAN"),
  property("teacher_auto_rotate", false, "BOOLEAN"),
  property("featured_course_ids", [], "JSON"),
  property("support_video_url", null),
  property("logo_display_size", "STANDARD"),
  property("header_font_family", "INTER"),
  property("body_font_family", "INTER"),
  property("primary_color", null),
  property("accent_color", null),
  property("background_color", null),
  property("surface_color", null),
  property("text_color", null),
  property("secondary_color", "#F59E0B"),
];

const GENERAL = [property("institute_name", "Northwind Academy")];

const COURSES = [
  { id: "c-1", name: "Applied Chemistry", status: "PUBLIC" },
  { id: "c-2", name: "Draft Physics", status: "DRAFT" },
].map((course) => ({
  ...course,
  teacherId: "t-1",
  slug: course.id,
  category: "Science",
  subject: null,
  stream: null,
  grade: null,
  academicYear: null,
  description: null,
  price: 0,
  accessDurationDays: null,
  enrollmentRule: null,
  pricingModel: "FREE",
  archivedAt: null,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  resolvedAmount: 0,
  currency: "LKR",
  requiresManualQuote: false,
}));

function banner(id: string, type: string) {
  return {
    id,
    assetType: type,
    contentType: "image/png",
    sizeBytes: 1000,
    createdAt: "2026-01-01T00:00:00Z",
    previewUrl: `https://assets.test/${id}.svg`,
    previewExpiresAt: null,
  };
}

async function setup(page: Page, role = "TENANT_ADMIN", options: { desktopBanners?: number } = {}) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess(GENERAL));
  await mockJson(page, "**/v1/tenant-config/BRANDING", 200, apiSuccess(BRANDING));
  await mockJson(page, "**/v1/tenant-assets/by-type/*", 404, apiError("NOT_FOUND", "No asset"));
  await page.route(
    (url) => url.pathname.endsWith("/v1/courses"),
    (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiPageSuccess(COURSES)) })
  );
  await page.route(
    (url) => url.pathname.endsWith("/v1/tenant-assets") && url.searchParams.has("type"),
    (route) => {
      const type = new URL(route.request().url()).searchParams.get("type")!;
      const count = type === "DESKTOP_HERO_BANNER" ? (options.desktopBanners ?? 0) : 0;
      const body = apiSuccess(Array.from({ length: count }, (_, i) => banner(`b-${i + 1}`, type)));
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    }
  );
  await page.route("https://assets.test/**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="480"><rect width="1920" height="480" fill="#334155"/></svg>',
    })
  );
}

test.describe("Appearance settings", () => {
  test("renders the Klass-style sections in order inside the Settings tabs", async ({ page }) => {
    await setup(page);
    await page.goto("/tenant-admin/settings/appearance");

    const tabs = page.getByRole("navigation", { name: "Settings sections" });
    await expect(tabs.getByRole("link", { name: "Appearance" })).toHaveAttribute("aria-current", "page");

    const headings = page.getByRole("region").getByRole("heading", { level: 2 });
    await expect(headings).toHaveText(["Branding & Media", "Public Page Settings", "Typography", "Theme Colors", "Language Settings"]);

    await expect(page.getByText(/Recommended ~600×180 px wide or 512×512 square/)).toBeVisible();
    await expect(page.getByText(/Shown at 16–32 px in browser tabs/)).toBeVisible();
    await expect(page.getByText(/Existing receipts are not changed/)).toBeVisible();
    await expect(page.getByText(/Desktop: 1920×480 \(4:1\), min 1280×427, max 2 MB JPG\/PNG/)).toBeVisible();
    await expect(page.getByText(/Mobile: 800×300 \(8:3\), max 2 MB JPG\/PNG/)).toBeVisible();

    const visit = page.getByRole("link", { name: /Visit page/ });
    await expect(visit).toHaveAttribute("href", "/");
    await expect(visit).toHaveAttribute("target", "_blank");
    await expect(page.getByRole("link", { name: /View support page/ })).toHaveAttribute("href", "/#support");

    await expect(page.getByRole("switch", { name: "Auto-rotate teachers" })).toHaveAttribute("aria-checked", "false");
    await expect(page.getByLabel("Default language")).toBeDisabled();
    await expect(page.getByLabel("Default language")).toHaveValue("en");
    await expect(page.getByText("English is currently the only supported interface language.")).toBeVisible();

    // Logo preview renders the real header logo at desktop and 390 px widths (monogram: no logo yet).
    await expect(page.getByText("Desktop header (actual size)")).toBeVisible();
    await expect(page.getByText("Mobile header (390 px)")).toBeVisible();
  });

  test("saves only changed keys (presets fill all colors, never secondary_color)", async ({ page }) => {
    await setup(page);
    let putBody: Record<string, unknown> | null = null;
    await page.route("**/v1/tenant-config/BRANDING", async (route) => {
      if (route.request().method() === "PUT") {
        putBody = route.request().postDataJSON();
        const updated = BRANDING.map((row) => (putBody && row.key in putBody ? { ...row, value: putBody[row.key] } : row));
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(updated)) });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(BRANDING)) });
    });
    await page.goto("/tenant-admin/settings/appearance");

    const save = page.getByRole("button", { name: "Save appearance" });
    await expect(save).toBeDisabled();
    await page.getByRole("switch", { name: "Call-To-Action (CTA) Section" }).click();
    await page.getByRole("button", { name: "Forest" }).click();
    await page.getByRole("radio", { name: /^Large/ }).check();
    await save.click();

    await expect(page.getByText("Saved", { exact: true })).toBeVisible();
    expect(putBody).toEqual({
      cta_enabled: false,
      logo_display_size: "LARGE",
      primary_color: "#15803D",
      accent_color: "#CA8A04",
      background_color: "#F7FBF8",
      surface_color: "#FFFFFF",
      text_color: "#14281D",
    });
  });

  test("low text contrast blocks Save with an inline error and a failing badge", async ({ page }) => {
    await setup(page);
    await page.goto("/tenant-admin/settings/appearance");

    await page.getByRole("textbox", { name: "Main text", exact: true }).fill("#DDDDDD");
    await expect(page.getByText(/Main text needs at least 4.5:1 contrast with the page background/)).toBeVisible();
    await expect(page.getByTestId("contrast-badge").filter({ hasText: "Text on background" })).toHaveAttribute("data-pass", "false");
    await expect(page.getByRole("button", { name: "Save appearance" })).toBeDisabled();

    await page.getByRole("button", { name: /Reset main text to default/ }).click();
    await expect(page.getByRole("textbox", { name: "Main text", exact: true })).toHaveValue("#0F172A");
    await expect(page.getByTestId("contrast-badge").filter({ hasText: "Text on background" })).toHaveAttribute("data-pass", "true");
  });

  test("a dark preset leaves no stale contrast error (all color fields re-validate together)", async ({ page }) => {
    await setup(page);
    await page.goto("/tenant-admin/settings/appearance");
    await page.getByRole("button", { name: "Midnight (dark)" }).click();
    await expect(page.getByRole("textbox", { name: "Page background", exact: true })).toHaveValue("#0B1220");
    await expect(page.getByText(/needs at least/)).toHaveCount(0);
    for (const badge of await page.getByTestId("contrast-badge").all()) await expect(badge).toHaveAttribute("data-pass", "true");
    await expect(page.getByRole("button", { name: "Save appearance" })).toBeEnabled();
  });

  test("settings tabs: one Settings sidebar entry covers General, Student fields, Integrations and Video", async ({ page }) => {
    await setup(page);
    await page.goto("/tenant-admin/settings/appearance");
    const tabs = page.getByRole("navigation", { name: "Settings sections" });
    // SET-PAY-01 added Payments after General; LOGIN-01 added Policies after Site media.
    await expect(tabs.getByRole("link")).toHaveText(["General", "Payments", "Appearance", "Student fields", "Site media", "Policies", "Integrations", "Video"]);
    await expect(page.getByRole("link", { name: "Student Fields", exact: true })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Branding", exact: true })).toHaveCount(0);
    await expect(page.getByRole("navigation", { name: "Primary" }).first().getByRole("link", { name: "Settings", exact: true })).toHaveAttribute("href", "/tenant-admin/settings/general");
  });

  test("font picker filters by name, previews in the chosen family and resets", async ({ page }) => {
    await setup(page);
    await page.goto("/tenant-admin/settings/appearance");
    const heading = page.getByTestId("font-picker-heading");
    await heading.getByRole("searchbox").fill("pop");
    await expect(heading.getByRole("radio")).toHaveCount(2); // Poppins + the current choice (Inter)
    await heading.getByRole("radio", { name: /Poppins/ }).check();
    await expect(page.getByTestId("font-sample-heading")).toHaveCSS("font-family", /Poppins/);
    await heading.getByRole("button", { name: "Reset to default" }).click();
    await expect(heading.getByRole("radio", { name: /Inter/ })).toBeChecked();
  });

  test("featured courses picker lists course names, not ids", async ({ page }) => {
    await setup(page);
    await page.goto("/tenant-admin/settings/appearance");
    await page.getByTestId("featured-courses").click();
    const popup = page.getByTestId("featured-courses-popup");
    await expect(popup.getByText("Applied Chemistry", { exact: true })).toBeVisible();
    await expect(popup.getByText(/Draft Physics \(draft — not shown publicly\)/)).toBeVisible();
  });

  test("banner gallery hides the upload tile at 6 banners and deletes by id", async ({ page }) => {
    await setup(page, "TENANT_ADMIN", { desktopBanners: 6 });
    let deleted = "";
    await page.route("**/v1/tenant-assets/b-*", async (route) => {
      if (route.request().method() === "DELETE") deleted = new URL(route.request().url()).pathname.split("/").pop()!;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(null)) });
    });
    await page.goto("/tenant-admin/settings/appearance");
    const desktop = page.getByTestId("banner-gallery-desktop");
    await expect(desktop.getByRole("img")).toHaveCount(6);
    await expect(desktop.getByText("Upload banner")).toHaveCount(0);
    await expect(desktop.getByText(/Maximum of 6 banners reached/)).toBeVisible();
    await expect(page.getByTestId("banner-gallery-mobile").getByText("Upload banner")).toBeVisible();

    await desktop.getByRole("button", { name: "Delete desktop banner 2" }).click();
    await expect.poll(() => deleted).toBe("b-2");
  });

  test("a non-PNG/JPEG file is rejected before upload", async ({ page }) => {
    await setup(page);
    let posted = false;
    await page.route(
      (url) => url.pathname.endsWith("/v1/tenant-assets") && !url.searchParams.has("type"),
      async (route) => {
        posted = true;
        await route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
      }
    );
    await page.goto("/tenant-admin/settings/appearance");
    await page.getByLabel("Institute logo").setInputFiles({
      name: "logo.gif",
      mimeType: "image/gif",
      buffer: Buffer.from("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;", "binary"),
    });
    await expect(page.getByText("Choose a PNG or JPEG image. Other file types are not accepted.")).toBeVisible();
    expect(posted).toBe(false);
  });

  test("Read-only Auditor sees values with every control disabled and no Save", async ({ page }) => {
    await setup(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/settings/appearance");
    await expect(page.getByText("You have view-only access to institute appearance.")).toBeVisible();
    await expect(page.getByRole("switch", { name: "Enable Public Page" })).toBeDisabled();
    await expect(page.getByRole("textbox", { name: "Primary", exact: true })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save appearance" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Upload", exact: true })).toHaveCount(0);
  });

  test("a 403 on load renders the permission-denied state", async ({ page }) => {
    await setup(page, "FINANCE_STAFF");
    await mockJson(page, "**/v1/tenant-config/BRANDING", 403, apiError("FORBIDDEN", "You do not have permission to view institute configuration."));
    await page.goto("/tenant-admin/settings/appearance");
    await expect(page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })).toBeVisible();
    await expect(page.getByRole("button", { name: "Save appearance" })).toHaveCount(0);
  });
});
