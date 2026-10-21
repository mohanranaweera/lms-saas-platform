import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, fulfillJson, loginResponseBody, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import { courseResponseBody, mockTenantSession } from "./fixtures/class-session-mocks";
import type { MediaFile } from "../src/lib/api/media-files";
import type { MaterialResponse } from "../src/lib/api/materials";

/**
 * R2-T6b / G1: Media Library documents/images and session attachments.
 * Mock-only UI contract tests: backend integration tests separately prove
 * tenant/ownership checks, content sniffing and lesson-entitlement enforcement.
 * These tests assert server-derived capabilities, request shape and error UX;
 * a mocked 403/404 is not evidence of backend isolation by itself.
 */

const COURSE_ID = "klass-library-course";
const MODULE_ID = "klass-library-module";
const LESSON_ID = "klass-library-lesson";
const MATERIALS_PATH = `/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`;
const VIDEO_PATH = `/api/v1/courses/${COURSE_ID}/lessons/${LESSON_ID}/media`;
const SIGNED_URL = "https://storage.example.test/library/notes?signature=test-only";

interface PopupState {
  opens: unknown[][];
  navigations: string[];
  closed: boolean;
  openerCleared: boolean;
  referrerPolicy: string | null;
}

/** A reserved tab stub keeps popup timing/navigation observable without leaving the test origin. */
async function stubReservedTab(page: Page, blocked = false) {
  await page.addInitScript(({ blocked }) => {
    const state: PopupState = { opens: [], navigations: [], closed: false, openerCleared: false, referrerPolicy: null };
    (window as unknown as { __libraryPopup: PopupState }).__libraryPopup = state;
    const tab = {
      set opener(value: unknown) { state.openerCleared = value === null; },
      document: {
        createElement: (name: string) => document.createElement(name),
        head: { appendChild: (element: HTMLMetaElement) => { state.referrerPolicy = element.name === "referrer" ? element.content : null; } },
      },
      location: { replace: (url: string) => { state.navigations.push(url); } },
      close: () => { state.closed = true; },
    };
    window.open = (...args: Parameters<typeof window.open>) => {
      state.opens.push(args);
      return blocked ? null : tab as unknown as Window;
    };
  }, { blocked });
}

async function popupState(page: Page): Promise<PopupState> {
  return page.evaluate(() => (window as unknown as { __libraryPopup: PopupState }).__libraryPopup);
}

function libraryFile(overrides: Partial<MediaFile> = {}): MediaFile {
  return {
    id: "file-notes",
    kind: "DOCUMENT",
    title: "Biology notes",
    originalFilename: "biology.pdf",
    contentType: "application/pdf",
    sizeBytes: 2048,
    uploadedBy: "teacher-1",
    uploaderName: "Demo teacher",
    createdAt: "2026-10-14T08:00:00Z",
    attachedLessonCount: 0,
    thumbnailUrl: null,
    canEdit: true,
    canDelete: true,
    ...overrides,
  };
}

function video(overrides: Record<string, unknown> = {}) {
  return {
    id: "video-shared",
    provider: "INTERNAL",
    title: "Shared institute video",
    description: null,
    thumbnailUrl: null,
    durationSeconds: 120,
    privacyStatus: null,
    processingStatus: "READY",
    failureReason: null,
    uploadedBy: "another-teacher",
    createdAt: "2026-10-14T08:00:00Z",
    capabilities: {},
    enabledPlaybackModes: [],
    uploadProgress: null,
    attached: false,
    attachedLessonCount: 0,
    youtubeUrl: null,
    integrationId: null,
    canEdit: false,
    canDelete: false,
    ...overrides,
  };
}

type Reply = { status: number; body: unknown };
type Captured = { method: string; path: string; body: string | null };

