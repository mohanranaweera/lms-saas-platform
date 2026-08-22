import type { Page, Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./auth-mocks";

/**
 * Shared stateful mocks for MVP-009 "Lessons and Learning Materials" specs
 * (`material-upload-states.spec.ts`, `material-reorder-keyboard.spec.ts`,
 * `material-student-visibility.spec.ts`).
 *
 * Follows `course-modules.spec.ts`'s established pattern exactly: a single
 * `page.route()` catch-all per role dispatches on method + parsed pathname
 * against an in-memory state object, so a GET issued after a prior
 * POST/PATCH/DELETE within the same test reflects that mutation — this is
 * what makes an upload-then-refetch or reorder-then-refetch flow actually
 * observable end-to-end instead of just asserting a request fired.
 *
 * No real backend runs in this environment (see `auth-mocks.ts`'s module
 * doc) — nothing here talks to Testcontainers/Postgres; it is a pure
 * fetch-interception fixture.
 */

export const COURSE_ID = "c0000000-0000-0000-0000-000000000001";
export const MODULE_ID = "module-1";
export const LESSON_ID = "lesson-1";
export const LESSON_TITLE = "What is a cell?";

export type MaterialVisibility = "VISIBLE" | "HIDDEN";

export interface MaterialRecord {
  id: string;
  lessonId: string;
  title: string;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  sequence: number;
  visibility: MaterialVisibility;
  expiryAt: string | null;
  uploadedBy: string;
  createdAt: string;
  updatedAt: string;
}

function nowIso(): string {
  return new Date().toISOString();
}

export function makeMaterial(
  overrides: Partial<MaterialRecord> & { id: string; title: string; sequence: number }
): MaterialRecord {
  return {
    lessonId: LESSON_ID,
    originalFilename: "material.pdf",
    mimeType: "application/pdf",
    sizeBytes: 1024,
    visibility: "VISIBLE",
    expiryAt: null,
    uploadedBy: "teacher-1",
    createdAt: nowIso(),
    updatedAt: nowIso(),
    ...overrides,
  };
}

function courseResponseBody() {
  return {
    id: COURSE_ID,
    teacherId: "teacher-1",
    name: "Introduction to Biology",
    slug: "intro-to-biology",
    category: "Science",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 1999,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "DRAFT",
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
}

function moduleResponseBody() {
  return {
    id: MODULE_ID,
    courseId: COURSE_ID,
    title: "Cell Biology",
    sequence: 1,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
}

function lessonResponseBody() {
  return {
    id: LESSON_ID,
    moduleId: MODULE_ID,
    title: LESSON_TITLE,
    sequence: 1,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
}

interface ParsedMultipart {
  fields: Record<string, string>;
  file?: { filename: string; contentType: string; content: string };
}

/**
 * Minimal `multipart/form-data` parser for the two-part body
 * (`file` + `title`) `useCreateMaterial` sends. Playwright's intercepted
 * `Route.request().postData()` returns the raw body as a utf-8 string, which
 * is lossless for the plain-text fixture file content every test below uses
 * (never real binary bytes), so a small manual parser is sufficient here
 * without pulling in a multipart-parsing dependency just for test mocks.
 */
function parseMultipart(contentType: string, body: string): ParsedMultipart {
  const boundaryMatch = contentType.match(/boundary=(.+)$/);
  const result: ParsedMultipart = { fields: {} };
  if (!boundaryMatch) return result;

  const boundary = `--${boundaryMatch[1]}`;
  const parts = body.split(boundary).slice(1, -1);

  for (const rawPart of parts) {
    const part = rawPart.replace(/^\r\n/, "").replace(/\r\n$/, "");
    const headerBodySplit = part.indexOf("\r\n\r\n");
    if (headerBodySplit === -1) continue;
    const headerBlock = part.slice(0, headerBodySplit);
    const content = part.slice(headerBodySplit + 4);
    const nameMatch = headerBlock.match(/name="([^"]+)"/);
    const filenameMatch = headerBlock.match(/filename="([^"]+)"/);
    const contentTypeMatch = headerBlock.match(/Content-Type:\s*(.+)/i);
    if (!nameMatch) continue;

    if (filenameMatch) {
      result.file = {
        filename: filenameMatch[1],
        contentType: contentTypeMatch ? contentTypeMatch[1].trim() : "application/octet-stream",
        content,
      };
    } else {
      result.fields[nameMatch[1]] = content;
    }
  }

  return result;
}

export interface UploadFailure {
  status: number;
  code: string;
  message: string;
}

export interface TeacherMaterialsMockState {
  materials: MaterialRecord[];
  patchCalls: Array<{ id: string; body: { title: string; sequence: number; visibility: MaterialVisibility } }>;
  createCalls: Array<{ title: string; filename: string | undefined }>;
}

export interface TeacherMaterialsMockOptions {
  /**
   * Consumed in order, one per POST call: the Nth upload attempt fails with
   * `uploadFailures[N]` until the queue is exhausted, after which uploads
   * succeed. Lets a test drive "first attempt fails with X, second attempt
   * fails with Y" without re-registering the route mid-test.
   */
  uploadFailures?: UploadFailure[];
}

/**
 * Registers the auth-refresh mock plus a single stateful mock for the
 * Teacher "Modules & lessons" page's full read chain (course -> its one
 * module -> that module's one lesson) and the materials CRUD endpoints
 * nested under that lesson. Returns the mutable state object so tests can
 * assert on captured PATCH/POST bodies.
 */
export async function setupTeacherMaterialsMocks(
  page: Page,
  initialMaterials: MaterialRecord[],
  options: TeacherMaterialsMockOptions = {}
): Promise<TeacherMaterialsMockState> {
  const token = fakeJwt({ role: "TEACHER" });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));

  const state: TeacherMaterialsMockState = {
    materials: [...initialMaterials],
    patchCalls: [],
    createCalls: [],
  };
  const uploadFailureQueue = [...(options.uploadFailures ?? [])];

  const materialsBase = `/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`;

  await page.route("**/api/v1/courses/**", async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname;

    // `NEXT_PUBLIC_API_BASE_URL` (`.env.local`) already ends in `/api`, and
    // every API client path here also starts with `/api/v1/...`, so the
    // real request pathname is `/api/api/v1/courses/...` — matched via
    // `endsWith`/a `$`-anchored regex below (never a leading-`^`/exact
    // match), mirroring `course-modules.spec.ts`'s own route matching.
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}`) && method === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(courseResponseBody())),
      });
      return;
    }

    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules`) && method === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess([moduleResponseBody()])),
      });
      return;
    }

    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons`) && method === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess([lessonResponseBody()])),
      });
      return;
    }

    const materialItemMatch = path.match(new RegExp(`${materialsBase}/([^/]+)/download-url$`));
    if (materialItemMatch && method === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            url: `https://cdn.example.test/signed/${materialItemMatch[1]}`,
            expiresAt: new Date(Date.now() + 60_000).toISOString(),
          })
        ),
      });
      return;
    }

    const materialByIdMatch = path.match(new RegExp(`${materialsBase}/([^/]+)$`));
    if (materialByIdMatch) {
      const materialId = materialByIdMatch[1];
      if (method === "PATCH") {
        const body = request.postDataJSON() as {
          title: string;
          sequence: number;
          visibility: MaterialVisibility;
        };
        state.patchCalls.push({ id: materialId, body });
        const target = state.materials.find((m) => m.id === materialId);
        if (target) {
          target.title = body.title;
          target.sequence = body.sequence;
          target.visibility = body.visibility;
          target.updatedAt = nowIso();
        }
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(target)),
        });
        return;
      }
      if (method === "DELETE") {
        state.materials = state.materials.filter((m) => m.id !== materialId);
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(null)),
        });
        return;
      }
    }

    if (path.endsWith(materialsBase)) {
      if (method === "GET") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(state.materials)),
        });
        return;
      }
      if (method === "POST") {
        const contentType = request.headers()["content-type"] ?? "";
        const raw = request.postData() ?? "";
        const { fields, file } = parseMultipart(contentType, raw);
        state.createCalls.push({ title: fields.title, filename: file?.filename });

        const failure = uploadFailureQueue.shift();
        if (failure) {
          await route.fulfill({
            status: failure.status,
            contentType: "application/json",
            body: JSON.stringify(apiError(failure.code, failure.message)),
          });
          return;
        }

        const maxSequence = state.materials.reduce((h, m) => Math.max(h, m.sequence), 0);
        const created = makeMaterial({
          id: `material-new-${state.materials.length + 1}`,
          title: fields.title ?? "",
          sequence: maxSequence + 1,
          originalFilename: file?.filename ?? "upload.pdf",
          mimeType: file?.contentType ?? "application/pdf",
          sizeBytes: file?.content.length ?? 0,
        });
        state.materials.push(created);
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(created)),
        });
        return;
      }
    }

    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify(apiError("NOT_FOUND", `Unhandled mock route: ${method} ${path}`)),
    });
  });

  return state;
}

