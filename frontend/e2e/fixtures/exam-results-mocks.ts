import type { Page, Request, Route } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson } from "./auth-mocks";
import { mockTenantSession } from "./exams-mocks";
import { EXAM_MCQ_LOCKED, EXAM_MCQ_UPCOMING, EXAM_PHYSICAL, EXAM_WRITTEN, SERVER_NOW } from "./exam-config-mocks";

/**
 * Mocks for the Klass Exam Module Slice E (`docs/api/exam-management-v2.md`
 * "Slice E" + "Slice E addendum"): Notify Marks preview/send with
 * Idempotency-Key replay, stats, staff leaderboards (CRUD + ranks) and the
 * student leaderboard endpoints. Register after `mockExamConfig` /
 * `mockExamManagement` (later routes win).
 */

interface RecordedRequest {
  method: string;
  path: string;
  body: unknown;
  idempotencyKey: string | null;
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

function recorder() {
  const requests: RecordedRequest[] = [];
  return {
    requests,
    record: (request: Request, path: string, search: string) =>
      requests.push({ method: request.method(), path: `${path}${search}`, body: parseBody(request), idempotencyKey: request.headers()["idempotency-key"] ?? null }),
    find: (method: string, pathPart: string) => requests.filter((item) => item.method === method && item.path.includes(pathPart)),
  };
}

// ---------------------------------------------------------------------------
// Notify Marks
// ---------------------------------------------------------------------------

export interface NotifyMockOptions {
  recipients?: number;
  withoutMarks?: number;
  alreadyNotifiedAt?: string | null;
  ended?: boolean;
  endAt?: string;
}

export interface NotifyMock {
  state: {
    recipients: number;
    withoutMarks: number;
    alreadyNotifiedAt: string | null;
    resultsPublishedAt: string | null;
    ended: boolean;
    dispatches: Map<string, Record<string, unknown>>;
    /** Next send commits, then answers 500 (lost response). */
    failNextAfterCommit: boolean;
    /** Next send answers this 409 code without committing. */
    nextConflict: "NOT_ENDED" | "ALREADY_NOTIFIED" | "NO_RECIPIENTS" | null;
  };
  requests: RecordedRequest[];
  find: (method: string, pathPart: string) => RecordedRequest[];
}

export const NOTIFY_SENT_AT = "2026-10-30T08:31:00Z";

export async function mockNotifyMarks(page: Page, examId: string, options: NotifyMockOptions = {}): Promise<NotifyMock> {
  const rec = recorder();
  const state: NotifyMock["state"] = {
    recipients: options.recipients ?? 3,
    withoutMarks: options.withoutMarks ?? 10,
    alreadyNotifiedAt: options.alreadyNotifiedAt ?? null,
    resultsPublishedAt: options.alreadyNotifiedAt ?? null,
    ended: options.ended ?? true,
    dispatches: new Map(),
    failNextAfterCommit: false,
    nextConflict: null,
  };
  const prefix = `/v1/exams/${examId}`;
  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    if (path !== `${prefix}/notify-marks/preview` && path !== `${prefix}/notify-marks`) return route.fallback();
    rec.record(request, path, url.search);
    if (request.method() === "GET") {
      return fulfillJson(
        route,
        200,
        apiSuccess({
          recipients: state.recipients,
          withoutMarks: state.withoutMarks,
          alreadyNotifiedAt: state.alreadyNotifiedAt,
          resultsPublishedAt: state.resultsPublishedAt,
          endAt: options.endAt ?? "2024-06-09T23:10:00Z",
          ended: state.ended,
        })
      );
    }
    const key = request.headers()["idempotency-key"];
    if (!key) return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", [{ field: "Idempotency-Key", message: "Required." }]));
    const stored = state.dispatches.get(key);
    if (stored) return fulfillJson(route, 200, apiSuccess({ ...stored, replayed: true }));
    if (state.nextConflict) {
      const code = state.nextConflict;
      state.nextConflict = null;
      if (code === "ALREADY_NOTIFIED") state.alreadyNotifiedAt = NOTIFY_SENT_AT;
      return fulfillJson(route, 409, apiError(code, code === "NOT_ENDED" ? "The exam has not ended." : code === "NO_RECIPIENTS" ? "No student has marks." : "Already notified."));
    }
    if (!state.ended) return fulfillJson(route, 409, apiError("NOT_ENDED", "The exam has not ended."));
    if (state.recipients === 0) return fulfillJson(route, 409, apiError("NO_RECIPIENTS", "No student has marks."));
    const body = parseBody(request) as { resend?: boolean } | null;
    if (state.alreadyNotifiedAt && !body?.resend) return fulfillJson(route, 409, apiError("ALREADY_NOTIFIED", "Already notified."));
    const response = {
      dispatchId: `dispatch-${state.dispatches.size + 1}`,
      recipients: state.recipients,
      withoutMarks: state.withoutMarks,
      resend: Boolean(body?.resend),
      sentAt: NOTIFY_SENT_AT,
      resultsPublishedAt: state.resultsPublishedAt ?? NOTIFY_SENT_AT,
      replayed: false,
    };
    state.dispatches.set(key, response);
    state.alreadyNotifiedAt = NOTIFY_SENT_AT;
    state.resultsPublishedAt = state.resultsPublishedAt ?? NOTIFY_SENT_AT;
    if (state.failNextAfterCommit) {
      state.failNextAfterCommit = false;
      return fulfillJson(route, 500, apiError("INTERNAL_ERROR", "Something went wrong."));
    }
    return fulfillJson(route, 200, apiSuccess(response));
  });
  return { state, requests: rec.requests, find: rec.find };
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

