import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

const COURSE_ID = "course-resources-1";
const MODULE_ID = "module-resources-1";
const LESSON_ID = "session-resources-1";

const catalog = [
  { type: "DOCUMENT", label: "Document", support: "INTEGRATED", help: "Choose documents.", liveClass: false },
  { type: "SECURE_VIDEO", label: "Player Video", support: "INTEGRATED", help: "Choose ready videos.", liveClass: false },
  { type: "IMAGE", label: "Image", support: "INTEGRATED", help: "Choose images.", liveClass: false },
  { type: "VIDEO_LINK", label: "Video Link", support: "LINK_ONLY", help: "Paste a link.", liveClass: false },
  { type: "GOOGLE_DRIVE_LINK", label: "Google Drive Link", support: "LINK_ONLY", help: "Paste a Drive link.", liveClass: false },
  { type: "MS_TEAMS_LINK", label: "MS Teams Link", support: "LINK_ONLY", help: "Paste a Teams link.", liveClass: true },
  { type: "GOOGLE_MEET", label: "Google Meet", support: "LINK_ONLY", help: "Paste a Meet link.", liveClass: true },
  { type: "ZOOM_MEETING", label: "Zoom Meeting", support: "INTEGRATED", help: "Zoom is parked.", liveClass: true },
  { type: "ZOOM_RECORDING", label: "Zoom Recording", support: "UNSUPPORTED", help: "Not available yet.", liveClass: false },
];

function course() {
  return {
    id: COURSE_ID, teacherId: "teacher-1", name: "Resource course", slug: "resource-course", category: "Science",
    subject: null, stream: null, grade: null, academicYear: null, description: null, price: 0,
    accessDurationDays: null, enrollmentRule: null, status: "DRAFT", pricingModel: "FREE", archivedAt: null,
    createdAt: "2026-10-17T00:00:00Z", updatedAt: "2026-10-17T00:00:00Z", resolvedAmount: 0,
    currency: "USD", requiresManualQuote: false, registrationEnabled: true, enrollmentFee: null,
    coverImageUrl: null, whatsappInviteUrl: null, telegramInviteUrl: null, paymentValidDays: null,
    commissionRate: null, teachers: [{ id: "teacher-1", name: "Teacher One", primary: true }], tags: [],
    linkedCourses: [], displayOrder: null, priceDisplay: null,
  };
}

function session(overrides: Record<string, unknown> = {}) {
  return {
    id: LESSON_ID, moduleId: MODULE_ID, title: "Resource session", sequence: 1,
    startsAt: "2026-10-17T10:00:23.123Z", endsAt: "2026-10-17T11:30:45.678Z",
    resourceType: "VIDEO_LINK", paymentType: "FREE", price: null, description: null,
    lessonLabel: null, billingYear: null, billingMonth: null, expired: false,
    createdAt: "2026-10-17T00:00:00Z", updatedAt: "2026-10-17T00:00:00Z", ...overrides,
  };
}

const file = (id: string, title: string, kind: "DOCUMENT" | "IMAGE" = "DOCUMENT") => ({
  id, kind, title, originalFilename: `${title}.pdf`, contentType: "application/pdf", sizeBytes: 1234,
  uploadedBy: "teacher-1", uploaderName: "Teacher One", createdAt: "2026-10-17T00:00:00Z",
  attachedLessonCount: 0, thumbnailUrl: null, canEdit: true, canDelete: true,
});
const video = (id: string, title: string) => ({
  id, provider: "INTERNAL", title, description: null, thumbnailUrl: null, durationSeconds: 60,
  privacyStatus: "PRIVATE", processingStatus: "READY", failureReason: null, uploadedBy: "teacher-1",
  createdAt: "2026-10-17T00:00:00Z", capabilities: {}, enabledPlaybackModes: [], uploadProgress: null,
  attached: false, youtubeUrl: null, integrationId: null, attachedLessonCount: 0,
});

