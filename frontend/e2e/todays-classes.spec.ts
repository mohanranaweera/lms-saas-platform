import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * CAL-01 public home "Today's Classes" (docs/specs/class-calendar.md §6.6/§8)
 * and the Appearance "Show Today's Classes" switch (§6.7). The browser clock
 * is driven by `page.clock`; the mocked `GET /v1/public/calendar/today`
 * answers with a `serverNow` equal to the test's current clock, so statuses
 * move Scheduled → Live now → Over and the list rolls over at tenant-local
 * midnight (Asia/Colombo, UTC+05:30) without a reload.
 */

const OFFSET_MIN = 330;
function at(date: string, time: string): number {
  const [y, m, d] = date.split("-").map(Number);
  const [h, mi] = time.split(":").map(Number);
  return Date.UTC(y, m - 1, d, h, mi) - OFFSET_MIN * 60_000;
}
const iso = (ms: number) => new Date(ms).toISOString();

function cls(id: string, courseName: string, date: string, start: string, end: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    courseName,
    courseSlug: id,
    coverUrl: null,
    teacherName: "Nimal Perera",
    teacherPhotoUrl: null,
    start: iso(at(date, start)),
    end: iso(at(date, end)),
    startLocalTime: start,
    endLocalTime: end,
    status: "SCHEDULED",
    ...extra,
  };
}

const HOME = {
  publicPageEnabled: true,
  instituteName: "Northwind Academy",
  logoUrl: null,
  faviconUrl: null,
  homepageUrl: null,
  subHeroEnabled: true,
  ctaEnabled: false,
  supportVideoUrl: null,
  desktopBannerUrls: [],
  mobileBannerUrls: [],
  featuredCourses: [],
  teacherAutoRotate: false,
  showTodaysClasses: true as boolean | null,
};

interface TodayState {
  serverNow: number;
  calls: number;
  enabled: boolean;
  byDate: Record<string, ReturnType<typeof cls>[]>;
  fail: boolean;
}

function localDate(ms: number): string {
  return new Date(ms + OFFSET_MIN * 60_000).toISOString().slice(0, 10);
}

async function mockPublic(page: Page, state: TodayState, home: Partial<typeof HOME> = {}) {
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify(apiError("NOT_FOUND", "Not mocked")) })
  );
  await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess({ ...HOME, ...home }));
  await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess([]));
  await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
  await page.route("**/v1/public/calendar/today", async (route) => {
    state.calls += 1;
    if (state.fail) {
      await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify(apiError("INTERNAL_ERROR", "Boom")) });
      return;
    }
    const date = localDate(state.serverNow);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        apiSuccess({
          enabled: state.enabled,
          timezone: "Asia/Colombo",
          serverNow: iso(state.serverNow),
          date,
          occurrences: state.enabled ? (state.byDate[date] ?? []) : [],
        })
      ),
    });
  });
}

const FRIDAY = "2026-11-06";
const SATURDAY = "2026-11-07";

function baseState(): TodayState {
  return {
    serverNow: at(FRIDAY, "07:30"),
    calls: 0,
    enabled: true,
    fail: false,
    byDate: {
      [FRIDAY]: [
        cls("biology", "2027 A/L Biology Theory and Revision for the Final Examination", FRIDAY, "07:00", "08:00"),
        cls("maths", "Combined Maths", FRIDAY, "08:00", "09:00"),
        cls("ict", "ICT Theory", FRIDAY, "06:00", "07:00"),
        cls("chem", "Chemistry", FRIDAY, "05:00", "06:30"),
      ],
      [SATURDAY]: [cls("weekend", "Weekend Revision", SATURDAY, "09:00", "11:00")],
    },
  };
}