const option = (id: string, text: string, correct: boolean, selectedCount: number) => ({ id, html: `<p>${text}</p>`, correct, selectedCount });

export function mcqStats() {
  return {
    examId: EXAM_MCQ_LOCKED,
    type: "MCQ",
    population: { enrolled: 13, attended: 3, graded: 3 },
    averageMarks: 3.33,
    maxMarks: 5,
    minMarks: 1,
    passed: 2,
    passedOf: 3,
    cutoffMarks: 2,
    totalMarks: 5,
    questions: [
      { sequence: 1, bodyHtml: "<p>What is the powerhouse of the cell?</p>", marks: 1, correct: 3, incorrect: 0, unanswered: 0, correctPercent: 100, options: [option("o11", "Mitochondria", true, 3), option("o12", "Nucleus", false, 0), option("o13", "Ribosome", false, 0)] },
      { sequence: 2, bodyHtml: "<p>Which gas do plants absorb?</p>", marks: 1, correct: 1, incorrect: 2, unanswered: 0, correctPercent: 33.3, options: [option("o21", "Oxygen", false, 2), option("o22", "Carbon dioxide", true, 1)] },
      { sequence: 3, bodyHtml: "<p>Solve <span data-math=\"inline\" data-latex=\"x^2 = 4\"></span> for positive x.</p>", marks: 2, correct: 1, incorrect: 1, unanswered: 1, correctPercent: 33.3, options: [option("o31", "2", true, 1), option("o32", "4", false, 1)] },
      { sequence: 4, bodyHtml: "<p>DNA stands for?</p>", marks: 1, correct: 2, incorrect: 0, unanswered: 1, correctPercent: 66.7, options: [option("o41", "Deoxyribonucleic acid", true, 2), option("o42", "Dinitro acid", false, 0)] },
    ],
    frequentlyMissed: [2, 3, 4],
  };
}

export function emptyMcqStats() {
  const base = mcqStats();
  return {
    ...base,
    population: { enrolled: 13, attended: 0, graded: 0 },
    averageMarks: null,
    maxMarks: null,
    minMarks: null,
    passed: null,
    passedOf: 0,
    questions: base.questions.map((question) => ({
      ...question,
      correct: 0,
      incorrect: 0,
      unanswered: 0,
      correctPercent: null,
      options: question.options.map((entry) => ({ ...entry, selectedCount: 0 })),
    })),
    frequentlyMissed: [],
  };
}

export function writtenStatsNoCutoff() {
  return {
    examId: EXAM_WRITTEN,
    type: "WRITTEN",
    population: { enrolled: 3, attended: 2, graded: 1 },
    averageMarks: 72.5,
    maxMarks: 72.5,
    minMarks: 72.5,
    passed: null,
    passedOf: 2,
    cutoffMarks: null,
    totalMarks: 100,
    questions: [],
    frequentlyMissed: [],
  };
}