async function fulfill(route: Route, data: unknown, status = 200) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(apiSuccess(data)) });
}

async function setup(page: Page, options: { failVideoOnce?: boolean } = {}) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
  const requests: Array<{ method: string; path: string; body: unknown }> = [];
  const materials: Record<string, unknown>[] = [];
  const videos: Record<string, unknown>[] = [];
  let failVideoOnce = Boolean(options.failVideoOnce);

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const method = request.method();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path.endsWith("/api/v1/auth/refresh")) return route.fallback();
    if (path.endsWith("/api/v1/tenant-config/GENERAL")) return fulfill(route, []);
    if (path.endsWith("/api/v1/courses/lesson-resource-types")) return fulfill(route, catalog);
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}`)) return fulfill(route, course());
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules`)) return fulfill(route, [{ id: MODULE_ID, courseId: COURSE_ID, title: "Module", sequence: 1, price: null }]);
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons`)) {
      if (method === "GET") return fulfill(route, [session()]);
      return fulfill(route, session(request.postDataJSON() as Record<string, unknown>), 201);
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}`) && method === "PATCH") {
      requests.push({ method, path, body: request.postDataJSON() });
      return fulfill(route, session(request.postDataJSON() as Record<string, unknown>));
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`)) {
      if (method === "GET") return fulfill(route, materials);
      const body = await request.postDataBuffer();
      requests.push({ method, path, body: body?.toString() ?? "" });
      const text = body?.toString() ?? "";
      const item = text.includes("doc-2") ? { id: "mat-2", mediaFileId: "doc-2", title: "Document two", materialType: "DOCUMENT" }
        : text.includes("doc-1") ? { id: "mat-1", mediaFileId: "doc-1", title: "Document one", materialType: "DOCUMENT" }
          : { id: "mat-link", mediaFileId: null, title: "External", materialType: "LINK", externalUrl: "https://example.test/video" };
      materials.push(item);
      return fulfill(route, item, 201);
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/lessons/${LESSON_ID}/media`)) {
      if (method === "GET") return fulfill(route, videos);
      requests.push({ method, path, body: request.postDataJSON() });
      if (failVideoOnce) {
        failVideoOnce = false;
        await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify(apiError("INTERNAL_ERROR", "Video attach failed.")) });
        return;
      }
      const body = request.postDataJSON() as { mediaAssetId: string };
      const item = { id: `lm-${body.mediaAssetId}`, lessonId: LESSON_ID, courseId: COURSE_ID, mediaAssetId: body.mediaAssetId, title: body.mediaAssetId, sequence: videos.length + 1, visibility: "VISIBLE", provider: "INTERNAL", durationSeconds: 60, status: "READY", createdAt: "2026-10-17T00:00:00Z", updatedAt: "2026-10-17T00:00:00Z" };
      videos.push(item);
      return fulfill(route, item, 201);
    }
    if (path.endsWith("/api/v1/media-files")) {
      const kind = url.searchParams.get("kind");
      const pageNumber = Number(url.searchParams.get("page") ?? 0);
      requests.push({ method, path: `${path}?${url.searchParams}`, body: null });
      const content = kind === "DOCUMENT" ? [file(pageNumber ? "doc-2" : "doc-1", pageNumber ? "Document two" : "Document one")] : [file("image-1", "Image one", "IMAGE")];
      return fulfill(route, { content, page: pageNumber, size: 20, totalElements: 21, totalPages: 2 });
    }
    if (path.endsWith("/api/v1/media")) {
      const pageNumber = Number(url.searchParams.get("page") ?? 0);
      requests.push({ method, path: `${path}?${url.searchParams}`, body: null });
      return fulfill(route, { content: [video(pageNumber ? "video-2" : "video-1", pageNumber ? "Video two" : "Video one")], page: pageNumber, size: 20, totalElements: 21, totalPages: 2 });
    }
    await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify(apiError("NOT_FOUND", `${method} ${path}`)) });
  });
  return { requests, materials, videos };
}

