import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/class-session-mocks";

/** Tab routing is UX only: these synthetic UI contracts do not prove backend authorization. */
const STAGING = "https://staging.example.test";
const DYNTUBE = { provider: "DYNTUBE", name: "DynTube", connected: true, canUpload: true, uploadBlockedReason: null as string | null };
const CHANNELS = [
  { integrationId: "science-channel", name: "Science channel", connected: true, canUpload: true },
  { integrationId: "maths-channel", name: "Maths channel", connected: true, canUpload: true },
];
const MP4 = { name: "lesson.mp4", mimeType: "video/mp4", buffer: Buffer.alloc(8, 7) };

type HostedCall = { method: string; path: string; body: Record<string, unknown> | null; search: string };
type Options = {
  noHosts?: boolean;
  managedHost?: typeof DYNTUBE;
  internalStatus?: number;
  internalGate?: Promise<void>;
  hostedLimits?: Record<string, unknown>;
  hostedLimitsStatus?: number;
  hostedCompleteStatus?: number;
  partGate?: Promise<void>;
};

function asset(provider: string, processingStatus = "READY", integrationId: string | null = null) {
  return {
    id: provider === "INTERNAL" ? "internal-video" : "hosted-video", provider, title: "lesson.mp4",
    description: null, thumbnailUrl: null, durationSeconds: null, privacyStatus: provider === "YOUTUBE" ? "UNLISTED" : null,
    processingStatus, failureReason: null, uploadedBy: "teacher-1", createdAt: "2026-10-16T08:00:00Z",
    capabilities: {}, enabledPlaybackModes: [], uploadProgress: null, attached: false, attachedLessonCount: 0,
    youtubeUrl: null, integrationId, canEdit: true, canDelete: true,
  };
}

async function mockUploads(page: Page, options: Options = {}) {
  const hosted: HostedCall[] = [];
  const internal: { body: string; contentType: string }[] = [];
  const filePosts: string[] = [];
  const parts: { authorization: string | undefined; size: number }[] = [];
  let internalCreated = false;
  let provider = "DYNTUBE";
  let integrationId: string | null = null;
  await page.route(/\/v1\/media-files(\/|\?|$)/, async (route) => {
    if (route.request().method() === "POST") filePosts.push(route.request().postData() ?? "");
    return fulfillJson(route, 200, new URL(route.request().url()).pathname.endsWith("/summary")
      ? apiSuccess({ documents: 0, images: 0, other: 0, total: 0 }) : apiPageSuccess([]));
  });
  await page.route(/\/v1\/videos(?:\?|$)/, async (route) => {
    const request = route.request();
    internal.push({ body: request.postData() ?? "", contentType: request.headers()["content-type"] ?? "" });
    await options.internalGate;
    if (options.internalStatus) {
      const errors: Record<number, [string, string]> = {
        403: ["FORBIDDEN", "You do not have permission to upload this video."],
        413: ["PAYLOAD_TOO_LARGE", "This video exceeds the tenant internal storage upload limit."],
        415: ["UNSUPPORTED_MEDIA_TYPE", "This file's content is not an accepted video format."],
        500: ["INTERNAL_ERROR", "Synthetic internal upload failure."],
      };
      const [code, message] = errors[options.internalStatus];
      return fulfillJson(route, options.internalStatus, apiError(code, message));
    }
    internalCreated = true;
    return fulfillJson(route, 201, apiSuccess({
      id: "internal-video", originalFilename: "lesson.mp4", mimeType: "video/mp4", sizeBytes: 8,
      durationSeconds: null, status: "READY", uploadedBy: "teacher-1", createdAt: "2026-10-16T08:00:00Z",
    }));
  });
  await page.route(/\/v1\/media(\/|\?|$)/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(url.pathname.indexOf("/v1/media") + "/v1/media".length);
    const method = request.method();
    const body = request.postData() ? request.postDataJSON() as Record<string, unknown> : null;
    hosted.push({ method, path, body, search: url.search });
    if (path === "/channels") return fulfillJson(route, 200, apiSuccess({
      managedHost: options.noHosts ? null : options.managedHost ?? DYNTUBE,
      integrations: options.noHosts ? [] : CHANNELS, hasArchived: true,
    }));
    if (path === "/upload-limits") {
      if (options.hostedLimitsStatus) return fulfillJson(route, options.hostedLimitsStatus, apiError("FORBIDDEN", "Video upload denied."));
      return fulfillJson(route, 200, apiSuccess({
        uploadsEnabled: true, channel: "CONNECTED", editingEnabled: true, dailyLimit: 20, usedToday: 0,
        maxFileSizeBytes: 1_000_000, partSizeBytes: 10, allowedMimeTypes: ["video/mp4", "video/webm", "video/quicktime"],
        canUpload: true, notAllowedReason: null, uploadsDisabledBy: null, uploadsDisabledReason: null,
        ...options.hostedLimits,
      }));
    }
    if (method === "POST" && path === "") {
      provider = body?.provider === "DYNTUBE" ? "DYNTUBE" : "YOUTUBE";
      integrationId = typeof body?.integrationId === "string" ? body.integrationId : null;
      return fulfillJson(route, 201, apiSuccess({
        mediaAsset: asset(provider, "CREATED", integrationId),
        upload: { partSizeBytes: 10, partCount: 1, parts: [{ partNumber: 1, url: `${STAGING}/hosted-video/1?signature=synthetic`, expiresAt: "2099-01-01T00:00:00Z" }] },
      }));
    }
    if (path.endsWith("/upload/complete")) return options.hostedCompleteStatus
      ? fulfillJson(route, options.hostedCompleteStatus, apiError("MEDIA_UPLOAD_FAILED", "The server rejected this video content."))
      : fulfillJson(route, 202, apiSuccess(asset(provider, "UPLOADING", integrationId)));
    if (path.endsWith("/upload/cancel")) return fulfillJson(route, 200, apiSuccess(asset(provider, "FAILED", integrationId)));
    if (method === "GET" && path === "") {
      const matchingInternal = internalCreated && url.searchParams.get("provider") === "INTERNAL";
      return fulfillJson(route, 200, apiPageSuccess(matchingInternal ? [asset("INTERNAL")] : []));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked"));
  });
  await page.route(`${STAGING}/**`, async (route) => {
    const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "PUT", "Access-Control-Allow-Headers": "*", "Access-Control-Expose-Headers": "ETag" };
    if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers: cors });
    parts.push({ authorization: route.request().headers()["authorization"], size: route.request().postDataBuffer()?.length ?? 0 });
    await options.partGate;
    try { await route.fulfill({ status: 200, headers: { ...cors, ETag: '"part-1"' }, body: "" }); } catch { /* cancelled intercepted PUT */ }
  });
  return { hosted, internal, filePosts, parts };
}

