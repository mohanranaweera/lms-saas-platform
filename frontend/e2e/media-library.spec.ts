import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/class-session-mocks";

/**
 * VIDEO Phase 5 — Media Library (frontend, mock-only).
 *
 * Who may see, upload, edit or delete media, the size/type checks on the
 * staged file, the YouTube sync and every cross-tenant case are proven by the
 * backend's `MediaUploadIntegrationTest` and
 * `MediaLibraryManagementIntegrationTest`. These tests prove the pages
 * reflect backend state, upload the file in parts straight to the presigned
 * URLs with no credentials, explain every waiting/failed state, and send
 * exactly what the user chose.
 */

const STAGING = "https://staging.example.test";

function asset(overrides: Record<string, unknown> = {}) {
  return {
    id: "media-1",
    provider: "YOUTUBE",
    title: "Algebra lesson 1",
    description: "Linear equations",
    thumbnailUrl: null,
    durationSeconds: 754,
    privacyStatus: "UNLISTED",
    processingStatus: "READY",
    failureReason: null,
    uploadedBy: "teacher-1",
    createdAt: "2026-10-04T08:00:00Z",
    capabilities: { supportsUpload: true },
    enabledPlaybackModes: [],
    uploadProgress: { state: "COMPLETED", bytesSent: 1000, totalBytes: 1000, message: null },
    attached: false,
    youtubeUrl: null,
    integrationId: "int-1",
    ...overrides,
  };
}

function limits(overrides: Record<string, unknown> = {}) {
  return {
    uploadsEnabled: true,
    channel: "CONNECTED",
    editingEnabled: true,
    dailyLimit: 20,
    usedToday: 2,
    maxFileSizeBytes: 1_000_000,
    partSizeBytes: 10,
    allowedMimeTypes: ["video/mp4", "video/quicktime", "video/webm"],
    canUpload: true,
    notAllowedReason: null,
    uploadsDisabledBy: null,
    uploadsDisabledReason: null,
    ...overrides,
  };
}

type Reply = { status: number; body: unknown };

function tabs(overrides: Record<string, unknown> = {}) {
  return {
    integrations: [{ integrationId: "int-1", name: "Institute YouTube", connected: true, canUpload: true }],
    hasArchived: false,
    ...overrides,
  };
}

interface MediaMock {
  tabs?: Record<string, unknown>;
  list?: unknown[] | Reply;
  detail?: Record<string, unknown>;
  limits?: Record<string, unknown>;
  start?: Reply;
  complete?: Reply;
  patch?: Reply;
  remove?: Reply;
  retry?: Reply;
}

interface Captured {
  method: string;
  path: string;
  body: unknown;
}

