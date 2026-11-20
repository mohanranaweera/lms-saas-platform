import type { Page, Request, Route } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson } from "./auth-mocks";
import { mockTenantSession } from "./exams-mocks";

/**
 * Stateful route mocks for the Klass Exam Module Slice C student endpoints
 * (`docs/api/exam-management-v2.md` "Slice C"). One dispatcher handles every
 * `/v1/**` call so reads reflect earlier writes (start → save → reload →
 * submit). The mock's own clock (`now`) decides lazy finalization, like the
 * server; tests move it with `setNow`.
 *
 * Paper items carry `correct`/`explanation` internally (the frozen paper) but
 * the attempt payload never includes them, mirroring the contract.
 */

export const SERVER_NOW = "2026-10-30T08:30:00Z";
export const EXAM_OPEN = "f0000000-0000-0000-0000-000000000001";
export const EXAM_RESUME = "f0000000-0000-0000-0000-000000000002";
export const EXAM_SUBMITTED = "f0000000-0000-0000-0000-000000000003";
export const EXAM_UPCOMING = "f0000000-0000-0000-0000-000000000004";
export const EXAM_PREVIOUS = "f0000000-0000-0000-0000-000000000005";
export const EXAM_PREVIOUS_HIDDEN = "f0000000-0000-0000-0000-000000000006";
export const EXAM_WRITTEN = "f0000000-0000-0000-0000-000000000007";
export const PASSWORD = "secret42";

type AttemptStatus = "IN_PROGRESS" | "SUBMITTED" | "EXPIRED";

export interface PaperOption {
  id: string;
  html: string;
  correct: boolean;
}

export interface PaperItem {
  id: string;
  bodyHtml: string;
  marks: number;
  explanationHtml: string | null;
  options: PaperOption[];
}

export interface MockStudentExam {
  id: string;
  type: "MCQ" | "WRITTEN" | "PHYSICAL";
  name: string;
  courseName: string;
  startAt: string;
  endAt: string;
  durationMinutes: number | null;
  cutoffMarks: number | null;
  showAnswersWithResults: boolean;
  requiresPassword: boolean;
  paper: PaperItem[];
  /** Per-attempt persisted order (item ids, option ids per item) — "shuffled once at start". */
  itemOrder?: string[];
  optionOrder?: Record<string, string[]>;
}

export interface MockAttempt {
  id: string;
  examId: string;
  status: AttemptStatus;
  startedAt: string;
  deadlineAt: string;
  selections: Record<string, { optionId: string | null; revision: number }>;
}

interface RecordedRequest {
  method: string;
  path: string;
  body: unknown;
}

export interface StudentExamMockOptions {
  exams?: MockStudentExam[];
  attempts?: MockAttempt[];
  /** Abort the next N item saves with a network error. */
  failSaves?: number;
  /** Answer the next item save with `stale: true` without storing it. */
  staleNextSave?: boolean;
  /** Answer the next item save with 409 FINALIZED. */
  finalizedOnSave?: boolean;
  /** Start answers 409 with this code. */
  startConflict?: "NOT_OPEN" | "CLOSED" | "ALREADY_ATTEMPTED";
  /** Wrong passwords after which the start endpoint answers 429. */
  rateLimitAfter?: number;
}

export interface StudentExamMock {
  state: { exams: MockStudentExam[]; attempts: MockAttempt[]; now: string };
  requests: RecordedRequest[];
  find: (method: string, pathPart: string) => RecordedRequest[];
  setNow: (iso: string) => void;
  setFailSaves: (count: number) => void;
  setStaleNextSave: () => void;
  setFinalizedOnSave: () => void;
}

function minutes(iso: string, delta: number): string {
  return new Date(new Date(iso).getTime() + delta * 60_000).toISOString();
}

