import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * I18N-01 runtime: language switcher, locale resolution, persistence, draft
 * preview, localized API errors and English fallback. Every backend call is
 * mocked with page.route (runs standalone, no backend). The "Sinhala"/"Tamil"
 * strings below are invented test fixtures, not reviewed translations.
 */

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
  showTodaysClasses: false,
};

const MESSAGES: Record<string, Record<string, string>> = {
  "en-LK": {},
  "si-LK": {
    "public.nav.courses": "පාඨමාලා",
    "teacher.courses.title": "මගේ පාඨමාලා",
    "errors.INVALID_CREDENTIALS": "ඇතුළු කළ විස්තර වැරදියි.",
    "common.language.label": "භාෂාව",
    // deliberately NO "auth.login.title": must fall back to the English source.
  },
  "ta-LK": {
    "public.nav.courses": "பாடநெறிகள்",
    "teacher.courses.title": "எனது பாடநெறிகள்",
  },
};

interface I18nMockLog {
  catalogLocales: (string | null)[];
  previewRequests: number;
  puts: unknown[];
}

async function mockI18n(
  page: Page,
  options: { enabled?: string[]; defaultLocale?: string; meLocale?: string | null; preview?: Record<string, string> } = {}
): Promise<I18nMockLog> {
  const log: I18nMockLog = { catalogLocales: [], previewRequests: 0, puts: [] };
  const enabled = options.enabled ?? ["en-LK", "si-LK", "ta-LK"];
  const defaultLocale = options.defaultLocale ?? "en-LK";
  await mockJson(
    page,
    "**/v1/public/i18n/settings",
    200,
    apiSuccess({
      supportedLocales: [
        { tag: "en-LK", autonym: "English", dir: "ltr" },
        { tag: "si-LK", autonym: "සිංහල", dir: "ltr" },
        { tag: "ta-LK", autonym: "தமிழ்", dir: "ltr" },
      ],
      enabledLocales: enabled,
      defaultLocale,
      browserLanguageDetection: false,
    })
  );
  await page.route(
    (url) => url.pathname.endsWith("/v1/public/i18n/catalog"),
    async (route: Route) => {
      const url = new URL(route.request().url());
      const requested = url.searchParams.get("locale");
      log.catalogLocales.push(requested);
      const locale = requested && enabled.includes(requested) ? requested : defaultLocale;
      await fulfillJson(
        route,
        200,
        apiSuccess({
          locale,
          resolvedFrom: requested ? "EXPLICIT" : "TENANT_DEFAULT",
          version: "test.0",
          namespaces: (url.searchParams.get("ns") ?? "common").split(","),
          messages: MESSAGES[locale] ?? {},
        })
      );
    }
  );
  await page.route(
    (url) => url.pathname.endsWith("/v1/i18n/admin/preview-catalog"),
    async (route: Route) => {
      log.previewRequests += 1;
      const locale = new URL(route.request().url()).searchParams.get("locale") ?? defaultLocale;
      await fulfillJson(
        route,
        200,
        apiSuccess({ locale, version: "test.draft", namespaces: ["common"], messages: { ...(MESSAGES[locale] ?? {}), ...(options.preview ?? {}) } })
      );
    }
  );
  await page.route("**/v1/me/locale", async (route: Route) => {
    if (route.request().method() === "PUT") {
      log.puts.push(route.request().postDataJSON());
      await fulfillJson(route, 200, apiSuccess({ locale: (route.request().postDataJSON() as { locale: string }).locale }));
      return;
    }
    await fulfillJson(route, 200, apiSuccess({ locale: options.meLocale ?? null }));
  });
  return log;
}

/** Fallback for every other API call (registered first, so specific mocks win). */
async function mockEverythingElse(page: Page) {
  await page.route("**/api/v1/**", (route) =>
    fulfillJson(route, 503, apiError("UNAVAILABLE", "Unavailable in this test"))
  );
}

async function mockAnonymousHome(page: Page) {
  await mockEverythingElse(page);
  await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess(HOME));
  await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess([]));
  await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
}

async function localeCookie(page: Page): Promise<string | undefined> {
  const cookies = await page.context().cookies();
  return cookies.find((cookie) => cookie.name === "lms_locale")?.value;
}