/** One router for `/v1/media…`; records every call so tests can assert what was sent. */
async function mockMedia(page: Page, mock: MediaMock): Promise<Captured[]> {
  const calls: Captured[] = [];
  await page.route(/\/v1\/media-files(\/|\?|$)/, async (route) => {
    const summary = new URL(route.request().url()).pathname.endsWith("/summary");
    return fulfillJson(route, 200, summary
      ? apiSuccess({ documents: 0, images: 0, other: 0, total: 0 })
      : apiPageSuccess([]));
  });
  await page.route(/\/v1\/media(\/|\?|$)/, async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^.*\/v1\/media/, "");
    const method = request.method();
    const raw = request.postData();
    calls.push({ method, path, body: raw ? JSON.parse(raw) : null });

    const reply = (r: Reply | undefined, fallback: unknown) => fulfillJson(route, r?.status ?? 200, r?.body ?? fallback);

    if (method === "GET" && path === "") {
      const list = mock.list ?? [];
      if (!Array.isArray(list)) return fulfillJson(route, list.status, list.body);
      const params = new URL(request.url()).searchParams;
      const filtered = list.filter((item) => {
        const media = item as ReturnType<typeof asset>;
        return (!params.get("provider") || media.provider === params.get("provider")) &&
          (!params.get("integrationId") || media.integrationId === params.get("integrationId")) &&
          (!params.get("status") || media.processingStatus === params.get("status")) &&
          media.title.toLowerCase().includes((params.get("q") ?? "").toLowerCase());
      });
      const pageIndex = Number(params.get("page") ?? 0);
      const size = Number(params.get("size") ?? 24);
      return fulfillJson(route, 200, apiPageSuccess(filtered.slice(pageIndex * size, (pageIndex + 1) * size), {
        page: pageIndex, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size),
      }));
    }
    if (method === "GET" && path === "/upload-limits") return fulfillJson(route, 200, apiSuccess(limits(mock.limits)));
    if (method === "GET" && path === "/channels") return fulfillJson(route, 200, apiSuccess(tabs(mock.tabs)));
    if (method === "POST" && path === "") return reply(mock.start, apiError("UNKNOWN", "not mocked"));
    if (method === "POST" && path.endsWith("/upload/complete")) return reply(mock.complete, apiError("UNKNOWN", "x"));
    if (method === "POST" && path.endsWith("/upload/cancel")) {
      return fulfillJson(route, 200, apiSuccess(asset({ processingStatus: "FAILED" })));
    }
    if (method === "POST" && path.endsWith("/upload/retry")) {
      return reply(mock.retry, apiSuccess(asset({ processingStatus: "UPLOADING" })));
    }
    if (method === "PATCH") return reply(mock.patch, apiError("UNKNOWN", "x"));
    if (method === "DELETE") return reply(mock.remove, apiSuccess(null));
    if (method === "GET") return fulfillJson(route, 200, apiSuccess(mock.detail ?? asset()));
    return fulfillJson(route, 404, apiError("NOT_FOUND", "not mocked"));
  });
  return calls;
}

/** The presigned S3 part URLs: answer the CORS preflight and every PUT with an ETag. */
async function mockStaging(page: Page) {
  const puts: { method: string; auth: string | undefined; size: number }[] = [];
  await page.route(`${STAGING}/**`, async (route) => {
    const request = route.request();
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "PUT",
      "Access-Control-Allow-Headers": "*",
      "Access-Control-Expose-Headers": "ETag",
    };
    if (request.method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers: cors });
      return;
    }
    const part = new URL(request.url()).pathname.split("/").pop();
    puts.push({
      method: request.method(),
      auth: request.headers()["authorization"],
      size: request.postDataBuffer()?.length ?? 0,
    });
    await route.fulfill({ status: 200, headers: { ...cors, ETag: `"etag-${part}"` }, body: "" });
  });
  return puts;
}

function startedUpload(partCount: number): Reply {
  return {
    status: 201,
    body: apiSuccess({
      mediaAsset: asset({ processingStatus: "CREATED", uploadProgress: null }),
      upload: {
        partSizeBytes: 10,
        partCount,
        parts: Array.from({ length: partCount }, (_, i) => ({
          partNumber: i + 1,
          url: `${STAGING}/up-1/${i + 1}?X-Amz-Signature=abc`,
          expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
        })),
      },
    }),
  };
}

async function chooseVideo(page: Page, bytes: number, mimeType = "video/mp4", name = "lesson.mp4") {
  await page.getByLabel("Video file").setInputFiles({ name, mimeType, buffer: Buffer.alloc(bytes, 7) });
}

function uploadForm(page: Page) {
  return page.getByRole("form", { name: "Upload a video" });
}