async function library(page: Page, role: "TEACHER" | "TENANT_ADMIN" = "TENANT_ADMIN") {
  await mockTenantSession(page, role, role === "TEACHER" ? "teacher-1" : "admin-1");
  await page.goto(role === "TEACHER" ? "/teacher/media" : "/tenant-admin/media");
  await expect(page.getByRole("tab", { name: "Uploads", exact: true })).toHaveAttribute("aria-selected", "true");
}

async function internalVideo(page: Page, file = MP4) {
  await page.getByTestId("media-files-new").click();
  await page.getByTestId("media-file-upload-input").setInputFiles(file);
  const dialog = page.getByTestId("media-file-upload-dialog");
  await expect(dialog).toContainText("Tenant internal storage");
  await expect(dialog.getByRole("combobox")).toHaveCount(0);
  return dialog;
}

async function hostedVideo(page: Page, tab: string) {
  await page.getByRole("tab", { name: tab, exact: true }).click();
  await page.getByRole("button", { name: "New media", exact: true }).first().click();
  const form = page.getByRole("form", { name: "Upload a video" });
  await expect(form).toBeVisible();
  await expect(page.getByTestId("new-media-video-destination")).toHaveCount(0);
  await expect(page.getByRole("combobox", { name: "Video destination", exact: true })).toHaveCount(0);
  await form.getByLabel("Video file", { exact: true }).setInputFiles(MP4);
  await form.getByLabel("Title", { exact: true }).fill("Lesson video");
  return form;
}

test("Uploads routes video straight to tenant internal storage even when hosted accounts exist", async ({ page }) => {
  const calls = await mockUploads(page);
  await library(page);
  const dialog = await internalVideo(page);
  await dialog.getByRole("button", { name: "Upload video", exact: true }).click();
  await expect(dialog.getByRole("button", { name: "View videos", exact: true })).toBeVisible();
  expect(calls.internal).toHaveLength(1);
  expect(calls.internal[0].contentType).toContain("multipart/form-data");
  expect(calls.internal[0].body).toContain('name="file"; filename="lesson.mp4"');
  expect(calls.internal[0].body).not.toMatch(/name="(?:tenantId|tenant_id|provider|integrationId|uploadedBy|title)"/);
  expect(calls.hosted.filter((call) => call.method === "POST")).toEqual([]);
  expect(calls.filePosts).toEqual([]);
  await dialog.getByRole("button", { name: "View videos", exact: true }).click();
  await expect(page.getByTestId("media-files-type")).toHaveValue("VIDEO");
  await expect(page.getByTestId("media-uploads-videos")).toContainText("lesson.mp4");
});