test.describe("language switcher - public site (anonymous)", () => {
  test("desktop: en -> si -> ta keeps the path and query, sets <html lang> and the cookie, never calls PUT /me/locale", async ({ page }) => {
    await mockAnonymousHome(page);
    const log = await mockI18n(page);
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/?utm_source=test&page=2");

    const header = page.getByTestId("public-site-header");
    await expect(header.getByRole("link", { name: "Courses" }).first()).toBeVisible();
    const switcher = header.getByRole("combobox", { name: "Language" }).first();
    await expect(switcher).toBeVisible();
    await expect(switcher.locator("option")).toHaveCount(3);
    await expect(switcher.locator('option[value="si-LK"]')).toHaveAttribute("lang", "si-LK");

    await switcher.selectOption("si-LK");
    await expect(page.locator("html")).toHaveAttribute("lang", "si-LK");
    await expect(header.getByRole("link", { name: "පාඨමාලා" }).first()).toBeVisible();
    await expect.poll(() => localeCookie(page)).toBe("si-LK");
    expect(new URL(page.url()).pathname).toBe("/");
    expect(new URL(page.url()).searchParams.get("utm_source")).toBe("test");
    expect(new URL(page.url()).searchParams.get("page")).toBe("2");
    await expect(page.getByTestId("language-switcher-status").first()).toContainText("සිංහල");

    await header.getByRole("combobox", { name: "භාෂාව" }).first().selectOption("ta-LK");
    await expect(page.locator("html")).toHaveAttribute("lang", "ta-LK");
    await expect(header.getByRole("link", { name: "பாடநெறிகள்" }).first()).toBeVisible();
    await expect.poll(() => localeCookie(page)).toBe("ta-LK");
    expect(new URL(page.url()).searchParams.get("utm_source")).toBe("test");

    expect(log.puts).toEqual([]);
    expect(log.catalogLocales).toContain("si-LK");
    expect(log.catalogLocales).toContain("ta-LK");
  });

  test("mobile (375px): the switcher in the menu changes the language without horizontal overflow", async ({ page }) => {
    await mockAnonymousHome(page);
    await mockI18n(page);
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto("/?ref=sms");

    await page.getByRole("button", { name: "Open menu" }).click();
    const switcher = page.getByRole("combobox", { name: "Language" }).last();
    await expect(switcher).toBeVisible();
    await switcher.selectOption("si-LK");
    await expect(page.locator("html")).toHaveAttribute("lang", "si-LK");
    await expect.poll(() => localeCookie(page)).toBe("si-LK");
    expect(new URL(page.url()).searchParams.get("ref")).toBe("sms");
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
  });

  test("?lang= deep link selects the language for that page but persists nothing", async ({ page }) => {
    await mockAnonymousHome(page);
    const log = await mockI18n(page);
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/courses?lang=ta");

    await expect(page.getByTestId("public-site-header").getByRole("link", { name: "பாடநெறிகள்" }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator("html")).toHaveAttribute("lang", "ta-LK");
    expect(log.catalogLocales).toContain("ta-LK");
    expect(await localeCookie(page)).toBeUndefined();
    expect(log.puts).toEqual([]);
  });

  test("the switcher is hidden when the institute enabled a single language", async ({ page }) => {
    await mockAnonymousHome(page);
    await mockI18n(page, { enabled: ["en-LK"] });
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/");
    await expect(page.getByTestId("public-site-header").getByRole("link", { name: "Courses" }).first()).toBeVisible();
    await expect(page.getByTestId("language-switcher")).toHaveCount(0);
  });
});

test.describe("sign-in page", () => {
  test("API error is localized via errors.<CODE> and a missing key falls back to English", async ({ page, context, baseURL }) => {
    await mockAnonymousHome(page);
    await mockI18n(page);
    await context.addCookies([{ name: "lms_locale", value: "si-LK", url: baseURL ?? "http://localhost:3000" }]);
    await mockJson(page, "**/v1/auth/login", 401, apiError("INVALID_CREDENTIALS", "Invalid email or password"));
    await page.goto("/login");

    await expect(page.locator("html")).toHaveAttribute("lang", "si-LK");
    // "auth.login.title" has no si-LK value: the English source is shown, never the raw key.
    await expect(page.getByText("Sign in", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("auth.login.title")).toHaveCount(0);

    await page.getByRole("textbox", { name: "Email" }).fill("someone@example.test");
    await page.getByLabel("Password", { exact: true }).fill("wrong-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByTestId("login-error-message")).toHaveText("ඇතුළු කළ විස්තර වැරදියි.");
  });

  test("in English the backend's own message is kept", async ({ page }) => {
    await mockAnonymousHome(page);
    await mockI18n(page);
    await mockJson(page, "**/v1/auth/login", 401, apiError("INVALID_CREDENTIALS", "Invalid email or password"));
    await page.goto("/login");
    await page.getByRole("textbox", { name: "Email" }).fill("someone@example.test");
    await page.getByLabel("Password", { exact: true }).fill("wrong-password");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByTestId("login-error-message")).toHaveText("Invalid email or password");
  });
});

async function mockSignedIn(page: Page, role: "TEACHER" | "TENANT_ADMIN") {
  await mockEverythingElse(page);
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess(HOME));
}

test.describe("signed-in persistence", () => {
  test("teacher: switching saves the preference with PUT /me/locale and sets the cookie", async ({ page }) => {
    await mockSignedIn(page, "TEACHER");
    await page.route(
      (url) => url.pathname.endsWith("/v1/courses"),
      (route) => fulfillJson(route, 200, apiPageSuccess([]))
    );
    const log = await mockI18n(page);
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/teacher/courses?tab=mine");

    await expect(page.getByRole("heading", { name: "My Courses" })).toBeVisible();
    await page.getByRole("combobox", { name: "Language" }).first().selectOption("si-LK");
    await expect(page.getByRole("heading", { name: "මගේ පාඨමාලා" })).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "si-LK");
    expect(log.puts).toEqual([{ locale: "si-LK" }]);
    await expect.poll(() => localeCookie(page)).toBe("si-LK");
    expect(new URL(page.url()).pathname).toBe("/teacher/courses");
    expect(new URL(page.url()).searchParams.get("tab")).toBe("mine");
  });

  test("teacher: the saved preference is applied on load", async ({ page }) => {
    await mockSignedIn(page, "TEACHER");
    await page.route(
      (url) => url.pathname.endsWith("/v1/courses"),
      (route) => fulfillJson(route, 200, apiPageSuccess([]))
    );
    await mockI18n(page, { meLocale: "ta-LK" });
    await page.goto("/teacher/courses");
    await expect(page.getByRole("heading", { name: "எனது பாடநெறிகள்" })).toBeVisible();
    await expect(page.locator("html")).toHaveAttribute("lang", "ta-LK");
  });
});