test.describe("Media Library list", () => {
  test("a Tenant Admin sees every video with its status, progress and lesson use", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, {
      list: [
        asset({ attached: true }),
        asset({
          id: "media-2",
          title: "Geometry intro",
          processingStatus: "UPLOADING",
          uploadProgress: { state: "TRANSFERRING", bytesSent: 500, totalBytes: 1000, message: null },
        }),
        asset({
          id: "media-3",
          title: "Paused video",
          processingStatus: "UPLOADING",
          uploadProgress: { state: "QUOTA_PAUSED", bytesSent: 0, totalBytes: 1000, message: "limit" },
        }),
        asset({ id: "media-4", title: "Broken video", processingStatus: "FAILED", failureReason: "Rejected." }),
      ],
    });

    await page.goto("/tenant-admin/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();

    await expect(page.getByRole("heading", { name: "Media Library" })).toBeVisible();
    const items = page.getByRole("list", { name: "Media" }).getByRole("listitem");
    await expect(items).toHaveCount(4);
    await expect(items.nth(0)).toContainText("Ready");
    await expect(items.nth(0)).toContainText("In a lesson");
    await expect(items.nth(0)).toContainText("12:34");
    await expect(items.nth(1)).toContainText("Sending to YouTube");
    await expect(items.nth(1).getByRole("progressbar")).toHaveAttribute("aria-valuenow", "50");
    await expect(items.nth(2)).toContainText("Paused until tomorrow");
    await expect(items.nth(3)).toContainText("Failed");
    await expect(page.getByRole("link", { name: "Media Library" }).first()).toBeVisible();

    await expect(items.nth(0).getByRole("link")).toHaveAttribute("href", "/tenant-admin/media/media-1");
  });

  test("an empty library offers an upload, a filtered one offers to clear filters", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, { list: [] });

    await page.goto("/tenant-admin/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await expect(page.getByText("No videos on this channel yet")).toBeVisible();

    await page.getByLabel("Status").selectOption("FAILED");
    await expect(page.getByText("No media matches")).toBeVisible();
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByText("No videos on this channel yet")).toBeVisible();
  });

  test("a 403 from the server shows the permission-denied state", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockMedia(page, { list: { status: 403, body: apiError("FORBIDDEN", "Forbidden") } });

    await page.goto("/tenant-admin/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Upload video" })).toHaveCount(0);
  });
});