export function samplePaper(prefix = "i"): PaperItem[] {
  return [
    {
      id: `${prefix}-1`,
      bodyHtml: "<p>Which organelle produces ATP?</p>",
      marks: 1,
      explanationHtml: "<p>Mitochondria run cellular respiration.</p>",
      options: [
        { id: `${prefix}-1-a`, html: "<p>Mitochondrion</p>", correct: true },
        { id: `${prefix}-1-b`, html: "<p>Ribosome</p>", correct: false },
        { id: `${prefix}-1-c`, html: "<p>Golgi body</p>", correct: false },
      ],
    },
    {
      id: `${prefix}-2`,
      bodyHtml: '<p>Solve <span data-math="inline" data-latex="x^2 = 9"></span> for positive x.</p>',
      marks: 2,
      explanationHtml: "<p>The positive square root of 9 is 3.</p>",
      options: [
        { id: `${prefix}-2-a`, html: "<p>2</p>", correct: false },
        { id: `${prefix}-2-b`, html: "<p>3</p>", correct: true },
        { id: `${prefix}-2-c`, html: "<p>9</p>", correct: false },
        { id: `${prefix}-2-d`, html: "<p>81</p>", correct: false },
      ],
    },
    {
      id: `${prefix}-3`,
      bodyHtml: "<p>DNA is made of which building blocks?</p>",
      marks: 2,
      explanationHtml: null,
      options: [
        { id: `${prefix}-3-a`, html: "<p>Amino acids</p>", correct: false },
        { id: `${prefix}-3-b`, html: "<p>Nucleotides</p>", correct: true },
      ],
    },
  ];
}

function exam(overrides: Partial<MockStudentExam> & Pick<MockStudentExam, "id" | "name" | "startAt" | "endAt">): MockStudentExam {
  return {
    type: "MCQ",
    courseName: "Biology 2027",
    durationMinutes: 30,
    cutoffMarks: 3,
    showAnswersWithResults: true,
    requiresPassword: true,
    paper: samplePaper(overrides.id.slice(-1)),
    ...overrides,
  };
}

/** A shuffled, persisted order: items 3,1,2; options reversed for item 1. */
export function shuffledOrder(paper: PaperItem[]) {
  const [first, second, third] = paper;
  return {
    itemOrder: [third.id, first.id, second.id],
    optionOrder: { [first.id]: [...first.options].reverse().map((option) => option.id) },
  };
}

export function sampleStudentExams(): MockStudentExam[] {
  const open = exam({
    id: EXAM_OPEN,
    name: "Cell Biology Quiz",
    startAt: minutes(SERVER_NOW, -30),
    endAt: minutes(SERVER_NOW, 90),
  });
  Object.assign(open, shuffledOrder(open.paper));
  return [
    open,
    exam({ id: EXAM_RESUME, name: "Genetics Sprint", startAt: minutes(SERVER_NOW, -20), endAt: minutes(SERVER_NOW, 60) }),
    exam({ id: EXAM_SUBMITTED, name: "Ecology Check", startAt: minutes(SERVER_NOW, -60), endAt: minutes(SERVER_NOW, 30) }),
    exam({
      id: EXAM_UPCOMING,
      name: "Unit Test 2",
      courseName: "Chemistry 2027",
      startAt: "2026-11-05T03:30:00Z",
      endAt: "2026-11-05T05:30:00Z",
    }),
    exam({ id: EXAM_PREVIOUS, name: "Term Revision MCQ", startAt: "2026-10-20T03:00:00Z", endAt: "2026-10-20T05:00:00Z" }),
    exam({
      id: EXAM_PREVIOUS_HIDDEN,
      name: "Weekly Quiz 5",
      startAt: "2026-10-22T03:00:00Z",
      endAt: "2026-10-22T05:00:00Z",
      showAnswersWithResults: false,
      cutoffMarks: null,
    }),
    exam({
      id: EXAM_WRITTEN,
      type: "WRITTEN",
      name: "Essay Paper",
      startAt: minutes(SERVER_NOW, -10),
      endAt: minutes(SERVER_NOW, 120),
      durationMinutes: 60,
      paper: [],
    }),
  ];
}

export function sampleAttempts(): MockAttempt[] {
  return [
    {
      id: "a-resume",
      examId: EXAM_RESUME,
      status: "IN_PROGRESS",
      startedAt: minutes(SERVER_NOW, -5),
      deadlineAt: minutes(SERVER_NOW, 25),
      selections: { "2-1": { optionId: "2-1-b", revision: 7 } },
    },
    {
      id: "a-submitted",
      examId: EXAM_SUBMITTED,
      status: "SUBMITTED",
      startedAt: minutes(SERVER_NOW, -50),
      deadlineAt: minutes(SERVER_NOW, -20),
      selections: { "3-1": { optionId: "3-1-a", revision: 1 } },
    },
    {
      id: "a-previous",
      examId: EXAM_PREVIOUS,
      status: "SUBMITTED",
      startedAt: "2026-10-20T03:05:00Z",
      deadlineAt: "2026-10-20T03:35:00Z",
      selections: {
        "5-1": { optionId: "5-1-a", revision: 1 },
        "5-2": { optionId: "5-2-c", revision: 2 },
      },
    },
    {
      id: "a-previous-hidden",
      examId: EXAM_PREVIOUS_HIDDEN,
      status: "EXPIRED",
      startedAt: "2026-10-22T03:05:00Z",
      deadlineAt: "2026-10-22T03:35:00Z",
      selections: { "6-3": { optionId: "6-3-b", revision: 1 } },
    },
  ];
}

