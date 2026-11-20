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

  test("a 409 PLAYBACK_WATCH_LIMIT heartbeat ends playback for good with the watch-time message", async ({
    page,
  }) => {
    await page.clock.install();
    await openVideoMaterial(page);
    await setupVideoMocks(page, {
      progressFailures: [
        { status: 409, code: "PLAYBACK_WATCH_LIMIT", message: "You have used all the watch time allowed for this video." },
      ],
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    const video = page.locator("video");
    await expect(video).toBeVisible();
    await video.dispatchEvent("play");

    await page.clock.fastForward(HEARTBEAT_INTERVAL_MS);

    await expect(page.getByText("You've used all the watch time allowed for this video.", { exact: false })).toBeVisible();
    await expect(page.locator("video")).toHaveCount(0);
    // Terminal: no reload offered, the limit doesn't go away by retrying.
    await expect(page.getByRole("button", { name: "Reload" })).toHaveCount(0);
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

test.describe("secure video player — DynTube HLS (VIDEO Phase 13)", () => {
  const PLAYLIST = "https://api.dyntube.test/v1/live/videos/tokens/dtKey000001.m3u8?token=signed&expires=1";

  test("an HLS session loads its signed playlist through hls.js, not as a file URL", async ({ page }) => {
    await openVideoMaterial(page);
    await setupVideoMocks(page, { playbackSession: { mediaFormat: "HLS", signedUrl: PLAYLIST } });
    const playlistRequests: string[] = [];
    await page.route("https://api.dyntube.test/**", async (route) => {
      playlistRequests.push(route.request().url());
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "application/vnd.apple.mpegurl", "Access-Control-Allow-Origin": "*" },
        body: "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-ENDLIST\n",
      });
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();

    await expect.poll(() => playlistRequests.length).toBeGreaterThan(0);
    expect(playlistRequests[0]).toBe(PLAYLIST);
    // hls.js feeds the element through Media Source Extensions: its src is a blob URL, never the playlist.
    await expect.poll(() => page.locator("video").evaluate((el: HTMLVideoElement) => el.src)).toMatch(/^blob:/);
    await expect(page.getByText("student-1", { exact: false })).toBeVisible();
  });

  test("a decryption key the host refuses ends in a clear message instead of a retry loop", async ({ page }) => {
    await openVideoMaterial(page);
    await setupVideoMocks(page, { playbackSession: { mediaFormat: "HLS", signedUrl: PLAYLIST } });
    const cors = { "Access-Control-Allow-Origin": "*" };
    await page.route("https://api.dyntube.test/**", async (route) => {
      const url = route.request().url();
      if (url.includes("/hls-key/")) {
        await route.fulfill({ status: 400, headers: { ...cors, "Content-Type": "application/problem+json" }, body: "{}" });
      } else if (url.includes(".m3u8")) {
        await route.fulfill({
          status: 200,
          headers: { ...cors, "Content-Type": "application/vnd.apple.mpegurl" },
          body:
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n" +
            '#EXT-X-KEY:METHOD=AES-128,URI="https://api.dyntube.test/v1/live/videos/hls-key/k/dtKey000001"\n' +
            "#EXTINF:6,\nhttps://api.dyntube.test/seg0.ts\n#EXT-X-ENDLIST\n",
        });
      } else {
        await route.fulfill({ status: 200, headers: { ...cors, "Content-Type": "video/MP2T" }, body: "" });
      }
    });

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "The video host refused to unlock this video" })
    ).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });
});