test.describe("Consistent media dialogs and pagination", () => {
  const providerTabs = {
    integrations: [
      { integrationId: "int-1", name: "Channel one", connected: true, canUpload: true },
      { integrationId: "int-2", name: "Channel two", connected: true, canUpload: true },
    ],
    managedHost: { provider: "DYNTUBE", name: "DynTube", connected: true, canUpload: true, uploadBlockedReason: null },
    hasArchived: true,
  };

  test("New media opens the same responsive dialog shell for all four destinations", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, { tabs: providerTabs, list: [] });
    await page.goto("/tenant-admin/media");
    for (const name of ["Uploads", "DynTube", "Channel one", "Channel two"]) {
      await page.getByRole("tab", { name, exact: true }).click();
      const trigger = page.getByRole("button", { name: "New media", exact: true }).first();
      await trigger.click();
      const dialog = page.getByRole("dialog", { name: "New media", exact: true });
      await expect(dialog).toBeVisible();
      await page.keyboard.press("Shift+Tab");
      await expect.poll(() => dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true);
      if (name !== "Uploads") await expect(dialog.getByRole("form", { name: "Upload a video" })).toBeVisible();
      await page.keyboard.press("Escape");
      await expect(dialog).toHaveCount(0);
      await expect(trigger).toBeFocused();
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("button", { name: "New media", exact: true }).first().click();
    const bounds = await page.getByRole("dialog", { name: "New media", exact: true }).boundingBox();
    expect(bounds?.width).toBe(390);
    expect(bounds?.x).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.keyboard.press("Escape");
    await page.getByRole("tab", { name: "Archived", exact: true }).click();
    await expect(page.getByRole("button", { name: "New media", exact: true })).toHaveCount(0);
  });

  for (const destination of ["DynTube", "Channel one", "Channel two"]) {
    test(`${destination} videos use server pagination and reset on search and status`, async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      const provider = destination === "DynTube" ? "DYNTUBE" : "YOUTUBE";
      const integrationId = destination === "DynTube" ? null : destination === "Channel one" ? "int-1" : "int-2";
      await mockMedia(page, { tabs: providerTabs, list: Array.from({ length: 25 }, (_, i) => asset({ id: `paged-${i}`, title: `Paged video ${i}`, provider, integrationId })) });
      await page.goto("/tenant-admin/media");
      await page.getByRole("tab", { name: destination, exact: true }).click();
      const pages = page.getByRole("navigation", { name: "Video pages" });
      await expect(page.getByRole("list", { name: "Media", exact: true }).getByRole("listitem")).toHaveCount(24);
      await pages.getByRole("button", { name: "Next" }).click();
      await expect(pages).toContainText("Page 2 of 2");
      await expect(pages.getByRole("button", { name: "Next" })).toBeDisabled();
      await page.getByLabel("Search", { exact: true }).fill("Paged video 24");
      await expect(pages).toHaveCount(0);
      await expect(page.getByRole("list", { name: "Media", exact: true }).getByRole("listitem")).toHaveCount(1);
      await page.getByLabel("Status", { exact: true }).selectOption("FAILED");
      await expect(page.getByText("No media matches", { exact: true })).toBeVisible();
      await page.getByRole("button", { name: "Clear filters" }).click();
      await expect(pages).toContainText("Page 1 of 2");
    });
  }

  test("a pending provider upload cannot be dismissed; success releases the dialog", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    await mockMedia(page, { list: [], start: startedUpload(1), complete: { status: 202, body: apiSuccess(asset({ processingStatus: "UPLOADING" })) } });
    await mockStaging(page);
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    await page.route(/\/v1\/media\/media-1\/upload\/complete$/, async (route) => { await gate; await route.fallback(); });
    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media", exact: true }).first().click();
    await page.getByLabel("Title").fill("Pending video");
    await chooseVideo(page, 8);
    await uploadForm(page).getByRole("button", { name: "Upload video" }).click();
    await expect(page.getByText("Checking the file…", { exact: true })).toBeVisible();
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Close", exact: true }).click();
    await expect(page.getByRole("dialog", { name: "New media", exact: true })).toBeVisible();
    await expect(uploadForm(page).getByRole("button", { name: "Cancel upload" })).toBeDisabled();
    release?.();
    await expect(page.getByText(/Upload received/)).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog", { name: "New media", exact: true })).toHaveCount(0);
    await page.getByRole("tab", { name: "Uploads", exact: true }).click();
    await expect(page.getByRole("tab", { name: "Uploads", exact: true })).toHaveAttribute("aria-selected", "true");
  });
});