export async function mockExamStats(page: Page, examId: string, stats: unknown, status = 200) {
  await page.route(`**/v1/exams/${examId}/stats`, (route) =>
    status === 200 ? fulfillJson(route, 200, apiSuccess(stats)) : fulfillJson(route, status, apiError("FORBIDDEN", "You do not have permission to perform this action"))
  );
}

// ---------------------------------------------------------------------------
// Leaderboards
// ---------------------------------------------------------------------------

export const LB_TERM = "lb000000-0000-0000-0000-000000000001";
export const LB_EMPTY = "lb000000-0000-0000-0000-000000000002";

interface MockBoard {
  id: string;
  name: string;
  published: boolean;
  examIds: string[];
}

const EXAM_INFO: Record<string, { name: string; totalMarks: number; published: boolean }> = {
  [EXAM_MCQ_LOCKED]: { name: "Word Exam", totalMarks: 5, published: true },
  [EXAM_WRITTEN]: { name: "test", totalMarks: 100, published: true },
  [EXAM_MCQ_UPCOMING]: { name: "Unit Test 1", totalMarks: 4.5, published: false },
  [EXAM_PHYSICAL]: { name: "Term Test", totalMarks: 80, published: false },
};

/** 23 ranked students (2 pages of 20) with ties at 1 and 3, over the two published exams. */
function rankRows() {
  const names = ["Kamala Silva", "Nimal Perera", "Sunil Fernando", "Amaya Jayasuriya", "Ravi Bandara"];
  const totals = [100, 100, 90, 90, 85];
  const rows = [];
  for (let index = 0; index < 23; index += 1) {
    const total = index < totals.length ? totals[index] : 80 - index;
    const rank = index === 1 ? 1 : index === 3 ? 3 : index + 1;
    const mcq = Math.min(5, Math.round(total / 21));
    rows.push({
      rank,
      studentId: `s-${index + 1}`,
      indexNumber: String(index + 1).padStart(4, "0"),
      name: names[index] ?? `Student ${index + 1}`,
      totalMarks: total,
      maxPossible: 105,
      share: Math.round((total / 105) * 1000) / 10,
      perExam: [
        { examId: EXAM_MCQ_LOCKED, marks: mcq },
        { examId: EXAM_WRITTEN, marks: index === 4 ? null : total - mcq },
      ],
      self: false,
    });
  }
  return rows;
}

export interface LeaderboardMock {
  state: { boards: MockBoard[] };
  requests: RecordedRequest[];
  find: (method: string, pathPart: string) => RecordedRequest[];
}

export function sampleBoards(): MockBoard[] {
  return [
    { id: LB_TERM, name: "Term 1 ranking", published: true, examIds: [EXAM_MCQ_LOCKED, EXAM_WRITTEN, EXAM_MCQ_UPCOMING] },
    { id: LB_EMPTY, name: "November mocks", published: false, examIds: [EXAM_MCQ_UPCOMING, EXAM_PHYSICAL] },
  ];
}

function ranksPayload(board: MockBoard, url: URL, student: boolean) {
  const exams = board.examIds.map((id) => ({ id, ...EXAM_INFO[id] }));
  const hasPublishedMarks = exams.some((exam) => exam.published);
  const search = (url.searchParams.get("search") ?? "").trim().toLowerCase();
  const page = Number(url.searchParams.get("page") ?? "0");
  const size = Number(url.searchParams.get("size") ?? "20");
  let rows = hasPublishedMarks ? rankRows() : [];
  if (student) {
    rows = rows.map((row) => (row.studentId === "s-4" ? { ...row, self: true } : { ...row, studentId: null as unknown as string, indexNumber: null as unknown as string, perExam: [] }));
  }
  const filtered = rows.filter((row) => !search || row.name.toLowerCase().includes(search) || (row.indexNumber ?? "").includes(search));
  return {
    id: board.id,
    name: board.name,
    published: board.published,
    exams,
    rows: filtered.slice(page * size, page * size + size),
    page,
    size,
    totalElements: filtered.length,
    totalPages: Math.ceil(filtered.length / size),
    hasPublishedMarks,
  };
}