function parseBody(request: Request): unknown {
  const raw = request.postData();
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

function at(iso: string): number {
  return new Date(iso).getTime();
}

/** Installs the student session + every Slice C student endpoint mock. Call before `page.goto`. */
export async function mockStudentExams(page: Page, options: StudentExamMockOptions = {}): Promise<StudentExamMock> {
  await mockTenantSession(page, "STUDENT");
  const state = {
    exams: options.exams ?? sampleStudentExams(),
    attempts: options.attempts ?? sampleAttempts(),
    now: SERVER_NOW,
  };
  const requests: RecordedRequest[] = [];
  let failSaves = options.failSaves ?? 0;
  let staleNext = options.staleNextSave ?? false;
  let finalizedNext = options.finalizedOnSave ?? false;
  let wrongPasswords = 0;
  let counter = 0;

  const statusOf = (entry: MockStudentExam) =>
    at(state.now) < at(entry.startAt) ? "UPCOMING" : at(state.now) < at(entry.endAt) ? "IN_PROGRESS" : "FINISHED";

  /** Lazy finalization at the deadline, like the server. */
  const finalize = (attempt: MockAttempt) => {
    if (attempt.status === "IN_PROGRESS" && at(state.now) >= at(attempt.deadlineAt)) attempt.status = "EXPIRED";
    return attempt;
  };
  const attemptFor = (examId: string) => {
    const attempt = state.attempts.find((entry) => entry.examId === examId);
    return attempt ? finalize(attempt) : undefined;
  };

  const examResponse = (entry: MockStudentExam) => {
    const attempt = attemptFor(entry.id);
    const finished = statusOf(entry) === "FINISHED";
    return {
      id: entry.id,
      type: entry.type,
      name: entry.name,
      courseName: entry.courseName,
      startAt: entry.startAt,
      endAt: entry.endAt,
      durationMinutes: entry.durationMinutes,
      questionCount: entry.type === "MCQ" ? entry.paper.length : null,
      totalMarks: entry.type === "MCQ" ? entry.paper.reduce((sum, item) => sum + item.marks, 0) : 100,
      status: statusOf(entry),
      requiresPassword: entry.requiresPassword,
      attempt: attempt ? { id: attempt.id, status: attempt.status, deadlineAt: attempt.deadlineAt } : null,
      resultAvailable: Boolean(attempt) && attempt?.status !== "IN_PROGRESS" && finished,
    };
  };

  const orderedItems = (entry: MockStudentExam) => {
    const order = entry.itemOrder ?? entry.paper.map((item) => item.id);
    return order.map((id) => entry.paper.find((item) => item.id === id)!);
  };

  const attemptResponse = (attempt: MockAttempt) => {
    const entry = state.exams.find((candidate) => candidate.id === attempt.examId)!;
    return {
      id: attempt.id,
      examId: attempt.examId,
      status: attempt.status,
      startedAt: attempt.startedAt,
      deadlineAt: attempt.deadlineAt,
      serverNow: state.now,
      items: orderedItems(entry).map((item, index) => {
        const optionOrder = entry.optionOrder?.[item.id] ?? item.options.map((option) => option.id);
        return {
          id: item.id,
          sequence: index + 1,
          bodyHtml: item.bodyHtml,
          marks: item.marks,
          // Never `correct` or explanations in the attempt payload.
          options: optionOrder.map((id) => {
            const option = item.options.find((candidate) => candidate.id === id)!;
            return { id: option.id, html: option.html };
          }),
          selectedOptionId: attempt.selections[item.id]?.optionId ?? null,
          revision: attempt.selections[item.id]?.revision ?? 0,
        };
      }),
    };
  };

  const resultResponse = (attempt: MockAttempt) => {
    const entry = state.exams.find((candidate) => candidate.id === attempt.examId)!;
    let score = 0;
    let answered = 0;
    const items = entry.paper.map((item, index) => {
      const selected = attempt.selections[item.id]?.optionId ?? null;
      const correct = item.options.find((option) => option.correct)!;
      if (selected) answered += 1;
      const awarded = selected === correct.id ? item.marks : 0;
      score += awarded;
      return {
        sequence: index + 1,
        bodyHtml: item.bodyHtml,
        marks: item.marks,
        awardedMarks: awarded,
        selectedOptionId: selected,
        correctOptionId: correct.id,
        explanationHtml: item.explanationHtml,
        options: item.options.map((option) => ({ id: option.id, html: option.html })),
      };
    });
    const total = entry.paper.reduce((sum, item) => sum + item.marks, 0);
    return {
      examId: entry.id,
      attemptId: attempt.id,
      score,
      totalMarks: total,
      cutoffMarks: entry.cutoffMarks,
      passed: entry.cutoffMarks === null ? null : score >= entry.cutoffMarks,
      answeredCount: answered,
      ...(entry.showAnswersWithResults ? { items } : {}),
    };
  };

  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    const method = request.method();
    if (path.startsWith("/v1/auth/")) return route.fallback();
    const body = parseBody(request);
    requests.push({ method, path: `${path}${url.search}`, body });

    if (method === "GET" && path === "/v1/student/exams") {
      const by = (status: string) => state.exams.filter((entry) => statusOf(entry) === status).map(examResponse);
      return fulfillJson(
        route,
        200,
        apiSuccess({ serverNow: state.now, inProgress: by("IN_PROGRESS"), upcoming: by("UPCOMING"), previous: by("FINISHED") })
      );
    }

    const examMatch = /^\/v1\/student\/exams\/([^/]+)$/.exec(path);
    if (method === "GET" && examMatch) {
      const entry = state.exams.find((candidate) => candidate.id === examMatch[1]);
      return entry
        ? fulfillJson(route, 200, apiSuccess(examResponse(entry)))
        : fulfillJson(route, 404, apiError("NOT_FOUND", "Exam not found."));
    }

    const startMatch = /^\/v1\/student\/exams\/([^/]+)\/attempts$/.exec(path);
    if (method === "POST" && startMatch) {
      const entry = state.exams.find((candidate) => candidate.id === startMatch[1]);
      if (!entry) return fulfillJson(route, 404, apiError("NOT_FOUND", "Exam not found."));
      if (options.startConflict) {
        return fulfillJson(route, 409, apiError(options.startConflict, `Exam ${options.startConflict.toLowerCase()}.`));
      }
      if (options.rateLimitAfter !== undefined && wrongPasswords >= options.rateLimitAfter) {
        return fulfillJson(route, 429, apiError("RATE_LIMITED", "Too many attempts."));
      }
      const password = (body as { password?: string | null } | null)?.password ?? null;
      if (entry.requiresPassword && password !== PASSWORD) {
        wrongPasswords += 1;
        return fulfillJson(route, 403, apiError("WRONG_PASSWORD", "Wrong exam password."));
      }
      const existing = attemptFor(entry.id);
      if (existing && existing.status !== "IN_PROGRESS") {
        return fulfillJson(route, 409, apiError("ALREADY_ATTEMPTED", "Already attempted."));
      }
      const attempt: MockAttempt = existing ?? {
        id: `a-new-${++counter}`,
        examId: entry.id,
        status: "IN_PROGRESS",
        startedAt: state.now,
        deadlineAt: new Date(Math.min(at(state.now) + (entry.durationMinutes ?? 0) * 60_000, at(entry.endAt))).toISOString(),
        selections: {},
      };
      if (!existing) state.attempts.push(attempt);
      return fulfillJson(route, 200, apiSuccess(attemptResponse(attempt)));
    }

    const attemptMatch = /^\/v1\/student\/attempts\/([^/]+)(\/.*)?$/.exec(path);
    if (attemptMatch) {
      const attempt = state.attempts.find((entry) => entry.id === attemptMatch[1]);
      if (!attempt) return fulfillJson(route, 404, apiError("NOT_FOUND", "Attempt not found."));
      finalize(attempt);
      const rest = attemptMatch[2] ?? "";
      if (method === "GET" && rest === "") return fulfillJson(route, 200, apiSuccess(attemptResponse(attempt)));
      const itemMatch = /^\/items\/([^/]+)$/.exec(rest);
      if (method === "PUT" && itemMatch) {
        if (failSaves > 0) {
          failSaves -= 1;
          return route.abort("internetdisconnected");
        }
        if (finalizedNext) {
          finalizedNext = false;
          attempt.status = "SUBMITTED";
          return fulfillJson(route, 409, apiError("FINALIZED", "This attempt is already submitted."));
        }
        if (attempt.status !== "IN_PROGRESS") return fulfillJson(route, 409, apiError("FINALIZED", "Finalized."));
        if (at(state.now) >= at(attempt.deadlineAt)) return fulfillJson(route, 409, apiError("DEADLINE_PASSED", "Deadline passed."));
        const { optionId, revision } = body as { optionId: string | null; revision: number };
        const stored = attempt.selections[itemMatch[1]];
        if (staleNext) {
          staleNext = false;
          return fulfillJson(route, 200, apiSuccess({ saved: false, stale: true, revision: (stored?.revision ?? 0) + 100 }));
        }
        if (stored && revision <= stored.revision) {
          return fulfillJson(route, 200, apiSuccess({ saved: false, stale: true, revision: stored.revision }));
        }
        attempt.selections[itemMatch[1]] = { optionId, revision };
        return fulfillJson(route, 200, apiSuccess({ saved: true, stale: false, revision }));
      }
      if (method === "POST" && rest === "/submit") {
        if (attempt.status === "IN_PROGRESS") attempt.status = "SUBMITTED";
        return fulfillJson(route, 200, apiSuccess(attemptResponse(attempt)));
      }
      if (method === "GET" && rest === "/result") {
        const entry = state.exams.find((candidate) => candidate.id === attempt.examId)!;
        if (at(state.now) < at(entry.endAt) || attempt.status === "IN_PROGRESS") {
          return fulfillJson(route, 409, apiError("NOT_RELEASED", "Results are not released yet."));
        }
        return fulfillJson(route, 200, apiSuccess(resultResponse(attempt)));
      }
    }

    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked."));
  });

  return {
    state,
    requests,
    find: (method, pathPart) => requests.filter((item) => item.method === method && item.path.includes(pathPart)),
    setNow: (iso) => {
      state.now = iso;
    },
    setFailSaves: (count) => {
      failSaves = count;
    },
    setStaleNextSave: () => {
      staleNext = true;
    },
    setFinalizedOnSave: () => {
      finalizedNext = true;
    },
  };
}