/** Stateful file mutations drive the subsequent refetch, rather than optimistic fixture assertions. */
async function mockLibrary(
  page: Page,
  options: {
    files?: MediaFile[];
    videos?: ReturnType<typeof video>[];
    listReply?: Reply;
    uploadReply?: Reply;
    deleteReply?: Reply;
    openReply?: Reply;
    renameReply?: Reply;
    channels?: unknown;
  } = {}
) {
  const files = [...(options.files ?? [libraryFile()])];
  const calls: Captured[] = [];
  const videos = options.videos ?? [video()];

  await page.route(/\/v1\/media(\/|\?|$)/, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/channels")) {
      return fulfillJson(route, 200, apiSuccess(options.channels ?? { integrations: [], managedHost: null, hasArchived: false }));
    }
    if (path.endsWith("/upload-limits")) {
      return fulfillJson(route, 200, apiSuccess({
        uploadsEnabled: true, channel: "CONNECTED", editingEnabled: true, dailyLimit: 20, usedToday: 0,
        maxFileSizeBytes: 1_000_000, partSizeBytes: 10, allowedMimeTypes: ["video/mp4"],
        canUpload: true, notAllowedReason: null, uploadsDisabledBy: null, uploadsDisabledReason: null,
      }));
    }
    if (path.endsWith("/video-shared")) return fulfillJson(route, 200, apiSuccess(videos[0]));
    const url = new URL(route.request().url());
    const status = url.searchParams.get("status");
    const provider = url.searchParams.get("provider");
    const integrationId = url.searchParams.get("integrationId");
    const search = (url.searchParams.get("q") ?? "").toLowerCase();
    const filtered = videos.filter((item) => (!status || item.processingStatus === status) &&
      (!provider || item.provider === provider) && (!integrationId || item.integrationId === integrationId) &&
      item.title.toLowerCase().includes(search));
    const pageIndex = Number(url.searchParams.get("page") ?? 0);
    const size = Number(url.searchParams.get("size") ?? 24);
    return fulfillJson(route, 200, apiPageSuccess(filtered.slice(pageIndex * size, (pageIndex + 1) * size), {
      page: pageIndex, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size),
    }));
  });
  await page.route(/\/v1\/media-files(\/|\?|$)/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(url.pathname.indexOf("/v1/media-files") + "/v1/media-files".length);
    const method = request.method();
    const body = request.postData();
    calls.push({ method, path, body });
    const reply = (response: Reply) => fulfillJson(route, response.status, response.body);

    if (method === "GET" && path === "/summary") {
      return fulfillJson(route, 200, apiSuccess({
        documents: files.filter((f) => f.kind === "DOCUMENT").length,
        images: files.filter((f) => f.kind === "IMAGE").length,
        other: files.filter((f) => f.kind === "OTHER").length,
        total: files.length,
      }));
    }
    if (method === "GET" && path === "") {
      if (options.listReply) return reply(options.listReply);
      const query = (url.searchParams.get("q") ?? "").toLowerCase();
      const kind = url.searchParams.get("kind");
      const filtered = files.filter((f) => (!kind || kind === f.kind) &&
        `${f.title} ${f.originalFilename}`.toLowerCase().includes(query));
      const pageIndex = Number(url.searchParams.get("page") ?? 0);
      const size = Number(url.searchParams.get("size") ?? 24);
      return fulfillJson(route, 200, apiPageSuccess(filtered.slice(pageIndex * size, (pageIndex + 1) * size), {
        page: pageIndex, size, totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size),
      }));
    }
    if (method === "POST" && path === "") {
      if (options.uploadReply) return reply(options.uploadReply);
      const created = libraryFile({ id: "file-uploaded", title: "Uploaded notes", originalFilename: "new-notes.txt", contentType: "text/plain" });
      files.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    if (method === "GET" && path.endsWith("/open-url")) {
      return reply(options.openReply ?? { status: 200, body: apiSuccess({ url: SIGNED_URL, expiresAt: "2099-01-01T00:00:00Z" }) });
    }
    const id = path.slice(1);
    const file = files.find((f) => f.id === id);
    if (!file) return fulfillJson(route, 404, apiError("NOT_FOUND", "Media not found"));
    if (method === "PATCH") {
      if (options.renameReply) return reply(options.renameReply);
      file.title = (request.postDataJSON() as { title: string }).title;
      return fulfillJson(route, 200, apiSuccess(file));
    }
    if (method === "DELETE") {
      if (options.deleteReply) return reply(options.deleteReply);
      files.splice(files.indexOf(file), 1);
      return fulfillJson(route, 200, apiSuccess(null));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked"));
  });
  return { files, calls };
}

function card(page: Page, id = "file-notes") {
  return page.locator(`[data-testid="media-file-card"][data-media-file-id="${id}"]`);
}

async function openLibrary(page: Page, role = "TENANT_ADMIN") {
  await mockTenantSession(page, role, role === "TEACHER" ? "teacher-1" : "admin-1");
  await page.goto(role === "TEACHER" ? "/teacher/media" : "/tenant-admin/media");
  await expect(page.getByRole("tab", { name: "Uploads", exact: true })).toHaveAttribute("aria-selected", "true");
}