/** Staff leaderboard endpoints (stateful). Call after `mockExamConfig`. */
export async function mockLeaderboards(page: Page, options: { boards?: MockBoard[] } = {}): Promise<LeaderboardMock> {
  const rec = recorder();
  const state = { boards: options.boards ?? sampleBoards() };
  let counter = 10;
  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    if (!path.startsWith("/v1/exam-leaderboards")) return route.fallback();
    rec.record(request, path, url.search);
    const method = request.method();
    const summary = (board: MockBoard) => ({ id: board.id, name: board.name, examCount: board.examIds.length, published: board.published });
    const validate = (body: { name?: string; examIds?: string[] }) => {
      if (!body.name?.trim()) return [{ field: "name", message: "Name is required" }];
      if (!body.examIds || body.examIds.length < 2) return [{ field: "examIds", message: "Choose at least 2 exams" }];
      return null;
    };
    if (path === "/v1/exam-leaderboards") {
      if (method === "GET") return fulfillJson(route, 200, apiSuccess(state.boards.map(summary)));
      if (method === "POST") {
        const body = parseBody(request) as { name: string; examIds: string[]; published: boolean };
        const errors = validate(body);
        if (errors) return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", errors));
        const board = { id: `lb000000-0000-0000-0000-0000000000${++counter}`, name: body.name, published: body.published, examIds: body.examIds };
        state.boards.push(board);
        return fulfillJson(route, 201, apiSuccess({ ...board, exams: board.examIds.map((id) => ({ id, ...EXAM_INFO[id] })), createdAt: SERVER_NOW, updatedAt: SERVER_NOW }));
      }
    }
    const match = /^\/v1\/exam-leaderboards\/([^/]+)(\/ranks)?$/.exec(path);
    if (match) {
      const board = state.boards.find((entry) => entry.id === match[1]);
      if (!board) return fulfillJson(route, 404, apiError("NOT_FOUND", "Leaderboard not found."));
      if (match[2]) return fulfillJson(route, 200, apiSuccess(ranksPayload(board, url, false)));
      if (method === "GET") return fulfillJson(route, 200, apiSuccess({ ...board, exams: board.examIds.map((id) => ({ id, ...EXAM_INFO[id] })), createdAt: SERVER_NOW, updatedAt: SERVER_NOW }));
      if (method === "PUT") {
        const body = parseBody(request) as { name: string; examIds: string[]; published: boolean };
        const errors = validate(body);
        if (errors) return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", errors));
        Object.assign(board, { name: body.name, examIds: body.examIds, published: body.published });
        return fulfillJson(route, 200, apiSuccess({ ...board, createdAt: SERVER_NOW, updatedAt: SERVER_NOW }));
      }
      if (method === "DELETE") {
        state.boards = state.boards.filter((entry) => entry !== board);
        return route.fulfill({ status: 204, body: "" });
      }
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked."));
  });
  return { state, requests: rec.requests, find: rec.find };
}

/** Student session + `GET /student/leaderboards` and `{id}/ranks` (only published boards). */
export async function mockStudentLeaderboards(page: Page, options: { boards?: MockBoard[] } = {}): Promise<LeaderboardMock> {
  await mockTenantSession(page, "STUDENT");
  const rec = recorder();
  const state = { boards: options.boards ?? sampleBoards() };
  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    if (path.startsWith("/v1/auth/")) return route.fallback();
    rec.record(request, path, url.search);
    const visible = state.boards.filter((board) => board.published);
    if (path === "/v1/student/leaderboards") {
      return fulfillJson(route, 200, apiSuccess(visible.map((board) => ({ id: board.id, name: board.name, examCount: board.examIds.length }))));
    }
    const match = /^\/v1\/student\/leaderboards\/([^/]+)\/ranks$/.exec(path);
    if (match) {
      const board = visible.find((entry) => entry.id === match[1]);
      if (!board) return fulfillJson(route, 404, apiError("NOT_FOUND", "Leaderboard not found."));
      return fulfillJson(route, 200, apiSuccess(ranksPayload(board, url, true)));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked."));
  });
  return { state, requests: rec.requests, find: rec.find };
}