test.describe("translation management", () => {
  test("Preview drafts swaps in the preview catalog with a visible banner; Exit preview restores published texts", async ({ page }) => {
    await mockSignedIn(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess([]));
    await page.route(
      (url) => url.pathname.endsWith("/v1/i18n/admin/entries"),
      (route) =>
        fulfillJson(
          route,
          200,
          apiSuccess({
            counts: { total: 1, missing: 0, translated: 1, needsReview: 0, overridden: 0, drafts: 1 },
            page: {
              content: [
                {
                  key: "public.nav.courses",
                  namespace: "public",
                  source: "Courses",
                  context: "Navigation link",
                  platformValue: "පාඨමාලා",
                  platformStatus: "TRANSLATED",
                  suggestion: null,
                  publishedOverride: null,
                  overrideNeedsReview: false,
                  draft: { action: "SET", value: "පාඨමාලා (කෙටුම්පත)", version: 1, updatedAt: "2026-11-01T10:00:00Z" },
                  effectiveValue: "පාඨමාලා",
                  origin: "PLATFORM",
                },
              ],
              page: 0,
              size: 25,
              totalElements: 1,
              totalPages: 1,
            },
          })
        )
    );
    await page.route(
      (url) => url.pathname.endsWith("/v1/i18n/admin/releases"),
      (route) => fulfillJson(route, 200, apiSuccess([]))
    );
    const log = await mockI18n(page, { meLocale: "si-LK", preview: { "common.language.label": "භාෂාව (කෙටුම්පත)" } });
    await page.setViewportSize({ width: 1280, height: 900 });
    await page.goto("/tenant-admin/settings/translations");

    await expect(page.getByTestId("translation-entry")).toHaveCount(1);
    await expect(page.getByTestId("i18n-preview-banner")).toHaveCount(0);
    await page.getByTestId("preview-drafts-toggle").click();
    await expect(page.getByTestId("i18n-preview-banner")).toBeVisible();
    await expect(page.getByRole("combobox", { name: "භාෂාව (කෙටුම්පත)" }).first()).toBeVisible();
    expect(log.previewRequests).toBeGreaterThan(0);

    await page.getByTestId("i18n-preview-banner").getByRole("button").click();
    await expect(page.getByTestId("i18n-preview-banner")).toHaveCount(0);
    await expect(page.getByRole("combobox", { name: "භාෂාව" }).first()).toBeVisible();
  });

  test("live validation flags a missing placeholder before saving", async ({ page }) => {
    await mockSignedIn(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess([]));
    await page.route(
      (url) => url.pathname.endsWith("/v1/i18n/admin/entries"),
      (route) =>
        fulfillJson(
          route,
          200,
          apiSuccess({
            counts: { total: 1, missing: 1, translated: 0, needsReview: 0, overridden: 0, drafts: 0 },
            page: {
              content: [
                {
                  key: "common.language.changed",
                  namespace: "common",
                  source: "Language changed to {language}",
                  context: null,
                  platformValue: null,
                  platformStatus: "MISSING",
                  suggestion: null,
                  publishedOverride: null,
                  overrideNeedsReview: false,
                  draft: null,
                  effectiveValue: "Language changed to {language}",
                  origin: "SOURCE",
                },
              ],
              page: 0,
              size: 25,
              totalElements: 1,
              totalPages: 1,
            },
          })
        )
    );
    await page.route(
      (url) => url.pathname.endsWith("/v1/i18n/admin/releases"),
      (route) => fulfillJson(route, 200, apiSuccess([]))
    );
    await mockI18n(page);
    await page.goto("/tenant-admin/settings/translations");
    await page.getByTestId("translation-entry").click();
    const field = page.getByLabel(/Translation \(/);
    await field.fill("භාෂාව වෙනස් විය");
    await expect(page.getByTestId("live-problems")).toContainText("{language}");
    await expect(page.getByRole("button", { name: "Save draft" })).toBeDisabled();
    await field.fill("භාෂාව {language} ලෙස වෙනස් විය");
    await expect(page.getByTestId("live-problems")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Save draft" })).toBeEnabled();
  });

  test("PO import: the needs-review switch is off by default and, when on, is sent with the file check", async ({ page }) => {
    await mockSignedIn(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess([]));
    await page.route(
      (url) => url.pathname.endsWith("/v1/i18n/admin/entries"),
      (route) =>
        fulfillJson(
          route,
          200,
          apiSuccess({
            counts: { total: 0, missing: 0, translated: 0, needsReview: 0, overridden: 0, drafts: 0 },
            page: { content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 },
          })
        )
    );
    await page.route(
      (url) => url.pathname.endsWith("/v1/i18n/admin/releases"),
      (route) => fulfillJson(route, 200, apiSuccess([]))
    );
    const importUrls: string[] = [];
    await page.route(
      (url) => url.pathname.endsWith("/v1/i18n/admin/import"),
      (route) => {
        const url = new URL(route.request().url());
        importUrls.push(url.search);
        const include = url.searchParams.get("includeNeedsReview") === "true";
        return fulfillJson(
          route,
          200,
          apiSuccess({
            locale: "si-LK", dryRun: true, valid: true, entries: 2,
            created: include ? 2 : 1, updated: 0, unchanged: 0, skippedEmpty: 0,
            skippedFuzzy: include ? 0 : 1, skippedObsolete: 0, needsReview: include ? 1 : 0,
            unknownKeys: [], sourceChanged: [], invalid: [],
          })
        );
      }
    );
    await mockI18n(page);
    await page.goto("/tenant-admin/settings/translations");

    const panel = page.getByTestId("import-panel");
    const toggle = panel.getByTestId("include-needs-review");
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await panel.getByLabel("PO file").setInputFiles({
      name: "si-LK.po",
      mimeType: "text/x-gettext-translation",
      buffer: Buffer.from(['msgid ""', 'msgstr ""', ""].join("\n")),
    });

    await panel.getByRole("button", { name: "Check file" }).click();
    await expect(panel.getByTestId("import-report")).toContainText("1 skipped");
    expect(importUrls.at(-1)).toContain("includeNeedsReview=false");

    // Switching it on clears the old check; the next check sends the opt-in.
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await expect(panel.getByTestId("import-report")).toHaveCount(0);
    await panel.getByRole("button", { name: "Check file" }).click();
    await expect(panel.getByTestId("import-report")).toContainText("1 of them still needs review");
    expect(importUrls.at(-1)).toContain("includeNeedsReview=true");
    await expect(panel.getByTestId("confirm-import")).toHaveText("Import 2 drafts");
  });
});
