import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, mockJson } from "./fixtures/auth-mocks";

/**
 * Tenant public home + chrome driven by the Appearance settings
 * (docs/specs/tenant-appearance-public-home-parity.md). Every backend call is
 * intercepted: `GET /v1/public/tenant-config/home`, `/v1/public/teachers*`,
 * `/v1/public/courses*`, and the auth refresh calls the public header makes to
 * detect an existing session (anonymous here). Images are served from a fake
 * `https://assets.test` origin. All institute data below is invented.
 */

const WIDE_LOGO = "https://assets.test/logo-wide.svg";
const DESKTOP_BANNER = "https://assets.test/banner-desktop.svg";
const MOBILE_BANNER = "https://assets.test/banner-mobile.svg";

function svg(width: number, height: number, fill: string) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><rect width="${width}" height="${height}" fill="${fill}"/></svg>`;
}

const BASE_HOME = {
  publicPageEnabled: true,
  instituteName: "Northwind Academy",
  logoUrl: WIDE_LOGO as string | null,
  faviconUrl: null as string | null,
  homepageUrl: null as string | null,
  subHeroEnabled: true,
  ctaEnabled: true,
  supportVideoUrl: null as string | null,
  desktopBannerUrls: [] as string[],
  mobileBannerUrls: [] as string[],
  featuredCourses: [
    {
      id: "c1",
      name: "Applied Chemistry",
      slug: "applied-chemistry",
      category: "Science",
      pricingModel: "MONTHLY",
      amount: 2500,
      currency: "LKR",
      requiresManualQuote: false,
      coverUrl: null,
    },
  ],
  theme: {
    primaryColor: "#15803D",
    accentColor: "#CA8A04",
    backgroundColor: "#F7FBF8",
    surfaceColor: "#FFFFFF",
    textColor: "#14281D",
    headingFont: "POPPINS",
    bodyFont: "LATO",
  },
  logoDisplaySize: "STANDARD",
  teacherAutoRotate: false,
  supportEmail: "help@northwind.example",
  supportPhone: "+94 11 000 0000",
};

type Home = typeof BASE_HOME;

function teacher(index: number) {
  return {
    id: `t-${index}`,
    name: `Teacher ${index}`,
    qualifications: `BSc (Hons) Subject ${index}`,
    description: null,
    profileImageUrl: null,
    courses: [],
  };
}

async function mockPublic(page: Page, home: Partial<Home> = {}, teacherCount = 0) {
  await page.route("https://assets.test/**", (route) => {
    const url = route.request().url();
    const body = url.includes("logo-wide") ? svg(600, 180, "#0B3D2E") : url.includes("banner-mobile") ? svg(800, 300, "#334155") : svg(1920, 480, "#1E293B");
    return route.fulfill({ status: 200, contentType: "image/svg+xml", body });
  });
  await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess({ ...BASE_HOME, ...home }));
  await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess(Array.from({ length: teacherCount }, (_, i) => teacher(i + 1))));
  await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
}

test.describe("public chrome", () => {
  test("renders exactly one tenant header and footer with institute details", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockPublic(page);
    await page.goto("/");

    await expect(page.getByTestId("public-site-header")).toHaveCount(1);
    await expect(page.getByTestId("public-site-footer")).toHaveCount(1);
    await expect(page.getByRole("banner")).toHaveCount(1);
    await expect(page.getByRole("contentinfo")).toHaveCount(1);
    await expect(page.getByText("LMS Platform")).toHaveCount(0);

    const header = page.getByTestId("public-site-header");
    await expect(header.getByRole("link", { name: "Northwind Academy home" })).toHaveAttribute("href", "/");
    await expect(header.getByRole("link", { name: "Courses" })).toHaveAttribute("href", "/courses");
    await expect(header.getByRole("link", { name: "Teachers" })).toHaveAttribute("href", "/teachers");
    await expect(header.getByRole("link", { name: "Support" })).toHaveAttribute("href", "/#support");
    await expect(header.getByRole("link", { name: "Login" })).toHaveAttribute("href", "/login");
    await expect(header.getByRole("link", { name: "Register" })).toHaveAttribute("href", "/register");

    const footer = page.getByTestId("public-site-footer");
    await expect(footer.getByText(`© ${new Date().getFullYear()} Northwind Academy. All rights reserved.`)).toBeVisible();
    await expect(footer.getByRole("link", { name: "+94 11 000 0000" })).toHaveAttribute("href", "tel:+94110000000");
    await expect(footer.getByRole("link", { name: "help@northwind.example" })).toHaveAttribute("href", "mailto:help@northwind.example");
    await expect(page).toHaveTitle("Northwind Academy");
  });

  test("other public routes inherit the same tenant chrome", async ({ page }) => {
    await mockPublic(page);
    await page.goto("/courses");
    await expect(page.getByTestId("public-site-header")).toHaveCount(1);
    await expect(page.getByRole("link", { name: "Northwind Academy home" })).toBeVisible();
    await expect(page.getByTestId("public-site-footer")).toHaveCount(1);
  });

  test("an external homepage URL becomes the logo destination", async ({ page }) => {
    await mockPublic(page, { homepageUrl: "https://www.northwind.example/" });
    await page.goto("/");
    await expect(page.getByRole("link", { name: "Northwind Academy home" })).toHaveAttribute("href", "https://www.northwind.example/");
  });

  test("without a logo a monogram tile and the institute name are shown", async ({ page }) => {
    await mockPublic(page, { logoUrl: null });
    await page.goto("/");
    const logo = page.getByTestId("public-logo-header");
    await expect(logo).toHaveAttribute("data-fallback", "monogram");
    await expect(logo).toContainText("NA");
    await expect(logo).toContainText("Northwind Academy");
  });

  test("mobile menu is an accessible disclosure", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await mockPublic(page);
    await page.goto("/");
    const toggle = page.getByRole("button", { name: "Open menu" });
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await toggle.click();
    await expect(page.getByRole("button", { name: "Close menu" })).toHaveAttribute("aria-expanded", "true");
    await expect(page.getByRole("navigation", { name: "Mobile" }).getByRole("link", { name: "Teachers" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", { name: "Open menu" })).toHaveAttribute("aria-expanded", "false");
  });
});

test.describe("logo size tokens", () => {
  test("a wide logo renders at the Standard token size on desktop and mobile", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockPublic(page);
    await page.goto("/");
    const img = page.getByTestId("public-site-header").getByRole("img", { name: "Northwind Academy" });
    await expect(img).toBeVisible();
    await expect.poll(async () => (await img.boundingBox())?.height).toBe(56);
    const desktop = await img.boundingBox();
    expect(desktop!.width).toBeGreaterThanOrEqual(160);
    expect(desktop!.width).toBeLessThanOrEqual(220);

    await page.setViewportSize({ width: 390, height: 844 });
    await expect.poll(async () => (await img.boundingBox())?.height).toBe(44);
    expect((await img.boundingBox())!.width).toBeLessThanOrEqual(170);
  });

  test("Large and Small tokens change the reserved height", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockPublic(page, { logoDisplaySize: "LARGE" });
    await page.goto("/");
    const img = page.getByTestId("public-site-header").getByRole("img", { name: "Northwind Academy" });
    await expect.poll(async () => (await img.boundingBox())?.height).toBe(68);
    expect((await img.boundingBox())!.width).toBeLessThanOrEqual(260);

    await page.unrouteAll({ behavior: "ignoreErrors" });
    await mockPublic(page, { logoDisplaySize: "SMALL" });
    await page.reload();
    await expect.poll(async () => (await img.boundingBox())?.height).toBe(44);
  });
});

test.describe("tenant theme scope", () => {
  test("colors and fonts apply on .tenant-public-theme only, never on <html>", async ({ page }) => {
    await mockPublic(page);
    await page.goto("/");
    const scope = page.locator(".tenant-public-theme").first();
    await expect(scope).toHaveAttribute("data-heading-font", "POPPINS");

    const vars = await scope.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        primary: style.getPropertyValue("--primary").trim(),
        background: style.getPropertyValue("--background").trim(),
        foreground: style.getPropertyValue("--foreground").trim(),
        destructive: style.getPropertyValue("--destructive").trim(),
      };
    });
    expect(vars.primary).toBe("#15803D");
    expect(vars.background).toBe("#F7FBF8");
    expect(vars.foreground).toBe("#14281D");

    const root = await page.evaluate(() => ({
      primary: getComputedStyle(document.documentElement).getPropertyValue("--primary").trim(),
      inline: document.documentElement.getAttribute("style") ?? "",
      destructive: getComputedStyle(document.documentElement).getPropertyValue("--destructive").trim(),
    }));
    expect(root.primary).not.toBe("#15803D");
    expect(root.inline).not.toContain("--primary");
    // Semantic tokens are inherited unchanged from the platform defaults.
    expect(vars.destructive).toBe(root.destructive);

    // Primary button actually uses the tenant color.
    const register = page.getByTestId("public-site-header").getByRole("link", { name: "Register" });
    await expect(register).toHaveCSS("background-color", "rgb(21, 128, 61)");
  });

  test("heading and body fonts differ and follow the tenant choice", async ({ page }) => {
    await mockPublic(page);
    await page.goto("/");
    const heading = page.getByRole("heading", { name: "Featured courses" });
    const body = page.getByText("Hand-picked courses to start with.");
    const headingFont = await heading.evaluate((element) => getComputedStyle(element).fontFamily);
    const bodyFont = await body.evaluate((element) => getComputedStyle(element).fontFamily);
    expect(headingFont).toContain("Poppins");
    expect(bodyFont).toContain("Lato");
    expect(headingFont).not.toBe(bodyFont);
    expect(bodyFont).toMatch(/sans-serif/);
  });
});

test.describe("home sections", () => {
  test("sub-hero and CTA appear when enabled and disappear when disabled", async ({ page }) => {
    await mockPublic(page);
    await page.goto("/");
    await expect(page.getByTestId("sub-hero")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Get an incredible learning experience" })).toBeVisible();
    await expect(page.getByTestId("cta-section")).toBeVisible();

    await page.unrouteAll({ behavior: "ignoreErrors" });
    await mockPublic(page, { subHeroEnabled: false, ctaEnabled: false });
    await page.reload();
    await expect(page.getByTestId("featured-courses")).toBeVisible();
    await expect(page.getByTestId("sub-hero")).toHaveCount(0);
    await expect(page.getByTestId("cta-section")).toHaveCount(0);
  });

  test("featured course cards show the shared price label and link by slug", async ({ page }) => {
    await mockPublic(page);
    await page.goto("/");
    const section = page.getByTestId("featured-courses");
    await expect(section.getByTestId("featured-price")).toContainText("/month");
    await expect(section.getByRole("link", { name: /View details for Applied Chemistry/ })).toHaveAttribute("href", "/courses/applied-chemistry");
  });

  test("without banners a branded hero is shown; uploaded banners use a picture with mobile source", async ({ page }) => {
    await mockPublic(page);
    await page.goto("/");
    await expect(page.getByTestId("branded-hero")).toBeVisible();
    await expect(page.getByRole("heading", { level: 1, name: "Northwind Academy" })).toBeVisible();
    await expect(page.getByTestId("hero-banners")).toHaveCount(0);

    await page.unrouteAll({ behavior: "ignoreErrors" });
    await mockPublic(page, { desktopBannerUrls: [DESKTOP_BANNER, `${DESKTOP_BANNER}?2`], mobileBannerUrls: [MOBILE_BANNER] });
    await page.reload();
    const hero = page.getByTestId("hero-banners");
    await expect(hero).toBeVisible();
    await expect(hero.locator("source")).toHaveAttribute("srcset", MOBILE_BANNER);
    await expect(hero.getByRole("img")).toHaveAttribute("alt", "Northwind Academy banner 1 of 2");
    await hero.getByRole("button", { name: "Next banner" }).click();
    await expect(hero.getByRole("img")).toHaveAttribute("alt", "Northwind Academy banner 2 of 2");
    await expect(hero.getByRole("button", { name: "Show banner 2" })).toHaveAttribute("aria-current", "true");
  });

  test("disabled public page shows an unavailable card with sign-in, chrome still once", async ({ page }) => {
    await mockPublic(page, { publicPageEnabled: false });
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Public page unavailable" })).toBeVisible();
    await expect(page.getByRole("main").getByRole("link", { name: "Sign in to the portal" })).toHaveAttribute("href", "/login");
    await expect(page.getByTestId("public-site-header")).toHaveCount(1);
    await expect(page.getByTestId("featured-courses")).toHaveCount(0);
  });

  test("support section is reachable at #support and embeds a configured YouTube video", async ({ page }) => {
    await mockPublic(page, { supportVideoUrl: "https://www.youtube.com/watch?v=dQw4w9WgXcQ" });
    await page.route("https://www.youtube-nocookie.com/**", (route) => route.fulfill({ status: 200, contentType: "text/html", body: "<html></html>" }));
    await page.goto("/#support");
    const support = page.locator("#support");
    await expect(support).toBeVisible();
    await expect(support.locator("iframe")).toHaveAttribute("src", "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ");
  });

  test("no horizontal overflow at 320 px", async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 700 });
    await mockPublic(page, { desktopBannerUrls: [DESKTOP_BANNER], mobileBannerUrls: [MOBILE_BANNER] }, 5);
    await page.goto("/");
    await expect(page.getByTestId("teacher-section")).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  });
});

test.describe("teacher carousel", () => {
  test("hidden when there are no public teachers", async ({ page }) => {
    await mockPublic(page, {}, 0);
    await page.goto("/");
    await expect(page.getByTestId("featured-courses")).toBeVisible();
    await expect(page.getByTestId("teacher-section")).toHaveCount(0);
  });

  test("a single teacher renders one card without controls", async ({ page }) => {
    await mockPublic(page, {}, 1);
    await page.goto("/");
    const section = page.getByTestId("teacher-section");
    await expect(section.getByRole("group", { name: "1 of 1" })).toBeVisible();
    await expect(section.getByRole("button", { name: "Next teachers" })).toHaveCount(0);
    await expect(section.getByRole("link", { name: /View profile of Teacher 1/ })).toHaveAttribute("href", "/teachers/t-1");
  });

  test("many teachers: prev/next stop at the ends, slides are labelled, no auto-rotate by default", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockPublic(page, {}, 8);
    await page.goto("/");
    const section = page.getByTestId("teacher-section");
    const track = section.getByRole("region", { name: "Teachers" });
    await expect(track).toHaveAttribute("aria-roledescription", "carousel");
    await expect(section.getByRole("group", { name: "8 of 8" })).toBeAttached();
    await expect(track).toHaveAttribute("data-rotating", "false");
    await expect(section.getByRole("button", { name: /Pause teacher rotation/ })).toHaveCount(0);

    const previous = section.getByRole("button", { name: "Previous teachers" });
    const next = section.getByRole("button", { name: "Next teachers" });
    await expect(previous).toBeDisabled();
    await expect(next).toBeEnabled();
    await next.click();
    await expect.poll(() => track.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
    await expect(previous).toBeEnabled();

    // 8 cards, 4 visible at lg: four steps reach the end and Next disables (no loop).
    for (let i = 0; i < 4; i += 1) {
      if (await next.isDisabled()) break;
      await next.click();
      await page.waitForTimeout(400);
    }
    await expect(next).toBeDisabled();
    await expect(section.getByRole("link", { name: /View profile of Teacher 8/ })).toHaveAttribute("href", "/teachers/t-8");
  });

  test("auto-rotate shows a Pause control and is off under reduced motion", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await mockPublic(page, { teacherAutoRotate: true }, 8);
    await page.goto("/");
    const section = page.getByTestId("teacher-section");
    const track = section.getByRole("region", { name: "Teachers" });
    await expect(track).toHaveAttribute("data-rotating", "true");
    await section.getByRole("button", { name: "Pause teacher rotation" }).click();
    await expect(track).toHaveAttribute("data-rotating", "false");
    await expect(section.getByRole("button", { name: "Resume teacher rotation" })).toBeVisible();

    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.reload();
    await expect(section.getByRole("region", { name: "Teachers" })).toHaveAttribute("data-rotating", "false");
    await expect(section.getByRole("button", { name: /teacher rotation/ })).toHaveCount(0);
  });
});
