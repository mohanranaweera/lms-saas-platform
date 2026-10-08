import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import {
  COURSE_ID,
  LESSON_ID,
  makeMaterial,
  setupStudentMaterialsMocks,
  studentMaterialsUrl,
} from "./fixtures/materials-mocks";
import { VIDEO_ASSET_ID } from "./fixtures/video-mocks";

/**
 * VIDEO Phase 8 (ADR-025): AOC Player screens. No backend and no real player:
 * every endpoint is mocked with `page.route`, and `window.__aocLaunch` captures
 * the `aocplayer://` links the page hands to the OS. The backend stays the only
 * authority - these specs check the UI reflects its answers and never invents
 * state.
 */

const CODE = "A".repeat(43);
const PAIR_URL = `aocplayer://pair?code=${CODE}`;
const PLAY_URL = `aocplayer://play?code=${CODE}`;
const SESSION_ID = "5e550000-0000-0000-0000-000000000001";
const MATERIAL_ID = "material-native-1";
const STUDENT_PROFILE_ID = "11111111-1111-1111-1111-111111111111";

function installation(overrides: Record<string, unknown> = {}) {
  return {
    id: "i0000000-0000-0000-0000-000000000001",
    deviceId: "d0000000-0000-0000-0000-000000000001",
    platform: "WINDOWS",
    playerVersion: "1.0.0",
    status: "ACTIVE",
    pairedAt: "2026-10-01T09:00:00Z",
    lastSeenAt: "2026-10-06T09:00:00Z",
    revokedAt: null,
    revocationSource: null,
    ...overrides,
  };
}

async function captureLaunches(page: Page): Promise<() => Promise<string[]>> {
  await page.addInitScript(() => {
    (window as unknown as { __aocLaunched: string[] }).__aocLaunched = [];
    (window as unknown as { __aocLaunch: (url: string) => void }).__aocLaunch = (url: string) => {
      (window as unknown as { __aocLaunched: string[] }).__aocLaunched.push(url);
    };
  });
  return () => page.evaluate(() => (window as unknown as { __aocLaunched: string[] }).__aocLaunched);
}

async function mockSession(page: Page, role: string) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/notifications/unread-count", 200, apiSuccess({ unreadCount: 0 }));
}

// ---------------------------------------------------------------------------
// Student - AOC Player page
// ---------------------------------------------------------------------------