async function openEdit(page: Page) {
  await page.goto(`/tenant-admin/courses/${COURSE_ID}/modules/${MODULE_ID}/sessions`);
  await page.getByTestId("session-row").getByTestId("session-action-edit").click();
  return page.getByTestId("session-form-dialog");
}

test("resource type opens filtered paginated document/video libraries and supports mixed drafts", async ({ page }) => {
  const state = await setup(page);
  const dialog = await openEdit(page);

  await dialog.getByTestId("session-form-resource-type").selectOption("DOCUMENT");
  await expect(dialog.getByRole("heading", { name: "Session resources" })).toHaveCount(0);
  await expect(dialog.getByRole("button", { name: "Add document" })).toHaveCount(0);
  const documentPicker = page.getByTestId("library-file-picker");
  await expect(documentPicker.getByTestId("library-file-picker-type-locked")).toHaveText("Documents");
  await documentPicker.getByRole("button", { name: "Select Document one" }).click();
  await dialog.getByRole("button", { name: "Change / add" }).click();
  await page.getByRole("navigation", { name: "Library file pages" }).getByRole("button", { name: "Next" }).click();
  await page.getByRole("button", { name: "Select Document two" }).click();

  await dialog.getByTestId("session-form-resource-type").selectOption("SECURE_VIDEO");
  await page.getByRole("navigation", { name: "Ready video pages" }).getByRole("button", { name: "Next" }).click();
  await page.getByRole("button", { name: "Select" }).click();
  await expect(dialog.getByRole("list", { name: "Pending resources" })).toContainText("Document one");
  await expect(dialog.getByRole("list", { name: "Pending resources" })).toContainText("Document two");
  await expect(dialog.getByRole("list", { name: "Pending resources" })).toContainText("Video two");

  await dialog.getByTestId("session-form-submit").click();
  await expect(dialog).toBeHidden();
  expect(state.materials).toHaveLength(2);
  expect(state.videos).toHaveLength(1);
  expect(state.requests.some((item) => item.path.includes("kind=DOCUMENT") && item.path.includes("page=1"))).toBe(true);
  expect(state.requests.some((item) => item.path.includes("status=READY") && item.path.includes("page=1"))).toBe(true);
});

test("Image resource opens a centered image-only Media Library popup", async ({ page }) => {
  const state = await setup(page);
  const dialog = await openEdit(page);
  const resourceType = dialog.getByTestId("session-form-resource-type");
  await expect(resourceType.locator("option", { hasText: "Image" })).toHaveCount(1);
  await expect(resourceType.locator("option", { hasText: "Player Video" })).toHaveCount(1);
  await resourceType.selectOption("IMAGE");
  const picker = page.getByTestId("library-file-picker");
  await expect(picker).toBeVisible();
  await expect(picker.getByTestId("library-file-picker-type-locked")).toHaveText("Images");
  await expect(picker).toHaveCSS("position", "fixed");
  const bounds = await picker.boundingBox();
  const viewport = page.viewportSize()!;
  expect(bounds).not.toBeNull();
  expect(Math.abs(bounds!.x + bounds!.width / 2 - viewport.width / 2)).toBeLessThan(3);
  expect(bounds!.width).toBeGreaterThan(600);
  expect(state.requests.some((item) => item.path.includes("kind=IMAGE"))).toBe(true);
  // Draft (select) mode keeps the library's upload, count and Added date.
  await expect(picker.getByTestId("library-file-picker-new")).toBeVisible();
  await expect(picker.getByTestId("library-file-picker-count")).toHaveText("21 files");
  await expect(picker.getByTestId("library-file-picker-item").first()).toContainText("Teacher One");
  await expect(picker.getByTestId("library-file-picker-item").first().getByTestId("media-added-date")).toHaveText(/^Added Oct 1[67], 2026/);
  await picker.getByRole("button", { name: "Select Image one" }).click();
  await expect(dialog.getByRole("list", { name: "Pending resources" })).toContainText("Image one");
  await expect(dialog.getByTestId("session-form-billing-month")).toHaveCount(0);
  await dialog.getByTestId("session-form-toggle-advanced").click();
  await expect(dialog.getByLabel("Session", { exact: true })).toBeVisible();
});