test("internal upload needs no DynTube or YouTube configuration and works from the Videos filter for teachers", async ({ page }) => {
  const calls = await mockUploads(page, { noHosts: true });
  await library(page, "TEACHER");
  await page.getByTestId("media-files-type").selectOption("VIDEO");
  const dialog = await internalVideo(page);
  await dialog.getByRole("button", { name: "Upload video", exact: true }).click();
  await expect(dialog.getByRole("button", { name: "View videos", exact: true })).toBeVisible();
  expect(calls.internal).toHaveLength(1);
  expect(calls.hosted.some((call) => call.path === "/upload-limits")).toBe(false);
});

test("internal video file chooser includes video while retaining existing file types", async ({ page }) => {
  await mockUploads(page);
  await library(page);
  await page.getByTestId("media-files-new").click();
  await expect(page.getByTestId("media-file-upload-input")).toHaveAttribute("accept", /video\//);
  await expect(page.getByTestId("media-file-upload-input")).toHaveAttribute("accept", /\.pdf/);
  await expect(page.getByRole("combobox", { name: "Video destination", exact: true })).toHaveCount(0);
});

test("internal oversized video is rejected locally instead of using the hosted large-file limit", async ({ page }) => {
  const calls = await mockUploads(page);
  await library(page);
  const dialog = await internalVideo(page, { name: "large.mp4", mimeType: "video/mp4", buffer: Buffer.alloc(25 * 1024 * 1024 + 1) });
  await dialog.getByRole("button", { name: "Upload video", exact: true }).click();
  await expect(dialog).toContainText(/25\s*(?:MiB|MB)/i);
  expect(calls.internal).toEqual([]);
  expect(calls.hosted.some((call) => call.method === "POST")).toBe(false);
});

test("unsupported internal video type never falls back to hosted upload or document storage", async ({ page }) => {
  const calls = await mockUploads(page);
  await library(page);
  const dialog = await internalVideo(page, { name: "legacy.avi", mimeType: "video/x-msvideo", buffer: Buffer.from("synthetic") });
  await dialog.getByRole("button", { name: "Upload video", exact: true }).click();
  await expect(dialog).toContainText(/MP4.*WebM.*MOV|accepted video format/i);
  expect(calls.internal).toEqual([]);
  expect(calls.hosted.some((call) => call.method === "POST")).toBe(false);
  expect(calls.filePosts).toEqual([]);
});

for (const status of [403, 413, 415, 500]) {
  test(`internal server ${status} remains visible without reporting receipt or switching provider`, async ({ page }) => {
    const calls = await mockUploads(page, { internalStatus: status });
    await library(page, "TEACHER");
    const dialog = await internalVideo(page);
    await dialog.getByRole("button", { name: "Upload video", exact: true }).click();
    await expect(dialog.getByRole("alert")).toBeVisible();
    await expect(dialog.getByRole("button", { name: "View videos", exact: true })).toHaveCount(0);
    expect(calls.internal).toHaveLength(1);
    expect(calls.hosted.some((call) => call.method === "POST")).toBe(false);
    expect(calls.filePosts).toEqual([]);
  });
}

test("pending internal upload blocks Escape and duplicate submission", async ({ page }) => {
  let release: (() => void) | undefined;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  const calls = await mockUploads(page, { internalGate: gate });
  try {
    await library(page);
    const dialog = await internalVideo(page);
    const submitted = page.waitForRequest((request) => request.method() === "POST" && new URL(request.url()).pathname.endsWith("/v1/videos"));
    await dialog.getByRole("button", { name: "Upload video", exact: true }).click();
    await submitted;
    await page.keyboard.press("Escape");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("button", { name: /Uploading/ })).toBeDisabled();
    expect(calls.internal).toHaveLength(1);
    release?.();
    await expect(dialog.getByRole("button", { name: "View videos", exact: true })).toBeVisible();
  } finally { release?.(); }
});

test("DynTube tab fixes the hosted destination without asking or posting to internal storage", async ({ page }) => {
  const calls = await mockUploads(page);
  await library(page);
  const form = await hostedVideo(page, "DynTube");
  await expect(form).toContainText("To DynTube");
  await form.getByRole("button", { name: "Upload video", exact: true }).click();
  await expect(page.getByText(/Upload received\./)).toBeVisible();
  const start = calls.hosted.find((call) => call.method === "POST" && call.path === "")?.body;
  expect(start).toMatchObject({ provider: "DYNTUBE", fileName: "lesson.mp4", title: "Lesson video" });
  expect(start).not.toHaveProperty("integrationId");
  expect(calls.internal).toEqual([]);
  expect(calls.filePosts).toEqual([]);
  expect(calls.parts).toEqual([{ authorization: undefined, size: 8 }]);
});