test.describe("Uploading", () => {
  test("a teacher's file goes to the presigned URLs in parts, with no credentials, then is completed", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    const calls = await mockMedia(page, {
      list: [],
      start: startedUpload(3),
      complete: { status: 202, body: apiSuccess(asset({ processingStatus: "UPLOADING" })) },
    });
    const puts = await mockStaging(page);

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();
    await expect(page.getByText("2 of 20 uploads used today", { exact: false })).toBeVisible();
    await page.getByLabel("Title").fill("Algebra lesson 1");
    await page.getByLabel("Description (optional)").fill("Linear equations");
    await page.getByLabel("Visibility on YouTube").selectOption("PRIVATE");
    await chooseVideo(page, 25);
    await uploadForm(page).getByRole("button", { name: "Upload video" }).click();

    await expect(page.getByText(/Upload received/)).toBeVisible();
    const start = calls.find((c) => c.method === "POST" && c.path === "");
    expect(start?.body).toEqual({
      integrationId: "int-1",
      title: "Algebra lesson 1",
      description: "Linear equations",
      privacyStatus: "PRIVATE",
      fileName: "lesson.mp4",
      mimeType: "video/mp4",
      sizeBytes: 25,
    });
    const sent = puts.filter((p) => p.method === "PUT");
    expect(sent.map((p) => p.size).sort((a, b) => a - b)).toEqual([5, 10, 10]);
    expect(sent.every((p) => p.auth === undefined)).toBe(true);
    const complete = calls.find((c) => c.path.endsWith("/upload/complete"));
    expect(complete?.body).toEqual({
      parts: [
        { partNumber: 1, etag: '"etag-1"' },
        { partNumber: 2, etag: '"etag-2"' },
        { partNumber: 3, etag: '"etag-3"' },
      ],
    });
  });

  test("a file the server rejects shows its reason and is not cancelled again", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    const calls = await mockMedia(page, {
      list: [],
      start: startedUpload(1),
      complete: {
        status: 422,
        body: apiError("MEDIA_UPLOAD_FAILED", "This file is not a supported video. Upload an MP4, WebM or MOV file."),
      },
    });
    await mockStaging(page);

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();
    await page.getByLabel("Title").fill("Not really a video");
    await chooseVideo(page, 8);
    await uploadForm(page).getByRole("button", { name: "Upload video" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "not a supported video" })).toBeVisible();
    expect(calls.some((c) => c.path.endsWith("/upload/cancel"))).toBe(false);
  });

  test("the form checks the title, the type and the size before anything is sent", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    const calls = await mockMedia(page, { list: [], limits: { maxFileSizeBytes: 100 } });

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();
    await chooseVideo(page, 50, "application/pdf", "notes.pdf");
    await uploadForm(page).getByRole("button", { name: "Upload video" }).click();
    await expect(page.getByText("Enter a title.")).toBeVisible();
    await expect(page.getByText("Choose an MP4, WebM or MOV video.")).toBeVisible();

    await page.getByLabel("Title").fill("Too big");
    await chooseVideo(page, 500);
    await uploadForm(page).getByRole("button", { name: "Upload video" }).click();
    await expect(page.getByText(/larger than the 100 B limit/)).toBeVisible();
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });

  test("uploading is blocked with the reason when the channel isn't connected", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, { list: [], limits: { channel: "NOT_CONNECTED" } });

    await page.goto("/tenant-admin/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();

    await expect(page.getByText("Your institute hasn't connected a YouTube channel yet.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Open video providers" })).toHaveAttribute(
      "href",
      "/tenant-admin/settings/video"
    );
    await expect(uploadForm(page).getByRole("button", { name: "Upload video" })).toBeDisabled();
  });

  test("the daily limit message replaces the form's action", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    await mockMedia(page, { list: [], limits: { usedToday: 20 } });

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();

    await expect(page.getByText("Your institute has used today's 20 uploads. Try again tomorrow.")).toBeVisible();
    await expect(uploadForm(page).getByRole("button", { name: "Upload video" })).toBeDisabled();
  });

  test("a platform that hasn't enabled uploads says so, not that the institute switched them off", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, {
      list: [],
      limits: {
        uploadsEnabled: false,
        uploadsDisabledBy: "PLATFORM",
        uploadsDisabledReason: "Video uploads aren't enabled on this platform yet. Contact platform support.",
      },
    });

    await page.goto("/tenant-admin/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();

    await expect(page.getByText("Video uploads aren't enabled on this platform yet. Contact platform support.")).toBeVisible();
    await expect(page.getByText(/switched off for your institute/)).toHaveCount(0);
    await expect(uploadForm(page).getByRole("button", { name: "Upload video" })).toBeDisabled();
  });

  test("a video YouTube has opens on YouTube in a new tab; Details stays available", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    await mockMedia(page, {
      list: [
        asset({ youtubeUrl: "https://www.youtube.com/watch?v=yt-abc" }),
        asset({ id: "media-2", title: "Still uploading", processingStatus: "UPLOADING" }),
      ],
    });

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();

    const youtubeLink = page.getByRole("link", { name: "Algebra lesson 1 - open on YouTube (new tab)" });
    await expect(youtubeLink).toHaveAttribute("href", "https://www.youtube.com/watch?v=yt-abc");
    await expect(youtubeLink).toHaveAttribute("target", "_blank");
    await expect(youtubeLink).toHaveAttribute("rel", /noopener/);
    await expect(page.getByRole("link", { name: "Details for Algebra lesson 1" })).toHaveAttribute(
      "href",
      "/teacher/media/media-1"
    );
    // Not on YouTube yet: the card opens the details page as before.
    await expect(page.getByRole("link", { name: /Still uploading/ })).toHaveAttribute("href", "/teacher/media/media-2");
  });

  test("a teacher not on the channel's allowed list sees the server's reason and can't upload", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    const calls = await mockMedia(page, {
      list: [asset()],
      limits: {
        canUpload: false,
        notAllowedReason: "Your institute hasn't given you access to upload videos. Ask your institute admin.",
      },
    });

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Institute YouTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();

    await expect(
      page.getByText("Your institute hasn't given you access to upload videos. Ask your institute admin.")
    ).toBeVisible();
    await expect(uploadForm(page).getByRole("button", { name: "Upload video" })).toBeDisabled();
    // Close the modal to return to their existing videos.
    await page.keyboard.press("Escape");
    await expect(page.getByText("Algebra lesson 1")).toBeVisible();
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });
});