test.describe("Klass Media Library uploads", () => {
  test("Uploads-first cards and summary count institute files/videos; search and type filter use server results", async ({ page }) => {
    await mockLibrary(page, { files: [libraryFile(), libraryFile({ id: "file-image", kind: "IMAGE", title: "Cell diagram", originalFilename: "cell.png", contentType: "image/png" })] });
    await openLibrary(page);
    await expect(page.getByTestId("media-summary-total")).toHaveText("3 items");
    await expect(page.getByTestId("media-summary-videos")).toHaveText("1 video");
    await expect(page.getByTestId("media-summary-documents")).toHaveText("1 document");
    await expect(page.getByTestId("media-summary-images")).toHaveText("1 image");
    await expect(page.getByTestId("media-file-card")).toHaveCount(2);
    await expect(page.getByTestId("media-uploads-videos")).toContainText("Shared institute video");
    await expect(card(page).getByTestId("media-file-kind")).toHaveText("Document");
    await expect(card(page)).toContainText("2 KB");

    await page.getByTestId("media-files-type").selectOption("IMAGE");
    await expect(page.getByTestId("media-file-card")).toHaveCount(1);
    await expect(card(page, "file-image")).toBeVisible();
    await page.getByTestId("media-files-search").fill("missing");
    await expect(page.getByText("No files match", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByTestId("media-file-card")).toHaveCount(2);
    await expect(page.getByTestId("media-files-type")).toHaveValue("ALL");

    await page.getByTestId("media-files-type").selectOption("VIDEO");
    await expect(page.getByTestId("media-uploads-videos")).toContainText("Shared institute video");
    await expect(page.getByTestId("media-file-card")).toHaveCount(0);
  });

  test("All types keeps a video-only library visible and searches both sources", async ({ page }) => {
    await mockLibrary(page, { files: [] });
    await openLibrary(page);
    await expect(page.getByTestId("media-uploads-videos")).toContainText("Shared institute video");
    await page.getByTestId("media-files-search").fill("Shared institute");
    await expect(page.getByTestId("media-uploads-videos")).toContainText("Shared institute video");
    await page.getByTestId("media-files-search").fill("missing");
    await expect(page.getByTestId("media-uploads-videos")).toContainText("No media matches");
    await page.getByTestId("media-uploads-videos").getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByTestId("media-files-search")).toHaveValue("");
    await expect(page.getByTestId("media-uploads-videos")).toContainText("Shared institute video");
  });

  test("All types paginates files and internal videos independently with bounded requests", async ({ page }) => {
    const files = Array.from({ length: 25 }, (_, i) => libraryFile({ id: `file-${i}`, title: `Notes ${i}`, attachedLessonCount: 0 }));
    const videos = Array.from({ length: 25 }, (_, i) => video({ id: `video-${i}`, title: `Video ${i}` }));
    const requests: URL[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (/\/v1\/(media|media-files)$/.test(url.pathname)) requests.push(url);
    });
    await mockLibrary(page, { files, videos });
    await openLibrary(page);
    const filePages = page.getByRole("navigation", { name: "Media file pages" });
    const videoPages = page.getByRole("navigation", { name: "Video pages" });
    await expect(page.getByTestId("media-file-card")).toHaveCount(24);
    await expect(page.getByRole("list", { name: "Media", exact: true }).getByRole("listitem")).toHaveCount(24);
    await expect(filePages.getByRole("button", { name: "Previous" })).toBeDisabled();
    await filePages.getByRole("button", { name: "Next" }).click();
    await expect(card(page, "file-24")).toBeVisible();
    await expect(page.getByTestId("media-file-card")).toHaveCount(1);
    await expect(filePages).toContainText("Page 2 of 2");
    await expect(filePages.getByRole("button", { name: "Next" })).toBeDisabled();
    await expect(videoPages).toContainText("Page 1 of 2");
    await videoPages.getByRole("button", { name: "Next" }).click();
    await expect(videoPages).toContainText("Page 2 of 2");
    await expect(page.getByRole("list", { name: "Media", exact: true })).toContainText("Video 24");
    await videoPages.getByRole("button", { name: "Previous" }).click();
    await expect(videoPages).toContainText("Page 1 of 2");
    await card(page, "file-24").getByTestId("media-file-delete").click();
    await page.getByTestId("media-file-delete-confirm").click();
    await expect(page.getByTestId("media-file-card")).toHaveCount(24);
    await expect(filePages).toHaveCount(0);
    await page.getByTestId("media-files-search").fill("Video 24");
    await expect(page.getByRole("list", { name: "Media", exact: true }).getByRole("listitem")).toHaveCount(1);
    await expect(videoPages).toHaveCount(0);
    expect(requests.filter((url) => url.searchParams.get("size") !== "1").every((url) => url.searchParams.get("size") === "24")).toBe(true);
    expect(requests.some((url) => url.pathname.endsWith("/media-files") && url.searchParams.get("page") === "1")).toBe(true);
    expect(requests.some((url) => url.pathname.endsWith("/media") && url.searchParams.get("page") === "1")).toBe(true);
  });

  test("New media uploads multipart without tenant/owner claims and refreshes grid and counts", async ({ page }) => {
    const { calls } = await mockLibrary(page, { files: [] });
    await openLibrary(page);
    await page.getByTestId("media-files-new").click();
    await page.getByTestId("media-file-upload-input").setInputFiles({ name: "new-notes.txt", mimeType: "text/plain", buffer: Buffer.from("Synthetic test notes") });
    await page.getByTestId("media-file-upload-title").fill("Uploaded notes");
    await page.getByTestId("media-file-upload-submit").click();
    await expect(page.getByTestId("media-file-upload-dialog")).toHaveCount(0);
    await expect(card(page, "file-uploaded")).toContainText("Uploaded notes");
    await expect(page.getByTestId("media-summary-documents")).toHaveText("1 document");
    const upload = calls.find((c) => c.method === "POST" && c.path === "");
    expect(upload?.body).toContain('name="file"; filename="new-notes.txt"');
    expect(upload?.body).toContain('name="title"');
    expect(upload?.body).toContain("Uploaded notes");
    expect(upload?.body).not.toMatch(/name="(?:tenant_?id|uploadedBy|userId)"/i);
  });

  test("upload checks unsupported extension and oversized files without sending them", async ({ page }) => {
    const { calls } = await mockLibrary(page);
    await openLibrary(page);
    await page.getByTestId("media-files-new").click();
    await page.getByTestId("media-file-upload-input").setInputFiles({ name: "notes.docx", mimeType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", buffer: Buffer.from("test") });
    await page.getByTestId("media-file-upload-submit").click();
    await expect(page.getByTestId("media-file-upload-file-error")).toContainText("This file type isn't supported.");
    await page.getByTestId("media-file-upload-input").setInputFiles({ name: "large.pdf", mimeType: "application/pdf", buffer: Buffer.alloc(25 * 1024 * 1024 + 1) });
    await page.getByTestId("media-file-upload-submit").click();
    await expect(page.getByTestId("media-file-upload-file-error")).toHaveText("This file is larger than 25 MB.");
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
  });

  test("server sniff rejection remains visible and does not create a card", async ({ page }) => {
    await mockLibrary(page, { files: [], uploadReply: { status: 415, body: apiError("UNSUPPORTED_MEDIA_TYPE", "Unsupported content") } });
    await openLibrary(page);
    await page.getByTestId("media-files-new").click();
    await page.getByTestId("media-file-upload-input").setInputFiles({ name: "pretend.pdf", mimeType: "application/pdf", buffer: Buffer.from("not a PDF") });
    await page.getByTestId("media-file-upload-submit").click();
    await expect(page.getByTestId("media-file-upload-error")).toContainText("This file type isn't supported.");
    await expect(page.getByTestId("media-file-upload-dialog")).toBeVisible();
    await expect(page.getByTestId("media-file-card")).toHaveCount(0);
  });

  test("rename persists only the title and delete removes an unused file", async ({ page }) => {
    const { calls } = await mockLibrary(page);
    await openLibrary(page);
    await card(page).getByTestId("media-file-rename").click();
    await page.getByTestId("media-file-rename-input").fill("Renamed notes");
    await page.getByTestId("media-file-rename-submit").click();
    await expect(card(page)).toContainText("Renamed notes");
    expect(JSON.parse(calls.find((c) => c.method === "PATCH")?.body ?? "null")).toEqual({ title: "Renamed notes" });
    await card(page).getByTestId("media-file-delete").click();
    await page.getByTestId("media-file-delete-confirm").click();
    await expect(page.getByTestId("media-file-card")).toHaveCount(0);
    await expect(page.getByText("No files yet", { exact: true })).toBeVisible();
    await expect(page.getByTestId("media-summary-documents")).toHaveText("0 documents");
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/file-notes")).toBe(true);
  });

  test("an attached file's server 409 keeps its card and explains how to remove its use first", async ({ page }) => {
    await mockLibrary(page, { files: [libraryFile({ attachedLessonCount: 2 })], deleteReply: { status: 409, body: apiError("MEDIA_ATTACHED", "File is attached") } });
    await openLibrary(page);
    await expect(card(page).getByTestId("media-file-usage")).toHaveText("Used in 2 lessons");
    await card(page).getByTestId("media-file-delete").click();
    await expect(page.getByTestId("media-file-delete-dialog")).toContainText("Remove it from those sessions before deleting it.");
    await page.getByTestId("media-file-delete-confirm").click();
    await expect(page.getByTestId("media-file-delete-error")).toHaveText("Used in 2 lessons — remove it from those sessions first.");
    await expect(page.getByTestId("media-file-delete-dialog")).toBeVisible();
    await expect(card(page)).toBeVisible();
  });

  test("teacher sees and opens shared files but mutation controls follow server capabilities", async ({ page }) => {
    const { calls } = await mockLibrary(page, { files: [libraryFile({ id: "shared-file", uploadedBy: "another-teacher", canEdit: false, canDelete: false }), libraryFile({ id: "own-file", title: "My notes" })] });
    await stubReservedTab(page);
    await openLibrary(page, "TEACHER");
    const shared = card(page, "shared-file");
    await expect(shared.getByTestId("media-file-open")).toBeVisible();
    await expect(shared.getByTestId("media-file-rename")).toHaveCount(0);
    await expect(shared.getByTestId("media-file-delete")).toHaveCount(0);
    await expect(card(page, "own-file").getByTestId("media-file-rename")).toBeVisible();
    await expect(card(page, "own-file").getByTestId("media-file-delete")).toBeVisible();
    await shared.getByTestId("media-file-open").click();
    await expect.poll(async () => (await popupState(page)).navigations).toEqual([SIGNED_URL]);
    expect(await popupState(page)).toMatchObject({ opens: [["about:blank", "_blank"]], closed: false, openerCleared: true, referrerPolicy: "no-referrer" });
    expect(calls.some((c) => c.method === "GET" && c.path === "/shared-file/open-url")).toBe(true);
    expect(calls.some((c) => c.method === "PATCH" || c.method === "DELETE")).toBe(false);
  });

  test("shared videos stay browseable with View only and no mutation controls on details", async ({ page }) => {
    await mockLibrary(page);
    await openLibrary(page, "TEACHER");
    await page.getByTestId("media-files-type").selectOption("VIDEO");
    await expect(page.getByTestId("media-video-view-only")).toHaveText("View only");
    await page.getByRole("link", { name: /Shared institute video/ }).click();
    await expect(page.getByRole("heading", { name: "Shared institute video" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Edit details", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete", exact: true })).toHaveCount(0);
  });

  test("stale permissions on rename show server 404 instead of reporting success", async ({ page }) => {
    await mockLibrary(page, { renameReply: { status: 404, body: apiError("NOT_FOUND", "Media not found") } });
    await openLibrary(page, "TEACHER");
    await card(page).getByTestId("media-file-rename").click();
    await page.getByTestId("media-file-rename-input").fill("Not permitted");
    await page.getByTestId("media-file-rename-submit").click();
    await expect(page.getByTestId("media-file-rename-error")).toHaveText("This file no longer exists or you can't rename it.");
    await expect(card(page)).toContainText("Biology notes");
  });

  for (const status of [403, 500]) {
    test(`file-list ${status} renders an error/permission state rather than empty-library copy`, async ({ page }) => {
      await mockLibrary(page, { listReply: { status, body: apiError(status === 403 ? "FORBIDDEN" : "INTERNAL_ERROR", "Synthetic failure") } });
      await openLibrary(page);
      await expect(page.getByText(status === 403 ? "You don't have permission to view this." : "Synthetic failure", { exact: true })).toBeVisible();
      await expect(page.getByText("No files yet", { exact: true })).toHaveCount(0);
      await expect(page.getByTestId("media-file-card")).toHaveCount(0);
    });
  }

  test("same-browser account switch does not reuse the previous teacher's file permissions or cached cards", async ({ page }) => {
    const library = await mockLibrary(page);
    let account = "teacher-1";
    const token = () => fakeJwt({ role: "TEACHER", sub: account });
    await page.route("**/v1/auth/refresh", (route) => fulfillJson(route, 200, apiSuccess(refreshResponseBody(token()))));
    await page.route("**/v1/auth/login", (route) => fulfillJson(route, 200, apiSuccess(loginResponseBody(token()))));
    await mockJson(page, "**/v1/auth/logout", 200, apiSuccess(null));
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));
    await page.goto("/teacher/media");
    await expect(card(page).getByTestId("media-file-rename")).toBeVisible();
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    account = "teacher-2";
    library.files.splice(0, library.files.length, libraryFile({ canEdit: false, canDelete: false }), libraryFile({ id: "second-teacher-file", title: "Second teacher notes", uploadedBy: account }));
    let release: (() => void) | undefined;
    const listGate = new Promise<void>((resolve) => { release = resolve; });
    await page.route(/\/v1\/media-files\?/, async (route) => {
      await listGate;
      await route.fallback();
    });
    await page.getByLabel("Email").fill("second-teacher@example.test");
    await page.getByLabel("Password", { exact: true }).fill("synthetic-test-password");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page).toHaveURL(/\/teacher\/dashboard$/);
    await page.getByRole("link", { name: "Media Library", exact: true }).first().click();
    await expect(page).toHaveURL(/\/teacher\/media$/);
    await expect(page.getByText("Loading media files…", { exact: true })).toBeVisible();
    await expect(page.getByTestId("media-file-card")).toHaveCount(0);
    release?.();
    await expect(card(page, "second-teacher-file")).toBeVisible();
    await expect(card(page).getByTestId("media-file-rename")).toHaveCount(0);
    await expect(card(page).getByTestId("media-file-delete")).toHaveCount(0);
    await expect(card(page, "second-teacher-file").getByTestId("media-file-rename")).toBeVisible();
  });

  test("open-url 404 exposes no URL and shows the unavailable-file message", async ({ page }) => {
    await mockLibrary(page, { openReply: { status: 404, body: apiError("NOT_FOUND", "Media not found") } });
    await stubReservedTab(page);
    await openLibrary(page);
    await card(page).getByTestId("media-file-open").click();
    await expect(card(page).getByRole("alert")).toHaveText("This file is no longer available.");
    expect(await popupState(page)).toMatchObject({ opens: [["about:blank", "_blank"]], navigations: [], closed: true });
  });

  test("blocked popup reports how to recover without requesting a signed URL", async ({ page }) => {
    const { calls } = await mockLibrary(page);
    await stubReservedTab(page, true);
    await openLibrary(page);
    await card(page).getByTestId("media-file-open").click();
    await expect(card(page).getByRole("alert")).toHaveText("Your browser blocked the new tab. Allow popups for this site and try again.");
    expect(calls.some((call) => call.path.endsWith("/open-url"))).toBe(false);
    expect(await popupState(page)).toMatchObject({ opens: [["about:blank", "_blank"]], navigations: [], closed: false });
  });

  test("a reserved tab stays blank during slow signing then navigates once to the signed URL", async ({ page }) => {
    await mockLibrary(page);
    await stubReservedTab(page);
    let release: (() => void) | undefined;
    const signingGate = new Promise<void>((resolve) => { release = resolve; });
    await page.route("**/v1/media-files/file-notes/open-url", async (route) => {
      await signingGate;
      await route.fallback();
    });
    await openLibrary(page);
    const signingRequested = page.waitForRequest((request) => new URL(request.url()).pathname.endsWith("/media-files/file-notes/open-url"));
    await card(page).getByTestId("media-file-open").click();
    await signingRequested;
    await expect(card(page).getByTestId("media-file-open")).toBeDisabled();
    expect(await popupState(page)).toEqual({ opens: [["about:blank", "_blank"]], navigations: [], closed: false, openerCleared: true, referrerPolicy: "no-referrer" });
    release?.();
    await expect.poll(async () => (await popupState(page)).navigations).toEqual([SIGNED_URL]);
    await expect(card(page).getByTestId("media-file-open")).toBeEnabled();
    expect((await popupState(page)).closed).toBe(false);
  });
});

