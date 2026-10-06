import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

const COURSE_ID = "course-lesson-media";
const MODULE_ID = "module-1";
const LESSON_ID = "lesson-1";
const MEDIA_BASE = `/api/v1/courses/${COURSE_ID}/lessons/${LESSON_ID}/media`;

function mediaAsset(overrides: Record<string, unknown> = {}) {
  return {
    id: "media-ready-1",
    provider: "INTERNAL",
    title: "Algebra lesson",
    description: null,
    thumbnailUrl: null,
    durationSeconds: 120,
    privacyStatus: null,
    processingStatus: "READY",
    failureReason: null,
    uploadedBy: "teacher-1",
    createdAt: "2026-10-06T08:00:00Z",
    capabilities: {},
    enabledPlaybackModes: [],
    uploadProgress: null,
    attached: false,
    attachedLessonCount: 0,
    youtubeUrl: null,
    integrationId: null,
    ...overrides,
  };
}

function lessonMedia(overrides: Record<string, unknown> = {}) {
  return {
    id: "lesson-media-1",
    lessonId: LESSON_ID,
    courseId: COURSE_ID,
    mediaAssetId: "media-ready-1",
    title: "Algebra lesson",
    sequence: 1,
    visibility: "VISIBLE",
    provider: "INTERNAL",
    durationSeconds: 120,
    status: "READY",
    createdAt: "2026-10-06T08:00:00Z",
    updatedAt: "2026-10-06T08:00:00Z",
    ...overrides,
  };
}

async function setupSession(page: Page, role = "TEACHER") {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

async function setupCourseEditorMocks(
  page: Page,
  options: {
    media?: unknown[] | { status: number; body: unknown };
    lesson?: unknown[] | { status: number; body: unknown };
    attach?: { status: number; body: unknown };
    detach?: { status: number; body: unknown };
  } = {}
) {
  const calls: Array<{ method: string; path: string; body: unknown }> = [];
  const lessonState = Array.isArray(options.lesson) ? [...options.lesson] : options.lesson ? [] : [lessonMedia()];
  await page.route("**/api/v1/courses/**", async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const raw = request.postData();
    calls.push({ method, path, body: raw ? JSON.parse(raw) : null });

    if (path.endsWith(`/api/v1/courses/${COURSE_ID}`) && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess({ id: COURSE_ID, name: "Algebra", status: "DRAFT", teacherId: "teacher-1" })) });
      return;
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules`) && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([{ id: MODULE_ID, courseId: COURSE_ID, title: "Module 1", sequence: 1 }])) });
      return;
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons`) && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([{ id: LESSON_ID, moduleId: MODULE_ID, title: "Lesson 1", sequence: 1 }])) });
      return;
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`) && method === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
      return;
    }
    if (path === MEDIA_BASE && method === "GET") {
      const response = options.lesson ?? lessonState;
      if (Array.isArray(response)) {
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(response)) });
      } else {
        await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
      }
      return;
    }
    if (path === MEDIA_BASE && method === "POST") {
      const response = options.attach ?? { status: 201, body: apiSuccess(lessonMedia()) };
      if (response.status >= 200 && response.status < 300) lessonState.push(lessonMedia());
      await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
      return;
    }
    if (path.startsWith(`${MEDIA_BASE}/`) && method === "PATCH") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(lessonMedia(request.postDataJSON() as Record<string, unknown>))) });
      return;
    }
    if (path.startsWith(`${MEDIA_BASE}/`) && method === "DELETE") {
      const response = options.detach ?? { status: 200, body: apiSuccess(null) };
      if (response.status >= 200 && response.status < 300) lessonState.splice(0, lessonState.length);
      await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
      return;
    }
    await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify(apiError("NOT_FOUND", "Not mocked")) });
  });

  await page.route(/\/api(?:\/api)?\/v1\/media\?/, async (route: Route) => {
    const response = options.media ?? [mediaAsset()];
    if (Array.isArray(response)) {
      const requestedStatus = new URL(route.request().url()).searchParams.get("status");
      const filtered = requestedStatus ? response.filter((asset) => (asset as { processingStatus?: string }).processingStatus === requestedStatus) : response;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess({ content: filtered, page: 0, size: 100, totalElements: filtered.length, totalPages: filtered.length ? 1 : 0 })) });
    } else {
      await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
    }
  });

  return calls;
}

function editorUrl() {
  return `/teacher/courses/${COURSE_ID}/modules`;
}

test.describe("Lesson Media integration", () => {
  test("loads ready media, attaches it, and shows the attached lesson row", async ({ page }) => {
    await setupSession(page);
    const calls = await setupCourseEditorMocks(page, { lesson: [], media: [mediaAsset(), mediaAsset({ id: "media-processing", title: "Processing", processingStatus: "PROCESSING" })] });

    await page.goto(editorUrl());
    await page.getByRole("button", { name: "Choose from Media Library" }).click();
    await expect(page.getByRole("heading", { name: "Choose lesson video" })).toBeVisible();
    await expect(page.getByText("Algebra lesson")).toBeVisible();
    await expect(page.getByText("Processing")).toHaveCount(0);

    await page.getByRole("button", { name: "Add" }).click();
    await expect(page.getByText("Media Library videos")).toBeVisible();
    await expect(page.locator('input[value="Algebra lesson"]')).toBeVisible();
    expect(calls.find((call) => call.method === "POST" && call.path === MEDIA_BASE)?.body).toEqual({ mediaAssetId: "media-ready-1" });
  });

  test("empty media shows actionable empty state", async ({ page }) => {
    await setupSession(page);
    await setupCourseEditorMocks(page, { lesson: [], media: [] });
    await page.goto(editorUrl());
    await page.getByRole("button", { name: "Choose from Media Library" }).click();
    await expect(page.getByText("No ready videos yet")).toBeVisible();
    await expect(page.getByText(/wait for it to become ready/)).toBeVisible();
  });

  test("a server 403 shows permission denied instead of an empty library", async ({ page }) => {
    await setupSession(page, "FINANCE_STAFF");
    await setupCourseEditorMocks(page, { lesson: [], media: { status: 403, body: apiError("FORBIDDEN", "Forbidden") } });
    await page.goto(editorUrl());
    await page.getByRole("button", { name: "Choose from Media Library" }).click();
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });

  test("removing an attached video uses the LessonMedia delete endpoint and explains preservation", async ({ page }) => {
    await setupSession(page);
    const calls = await setupCourseEditorMocks(page, { media: [] });
    await page.goto(editorUrl());
    await expect(page.getByText("Media Library videos")).toBeVisible();
    await page.getByRole("button", { name: "Remove Algebra lesson from lesson" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toContainText("remains in the Media Library");
    await dialog.getByRole("button", { name: "Remove from lesson" }).click();
    await expect.poll(() => calls.some((call) => call.method === "DELETE" && call.path.endsWith("/lesson-media-1"))).toBe(true);
  });
});