test.describe("Media Library tabs (Phase 5d)", () => {
  test("each YouTube integration is a tab; Uploads and Archived list their own videos", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const calls = await mockMedia(page, {
      tabs: tabs({
        integrations: [
          { integrationId: "int-1", name: "AOC YT", connected: true, canUpload: true },
          { integrationId: "int-2", name: "Science", connected: false, canUpload: false },
        ],
        hasArchived: true,
      }),
      list: [asset()],
    });

    await page.goto("/tenant-admin/media");

    const tablist = page.getByRole("tablist", { name: "Media sources" });
    await expect(tablist.getByRole("tab")).toHaveText(["Uploads", "AOC YT", "ScienceNot connected", "Archived"]);
    await expect(tablist.getByRole("tab", { name: "Uploads" })).toHaveAttribute("aria-selected", "true");
    await expect.poll(() => calls.some((c) => c.method === "GET" && c.path === "")).toBe(true);

    await tablist.getByRole("tab", { name: "Uploads" }).click();
    await page.getByTestId("media-files-type").selectOption("VIDEO");
    await expect(page.getByText(/Use New media to upload a video to tenant internal storage/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Upload video" })).toHaveCount(0);
    await tablist.getByRole("tab", { name: "Archived" }).click();
    await expect(page.getByText(/YouTube integrations that were removed/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Upload video" })).toHaveCount(0);
  });

  test("each tab asks the server for its own videos and uploads go to the selected channel", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    await mockMedia(page, {
      tabs: tabs({
        integrations: [
          { integrationId: "int-1", name: "AOC YT", connected: true, canUpload: true },
          { integrationId: "int-2", name: "Science", connected: true, canUpload: true },
        ],
      }),
      list: [],
    });
    // Registered after mockMedia so it runs first, records, then falls back to it.
    const requested: string[] = [];
    await page.route(/\/v1\/media\?/, async (route) => {
      requested.push(new URL(route.request().url()).search);
      await route.fallback();
    });

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "Science" }).click();
    await page.getByRole("tabpanel").getByRole("button", { name: "New media" }).first().click();

    await expect(page.getByTestId("media-provider-upload-dialog")).toBeVisible();
    await expect(page.getByText("To YouTube · Science")).toBeVisible();
    await page.keyboard.press("Escape");
    await page.getByRole("tab", { name: "Uploads" }).click();
    await page.getByTestId("media-files-type").selectOption("VIDEO");
    await expect.poll(() => requested.some((q) => q.includes("integrationId=int-2"))).toBe(true);
    await expect.poll(() => requested.some((q) => q.includes("provider=INTERNAL"))).toBe(true);
  });
});

