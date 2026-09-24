import type { Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./auth-mocks";

/**
 * Shared stateful mocks for Wave 4 "Class Sessions + Zoom/Meeting
 * Integration" specs (`live-class-*.spec.ts`). No real backend runs in this
 * environment (see `auth-mocks.ts`'s module doc) — every scenario mocks
 * `/v1/**` responses shaped like the documented `ApiResponse<T>` envelope,
 * following the exact same posture already established by
 * `attendance.spec.ts`/`materials-mocks.ts`: a single stateful
 * `page.route()` handler dispatches on method + parsed pathname against an
 * in-memory array, so a GET issued after a prior POST/PATCH within the same
 * test reflects that mutation.
 *
 * Deliberately ONE `page.route("**​/v1/class-sessions**", ...)` registration
 * (not several narrower globs for `/start`, `/complete`, `/join`, etc.) —
 * Playwright evaluates multiple matching routes in reverse-registration
 * order via `route.fallback()` chaining, which is easy to get subtly wrong
 * when several of this resource's sub-action paths are prefixes of each
 * other (`/recording` vs a bare `/{id}` GET, for instance); one handler that
 * inspects the full pathname itself is simpler and unambiguous.
 */

export const COURSE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
export const OTHER_COURSE_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd";
export const TEACHER_ID = "teacher-1";
export const SESSION_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

export type ClassSessionStatus = "SCHEDULED" | "LIVE" | "COMPLETED" | "CANCELLED";
export type ProviderStatus = "PENDING" | "PROVISIONED" | "FAILED";

export interface ClassSessionRecord {
  id: string;
  courseId: string;
  teacherId: string;
  lessonId: string | null;
  title: string;
  description: string | null;
  scheduledStart: string;
  scheduledEnd: string;
  status: ClassSessionStatus;
  meetingProvider: "ZOOM";
  providerStatus: ProviderStatus;
  providerFailureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

function nowIso(): string {
  return new Date().toISOString();
}

function inFutureIso(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

/** Establishes a session for the given role/userId by mocking `POST /v1/auth/refresh`, mirroring `attendance.spec.ts`'s identical helper. */
export async function mockTenantSession(page: Page, role: string, userId = "user-1"): Promise<void> {
  const token = fakeJwt({ role, sub: userId });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

/** Stubs `window.open` so the app's `window.open(url, "_blank", "noopener,noreferrer")` calls (join/recording) are captured instead of actually opening a tab. Call before `page.goto`. */
export async function stubWindowOpen(page: Page): Promise<void> {
  await page.addInitScript(() => {
    (window as unknown as { __openedUrls: unknown[] }).__openedUrls = [];
    window.open = (...args: Parameters<typeof window.open>) => {
      (window as unknown as { __openedUrls: unknown[] }).__openedUrls.push(args);
      return null;
    };
  });
}

export async function getOpenedUrls(page: Page): Promise<unknown[]> {
  return page.evaluate(() => (window as unknown as { __openedUrls: unknown[] }).__openedUrls ?? []);
}

export function makeClassSession(overrides: Partial<ClassSessionRecord> & { id: string }): ClassSessionRecord {
  return {
    courseId: COURSE_ID,
    teacherId: TEACHER_ID,
    lessonId: null,
    title: "Live Q&A Session",
    description: null,
    scheduledStart: inFutureIso(60),
    scheduledEnd: inFutureIso(120),
    status: "SCHEDULED",
    meetingProvider: "ZOOM",
    providerStatus: "PROVISIONED",
    providerFailureReason: null,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    ...overrides,
  };
}

export function courseResponseBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: COURSE_ID,
    teacherId: TEACHER_ID,
    name: "Intro to Biology",
    slug: "intro-to-biology",
    category: "Science",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 49.99,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "PUBLIC",
    pricingModel: "ONE_TIME",
    archivedAt: null,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    resolvedAmount: 49.99,
    currency: "USD",
    requiresManualQuote: false,
    ...overrides,
  };
}

/**
 * Registers the single stateful class-sessions route (see module doc) over
 * `initialSessions` (mutated in place — a later assertion in the same test
 * can inspect `store` directly). Also mocks the `GET /v1/courses*` list (a
 * single course, `courseResponseBody()`) unless `mockCourses: false`, since
 * every live-class screen resolves a course name/ownership via that read.
 */
export async function mockClassSessionsApi(
  page: Page,
  options: {
    initialSessions?: ClassSessionRecord[];
    mockCourses?: boolean;
  } = {}
): Promise<{ store: ClassSessionRecord[] }> {
  const store: ClassSessionRecord[] = options.initialSessions ?? [];

  if (options.mockCourses ?? true) {
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
  }

  await page.route("**/v1/class-sessions**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    // Everything after ".../class-sessions" in the path, split into segments
    // — e.g. `/v1/class-sessions` -> [], `/v1/class-sessions/{id}` -> [id],
    // `/v1/class-sessions/{id}/start` -> [id, "start"].
    const afterIndex = url.pathname.indexOf("/class-sessions") + "/class-sessions".length;
    const tail = url.pathname
      .slice(afterIndex)
      .split("/")
      .filter(Boolean);

    // POST /v1/class-sessions — create.
    if (method === "POST" && tail.length === 0) {
      const body = request.postDataJSON() as {
        courseId: string;
        lessonId?: string;
        title: string;
        description?: string;
        scheduledStart: string;
        scheduledEnd: string;
      };
      const created = makeClassSession({
        id: `session-${store.length + 1}`,
        courseId: body.courseId,
        lessonId: body.lessonId ?? null,
        title: body.title,
        description: body.description ?? null,
        scheduledStart: body.scheduledStart,
        scheduledEnd: body.scheduledEnd,
        status: "SCHEDULED",
        providerStatus: "PROVISIONED",
      });
      store.push(created);
      await fulfillJson(route, 201, apiSuccess(created));
      return;
    }

    // GET /v1/class-sessions?courseId=&status=&... — list.
    if (method === "GET" && tail.length === 0) {
      const courseId = url.searchParams.get("courseId");
      const status = url.searchParams.get("status");
      const filtered = store.filter(
        (s) => (!courseId || s.courseId === courseId) && (!status || s.status === status)
      );
      await fulfillJson(route, 200, apiSuccess(filtered));
      return;
    }

    const id = tail[0];
    const record = store.find((s) => s.id === id);
    const action = tail[1];

    if (!record) {
      await fulfillJson(route, 404, apiError("NOT_FOUND", "Class session not found"));
      return;
    }

    // GET /v1/class-sessions/{id} — detail.
    if (method === "GET" && tail.length === 1) {
      await fulfillJson(route, 200, apiSuccess(record));
      return;
    }

    // PATCH /v1/class-sessions/{id} — update, legal only while SCHEDULED.
    if (method === "PATCH" && tail.length === 1) {
      if (record.status !== "SCHEDULED") {
        await fulfillJson(
          route,
          409,
          apiError("CONFLICT", "This action requires the class session to be currently SCHEDULED")
        );
        return;
      }
      const body = request.postDataJSON() as Partial<ClassSessionRecord>;
      Object.assign(record, body, { updatedAt: nowIso() });
      await fulfillJson(route, 200, apiSuccess(record));
      return;
    }

    if (method === "POST" && action === "retry-provisioning") {
      if (record.providerStatus !== "PROVISIONED") {
        record.providerStatus = "PROVISIONED";
        record.providerFailureReason = null;
        record.updatedAt = nowIso();
      }
      await fulfillJson(route, 200, apiSuccess(record));
      return;
    }

    if (method === "POST" && action === "start") {
      if (record.status !== "SCHEDULED") {
        await fulfillJson(
          route,
          409,
          apiError("CONFLICT", "This action requires the class session to be currently SCHEDULED")
        );
        return;
      }
      record.status = "LIVE";
      record.updatedAt = nowIso();
      await fulfillJson(route, 200, apiSuccess(record));
      return;
    }

    if (method === "POST" && action === "complete") {
      if (record.status !== "LIVE") {
        await fulfillJson(
          route,
          409,
          apiError("CONFLICT", "Cannot complete a class session that is not currently LIVE")
        );
        return;
      }
      record.status = "COMPLETED";
      record.updatedAt = nowIso();
      await fulfillJson(route, 200, apiSuccess(record));
      return;
    }

    if (method === "POST" && action === "cancel") {
      if (record.status === "COMPLETED" || record.status === "CANCELLED") {
        await fulfillJson(
          route,
          409,
          apiError("CONFLICT", `Cannot cancel a class session that is already ${record.status}`)
        );
        return;
      }
      record.status = "CANCELLED";
      record.updatedAt = nowIso();
      await fulfillJson(route, 200, apiSuccess(record));
      return;
    }

    if (method === "POST" && action === "join") {
      if (record.status !== "LIVE") {
        await fulfillJson(route, 409, apiError("CONFLICT", "This class session is not currently live"));
        return;
      }
      if (record.providerStatus !== "PROVISIONED") {
        await fulfillJson(
          route,
          409,
          apiError("CONFLICT", "This class session's meeting has not been successfully provisioned yet")
        );
        return;
      }
      await fulfillJson(
        route,
        200,
        apiSuccess({ joinUrl: `https://live-class-provider.test/join/${id}`, expiresAt: inFutureIso(5) })
      );
      return;
    }

    if (method === "GET" && action === "recording") {
      if (record.status !== "COMPLETED") {
        await fulfillJson(route, 409, apiError("CONFLICT", "This class session has not completed yet"));
        return;
      }
      await fulfillJson(
        route,
        200,
        apiSuccess({ playbackUrl: `https://live-class-provider.test/playback/${id}`, expiresAt: inFutureIso(5) })
      );
      return;
    }

    await route.fallback();
  });

  return { store };
}