test("link fields validate https and Zoom surfaces stay honestly gated", async ({ page }) => {
  await setup(page);
  const dialog = await openEdit(page);

  await dialog.getByTestId("session-form-resource-type").selectOption("GOOGLE_DRIVE_LINK");
  await dialog.getByLabel("Link title").fill("Drive notes");
  await dialog.getByLabel("Google Drive link").fill("javascript:alert(1)");
  await dialog.getByRole("button", { name: "Add link to session" }).click();
  await expect(dialog.getByText("Enter a full https:// link without credentials.")).toBeVisible();
  await dialog.getByLabel("Google Drive link").fill("https://drive.example.test/file");
  await dialog.getByRole("button", { name: "Add link to session" }).click();
  await expect(dialog.getByRole("list", { name: "Pending resources" })).toContainText("Drive notes");

  await dialog.getByTestId("session-form-resource-type").selectOption("ZOOM_MEETING");
  const zoom = dialog.getByTestId("session-zoom-unavailable");
  await expect(zoom.getByLabel("Zoom Integration Name")).toBeDisabled();
  await expect(zoom.getByText("Mark Attendance When Joining")).toBeVisible();
  await expect(zoom.getByText("Require Registration")).toBeVisible();

  await dialog.getByTestId("session-form-resource-type").selectOption("ZOOM_RECORDING");
  await expect(dialog.getByTestId("zoom-recording-library-unavailable")).toBeVisible();
  await dialog.getByTestId("session-form-submit").click();
  await expect(dialog.getByText("Zoom recording import is not available yet.", { exact: false })).toBeVisible();
});

test("existing availability can be cleared", async ({ page }) => {
  const state = await setup(page);
  const dialog = await openEdit(page);
  await dialog.getByTestId("session-form-from-date").fill("");
  await dialog.getByTestId("session-form-from-time").fill("");
  await dialog.getByTestId("session-form-to-date").fill("");
  await dialog.getByTestId("session-form-to-time").fill("");
  await dialog.getByTestId("session-form-submit").click();
  await expect(dialog).toBeHidden();
  const body = state.requests.find((item) => item.method === "PATCH")?.body as Record<string, unknown>;
  expect(body.startsAt).toBeNull();
  expect(body.endsAt).toBeNull();
});

test("partial attachment failure retries outstanding resources without resaving metadata", async ({ page }) => {
  const state = await setup(page, { failVideoOnce: true });
  const dialog = await openEdit(page);
  await dialog.getByTestId("session-form-resource-type").selectOption("DOCUMENT");
  await page.getByRole("button", { name: "Select Document one" }).click();
  await dialog.getByTestId("session-form-resource-type").selectOption("SECURE_VIDEO");
  await page.getByRole("button", { name: "Select" }).click();

  await dialog.getByTestId("session-form-submit").click();
  await expect(dialog.getByTestId("session-form-error")).toContainText("Session saved, but some resources were not added");
  expect(state.materials).toHaveLength(1);
  expect(state.videos).toHaveLength(0);
  const metadataWritesAfterFirst = state.requests.filter((item) => item.method === "PATCH").length;

  await dialog.getByTestId("session-form-submit").click();
  await expect(dialog).toBeHidden();
  expect(state.materials).toHaveLength(1);
  expect(state.videos).toHaveLength(1);
  expect(state.requests.filter((item) => item.method === "PATCH")).toHaveLength(metadataWritesAfterFirst);
});
