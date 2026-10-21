import type { Page, Route } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson } from "../auth-mocks";
import { COURSE_ID, courseResponseBody } from "../class-session-mocks";

/**
 * Mocks for the Klass student course detail page (`/student/courses/[courseId]`,
 * KLASS-T5b). That page reads `GET /v1/courses/{id}`, `GET .../content`,
 * `GET .../live-classes` and opens lessons with `POST .../lessons/{id}/launch`
 * (plan §4.3). Class-session join/recording keep their own endpoints, so specs
 * combine this helper with `mockClassSessionsApi` from `class-session-mocks.ts`.
 */

export const MODULE_ID = "bbbbbbbb-0000-0000-0000-000000000001";

type AccessStatus =
  | "ALLOWED"
  | "NOT_ENROLLED"
  | "PAYMENT_REQUIRED"
  | "PAYMENT_PENDING"
  | "NOT_STARTED"
  | "EXPIRED"
  | "UNAVAILABLE"
  | "DEVICE_NOT_AUTHORIZED";

export interface LessonNodeMock {
  lessonId: string;
  courseId: string;
  moduleId: string;
  title: string;
  lessonLabel: string | null;
  resourceType: string | null;
  paymentType: "FREE" | "COVERED_BY_CLASS_FEES" | "PAID";
  price: string | null;
  startsAt: string | null;
  endsAt: string | null;
  viewed: boolean;
  access: {
    status: AccessStatus;
    availability: "NOT_STARTED" | "OPEN" | "ENDED" | "ALWAYS";
    payment: "FREE" | "COVERED" | "PENDING" | "REQUIRED";
    requiredPurchase: null | {
      scope: string;
      courseId: string;
      moduleId: string | null;
      lessonId: string | null;
      year: number | null;
      month: number | null;
    };
  };
}

export function isoInMinutes(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

export function lessonNode(overrides: Partial<LessonNodeMock> & { lessonId: string; title: string }): LessonNodeMock {
  return {
    courseId: COURSE_ID,
    moduleId: MODULE_ID,
    lessonLabel: null,
    resourceType: "DOCUMENT",
    paymentType: "COVERED_BY_CLASS_FEES",
    price: null,
    startsAt: null,
    endsAt: null,
    viewed: false,
    access: { status: "ALLOWED", availability: "ALWAYS", payment: "COVERED", requiredPurchase: null },
    ...overrides,
  };
}

/** A live (ZOOM_MEETING) lesson as the live-classes endpoint returns it. */
export function liveClassNode(
  overrides: Partial<LessonNodeMock> & { lessonId: string; title: string }
): LessonNodeMock & { moduleTitle: string } {
  return {
    ...lessonNode({
      resourceType: "ZOOM_MEETING",
      startsAt: isoInMinutes(-10),
      endsAt: isoInMinutes(80),
      access: { status: "ALLOWED", availability: "OPEN", payment: "COVERED", requiredPurchase: null },
      ...overrides,
    }),
    moduleTitle: "Live classes",
  };
}

/** One undated "Course content" period holding one module with the given lessons. */
export function contentWith(lessons: LessonNodeMock[], courseId = COURSE_ID) {
  return {
    courseId,
    viewedCount: lessons.filter((lesson) => lesson.viewed).length,
    totalCount: lessons.length,
    timezone: "Asia/Colombo",
    periods:
      lessons.length === 0
        ? []
        : [
            {
              key: null,
              year: null,
              month: null,
              label: "Course content",
              payment: { status: "NOT_REQUIRED", purchase: null },
              modules: [{ moduleId: MODULE_ID, title: "Module 1", sequence: 1, sourceCourseId: null, lessons }],
            },
          ],
  };
}

export interface LaunchResourceMock {
  kind: "MATERIAL" | "CLASS_SESSION";
  id: string;
  title: string;
  type: string;
}

export function launchAllowed(resources: LaunchResourceMock[]) {
  return {
    access: { status: "ALLOWED", availability: "OPEN", payment: "COVERED", requiredPurchase: null },
    resources,
    playerHandoffRequired: false,
    playerLinks: { androidUrl: null, iosUrl: null },
  };
}

export interface KlassStudentCourseMockOptions {
  courseId?: string;
  /** `null` -> the course read answers 404 (not visible / other tenant). */
  course?: Record<string, unknown> | null;
  content?: ReturnType<typeof contentWith>;
  /** Answer for `/content` and `/live-classes` instead of data (e.g. 403 NOT_ENROLLED). */
  contentError?: { status: number; code: string; message: string };
  liveClasses?: unknown[];
  /** Launch answers keyed by lesson id; a missing id answers 404 (UNAVAILABLE). */
  launches?: Record<string, { status: number; body: unknown }>;
}

/** Records every launch request so specs can assert the server was asked. */
export interface KlassStudentCourseMockHandle {
  launchedLessonIds: string[];
}

export async function mockKlassStudentCourse(
  page: Page,
  options: KlassStudentCourseMockOptions = {}
): Promise<KlassStudentCourseMockHandle> {
  const courseId = options.courseId ?? COURSE_ID;
  const handle: KlassStudentCourseMockHandle = { launchedLessonIds: [] };
  const course =
    options.course === undefined
      ? {
          ...courseResponseBody({ id: courseId }),
          coverImageUrl: null,
          priceDisplay: null,
          teachers: [{ id: "teacher-1", name: "Teacher One", primary: true }],
          whatsappInviteUrl: null,
          telegramInviteUrl: null,
          timezone: "Asia/Colombo",
          registrationEnabled: true,
        }
      : options.course;

  await page.route(`**/v1/courses/${courseId}**`, async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const tail = path.slice(path.indexOf(`/courses/${courseId}`) + `/courses/${courseId}`.length);
    const method = request.method();

    if (method === "GET" && tail === "") {
      if (course === null) return fulfillJson(route, 404, apiError("NOT_FOUND", "Course not found"));
      return fulfillJson(route, 200, apiSuccess(course));
    }
    if (course === null) return fulfillJson(route, 404, apiError("NOT_FOUND", "Course not found"));
    if (method === "GET" && (tail === "/content" || tail === "/live-classes") && options.contentError) {
      const error = options.contentError;
      return fulfillJson(route, error.status, apiError(error.code, error.message));
    }
    if (method === "GET" && tail === "/content") {
      return fulfillJson(route, 200, apiSuccess(options.content ?? contentWith([], courseId)));
    }
    if (method === "GET" && tail === "/live-classes") {
      return fulfillJson(route, 200, apiSuccess(options.liveClasses ?? []));
    }
    const launch = tail.match(/^\/lessons\/([^/]+)\/launch$/);
    if (method === "POST" && launch) {
      handle.launchedLessonIds.push(launch[1]);
      const answer = options.launches?.[launch[1]];
      if (!answer) return fulfillJson(route, 404, apiError("NOT_FOUND", "Lesson not found"));
      return fulfillJson(route, answer.status, answer.body);
    }
    // Exams and any other per-course read: an honest empty list.
    return fulfillJson(route, 200, apiSuccess([]));
  });
  return handle;
}
