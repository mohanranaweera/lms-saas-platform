import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess } from "./fixtures/auth-mocks";
import {
  COURSE_ID,
  LESSON_ID,
  makeMaterial,
  setupStudentMaterialsMocks,
  studentMaterialsUrl,
} from "./fixtures/materials-mocks";
import { setupVideoMocks, VIDEO_ASSET_ID } from "./fixtures/video-mocks";

/**
 * VIDEO Phase 7 (B3-only WEB scope): Student YouTube lesson playback on the
 * lesson materials page (`components/player/youtube-lesson-player.tsx`),
 * driven by `POST /api/v1/playback/sessions` (`docs/api/playback-authorization.md`).
 * Every backend response is mocked with `page.route`; the YouTube embed
 * itself is never loaded (its requests are aborted).
 */

const MATERIAL_ID = "material-yt-1";
const YOUTUBE_VIDEO_ID = "dQw4w9WgXcQ";

function youTubeMaterial() {
  return makeMaterial({
    id: MATERIAL_ID,
    title: "Week 1 lecture",
    sequence: 1,
    materialType: "VIDEO",
    videoAssetId: VIDEO_ASSET_ID,
    originalFilename: null,
    mimeType: null,
    sizeBytes: null,
  });
}

function lessonMedia(provider: "YOUTUBE" | "INTERNAL") {
  return {
    id: MATERIAL_ID,
    lessonId: LESSON_ID,
    courseId: COURSE_ID,
    mediaAssetId: VIDEO_ASSET_ID,
    title: "Week 1 lecture",
    sequence: 1,
    visibility: "VISIBLE",
    provider,
    durationSeconds: 120,
    status: "READY",
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
  };
}

function descriptor(overrides: Record<string, unknown> = {}) {
  const expiresAt = new Date(Date.now() + 5 * 60_000).toISOString();
  return {
    sessionId: null,
    expiresAt,
    descriptor: {
      sessionId: null,
      provider: "YOUTUBE",
      providerVideoId: YOUTUBE_VIDEO_ID,
      signedUrl: null,
      expiresAt,
      title: "Week 1 lecture",
      durationSeconds: 120,
      watermarkText: null,
      allowSeeking: true,
      allowDownload: false,
      ...overrides,
    },
  };
}

interface PlaybackResponse {
  status: number;
  body: unknown;
}

/** Mocks the playback endpoint with a queue of responses (the last one repeats); records request bodies. */
async function mockPlayback(page: Page, responses: PlaybackResponse[]): Promise<unknown[]> {
  const requests: unknown[] = [];
  await page.route("**/api/v1/playback/sessions", async (route: Route) => {
    requests.push(route.request().postDataJSON());
    const next = responses[Math.min(requests.length - 1, responses.length - 1)];
    await route.fulfill({ status: next.status, contentType: "application/json", body: JSON.stringify(next.body) });
  });
  return requests;
}

async function setup(page: Page, provider: "YOUTUBE" | "INTERNAL" = "YOUTUBE") {
  // Never load the real YouTube player in tests.
  await page.route("https://www.youtube-nocookie.com/**", (route) => route.abort());
  await setupStudentMaterialsMocks(page, {
    materialsBody: apiSuccess([youTubeMaterial()]),
    lessonMediaBody: apiSuccess([lessonMedia(provider)]),
  });
}