test("file cards show the Added date beside size and uploader", async ({ page }) => {
  await mockLibrary(page);
  await openLibrary(page);
  await expect(card(page)).toContainText("Demo teacher");
  await expect(card(page).getByTestId("media-added-date")).toHaveText(/^Added Oct 1[34], 2026/);
});

function material(file: MediaFile): MaterialResponse {
  return {
    id: "library-material", lessonId: LESSON_ID, sessionId: null, materialType: "PDF", title: file.title,
    originalFilename: file.originalFilename, mimeType: file.contentType, sizeBytes: file.sizeBytes,
    externalUrl: null, noteContent: null, videoAssetId: null, sequence: 1, visibility: "VISIBLE",
    maxDownloads: null, downloadCount: 0, availableFromAt: null, expiryAt: null, uploadedBy: "teacher-1",
    createdAt: "2026-10-14T08:00:00Z", updatedAt: "2026-10-14T08:00:00Z", mediaFileId: file.id,
  };
}

async function mockSessionEditor(page: Page, options: { attached?: boolean; attachReply?: Reply; role?: "TEACHER" | "TENANT_ADMIN" } = {}) {
  const materials = options.attached ? [material(libraryFile())] : [];
  const lessonVideos: unknown[] = [];
  const calls: Captured[] = [];
  await page.route("**/api/v1/courses/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    calls.push({ method, path, body: request.postData() });
    if (path === `/api/v1/courses/${COURSE_ID}`) return fulfillJson(route, 200, apiSuccess(courseResponseBody({ id: COURSE_ID, teacherId: "teacher-1", name: "Library QA course", status: "DRAFT" })));
    if (path === `/api/v1/courses/${COURSE_ID}/modules`) return fulfillJson(route, 200, apiSuccess([{ id: MODULE_ID, courseId: COURSE_ID, title: "Module 1", sequence: 1 }]));
    if (path === `/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons`) return fulfillJson(route, 200, apiSuccess([{ id: LESSON_ID, moduleId: MODULE_ID, title: "Session 1", sequence: 1 }]));
    if (path === MATERIALS_PATH && method === "GET") return fulfillJson(route, 200, apiSuccess(materials));
    if (path === MATERIALS_PATH && method === "POST") {
      if (options.attachReply) return fulfillJson(route, options.attachReply.status, options.attachReply.body);
      const created = material(libraryFile());
      materials.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    if (path === `${MATERIALS_PATH}/library-material` && method === "DELETE") {
      materials.splice(0, materials.length);
      return fulfillJson(route, 200, apiSuccess(null));
    }
    if (path === VIDEO_PATH && method === "GET") return fulfillJson(route, 200, apiSuccess(lessonVideos));
    if (path === VIDEO_PATH && method === "POST") {
      const attached = { id: "lesson-video", lessonId: LESSON_ID, courseId: COURSE_ID, mediaAssetId: "video-shared", title: "Shared institute video", sequence: 1, visibility: "VISIBLE", provider: "INTERNAL", durationSeconds: 120, status: "READY", createdAt: "2026-10-14T08:00:00Z", updatedAt: "2026-10-14T08:00:00Z" };
      lessonVideos.push(attached);
      return fulfillJson(route, 201, apiSuccess(attached));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked"));
  });
  const role = options.role ?? "TEACHER";
  await mockTenantSession(page, role, role === "TEACHER" ? "teacher-1" : "admin-1");
  const portal = role === "TEACHER" ? "teacher" : "tenant-admin";
  const editor = role === "TEACHER" ? "modules" : "content";
  await page.goto(`/${portal}/courses/${COURSE_ID}/${editor}`);
  await expect(page.getByTestId("materials-add-from-library")).toBeVisible();
  return calls;
}

test.describe("Klass session library picker", () => {
  test("teacher attaches a shared file without copying bytes, then detaches only its lesson material", async ({ page }) => {
    await mockLibrary(page, { files: [libraryFile({ uploadedBy: "another-teacher", canEdit: false, canDelete: false })] });
    const calls = await mockSessionEditor(page);
    await page.getByTestId("materials-add-from-library").click();
    await expect(page.getByTestId("library-file-picker")).toContainText("It is linked, not copied");
    await page.getByTestId("library-file-picker-add").click();
    await expect(page.getByTestId("library-file-picker")).toHaveCount(0);
    await expect(page.getByTestId("material-row-library")).toContainText("Biology notes");
    await expect(page.getByTestId("material-library-badge")).toHaveText("Library");
    const attach = calls.find((c) => c.path === MATERIALS_PATH && c.method === "POST");
    expect(attach?.body).toContain('name="mediaFileId"');
    expect(attach?.body).toContain("file-notes");
    expect(attach?.body).toContain('name="title"');
    expect(attach?.body).not.toMatch(/filename=|name="(?:file|materialType|tenant_?id|uploadedBy)"/i);

    await page.getByTestId("materials-add-from-library").click();
    await expect(page.getByTestId("library-file-picker-add")).toBeDisabled();
    await expect(page.getByTestId("library-file-picker-add")).toContainText("Attached");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: 'Delete material "Biology notes"', exact: true }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Delete material", exact: true }).click();
    await expect(page.getByTestId("material-row-library")).toHaveCount(0);
    expect(calls.some((c) => c.method === "DELETE" && c.path === `${MATERIALS_PATH}/library-material`)).toBe(true);
    await page.getByTestId("materials-add-from-library").click();
    await expect(page.getByTestId("library-file-picker-add")).toBeEnabled();
  });

  for (const response of [
    { status: 409, code: "MEDIA_ALREADY_ATTACHED", expected: "Already attached to this session." },
    { status: 403, code: "FORBIDDEN", expected: "You don't have permission to add materials to this session." },
    { status: 404, code: "NOT_FOUND", expected: "This file is no longer in the Media Library. The list has been refreshed." },
  ]) {
    test(`attach ${response.status} stays in the picker without a local success or material`, async ({ page }) => {
      const library = await mockLibrary(page);
      await mockSessionEditor(page, { attachReply: { status: response.status, body: apiError(response.code, "Synthetic failure") } });
      await page.getByTestId("materials-add-from-library").click();
      const readsBefore = library.calls.filter((c) => c.method === "GET" && c.path === "").length;
      await page.getByTestId("library-file-picker-add").click();
      await expect(page.getByTestId("library-file-picker-error")).toHaveText(response.expected);
      await expect(page.getByTestId("library-file-picker")).toBeVisible();
      await expect(page.getByTestId("material-row-library")).toHaveCount(0);
      await expect(page.getByTestId("materials-library-status")).toHaveText("");
      if (response.status === 404) {
        await expect.poll(() => library.calls.filter((c) => c.method === "GET" && c.path === "").length).toBeGreaterThan(readsBefore);
      }
    });
  }

  test("tenant-admin picker permission denial links to the tenant-admin dashboard", async ({ page }) => {
    await mockLibrary(page, { listReply: { status: 403, body: apiError("FORBIDDEN", "Synthetic denial") } });
    await mockSessionEditor(page, { role: "TENANT_ADMIN" });
    await page.getByTestId("materials-add-from-library").click();
    const picker = page.getByTestId("library-file-picker");
    await expect(picker.getByText("You don't have permission to view this.", { exact: true })).toBeVisible();
    await expect(picker.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute("href", "/tenant-admin/dashboard");
    await expect(picker.getByTestId("library-file-picker-item")).toHaveCount(0);
  });

  test("Escape cannot dismiss a picker during attachment or expose a second submission", async ({ page }) => {
    await mockLibrary(page);
    const calls = await mockSessionEditor(page);
    let release: (() => void) | undefined;
    const attachmentGate = new Promise<void>((resolve) => { release = resolve; });
    await page.route(`**${MATERIALS_PATH}`, async (route) => {
      if (route.request().method() === "POST") await attachmentGate;
      await route.fallback();
    });
    await page.getByTestId("materials-add-from-library").click();
    const submitted = page.waitForRequest((request) => request.method() === "POST" && new URL(request.url()).pathname === MATERIALS_PATH);
    await page.getByTestId("library-file-picker-add").click();
    await submitted;
    await expect(page.getByTestId("library-file-picker-add")).toBeDisabled();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("library-file-picker")).toBeVisible();
    await expect(page.getByTestId("library-file-picker-add")).toBeDisabled();
    release?.();
    await expect(page.getByTestId("library-file-picker")).toHaveCount(0);
    await expect(page.getByTestId("material-row-library")).toContainText("Biology notes");
    expect(calls.filter((call) => call.method === "POST" && call.path === MATERIALS_PATH)).toHaveLength(1);
  });

  test("shared-video policy denial preserves successful attachment and offers Done without a duplicate attach", async ({ page }) => {
    await mockLibrary(page);
    const calls = await mockSessionEditor(page);
    const policies: unknown[] = [];
    await page.route("**/v1/videos/video-shared/watch-time-limit", async (route) => {
      if (route.request().method() === "PUT") policies.push(route.request().postDataJSON());
      await fulfillJson(route, 403, apiError("FORBIDDEN", "You can't change this video's policy."));
    });
    await page.getByRole("button", { name: "Choose from Media Library", exact: true }).click();
    await page.getByLabel(/Shared video watch-time limit/).fill("45");
    await page.getByRole("button", { name: "Add", exact: true }).click();
    const error = page.getByRole("alert").filter({ hasText: "The video was added" });
    await expect(error).toContainText("Its existing playback rules remain unchanged.");
    await expect(error).toContainText("Attaching a video does not necessarily give you permission to change its rules.");
    await expect(page.locator('input[value="Shared institute video"]')).toBeAttached();
    await expect(page.getByRole("list", { name: "Ready media" }).getByRole("button", { name: "Attached", exact: true })).toBeDisabled();
    expect(policies).toEqual([{ limitSeconds: 2700 }]);
    expect(calls.filter((call) => call.method === "POST" && call.path === VIDEO_PATH)).toHaveLength(1);
    await error.getByRole("button", { name: "Done", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Choose session video" })).toHaveCount(0);
    await expect(page.locator('input[value="Shared institute video"]')).toBeVisible();
    await page.getByRole("button", { name: "Choose from Media Library", exact: true }).click();
    await expect(page.getByRole("list", { name: "Ready media" }).getByRole("button", { name: "Attached", exact: true })).toBeDisabled();
    expect(calls.filter((call) => call.method === "POST" && call.path === VIDEO_PATH)).toHaveLength(1);
  });

  test("teacher can attach a ready shared video despite its owner-only edit/delete capabilities", async ({ page }) => {
    await mockLibrary(page, { videos: [video(), video({ id: "video-processing", title: "Not ready", processingStatus: "PROCESSING" })] });
    const calls = await mockSessionEditor(page);
    await page.getByRole("button", { name: "Choose from Media Library", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Choose session video" })).toBeVisible();
    await expect(page.getByText("Shared institute video", { exact: true })).toBeVisible();
    await expect(page.getByText("Not ready", { exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Add", exact: true }).click();
    await expect(page.locator('input[value="Shared institute video"]')).toBeVisible();
    expect(JSON.parse(calls.find((c) => c.method === "POST" && c.path === VIDEO_PATH)?.body ?? "null")).toEqual({ mediaAssetId: "video-shared" });
  });

  test("uploads a file from the picker, keeps it open, announces and marks it new, then attaches it", async ({ page }) => {
    await mockLibrary(page);
    const calls = await mockSessionEditor(page);
    await page.getByTestId("materials-add-from-library").click();
    const picker = page.getByTestId("library-file-picker");
    await expect(picker.getByTestId("library-file-picker-count")).toHaveText("1 file");
    const existing = picker.locator('[data-testid="library-file-picker-item"][data-media-file-id="file-notes"]');
    await expect(existing).toContainText("Demo teacher");
    await expect(existing.getByTestId("media-added-date")).toHaveText(/^Added Oct 1[34], 2026/);

    await picker.getByTestId("library-file-picker-new").click();
    await page.getByTestId("media-file-upload-input").setInputFiles({ name: "new-notes.txt", mimeType: "text/plain", buffer: Buffer.from("Synthetic test notes") });
    await page.getByTestId("media-file-upload-title").fill("Uploaded notes");
    await page.getByTestId("media-file-upload-submit").click();
    await expect(page.getByTestId("media-file-upload-dialog")).toHaveCount(0);
    await expect(picker).toBeVisible();
    await expect(picker.getByTestId("library-file-picker-new")).toBeFocused();
    await expect(picker.getByTestId("library-file-picker-status")).toHaveText('Uploaded "Uploaded notes". Select Add to attach it.');
    await expect(picker.getByTestId("library-file-picker-status")).toHaveAttribute("role", "status");
    const uploaded = picker.locator('[data-testid="library-file-picker-item"][data-media-file-id="file-uploaded"]');
    await expect(uploaded).toHaveAttribute("data-new", "true");
    await expect(uploaded.getByTestId("library-file-picker-new-badge")).toHaveText("New");
    await expect(picker.getByTestId("library-file-picker-count")).toHaveText("2 files");

    await uploaded.getByTestId("library-file-picker-add").click();
    await expect(picker).toHaveCount(0);
    const attach = calls.find((c) => c.path === MATERIALS_PATH && c.method === "POST");
    expect(attach?.body).toContain("file-uploaded");
  });

  test("picker type filter asks the server for that kind only", async ({ page }) => {
    await mockLibrary(page, { files: [libraryFile(), libraryFile({ id: "file-image", kind: "IMAGE", title: "Cell diagram", originalFilename: "cell.png", contentType: "image/png" })] });
    await mockSessionEditor(page);
    await page.getByTestId("materials-add-from-library").click();
    const picker = page.getByTestId("library-file-picker");
    await expect(picker.getByTestId("library-file-picker-count")).toHaveText("2 files");
    const imagesRequested = page.waitForRequest((request) => {
      const url = new URL(request.url());
      return url.pathname.endsWith("/v1/media-files") && url.searchParams.get("kind") === "IMAGE";
    });
    await picker.getByTestId("library-file-picker-type").selectOption("IMAGE");
    await imagesRequested;
    await expect(picker.getByTestId("library-file-picker-item")).toHaveCount(1);
    await expect(picker.getByTestId("library-file-picker-item")).toContainText("Cell diagram");
    await expect(picker.getByTestId("library-file-picker-count")).toHaveText("1 file");
  });

  test("video picker filters by source, shows Added dates, and uploads only to a chosen source", async ({ page }) => {
    await mockLibrary(page, {
      channels: {
        integrations: [{ integrationId: "int-1", name: "Channel A", connected: true, canUpload: true }],
        managedHost: { provider: "DYNTUBE", name: "DynTube", connected: true, canUpload: false, uploadBlockedReason: "DynTube isn't set up for uploads yet." },
        hasArchived: false,
      },
      videos: [video(), video({ id: "video-yt", provider: "YOUTUBE", title: "Channel video", integrationId: "int-1" })],
    });
    await mockSessionEditor(page, { role: "TENANT_ADMIN" });
    await page.getByRole("button", { name: "Choose from Media Library", exact: true }).click();
    const picker = page.getByTestId("media-video-picker");
    await expect(picker.getByTestId("media-video-picker-count")).toHaveText("2 ready videos");
    await expect(picker.getByTestId("media-video-picker-ready-note")).toContainText("appear here once they're ready");
    await expect(picker.getByTestId("media-video-picker-item").first().getByTestId("media-added-date")).toHaveText(/^Added Oct 1[34], 2026/);
    const source = picker.getByTestId("media-video-picker-source");
    await expect(source.locator("option")).toHaveText(["All sources", "Uploads (internal storage)", "DynTube", "Channel A (YouTube)"]);
    await expect(picker.getByTestId("media-video-picker-new")).toBeDisabled();
    await expect(picker.getByTestId("media-video-picker-upload-hint")).toHaveText("Choose a source to upload a new video to it.");

    const internalRequested = page.waitForRequest((request) => {
      const url = new URL(request.url());
      return url.pathname.endsWith("/v1/media") && url.searchParams.get("provider") === "INTERNAL" && url.searchParams.get("status") === "READY";
    });
    await source.selectOption("INTERNAL");
    await internalRequested;
    await expect(picker.getByTestId("media-video-picker-count")).toHaveText("1 ready video");
    await expect(picker.getByTestId("media-video-picker-new")).toBeEnabled();

    const channelRequested = page.waitForRequest((request) => {
      const url = new URL(request.url());
      return url.pathname.endsWith("/v1/media") && url.searchParams.get("integrationId") === "int-1" &&
        url.searchParams.get("status") === "READY" && !url.searchParams.has("provider");
    });
    await source.selectOption("integration:int-1");
    await channelRequested;
    await expect(picker.getByTestId("media-video-picker-item")).toHaveCount(1);
    await expect(picker.getByTestId("media-video-picker-item")).toContainText("Channel video");
    await picker.getByTestId("media-video-picker-new").click();
    const providerUpload = page.getByTestId("media-provider-upload-dialog");
    await expect(providerUpload).toContainText("Upload a video to Channel A on YouTube.");
    await page.keyboard.press("Escape");
    await expect(providerUpload).toHaveCount(0);
    await expect(picker).toBeVisible();

    await source.selectOption("DYNTUBE");
    await expect(picker.getByTestId("media-video-picker-upload-blocked")).toContainText("DynTube isn't set up for uploads yet.");
    await expect(picker.getByTestId("media-video-picker-new")).toBeDisabled();
  });

  test("an internal video uploaded from the video picker is announced as attachable once processed", async ({ page }) => {
    await mockLibrary(page);
    await mockSessionEditor(page);
    const posts: string[] = [];
    await page.route(/\/v1\/videos(?:\?|$)/, async (route) => {
      posts.push(route.request().headers()["content-type"] ?? "");
      return fulfillJson(route, 201, apiSuccess({
        id: "internal-video", originalFilename: "lesson.mp4", mimeType: "video/mp4", sizeBytes: 8,
        durationSeconds: null, status: "PROCESSING", uploadedBy: "teacher-1", createdAt: "2026-10-16T08:00:00Z",
      }));
    });
    await page.getByRole("button", { name: "Choose from Media Library", exact: true }).click();
    const picker = page.getByTestId("media-video-picker");
    await picker.getByTestId("media-video-picker-source").selectOption("INTERNAL");
    await expect(picker.getByTestId("media-video-picker-count")).toHaveText("1 ready video");
    await picker.getByTestId("media-video-picker-new").click();
    const dialog = page.getByTestId("media-file-upload-dialog");
    await dialog.getByTestId("media-file-upload-input").setInputFiles({ name: "lesson.mp4", mimeType: "video/mp4", buffer: Buffer.alloc(8, 7) });
    await expect(dialog).toContainText("Tenant internal storage");
    const refetched = page.waitForRequest((request) => {
      const url = new URL(request.url());
      return url.pathname.endsWith("/v1/media") && url.searchParams.get("provider") === "INTERNAL";
    });
    await dialog.getByRole("button", { name: "Upload video", exact: true }).click();
    await expect(picker.getByTestId("media-video-picker-status")).toHaveText("Video uploaded. It becomes attachable here once processing finishes.");
    await refetched;
    expect(posts).toHaveLength(1);
    await dialog.getByTestId("media-file-upload-view-videos").click();
    await expect(dialog).toHaveCount(0);
    await expect(picker).toBeVisible();
  });
});