export interface EnrollmentMockOptions {
  candidates?: { studentId: string; indexNumber: string; firstName: string; lastName: string }[];
  alreadyEnrolledIds?: string[];
  syncAdded?: number;
}

/**
 * Staff enrollment endpoints. Register *after* `mockExamConfig` so these
 * routes take precedence over its catch-all.
 */
export async function mockExamEnrollment(page: Page, examId: string, options: EnrollmentMockOptions = {}) {
  const candidates = options.candidates ?? [
    { studentId: "s-1", indexNumber: "000123", firstName: "Nimal", lastName: "Perera" },
    { studentId: "s-2", indexNumber: "000124", firstName: "Kamala", lastName: "Silva" },
    { studentId: "s-3", indexNumber: "000125", firstName: "Ruwan", lastName: "Fernando" },
  ];
  const enrolled = new Set<string>(options.alreadyEnrolledIds ?? []);
  const requests: RecordedRequest[] = [];
  await page.route(`**/v1/exams/${examId}/enrollment**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    const body = parseBody(request);
    requests.push({ method: request.method(), path: `${path}${url.search}`, body });
    if (request.method() === "GET" && path.endsWith("/enrollment-candidates")) {
      const search = (url.searchParams.get("search") ?? "").toLowerCase();
      const list = candidates.filter(
        (candidate) =>
          !enrolled.has(candidate.studentId) &&
          (!search ||
            `${candidate.firstName} ${candidate.lastName} ${candidate.indexNumber}`.toLowerCase().includes(search))
      );
      return fulfillJson(route, 200, apiSuccess(list));
    }
    if (request.method() === "POST" && path.endsWith("/enrollments")) {
      const ids = (body as { studentIds: string[] }).studentIds;
      const already = ids.filter((id) => (options.alreadyEnrolledIds ?? []).includes(id)).length;
      ids.forEach((id) => enrolled.add(id));
      return fulfillJson(route, 200, apiSuccess({ added: ids.length - already, alreadyEnrolled: already }));
    }
    if (request.method() === "POST" && path.endsWith("/enrollments/sync-payments")) {
      return fulfillJson(route, 200, apiSuccess({ added: options.syncAdded ?? 0 }));
    }
    return route.fallback();
  });
  return { requests };
}