test.describe("Today's Classes on the public home", () => {
  test("groups Live now → Scheduled → Over and advances statuses without a reload, across tenant midnight", async ({ page }) => {
    const state = baseState();
    await page.clock.install({ time: new Date(state.serverNow) });
    await mockPublic(page, state);
    await page.goto("/");

    const section = page.getByTestId("todays-classes");
    await expect(section.getByRole("heading", { name: "Today's Classes" })).toBeVisible();
    await expect(section.getByTestId("todays-classes-date")).toHaveText("Friday, 6 November 2026 · Sri Lanka time (Asia/Colombo)");
    await expect(section.getByTestId("todays-classes-summary")).toHaveText("1 live now · 1 scheduled · 2 over");

    const groups = section.getByTestId("todays-classes-group");
    await expect(groups).toHaveCount(3);
    await expect(groups.nth(0)).toHaveAttribute("data-status", "LIVE");
    await expect(groups.nth(1)).toHaveAttribute("data-status", "SCHEDULED");
    await expect(groups.nth(2)).toHaveAttribute("data-status", "OVER");
    // Over: most recently ended first (ICT ended 07:00, Chemistry 06:30).
    await expect(groups.nth(2).getByTestId("todays-class-card").first()).toHaveAttribute("data-occurrence-id", "ict");

    const live = section.locator('[data-testid="todays-class-card"][data-occurrence-id="biology"]');
    // The card renders the badge twice (image overlay on sm+, inline below sm); only one is displayed.
    await expect(live.getByTestId("schedule-status-badge").filter({ visible: true })).toContainText("Live now");
    await expect(live.getByText("Based on the scheduled class time").filter({ visible: true })).toHaveCount(1);
    await expect(live.getByRole("heading", { level: 4 })).toHaveAttribute("title", /2027 A\/L Biology/);
    await expect(live.getByTestId("todays-class-time")).toHaveText("7:00 AM – 8:00 AM");
    await expect(live.getByRole("link", { name: /View course/ })).toHaveAttribute("href", "/courses/biology");

    // 08:00 boundary: Biology → Over, Maths → Live now. No reload.
    state.serverNow = at(FRIDAY, "08:00") + 1_000;
    await page.clock.fastForward(30 * 60_000 + 1_000);
    await expect(section.getByTestId("todays-classes-summary")).toHaveText("1 live now · 3 over");
    await expect(section.locator('[data-testid="todays-class-card"][data-occurrence-id="maths"]')).toHaveAttribute("data-status", "LIVE");
    await expect(section.locator('[data-testid="todays-class-card"][data-occurrence-id="biology"]')).toHaveAttribute("data-status", "OVER");

    // 09:00: everything is over, cards stay visible.
    state.serverNow = at(FRIDAY, "09:00") + 1_000;
    await page.clock.fastForward(60 * 60_000);
    await expect(section.getByTestId("todays-classes-summary")).toHaveText("4 over");
    await expect(section.getByTestId("todays-class-card")).toHaveCount(4);

    // Tenant-local midnight: the list refetches for Saturday.
    state.serverNow = at(SATURDAY, "00:00") + 2_000;
    await page.clock.fastForward(at(SATURDAY, "00:00") - at(FRIDAY, "09:00") + 1_000);
    await expect(section.getByTestId("todays-classes-date")).toHaveText("Saturday, 7 November 2026 · Sri Lanka time (Asia/Colombo)");
    await expect(section.getByTestId("todays-class-card")).toHaveCount(1);
    await expect(section.getByTestId("todays-classes-summary")).toHaveText("1 scheduled");
  });

  test("sits after the sub-hero and before featured content", async ({ page }) => {
    const state = baseState();
    await page.clock.install({ time: new Date(state.serverNow) });
    await mockPublic(page, state);
    await page.goto("/");
    await expect(page.getByTestId("todays-classes")).toBeVisible();
    const order = await page.evaluate(() => {
      const ids = ["sub-hero", "todays-classes", "support-section"];
      return ids.map((id) => document.querySelector(`[data-testid="${id}"]`)?.getBoundingClientRect().top ?? -1);
    });
    expect(order[0]).toBeLessThan(order[1]);
    expect(order[1]).toBeLessThan(order[2]);
  });

  test("empty day shows the empty state", async ({ page }) => {
    const state = baseState();
    state.byDate = {};
    await page.clock.install({ time: new Date(state.serverNow) });
    await mockPublic(page, state);
    await page.goto("/");
    await expect(page.getByTestId("todays-classes-empty")).toContainText("No classes scheduled today.");
  });

  test("error shows an inline retry", async ({ page }) => {
    const state = baseState();
    state.fail = true;
    await page.clock.install({ time: new Date(state.serverNow) });
    await mockPublic(page, state);
    await page.goto("/");
    const error = page.getByTestId("todays-classes-error");
    await expect(error).toContainText("Today's classes couldn't be loaded.");
    state.fail = false;
    await error.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByTestId("todays-class-card")).toHaveCount(4);
  });

  test("hidden when the server reports enabled=false, and not requested when the switch is off", async ({ page }) => {
    const state = baseState();
    state.enabled = false;
    await page.clock.install({ time: new Date(state.serverNow) });
    await mockPublic(page, state);
    await page.goto("/");
    await expect(page.getByTestId("sub-hero")).toBeVisible();
    await expect.poll(() => state.calls).toBeGreaterThan(0);
    await expect(page.getByTestId("todays-classes")).toHaveCount(0);

    const page2 = await page.context().newPage();
    const state2 = baseState();
    await page2.clock.install({ time: new Date(state2.serverNow) });
    await mockPublic(page2, state2, { showTodaysClasses: false });
    await page2.goto("/");
    await expect(page2.getByTestId("sub-hero")).toBeVisible();
    await expect(page2.getByTestId("todays-classes")).toHaveCount(0);
    expect(state2.calls).toBe(0);
  });

  test("mobile 390×844: single column, no horizontal overflow", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    const state = baseState();
    await page.clock.install({ time: new Date(state.serverNow) });
    await mockPublic(page, state);
    await page.goto("/");
    const cards = page.getByTestId("todays-class-card");
    await expect(cards).toHaveCount(4);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    const boxes = await Promise.all([0, 1].map((i) => page.getByTestId("todays-classes-group").nth(2).getByTestId("todays-class-card").nth(i).boundingBox()));
    expect(Math.abs(boxes[0]!.x - boxes[1]!.x)).toBeLessThan(2);
  });
});