test.describe("student YouTube lesson playback", () => {
  test("opening a YouTube lesson video authorizes playback and embeds only that video", async ({ page }) => {
    await setup(page);
    const requests = await mockPlayback(page, [{ status: 201, body: apiSuccess(descriptor()) }]);

    await page.goto(studentMaterialsUrl());
    await expect(page.getByRole("button", { name: "Open video" })).toBeVisible();
    expect(requests).toHaveLength(0);

    await page.getByRole("button", { name: "Open video" }).click();

    const frame = page.locator("iframe[title='Week 1 lecture']");
    await expect(frame).toHaveAttribute("src", `https://www.youtube-nocookie.com/embed/${YOUTUBE_VIDEO_ID}?rel=0&playsinline=1`);
    await expect(page.getByText("This video plays through YouTube.")).toBeVisible();
    // The client sends the LessonMedia id only - never a tenant, role or asset id.
    expect(requests[0]).toEqual({ lessonMediaId: MATERIAL_ID });
    // The legacy platform-storage player is not used for YouTube.
    await expect(page.locator("video")).toHaveCount(0);
  });

  test("a watermark goes in a band below the player, never over it", async ({ page }) => {
    await setup(page);
    await mockPlayback(page, [{ status: 201, body: apiSuccess(descriptor({ watermarkText: "student-42 · acme" })) }]);

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();

    const band = page.getByTestId("watermark-band");
    await expect(band).toHaveText("student-42 · acme");
    const frameBox = await page.locator("iframe").boundingBox();
    const bandBox = await band.boundingBox();
    expect(frameBox && bandBox && bandBox.y >= frameBox.y + frameBox.height).toBeTruthy();
  });

  test("a denied or foreign lesson video shows fixed copy and embeds nothing", async ({ page }) => {
    await setup(page);
    await mockPlayback(page, [
      { status: 404, body: apiError("NOT_FOUND", "Lesson media 1234 belongs to tenant B") },
    ]);

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();

    await expect(page.getByRole("listitem").getByRole("alert")).toContainText("This video isn't available.");
    await expect(page.getByText("tenant B")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
    await expect(page.locator("iframe")).toHaveCount(0);
  });

  test("a video still processing can be retried", async ({ page }) => {
    await setup(page);
    // Fail every request until the test flips it: React Strict Mode may fire the mount request twice in dev.
    let ready = false;
    const requests: unknown[] = [];
    await page.route("**/api/v1/playback/sessions", async (route: Route) => {
      requests.push(route.request().postDataJSON());
      const response = ready
        ? { status: 201, body: apiSuccess(descriptor()) }
        : { status: 409, body: apiError("MEDIA_NOT_READY", "This video is still processing.") };
      await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByRole("listitem").getByRole("alert")).toContainText("still processing");
    await expect(page.locator("iframe")).toHaveCount(0);

    ready = true;
    const before = requests.length;
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.locator("iframe")).toBeVisible();
    expect(requests.length).toBeGreaterThan(before);
  });

  test("YouTube playback switched off by the platform shows a calm message", async ({ page }) => {
    await setup(page);
    await mockPlayback(page, [
      { status: 409, body: apiError("VIDEO_PLAYBACK_DISABLED", "YouTube playback is not enabled.") },
    ]);

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByRole("listitem").getByRole("alert")).toContainText("YouTube videos can't be played right now.");
    await expect(page.locator("iframe")).toHaveCount(0);
  });

  test("an unexpected video id in the descriptor is never embedded", async ({ page }) => {
    await setup(page);
    await mockPlayback(page, [
      { status: 201, body: apiSuccess(descriptor({ providerVideoId: "abc\"><script>" })) },
    ]);

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByRole("listitem").getByRole("alert")).toContainText("This video isn't available.");
    await expect(page.locator("iframe")).toHaveCount(0);
  });

  test("a platform-storage lesson video keeps the legacy player and never calls the playback endpoint", async ({
    page,
  }) => {
    await setup(page, "INTERNAL");
    await setupVideoMocks(page);
    const requests = await mockPlayback(page, [{ status: 201, body: apiSuccess(descriptor()) }]);

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.locator("video")).toBeVisible();
    expect(requests).toHaveLength(0);
  });

  test("if the lesson's video list can't load, the video offers a retry", async ({ page }) => {
    await page.route("https://www.youtube-nocookie.com/**", (route) => route.abort());
    await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([youTubeMaterial()]),
      lessonMediaStatus: 500,
      lessonMediaBody: apiError("INTERNAL_ERROR", "boom"),
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByRole("listitem").getByRole("alert")).toContainText("Couldn't load this video.");
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });
});