for (const channel of CHANNELS) {
  test(`${channel.name} tab uploads to its exact integration rather than the first available account`, async ({ page }) => {
    const calls = await mockUploads(page);
    await library(page, "TEACHER");
    const form = await hostedVideo(page, channel.name);
    await expect(form).toContainText(`To YouTube · ${channel.name}`);
    await form.getByLabel("Visibility on YouTube").selectOption("PRIVATE");
    await form.getByRole("button", { name: "Upload video", exact: true }).click();
    await expect(page.getByText(/Upload received\./)).toBeVisible();
    const start = calls.hosted.find((call) => call.method === "POST" && call.path === "")?.body;
    expect(start).toMatchObject({ integrationId: channel.integrationId, privacyStatus: "PRIVATE", fileName: "lesson.mp4" });
    expect(start).not.toHaveProperty("provider");
    expect(calls.internal).toEqual([]);
    expect(calls.hosted.some((call) => call.path === "/upload-limits" && call.search.includes(channel.integrationId))).toBe(true);
  });
}

test("blocked hosted permission has no internal fallback", async ({ page }) => {
  const calls = await mockUploads(page, { hostedLimitsStatus: 403 });
  await library(page, "TEACHER");
  await page.getByRole("tab", { name: "Science channel", exact: true }).click();
  await page.getByRole("button", { name: "New media", exact: true }).first().click();
  await expect(page.getByText("You don't have permission to upload videos.", { exact: true })).toBeVisible();
  expect(calls.internal).toEqual([]);
  expect(calls.hosted.some((call) => call.method === "POST")).toBe(false);
});

test("unavailable DynTube tab explains its block without using another destination", async ({ page }) => {
  const reason = "DynTube uploads are switched off for this institute.";
  const calls = await mockUploads(page, { managedHost: { ...DYNTUBE, canUpload: false, uploadBlockedReason: reason } });
  await library(page);
  await page.getByRole("tab", { name: "DynTube", exact: true }).click();
  await expect(page.getByText(reason, { exact: false })).toBeVisible();
  expect(calls.internal).toEqual([]);
  expect(calls.hosted.some((call) => call.method === "POST")).toBe(false);
});

test("hosted cancellation retains its fixed tab and avoids completion", async ({ page }) => {
  let release: (() => void) | undefined;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  const calls = await mockUploads(page, { partGate: gate });
  try {
    await library(page);
    const form = await hostedVideo(page, "DynTube");
    await form.getByRole("button", { name: "Upload video", exact: true }).click();
    await expect(form.getByRole("button", { name: "Cancel upload", exact: true })).toBeVisible();
    await form.getByRole("button", { name: "Cancel upload", exact: true }).click();
    await expect.poll(() => calls.hosted.some((call) => call.path.endsWith("/upload/cancel"))).toBe(true);
    await expect(form).toContainText("To DynTube");
    expect(calls.hosted.some((call) => call.path.endsWith("/upload/complete"))).toBe(false);
    expect(calls.internal).toEqual([]);
  } finally { release?.(); }
});

test("an active hosted upload blocks tab switching until cancellation releases the guard", async ({ page }) => {
  let release: (() => void) | undefined;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  const calls = await mockUploads(page, { partGate: gate });
  try {
    await library(page);
    const form = await hostedVideo(page, "DynTube");
    await form.getByRole("button", { name: "Upload video", exact: true }).click();
    await expect.poll(() => calls.parts.length).toBe(1);
    const uploadsTab = page.getByRole("tab", { name: "Uploads", exact: true });
    const dynTubeTab = page.getByRole("tab", { name: "DynTube", exact: true });
    // The modal keeps background tabs inaccessible while the upload is active.
    await expect(uploadsTab).toHaveCount(0);
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Close", exact: true }).click();
    await expect(form).toBeVisible();
    await expect(form.getByRole("button", { name: "Cancel upload", exact: true })).toBeVisible();
    expect(calls.hosted.some((call) => call.path.endsWith("/upload/complete"))).toBe(false);

    await form.getByRole("button", { name: "Cancel upload", exact: true }).click();
    await expect.poll(() => calls.hosted.some((call) => call.path.endsWith("/upload/cancel"))).toBe(true);
    await expect(form.getByRole("button", { name: "Upload video", exact: true })).toBeVisible();
    await page.keyboard.press("Escape");
    await uploadsTab.click();
    await expect(uploadsTab).toHaveAttribute("aria-selected", "true");
    await expect(dynTubeTab).toHaveAttribute("aria-selected", "false");
    await expect(form).toHaveCount(0);
  } finally { release?.(); }
});

test("Archived tab never offers an upload action", async ({ page }) => {
  await mockUploads(page);
  await library(page);
  await page.getByRole("tab", { name: "Archived", exact: true }).click();
  await expect(page.getByRole("button", { name: "New media", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Upload video", exact: true })).toHaveCount(0);
});