test.describe("student AOC Player page", () => {
  test("pairing opens a one-time link and shows the new player once the backend lists it", async ({ page }) => {
    const launched = await captureLaunches(page);
    await mockSession(page, "STUDENT");
    let paired = false;
    await page.route("**/v1/player/installations", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(paired ? [installation()] : [])),
      })
    );
    await page.route("**/v1/player/pairings", (route) =>
      route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ pairingUrl: PAIR_URL, expiresAt: new Date(Date.now() + 90_000).toISOString() })),
      })
    );

    await page.goto("/student/player");
    await expect(page.getByText("No AOC Player paired yet")).toBeVisible();

    await page.getByRole("button", { name: "Pair the AOC Player" }).click();
    await expect(page.getByText("Waiting for the AOC Player to finish pairing…")).toBeVisible();
    expect(await launched()).toEqual([PAIR_URL]);

    paired = true;
    await expect(page.getByText("The AOC Player is paired.")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("list", { name: "Your AOC Players" })).toContainText("Version 1.0.0");
  });

  test("pairing again on this computer is recognised although the number of players stays the same", async ({ page }) => {
    // Re-pairing replaces this device's player: the old one is revoked (REPAIRED), a new one is active.
    const launched = await captureLaunches(page);
    await mockSession(page, "STUDENT");
    const oldPlayer = installation();
    const newPlayer = installation({ id: "i0000000-0000-0000-0000-000000000002", pairedAt: "2026-10-08T03:34:04Z" });
    let repaired = false;
    await page.route("**/v1/player/installations", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess(
            repaired
              ? [newPlayer, { ...oldPlayer, status: "REVOKED", revokedAt: "2026-10-08T03:34:04Z", revocationSource: "REPAIRED" }]
              : [oldPlayer]
          )
        ),
      })
    );
    await page.route("**/v1/player/pairings", (route) =>
      route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ pairingUrl: PAIR_URL, expiresAt: new Date(Date.now() + 90_000).toISOString() })),
      })
    );

    await page.goto("/student/player");
    await expect(page.getByRole("list", { name: "Your AOC Players" })).toContainText("Active");
    await page.getByRole("button", { name: "Pair the AOC Player" }).click();
    await expect(page.getByText("Waiting for the AOC Player to finish pairing…")).toBeVisible();
    expect(await launched()).toEqual([PAIR_URL]);

    repaired = true;
    await expect(page.getByText("The AOC Player is paired.")).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole("list", { name: "Your AOC Players" })).toContainText("replaced by a newer pairing");
  });

  test("a platform switch-off or an unregistered browser explains what to do and opens nothing", async ({ page }) => {
    const launched = await captureLaunches(page);
    await mockSession(page, "STUDENT");
    await mockJson(page, "**/v1/player/installations", 200, apiSuccess([]));
    await mockJson(
      page,
      "**/v1/player/pairings",
      409,
      apiError("PLAYER_DEVICE_NOT_REGISTERED", "Sign in again on this device to use the AOC Player.")
    );

    await page.goto("/student/player");
    await page.getByRole("button", { name: "Pair the AOC Player" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Sign out and sign in again on this device" })).toBeVisible();
    expect(await launched()).toEqual([]);
  });

  test("a student can remove an active player; removed players say why", async ({ page }) => {
    await mockSession(page, "STUDENT");
    let removed = false;
    await page.route("**/v1/player/installations", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess([
            removed
              ? installation({ status: "REVOKED", revokedAt: "2026-10-07T09:00:00Z", revocationSource: "STUDENT" })
              : installation(),
            installation({ id: "old", status: "REVOKED", revocationSource: "REPAIRED", revokedAt: "2026-10-01T09:00:00Z" }),
          ])
        ),
      })
    );
    let revokedId: string | null = null;
    await page.route("**/v1/player/installations/*/revoke", (route) => {
      revokedId = new URL(route.request().url()).pathname.split("/").slice(-2, -1)[0];
      removed = true;
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(installation({ status: "REVOKED", revocationSource: "STUDENT" }))),
      });
    });

    await page.goto("/student/player");
    const list = page.getByRole("list", { name: "Your AOC Players" });
    await expect(list).toContainText("replaced by a newer pairing");
    await page.getByRole("button", { name: "Remove this AOC Player" }).click();
    await page.getByRole("button", { name: "Remove player" }).click();
    await expect(list).toContainText("removed by you");
    expect(revokedId).toBe("i0000000-0000-0000-0000-000000000001");
    await expect(page.getByRole("button", { name: "Remove this AOC Player" })).toHaveCount(0);
  });

  test("the student navigation links to the AOC Player page", async ({ page }) => {
    await mockSession(page, "STUDENT");
    await mockJson(page, "**/v1/player/installations", 200, apiSuccess([]));
    await page.goto("/student/player");
    await expect(page.getByRole("link", { name: "AOC Player" }).first()).toHaveAttribute("href", "/student/player");
  });

  // VIDEO Phase 9: the installer URL is a build-time setting, unset until signed
  // releases exist. The dev server Playwright starts inherits this process's
  // environment, so each state runs when the environment matches it.
  const downloadUrl = process.env.NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL;

  test("the install step says the download isn't available while no installer is configured", async ({ page }) => {
    test.skip(!!downloadUrl, "an installer URL is configured");
    await mockSession(page, "STUDENT");
    await mockJson(page, "**/v1/player/installations", 200, apiSuccess([]));
    await page.goto("/student/player");
    await expect(page.getByText("Install the AOC Player", { exact: true })).toBeVisible();
    await expect(page.getByText("The AOC Player download isn't available yet.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Download for Windows" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Pair the AOC Player" })).toBeVisible();
  });

  test("the install step links to the configured installer", async ({ page }) => {
    test.skip(!downloadUrl, "set NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL to run");
    await mockSession(page, "STUDENT");
    await mockJson(page, "**/v1/player/installations", 200, apiSuccess([]));
    await page.goto("/student/player");
    const link = page.getByRole("link", { name: "Download for Windows" });
    await expect(link).toHaveAttribute("href", downloadUrl!);
    await expect(link).toHaveAttribute("rel", "noopener noreferrer");
    await expect(page.getByText("The AOC Player download isn't available yet.")).toHaveCount(0);
  });
});

// ---------------------------------------------------------------------------
// Student - lesson video in the AOC Player
// ---------------------------------------------------------------------------