test.describe("Media detail", () => {
  test("the details page links to the video on YouTube in a new tab", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    await mockMedia(page, { detail: asset({ youtubeUrl: "https://www.youtube.com/watch?v=yt-abc" }) });

    await page.goto("/teacher/media/media-1");

    const link = page.getByRole("link", { name: /Open on YouTube/ });
    await expect(link).toHaveAttribute("href", "https://www.youtube.com/watch?v=yt-abc");
    await expect(link).toHaveAttribute("target", "_blank");
  });

  test("editing a video on YouTube sends the changes and confirms them", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    const calls = await mockMedia(page, {
      detail: asset(),
      patch: { status: 200, body: apiSuccess(asset({ title: "Algebra 1", privacyStatus: "PRIVATE" })) },
    });

    await page.goto("/teacher/media/media-1");
    await page.getByRole("button", { name: "Edit details" }).click();
    await expect(page.getByText("Changes are also made on YouTube.")).toBeVisible();
    await page.getByLabel("Title").fill("Algebra 1");
    await page.getByLabel("Visibility on YouTube").selectOption("PRIVATE");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByText("Changes saved.")).toBeVisible();
    const patch = calls.find((c) => c.method === "PATCH");
    expect(patch?.body).toEqual({ title: "Algebra 1", description: "Linear equations", privacyStatus: "PRIVATE" });
  });

  test("a channel without the edit permission explains how to fix it", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, {
      detail: asset(),
      limits: { editingEnabled: false },
      patch: {
        status: 409,
        body: apiError("VIDEO_PROVIDER_SCOPE_MISSING", "Reconnect YouTube in Settings → Video Providers to allow editing videos."),
      },
    });

    await page.goto("/tenant-admin/media/media-1");
    await page.getByRole("button", { name: "Edit details" }).click();
    await expect(page.getByText(/connected before editing was available/)).toBeVisible();
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "to allow editing videos" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Open video providers" }).first()).toBeVisible();
  });

  test("deleting explains the YouTube copy is kept and returns to the library", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const calls = await mockMedia(page, { detail: asset(), list: [] });

    await page.goto("/tenant-admin/media/media-1");
    await page.getByRole("button", { name: "Delete" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("The video stays on your YouTube channel");
    await dialog.getByRole("button", { name: "Delete" }).click();

    await expect(page).toHaveURL(/\/tenant-admin\/media$/);
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/media-1")).toBe(true);
  });

  test("media used in a lesson can't be deleted", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, { detail: asset({ attached: true }) });

    await page.goto("/tenant-admin/media/media-1");

    await expect(page.getByText("Remove it from the lesson before deleting it.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
  });

  test("an upload waiting for a reconnect is explained and can be cancelled", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockMedia(page, {
      detail: asset({
        processingStatus: "UPLOADING",
        uploadProgress: {
          state: "PARKED_RECONNECT",
          bytesSent: 0,
          totalBytes: 1000,
          message: "Reconnect YouTube in Settings → Video Providers.",
        },
      }),
    });

    await page.goto("/tenant-admin/media/media-1");

    await expect(page.getByText(/continues once the same channel is connected again/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel upload" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit details" })).toBeDisabled();
  });

  test("a failed upload shows its reason and can be retried", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const calls = await mockMedia(page, {
      detail: asset({
        processingStatus: "FAILED",
        failureReason: "The upload to YouTube kept failing. Try again later.",
        uploadProgress: { state: "FAILED", bytesSent: 0, totalBytes: 1000, message: "x" },
      }),
    });

    await page.goto("/tenant-admin/media/media-1");
    await expect(page.getByText("The upload to YouTube kept failing. Try again later.")).toBeVisible();
    await page.getByRole("button", { name: "Retry upload" }).click();

    await expect.poll(() => calls.some((c) => c.path.endsWith("/upload/retry"))).toBe(true);
  });

  test("a read-only role sees the details without any action", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockMedia(page, { detail: asset() });

    await page.goto("/tenant-admin/media/media-1");

    await expect(page.getByRole("heading", { name: "Algebra lesson 1" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit details" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete" })).toHaveCount(0);
  });
});

