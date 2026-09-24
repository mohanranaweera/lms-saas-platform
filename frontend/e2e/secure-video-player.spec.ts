import { test, expect } from "@playwright/test";
import { apiSuccess } from "./fixtures/auth-mocks";
import { makeMaterial, setupStudentMaterialsMocks, studentMaterialsUrl } from "./fixtures/materials-mocks";
import { makePlaybackSession, setupVideoMocks, VIDEO_ASSET_ID } from "./fixtures/video-mocks";

/**
 * `components/courses/secure-video-player.tsx` smoke coverage, mounted via
 * the Student materials page's "Open video" accordion (its real, only
 * production mount point — no standalone test harness route exists for this
 * component). No real backend/object-storage/video-decode runs in this
 * environment: the mocked `signedUrl` is never actually played by the
 * browser here — every assertion below only depends on the player's own
 * state machine (session issuance, watermark render, heartbeat handling),
 * driven via a dispatched native `play` event (see below) plus Playwright's
 * `page.clock` to deterministically fast-forward the 12s heartbeat interval
 * without a real 12-second wait or real video decoding.
 */

const HEARTBEAT_INTERVAL_MS = 12_000;

async function openVideoMaterial(page: import("@playwright/test").Page) {
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
}

test.describe("secure video player — watermark", () => {
  test("renders the watermark overlay text from the mocked playback session", async ({ page }) => {
    await openVideoMaterial(page);
    await setupVideoMocks(page, {
      playbackSession: { watermarkText: "jane-doe-student-42 · acme-tenant" },
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();

    await expect(page.getByText("jane-doe-student-42", { exact: false })).toBeVisible();
  });

  test("renders no watermark overlay when the session returns an empty watermarkText", async ({ page }) => {
    await openVideoMaterial(page);
    await setupVideoMocks(page, { playbackSession: { watermarkText: null } });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.locator("video")).toBeVisible();

    // The default mock's watermark text must not leak in when this test's
    // own override sets it to `null`.
    await expect(page.getByText(makePlaybackSession().watermarkText!, { exact: false })).toHaveCount(0);
  });
});

test.describe("secure video player — policy violation heartbeat", () => {
  test("a mocked 409 POLICY_VIOLATION heartbeat pauses playback and shows the terminal message", async ({
    page,
  }) => {
    await page.clock.install();
    await openVideoMaterial(page);
    await setupVideoMocks(page, {
      progressFailures: [
        { status: 409, code: "POLICY_VIOLATION", message: "Maximum watch duration reached" },
      ],
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();

    const video = page.locator("video");
    await expect(video).toBeVisible();

    // Dispatching the native `play` event (rather than calling the real
    // `HTMLMediaElement.play()`, which would require the mocked `signedUrl`
    // to be real, decodable media) is enough to flip the component's
    // `isPlaying` state, since React binds media element events directly to
    // the DOM node — this starts the heartbeat interval without any real
    // video decoding in this environment.
    await video.dispatchEvent("play");

    await page.clock.fastForward(HEARTBEAT_INTERVAL_MS);

    await expect(
      page.getByText("This viewing session has ended because a limit was reached.")
    ).toBeVisible();
    // The player tears itself down on a policy violation — no `<video>` left mounted.
    await expect(page.locator("video")).toHaveCount(0);
  });

  test("a mocked 409 SEEK_NOT_ALLOWED heartbeat shows a brief notice without ending the session", async ({
    page,
  }) => {
    await page.clock.install();
    await openVideoMaterial(page);
    await setupVideoMocks(page, {
      playbackSession: { allowSeeking: false },
      progressFailures: [{ status: 409, code: "SEEK_NOT_ALLOWED", message: "Seeking is not allowed" }],
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();

    const video = page.locator("video");
    await expect(video).toBeVisible();
    await video.dispatchEvent("play");
    await page.clock.fastForward(HEARTBEAT_INTERVAL_MS);

    await expect(page.getByText("Seeking isn't allowed for this video.")).toBeVisible();
    // The session stays active — the player itself remains mounted.
    await expect(page.locator("video")).toBeVisible();
  });
});