function videoMaterial() {
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

function nativeStart() {
  const expiresAt = new Date(Date.now() + 45_000).toISOString();
  return { sessionId: SESSION_ID, channel: "NATIVE", expiresAt, descriptor: null, launch: { url: PLAY_URL, expiresAt } };
}

async function setupLesson(page: Page, provider: "YOUTUBE" | "INTERNAL") {
  await page.route("https://www.youtube-nocookie.com/**", (route) => route.abort());
  await setupStudentMaterialsMocks(page, {
    materialsBody: apiSuccess([videoMaterial()]),
    lessonMediaBody: apiSuccess([lessonMedia(provider)]),
  });
}

async function mockStart(page: Page): Promise<unknown[]> {
  const bodies: unknown[] = [];
  await page.route("**/api/v1/playback/sessions", (route: Route) => {
    bodies.push(route.request().postDataJSON());
    return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(apiSuccess(nativeStart())) });
  });
  return bodies;
}

async function mockLaunchStatus(page: Page, status: () => { status: string; exchanged: boolean }) {
  await page.route(`**/api/v1/playback/sessions/${SESSION_ID}/status`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(status())) })
  );
}

test.describe("lesson video in the AOC Player", () => {
  test("a required player shows the launcher, opens the link from the click and confirms the player took it", async ({
    page,
  }) => {
    const launched = await captureLaunches(page);
    await setupLesson(page, "YOUTUBE");
    const bodies = await mockStart(page);
    let claimed = false;
    await mockLaunchStatus(page, () => ({ status: claimed ? "ACTIVE" : "PENDING_LAUNCH", exchanged: claimed }));

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByText("Your institute plays lesson videos in the AOC Player app.")).toBeVisible();
    await expect(page.locator("iframe")).toHaveCount(0);
    expect(await launched()).toEqual([]);

    await page.getByRole("button", { name: "Open in AOC Player" }).click();
    await expect.poll(launched).toEqual([PLAY_URL]);
    await expect(page.getByText("Opening the AOC Player…")).toBeVisible();

    claimed = true;
    await expect(page.getByText("Playing in the AOC Player.")).toBeVisible({ timeout: 10_000 });
    expect(bodies[0]).toEqual({ lessonMediaId: MATERIAL_ID });
  });

  test("when the player never takes the link, the page says so and links to setup", async ({ page }) => {
    await captureLaunches(page);
    await setupLesson(page, "YOUTUBE");
    await mockStart(page);
    await mockLaunchStatus(page, () => ({ status: "EXPIRED", exchanged: false }));

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await page.getByRole("button", { name: "Open in AOC Player" }).click();

    const missed = page.getByRole("listitem").getByRole("alert");
    await expect(missed).toContainText("The AOC Player didn't open.");
    await expect(missed.getByRole("link", { name: "Set up the AOC Player" })).toHaveAttribute("href", "/student/player");
  });

  test("opening again asks for a fresh link instead of reusing a used one", async ({ page }) => {
    const launched = await captureLaunches(page);
    await setupLesson(page, "YOUTUBE");
    const bodies = await mockStart(page);
    await mockLaunchStatus(page, () => ({ status: "ACTIVE", exchanged: true }));

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await page.getByRole("button", { name: "Open in AOC Player" }).click();
    await expect(page.getByText("Playing in the AOC Player.")).toBeVisible();
    const before = bodies.length;
    await page.getByRole("button", { name: "Open again" }).click();
    await expect.poll(() => bodies.length).toBeGreaterThan(before);
    await expect.poll(async () => (await launched()).length).toBe(2);
  });

  test("a platform-storage video whose institute requires the player switches to the launcher", async ({ page }) => {
    const launched = await captureLaunches(page);
    await setupLesson(page, "INTERNAL");
    await page.route("**/api/v1/videos/**", (route) =>
      route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify(apiError("NATIVE_PLAYER_REQUIRED", "Open this lesson in the AOC Player.")),
      })
    );
    await mockStart(page);
    await mockLaunchStatus(page, () => ({ status: "PENDING_LAUNCH", exchanged: false }));

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.locator("video")).toHaveCount(0);
    await page.getByRole("button", { name: "Open in AOC Player" }).click();
    // This path has no pre-issued link: the click waits for a fresh one first.
    await expect.poll(launched).toEqual([PLAY_URL]);
  });

  test("a switched-off player is explained, not downgraded to the browser", async ({ page }) => {
    const launched = await captureLaunches(page);
    await setupLesson(page, "YOUTUBE");
    await page.route("**/api/v1/playback/sessions", (route) =>
      route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify(apiError("NATIVE_PLAYER_UNAVAILABLE", "The AOC Player isn't available right now.")),
      })
    );

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByRole("listitem").getByRole("alert")).toContainText("The AOC Player isn't available right now.");
    await expect(page.locator("iframe")).toHaveCount(0);
    expect(await launched()).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Tenant Admin - student Devices tab
// ---------------------------------------------------------------------------