test.describe("DynTube (VIDEO Phase 13, ADR-026)", () => {
  const DYNTUBE_TAB = {
    provider: "DYNTUBE",
    name: "DynTube",
    connected: true,
    canUpload: true,
    uploadBlockedReason: null as string | null,
  };

  test("the DynTube tab lists DynTube videos and an upload goes to DynTube without a channel or privacy", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    const calls = await mockMedia(page, {
      tabs: { integrations: [], managedHost: DYNTUBE_TAB },
      list: [],
      start: startedUpload(1),
      complete: { status: 202, body: apiSuccess(asset({ provider: "DYNTUBE", processingStatus: "UPLOADING" })) },
    });
    await mockStaging(page);
    const listRequest = page.waitForRequest((r) => /\/v1\/media\?.*provider=DYNTUBE/.test(r.url()));
    const limitsRequest = page.waitForRequest((r) => /\/v1\/media\/upload-limits\?provider=DYNTUBE/.test(r.url()));

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "DynTube" }).click();
    await expect(page.getByRole("tab", { name: "DynTube" })).toHaveAttribute("aria-selected", "true");
    await listRequest;
    await expect(page.getByText("No DynTube videos yet")).toBeVisible();

    await page.getByRole("button", { name: "New media" }).first().click();
    await limitsRequest;
    await expect(uploadForm(page).getByText("To DynTube")).toBeVisible();
    await expect(page.getByLabel("Visibility on YouTube")).toHaveCount(0);
    await page.getByLabel("Title").fill("Organic chemistry 1");
    await chooseVideo(page, 8);
    await uploadForm(page).getByRole("button", { name: "Upload video" }).click();

    await expect(page.getByText(/is being sent to DynTube/)).toBeVisible();
    const start = calls.find((c) => c.method === "POST" && c.path === "");
    expect(start?.body).toEqual({
      provider: "DYNTUBE",
      title: "Organic chemistry 1",
      fileName: "lesson.mp4",
      mimeType: "video/mp4",
      sizeBytes: 8,
    });
  });

  test("uploading to DynTube is blocked with the reason when the account isn't set up", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN", "admin-1");
    await mockMedia(page, {
      tabs: { integrations: [], managedHost: { ...DYNTUBE_TAB, canUpload: true } },
      list: [],
      limits: { channel: "NOT_CONNECTED" },
    });

    await page.goto("/tenant-admin/media");
    await page.getByRole("tab", { name: "DynTube" }).click();
    await page.getByRole("button", { name: "New media" }).first().click();

    await expect(page.getByText("Your institute hasn't finished setting up DynTube yet.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Open integrations" })).toHaveAttribute(
      "href",
      "/tenant-admin/settings/integrations"
    );
    await expect(uploadForm(page).getByRole("button", { name: "Upload video" })).toBeDisabled();
  });

  test("the DynTube tab shows the server's reason instead of an Upload button when uploading is unavailable", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", "teacher-1");
    await mockMedia(page, {
      tabs: {
        integrations: [],
        managedHost: {
          ...DYNTUBE_TAB,
          canUpload: false,
          uploadBlockedReason: "No DynTube project is chosen yet. An institute admin can choose one in Settings → Integrations.",
        },
      },
      list: [],
    });

    await page.goto("/teacher/media");
    await page.getByRole("tab", { name: "DynTube" }).click();

    await expect(page.getByRole("note")).toContainText("Uploading is unavailable.");
    await expect(page.getByRole("note")).toContainText("No DynTube project is chosen yet.");
    await expect(page.getByRole("button", { name: "Upload video" })).toHaveCount(0);
    // Teachers can't open institute settings, so no link is offered.
    await expect(page.getByRole("link", { name: "Open integrations" })).toHaveCount(0);
  });
});