export interface StudentMaterialsMockOptions {
  materialsStatus?: number;
  materialsBody?: unknown;
  downloadUrl?: { url: string; expiresAt: string };
  /**
   * When set to a non-200 status, `GET .../materials/{id}/download-url`
   * fails with this status instead of returning a signed URL — lets a test
   * drive the "View" action's anti-enumeration failure path (403 or 404)
   * without re-registering the route mid-test.
   */
  downloadUrlStatus?: number;
  /** Response body for the failing `download-url` response above; defaults to a generic `apiError`. */
  downloadUrlBody?: unknown;
}

export interface StudentMaterialsMockState {
  downloadUrlCallCount: number;
  downloadUrlRequestedIds: string[];
}

/**
 * Registers the auth-refresh mock plus mocks for the Student Lesson/Material
 * View's own two calls: `GET .../materials` (list, status/body fully
 * controlled by the caller so 200/403/404/500 can each be exercised) and
 * `GET .../materials/{id}/download-url` (the "View" action).
 */
export async function setupStudentMaterialsMocks(
  page: Page,
  options: StudentMaterialsMockOptions = {}
): Promise<StudentMaterialsMockState> {
  const token = fakeJwt({ role: "STUDENT" });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));

  const state: StudentMaterialsMockState = {
    downloadUrlCallCount: 0,
    downloadUrlRequestedIds: [],
  };

  const materialsBase = `/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`;
  const status = options.materialsStatus ?? 200;
  const body = options.materialsBody ?? apiSuccess([]);

  await page.route(`**${materialsBase}/*/download-url`, async (route: Route) => {
    const materialId = new URL(route.request().url()).pathname.split("/").slice(-2, -1)[0];
    state.downloadUrlCallCount += 1;
    state.downloadUrlRequestedIds.push(materialId);

    if (options.downloadUrlStatus && options.downloadUrlStatus !== 200) {
      await route.fulfill({
        status: options.downloadUrlStatus,
        contentType: "application/json",
        body: JSON.stringify(options.downloadUrlBody ?? apiError("NOT_FOUND", "Not found.")),
      });
      return;
    }

    const result = options.downloadUrl ?? {
      url: `https://cdn.example.test/signed/${materialId}`,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
    };
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess(result)),
    });
  });

  await page.route(`**${materialsBase}`, async (route: Route) => {
    await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
  });

  return state;
}

export function studentMaterialsUrl(): string {
  return `/student/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`;
}

export function teacherModulesUrl(): string {
  return `/teacher/courses/${COURSE_ID}/modules`;
}