async function gotoDevicesTab(page: Page, role: string, installations: unknown) {
  await mockSession(page, role);
  await mockJson(
    page,
    `**/v1/students/${STUDENT_PROFILE_ID}`,
    200,
    apiSuccess({ id: STUDENT_PROFILE_ID, name: "Ada Lovelace", email: "ada@example-institute.test", roleCode: "STUDENT", status: "ACTIVE" })
  );
  await mockJson(
    page,
    `**/v1/students/${STUDENT_PROFILE_ID}/devices`,
    200,
    apiSuccess({
      devices: [],
      limit: 2,
      limitSource: "TENANT",
      occupiedSlots: 0,
      nextSlotFreeAt: null,
      studentOverride: null,
      studentOverrideReason: null,
      selfServiceEnabled: false,
      resetCooldownHours: 24,
    })
  );
  await page.route(`**/v1/students/${STUDENT_PROFILE_ID}/player-installations`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(installations) })
  );
  await page.goto(`/tenant-admin/students/${STUDENT_PROFILE_ID}?tab=devices`);
  await expect(page.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();
}

test.describe("tenant admin - AOC Player on the Devices tab", () => {
  test("removing a player needs a reason and sends it", async ({ page }) => {
    await gotoDevicesTab(page, "TENANT_ADMIN", apiSuccess([installation()]));
    let body: unknown = null;
    await page.route(`**/v1/students/${STUDENT_PROFILE_ID}/player-installations/*/revoke`, (route) => {
      body = route.request().postDataJSON();
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(installation({ status: "REVOKED", revocationSource: "ADMIN" }))),
      });
    });

    await expect(page.getByRole("list", { name: "Student AOC Players" })).toContainText("Windows · v1.0.0");
    await page.getByRole("button", { name: "Remove this AOC Player" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Remove player" }).click();
    await expect(dialog.getByLabel("Reason")).toHaveAttribute("aria-invalid", "true");
    expect(body).toBeNull();

    await dialog.getByLabel("Reason").fill("Student reported a stolen laptop");
    await dialog.getByRole("button", { name: "Remove player" }).click();
    await expect(page.getByText("The AOC Player was removed.")).toBeVisible();
    expect(body).toEqual({ reason: "Student reported a stolen laptop" });
  });

  test("view-only staff see players but no remove action", async ({ page }) => {
    await gotoDevicesTab(page, "STUDENT_SUPPORT", apiSuccess([installation()]));
    await expect(page.getByRole("list", { name: "Student AOC Players" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Remove this AOC Player" })).toHaveCount(0);
  });

  test("no players shows an explanation, not an empty list", async ({ page }) => {
    await gotoDevicesTab(page, "TENANT_ADMIN", apiSuccess([]));
    await expect(page.getByText("No AOC Player paired.")).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Phones, tablets and Macs (the AOC Player is Windows-only for now)
// ---------------------------------------------------------------------------

test.describe("AOC Player on a phone", () => {
  test.use({
    userAgent:
      "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
  });

  test("a lesson video explains it needs a Windows computer and offers no launch", async ({ page }) => {
    const launched = await captureLaunches(page);
    await setupLesson(page, "YOUTUBE");
    await mockStart(page);

    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByText("This video can only be watched on a Windows computer with the AOC Player.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Open in AOC Player" })).toHaveCount(0);
    await expect(page.locator("iframe")).toHaveCount(0);
    expect(await launched()).toEqual([]);
  });

  test("the AOC Player page offers no pairing on a phone", async ({ page }) => {
    await mockSession(page, "STUDENT");
    await mockJson(page, "**/v1/player/installations", 200, apiSuccess([]));
    await page.goto("/student/player");
    await expect(page.getByText("This video can only be watched on a Windows computer with the AOC Player.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Pair the AOC Player" })).toHaveCount(0);
    await expect(page.getByText("Install the AOC Player", { exact: true })).toHaveCount(0);
  });
});

test.describe("AOC Player on a Mac", () => {
  test.use({
    userAgent:
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
  });

  test("a platform-storage lesson video on a Mac shows the Windows-only notice", async ({ page }) => {
    await setupLesson(page, "INTERNAL");
    await page.route("**/api/v1/videos/**", (route) =>
      route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify(apiError("NATIVE_PLAYER_REQUIRED", "Open this lesson in the AOC Player.")),
      })
    );
    await page.goto(studentMaterialsUrl());
    await page.getByRole("button", { name: "Open video" }).click();
    await expect(page.getByText("This video can only be watched on a Windows computer with the AOC Player.")).toBeVisible();
  });
});
