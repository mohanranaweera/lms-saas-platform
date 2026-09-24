import { test, expect } from "@playwright/test";
import { apiError, apiSuccess } from "./fixtures/auth-mocks";
import { makeMaterial, setupStudentMaterialsMocks, studentMaterialsUrl } from "./fixtures/materials-mocks";
import { setupVideoMocks, VIDEO_ASSET_ID } from "./fixtures/video-mocks";

/**
 * Student "Lesson/Material View" — Wave 5 per-type rendering (plan §4/§5):
 * a `NOTE` renders inline, a `LINK` opens `externalUrl` in a new tab via a
 * fresh `download-url` fetch, a `VIDEO`/`RECORDING` mounts the secure player
 * only once its accordion is expanded, and the three new type-dependent
 * download-url 403s (`MATERIAL_NOT_YET_AVAILABLE`/`MATERIAL_EXPIRED`/
 * `DOWNLOAD_LIMIT_REACHED`) each render their own clear inline message.
 */

test.describe("student materials view — NOTE", () => {
  test("renders noteContent inline as plain text, with no download action", async ({ page }) => {
    const material = makeMaterial({
      id: "material-note-1",
      title: "Reading tip",
      sequence: 1,
      materialType: "NOTE",
      noteContent: "Read chapter 3 before next class.",
      originalFilename: null,
      mimeType: null,
      sizeBytes: null,
    });
    await setupStudentMaterialsMocks(page, { materialsBody: apiSuccess([material]) });
    await page.goto(studentMaterialsUrl());

    await expect(page.getByText("Read chapter 3 before next class.")).toBeVisible();
    await expect(page.getByRole("button", { name: "View" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Open link" })).toHaveCount(0);
  });
});

test.describe("student materials view — LINK", () => {
  test("Open link fetches a fresh download-url and opens the returned url in a new tab", async ({
    page,
  }) => {
    const material = makeMaterial({
      id: "material-link-1",
      title: "Bonus explainer",
      sequence: 1,
      materialType: "LINK",
      externalUrl: "https://example.com/watch?v=xyz",
      originalFilename: null,
      mimeType: null,
      sizeBytes: null,
    });
    const signedUrl = "https://example.com/watch?v=xyz";
    const state = await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrl: { url: signedUrl, expiresAt: new Date(Date.now() + 3_600_000).toISOString() },
    });

    await page.addInitScript(() => {
      (window as unknown as { __openedUrls: unknown[] }).__openedUrls = [];
      window.open = (...args: Parameters<typeof window.open>) => {
        (window as unknown as { __openedUrls: unknown[] }).__openedUrls.push(args);
        return null;
      };
    });

    await page.goto(studentMaterialsUrl());
    await expect(page.getByText("Bonus explainer")).toBeVisible();

    await page.getByRole("button", { name: "Open link" }).click();

    await expect.poll(() => state.downloadUrlCallCount).toBe(1);
    const openedUrls = await page.evaluate(
      () => (window as unknown as { __openedUrls: unknown[] }).__openedUrls
    );
    expect(openedUrls).toEqual([[signedUrl, "_blank", "noopener,noreferrer"]]);
  });
});

test.describe("student materials view — VIDEO", () => {
  test("mounts the secure video player only once 'Open video' is expanded, then issues a playback session", async ({
    page,
  }) => {
    const material = makeMaterial({
      id: "material-video-1",
      title: "Week 1 lecture",
      sequence: 1,
      materialType: "VIDEO",
      videoAssetId: VIDEO_ASSET_ID,
      originalFilename: null,
      mimeType: null,
      sizeBytes: null,
    });
    await setupStudentMaterialsMocks(page, { materialsBody: apiSuccess([material]) });
    const videoState = await setupVideoMocks(page);

    await page.goto(studentMaterialsUrl());
    await expect(page.getByText("Week 1 lecture")).toBeVisible();

    // No playback session before the accordion is expanded.
    expect(videoState.playbackSessionCalls).toBe(0);

    await page.getByRole("button", { name: "Open video" }).click();

    // At least one playback session is issued once expanded (never before).
    // Not asserted as exactly 1: `npm run dev`'s React Strict Mode
    // double-invokes effects on mount in this local/dev Playwright
    // configuration (see `playwright.config.ts`'s own comment on `dev` vs.
    // `start`), which can issue a second session — this is a dev-only
    // characteristic of the mount effect itself, not a defect in the
    // player's session-issuance logic, and does not occur against a
    // production build (CI's `npm run start`).
    await expect.poll(() => videoState.playbackSessionCalls).toBeGreaterThan(0);
    await expect(page.locator("video")).toBeVisible();
  });
});

test.describe("student materials view — type-dependent download-url 403s", () => {
  test("MATERIAL_NOT_YET_AVAILABLE renders a clear inline message, not a generic failure", async ({
    page,
  }) => {
    const material = makeMaterial({ id: "material-1", title: "Handout", sequence: 1 });
    await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrlStatus: 403,
      downloadUrlBody: apiError("MATERIAL_NOT_YET_AVAILABLE", "This material is not yet available"),
    });
    await page.goto(studentMaterialsUrl());

    await page.getByRole("button", { name: "View" }).click();
    await expect(page.getByText("This material isn't available yet.")).toBeVisible();
  });

  test("MATERIAL_EXPIRED renders a clear inline message, not a generic failure", async ({ page }) => {
    const material = makeMaterial({ id: "material-1", title: "Handout", sequence: 1 });
    await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrlStatus: 403,
      downloadUrlBody: apiError("MATERIAL_EXPIRED", "This material's availability window has expired"),
    });
    await page.goto(studentMaterialsUrl());

    await page.getByRole("button", { name: "View" }).click();
    await expect(page.getByText("This material has expired.")).toBeVisible();
  });

  test("DOWNLOAD_LIMIT_REACHED renders a clear inline message, not a generic failure", async ({ page }) => {
    const material = makeMaterial({ id: "material-1", title: "Handout", sequence: 1 });
    await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrlStatus: 403,
      downloadUrlBody: apiError("DOWNLOAD_LIMIT_REACHED", "This material's download limit has been reached"),
    });
    await page.goto(studentMaterialsUrl());

    await page.getByRole("button", { name: "View" }).click();
    await expect(page.getByText("The download limit for this material has been reached.")).toBeVisible();
  });

  test("a generic 403/404 still renders the fixed anti-enumeration copy, never the backend's message", async ({
    page,
  }) => {
    const material = makeMaterial({ id: "material-1", title: "Handout", sequence: 1 });
    const distinctiveBackendMessage = "xyz-should-never-render-in-ui";
    await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrlStatus: 404,
      downloadUrlBody: apiError("NOT_FOUND", distinctiveBackendMessage),
    });
    await page.goto(studentMaterialsUrl());

    await page.getByRole("button", { name: "View" }).click();
    await expect(
      page.getByText("This material isn't available. It may have been removed, or you may not have access to it.")
    ).toBeVisible();
    await expect(page.getByText(distinctiveBackendMessage)).toHaveCount(0);
  });
});