// ---------------------------------------------------------------------------
// Appearance switch
// ---------------------------------------------------------------------------

function property(key: string, value: unknown, type = "STRING") {
  return { key, value, type, defaultValue: null, sensitive: false };
}

test.describe("Appearance: Show Today's Classes", () => {
  test("defaults on, and turning it off saves only show_todays_classes", async ({ page }) => {
    const branding = [
      property("public_page_enabled", true, "BOOLEAN"),
      property("sub_hero_enabled", true, "BOOLEAN"),
      property("cta_enabled", true, "BOOLEAN"),
      property("teacher_auto_rotate", false, "BOOLEAN"),
      // show_todays_classes not yet stored → default on.
    ];
    let putBody: Record<string, unknown> | null = null;
    await page.route("**/api/v1/**", (route) =>
      route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify(apiError("NOT_FOUND", "Not mocked")) })
    );
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess([property("institute_name", "Northwind Academy")]));
    await mockJson(page, "**/v1/tenant-assets/by-type/*", 404, apiError("NOT_FOUND", "No asset"));
    await page.route(
      (url) => url.pathname.endsWith("/v1/courses"),
      (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiPageSuccess([])) })
    );
    await page.route(
      (url) => url.pathname.endsWith("/v1/tenant-assets"),
      (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) })
    );
    await page.route("**/v1/tenant-config/BRANDING", async (route) => {
      if (route.request().method() === "PUT") {
        putBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess([...branding, property("show_todays_classes", false, "BOOLEAN")])),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(branding)) });
    });

    await page.goto("/tenant-admin/settings/appearance");
    const toggle = page.getByRole("switch", { name: "Show Today's Classes" });
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await page.getByRole("button", { name: "Save appearance" }).click();
    await expect(page.getByText("Saved", { exact: true })).toBeVisible();
    expect(putBody).toEqual({ show_todays_classes: false });
  });
});
