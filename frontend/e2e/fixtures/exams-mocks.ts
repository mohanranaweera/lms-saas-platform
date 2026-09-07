import type { Page } from "@playwright/test";
import { apiSuccess, mockJson, fakeJwt, refreshResponseBody } from "./auth-mocks";

/**
 * Shared builders/helpers for MVP-017 "Exams" specs
 * (`exams-authoring-keyboard.spec.ts`, `exams-student-taking.spec.ts`,
 * `exams-marking-queue.spec.ts`, `exams-results-and-list.spec.ts`,
 * `exams-publish-and-oversight.spec.ts`). Mirrors `attendance.spec.ts`'s
 * per-endpoint `mockJson`/`page.route` mocking granularity (not
 * `materials-mocks.ts`'s single stateful dispatcher) since exam-management's
 * endpoints are mostly independent per screen — see `lib/api/exams.ts` for
 * the exact request/response shapes every builder below mirrors field-for-
 * field.
 *
 * No real backend runs in this environment (see `auth-mocks.ts`'s module
 * doc) — every scenario mocks `/v1/**` responses shaped like the documented
 * `ApiResponse<T>` envelope.
 */

export function nowIso(): string {
  return new Date().toISOString();
}

/** `minutes` may be negative (past) or positive (future), relative to now. */
export function isoOffsetMinutes(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
export async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

export async function selectOption(page: Page, labelName: string, optionName: string): Promise<void> {
  await page.getByLabel(labelName, { exact: true }).click();
  await page.getByRole("option", { name: optionName }).click();
}

/** Mirrors `course-management`'s `CourseResponse` — same shape `attendance.spec.ts` uses for `GET /v1/courses`. */
export function courseResponseBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    teacherId: "teacher-1",
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
    createdAt: nowIso(),
    updatedAt: nowIso(),
    ...overrides,
  };
}

/** Mirrors `enrollment-management`'s `CourseSummaryResponse` — `GET /v1/enrollments/my/courses`'s row shape. */
export function courseSummaryResponseBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    name: "Intro to Biology",
    slug: "intro-to-biology",
    category: "Science",
    ...overrides,
  };
}

/** Mirrors `ExamQuestionOptionResponse` — never carries `isCorrect` (see `lib/api/exams.ts`). */
export function examQuestionOptionBody(overrides: { id: string; optionText: string }) {
  return { id: overrides.id, optionText: overrides.optionText };
}

/** Mirrors `ExamQuestionResponse` field-for-field. */
export function examQuestionResponseBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "q0000000-0000-0000-0000-000000000001",
    courseId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    questionType: "STRUCTURED",
    body: "Explain photosynthesis.",
    options: [],
    ...overrides,
  };
}

/** Mirrors `ExamResponse` field-for-field. */
export function examResponseBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "e0000000-0000-0000-0000-000000000001",
    courseId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    title: "Midterm Exam",
    scheduledStart: isoOffsetMinutes(-60),
    scheduledEnd: isoOffsetMinutes(60),
    timeLimitMinutes: 60,
    status: "DRAFT",
    resultsPublishedAt: null,
    questions: [],
    ...overrides,
  };
}

/** Mirrors `ExamSummaryResponse` — `GET /my/upcoming`'s row shape. */
export function examSummaryResponseBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "e0000000-0000-0000-0000-000000000001",
    courseId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    title: "Midterm Exam",
    status: "SCHEDULED",
    scheduledStart: isoOffsetMinutes(60),
    scheduledEnd: isoOffsetMinutes(120),
    ...overrides,
  };
}

/** Mirrors `ExamAttemptResponse` field-for-field. */
export function examAttemptResponseBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "att00000-0000-0000-0000-000000000001",
    examId: "e0000000-0000-0000-0000-000000000001",
    studentId: "student-1",
    startedAt: nowIso(),
    submittedAt: null,
    status: "IN_PROGRESS",
    ...overrides,
  };
}

/** Mirrors `SavedAnswerResponse` field-for-field — `GET /attempts/{attemptId}/answers`'s row shape. */
export function savedAnswerResponseBody(overrides: { questionId: string; response: string | null }) {
  return { questionId: overrides.questionId, response: overrides.response };
}

/** Mirrors `MarkingQueueEntryResponse` field-for-field. */
export function markingQueueEntryBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    answerId: "ans00000-0000-0000-0000-000000000001",
    examId: "e0000000-0000-0000-0000-000000000001",
    attemptId: "att00000-0000-0000-0000-000000000001",
    questionId: "q0000000-0000-0000-0000-000000000001",
    questionBody: "Explain photosynthesis.",
    response: "Plants convert sunlight into chemical energy.",
    ...overrides,
  };
}
