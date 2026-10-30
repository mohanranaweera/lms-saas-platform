import type { Page, Request, Route } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson } from "./auth-mocks";
import { mockTenantSession } from "./exams-mocks";
import { SERVER_NOW, type ExamConfigMock, type MockExam } from "./exam-config-mocks";

/**
 * Stateful mocks for the Klass Exam Module Slice D (`docs/api/exam-management-v2.md`
 * "Slice D"): staff management (summary, students page, CSV export, results PUT,
 * total marks, answer/marked-paper documents, password reveal, removal) layered
 * over `mockExamConfig` (register after it — later routes win), and the student
 * written/physical endpoints. Rules mirror the contract so the UI is exercised
 * against server-style 400/409 answers.
 */

type ResultLabel =
  | "NOT_PARTICIPATED"
  | "PENDING"
  | "IN_PROGRESS"
  | "AWAITING_MARKS"
  | "PASSED"
  | "FAILED"
  | "MARKED"
  | "ABSENT";

export interface MockRow {
  studentId: string;
  indexNumber: string;
  firstName: string;
  lastName: string;
  result: ResultLabel;
  marks: number | null;
  attemptStatus: "IN_PROGRESS" | "SUBMITTED" | "EXPIRED" | null;
  answeredCount: number | null;
  answerDocuments: { id: string; fileName: string }[];
  markedPaper: { fileName: string } | null;
  attendance: "PRESENT" | "ABSENT" | null;
  enrollmentSource: string;
}

interface RecordedRequest {
  method: string;
  path: string;
  body: unknown;
}

export const REVEALED_PASSWORD = "123456";
export const FILE_HOST = "https://files.example.test";

const FIRST = ["Kamala", "Nimal", "Sunil", "Amaya", "Ravi", "Dilani", "Kasun", "Tharushi", "Ishara", "Pradeep", "Sachini", "Lahiru", "Nadeesha"];
const LAST = ["Silva", "Perera", "Fernando", "Jayasuriya", "Bandara", "Wickrama", "Dias", "Rajapaksa", "Gunawardena", "Herath", "Peiris", "Senanayake", "Kumara"];

function row(index: number, overrides: Partial<MockRow> = {}): MockRow {
  return {
    studentId: `s-${index}`,
    indexNumber: String(index).padStart(4, "0"),
    firstName: FIRST[(index - 1) % FIRST.length],
    lastName: LAST[(index - 1) % LAST.length],
    result: "NOT_PARTICIPATED",
    marks: null,
    attemptStatus: null,
    answeredCount: null,
    answerDocuments: [],
    markedPaper: null,
    attendance: null,
    enrollmentSource: "MANUAL",
    ...overrides,
  };
}

/** 13 MCQ rows (pagination 10/page): a mix of passed/failed/not participated. */
export function mcqRows(): MockRow[] {
  return Array.from({ length: 13 }, (_, position) => {
    const index = position + 1;
    if (index === 1) return row(index, { result: "PASSED", marks: 4, attemptStatus: "SUBMITTED", answeredCount: 4 });
    if (index === 2) return row(index, { result: "FAILED", marks: 1, attemptStatus: "EXPIRED", answeredCount: 2 });
    if (index === 3) return row(index, { result: "PASSED", marks: 5, attemptStatus: "SUBMITTED", answeredCount: 4, enrollmentSource: "PAYMENT" });
    return row(index);
  });
}

export function writtenRows(): MockRow[] {
  return [
    row(1, { result: "AWAITING_MARKS", answerDocuments: [{ id: "d-1", fileName: "kamala-page1.pdf" }, { id: "d-2", fileName: "kamala-page2.jpg" }] }),
    row(2, { result: "PASSED", marks: 72.5, answerDocuments: [{ id: "d-3", fileName: "nimal.pdf" }], markedPaper: { fileName: "nimal-marked.pdf" } }),
    row(3, { result: "NOT_PARTICIPATED" }),
  ];
}

export function physicalRows(): MockRow[] {
  return [row(1, { result: "PENDING" }), row(2, { result: "PENDING" }), row(3, { result: "PENDING" })];
}

function at(iso: string): number {
  return new Date(iso).getTime();
}

function statusOf(exam: MockExam): "UPCOMING" | "IN_PROGRESS" | "FINISHED" {
  const now = at(SERVER_NOW);
  if (now < at(exam.startAt)) return "UPCOMING";
  if (now < at(exam.endAt)) return "IN_PROGRESS";
  return "FINISHED";
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

/** File names from a multipart body (enough for the mock; the real server sniffs content). */
function multipartFiles(request: Request): { field: string; fileName: string }[] {
  const raw = request.postDataBuffer()?.toString("latin1") ?? "";
  const files: { field: string; fileName: string }[] = [];
  const pattern = /name="([^"]+)"; filename="([^"]*)"/g;
  for (let match = pattern.exec(raw); match; match = pattern.exec(raw)) files.push({ field: match[1], fileName: match[2] });
  return files;
}

function participated(entry: MockRow): boolean {
  return (
    entry.attemptStatus !== null ||
    entry.answerDocuments.length > 0 ||
    entry.marks !== null ||
    entry.attendance !== null ||
    entry.markedPaper !== null
  );
}

export interface ManagementMockOptions {
  rows: MockRow[];
  /** Answer the next marks PUT with this 400 field error (server-only rule). */
  nextResultFieldError?: { field: string; message: string };
}

export interface ManagementMock {
  state: { rows: MockRow[] };
  requests: RecordedRequest[];
  find: (method: string, pathPart: string) => RecordedRequest[];
}

/** Slice D staff endpoints for one exam. Call after `mockExamConfig` (shares its exam state). */
export async function mockExamManagement(page: Page, config: ExamConfigMock, examId: string, options: ManagementMockOptions): Promise<ManagementMock> {
  const state = { rows: options.rows };
  const requests: RecordedRequest[] = [];
  let nextFieldError = options.nextResultFieldError ?? null;
  let counter = 100;
  const exam = () => config.state.exams.find((entry) => entry.id === examId)!;

  const relabel = (entry: MockRow) => {
    const current = exam();
    if (current.type === "PHYSICAL" && entry.attendance === "ABSENT") entry.result = "ABSENT";
    else if (entry.marks === null) {
      if (current.type === "WRITTEN" && entry.answerDocuments.length > 0) entry.result = "AWAITING_MARKS";
      else if (current.type === "PHYSICAL" && entry.attendance === "PRESENT") entry.result = "AWAITING_MARKS";
      else entry.result = statusOf(current) === "FINISHED" ? "NOT_PARTICIPATED" : "PENDING";
    } else if (current.cutoffMarks !== null) entry.result = entry.marks >= current.cutoffMarks ? "PASSED" : "FAILED";
    else entry.result = "MARKED";
  };

  const filtered = (search: string) => {
    const needle = search.trim().toLowerCase();
    return state.rows.filter(
      (entry) =>
        !needle ||
        entry.indexNumber.includes(needle) ||
        `${entry.firstName} ${entry.lastName}`.toLowerCase().includes(needle)
    );
  };

  const summary = () => {
    const current = exam();
    const enrolled = state.rows.length;
    const attended = state.rows.filter((entry) =>
      current.type === "PHYSICAL" ? entry.attendance === "PRESENT" : entry.attemptStatus !== null || entry.answerDocuments.length > 0
    ).length;
    const completed = state.rows.filter((entry) => entry.attemptStatus === "SUBMITTED" || entry.attemptStatus === "EXPIRED").length;
    const submitted = state.rows.filter((entry) => entry.answerDocuments.length > 0).length;
    const pct = (n: number) => (enrolled === 0 ? 0 : Math.round((n / enrolled) * 1000) / 10);
    return { enrolled, attended, attendedPercent: pct(attended), completed, completedPercent: pct(completed), submitted, submittedPercent: pct(submitted) };
  };

  const examPayload = () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { enrolled, attended, completed, submitted, ...detail } = exam();
    return { ...detail, status: statusOf(exam()) };
  };

  await page.context().route(`${FILE_HOST}/**`, (route) =>
    route.fulfill({ status: 200, contentType: "application/pdf", body: "%PDF-1.4\n%mock\n" })
  );

  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    const method = request.method();
    const prefix = `/v1/exams/${examId}`;
    if (!path.startsWith(prefix + "/")) return route.fallback();
    const rest = path.slice(prefix.length);
    requests.push({ method, path: `${path}${url.search}`, body: parseBody(request) });

    if (method === "GET" && rest === "/management") {
      const page0 = Number(url.searchParams.get("page") ?? "0");
      const size = Number(url.searchParams.get("size") ?? "10");
      const list = filtered(url.searchParams.get("search") ?? "");
      const content = list.slice(page0 * size, page0 * size + size);
      return fulfillJson(
        route,
        200,
        apiSuccess({
          serverNow: SERVER_NOW,
          resultsPublishedAt: null,
          exam: examPayload(),
          summary: summary(),
          students: { content, page: page0, size, totalElements: list.length, totalPages: Math.ceil(list.length / size) },
        })
      );
    }
    if (method === "GET" && rest === "/export.csv") {
      const list = filtered(url.searchParams.get("search") ?? "");
      const lines = ["Index Number,First Name,Last Name,Result,Marks", ...list.map((entry) => `${entry.indexNumber},${entry.firstName},${entry.lastName},${entry.result},${entry.marks ?? ""}`)];
      return route.fulfill({
        status: 200,
        headers: { "Content-Type": "text/csv; charset=utf-8", "Content-Disposition": 'attachment; filename="word-exam-students.csv"' },
        body: "﻿" + lines.join("\r\n"),
      });
    }
    if (method === "POST" && rest === "/password/reveal") {
      return fulfillJson(route, 200, apiSuccess({ password: REVEALED_PASSWORD }));
    }
    if (method === "PUT" && rest === "/total-marks") {
      const body = parseBody(request) as { totalMarks: number };
      if (state.rows.some((entry) => entry.marks !== null && entry.marks > body.totalMarks)) {
        return fulfillJson(route, 409, apiError("MARKS_EXCEED_TOTAL", "Some students already have more marks than this total."));
      }
      exam().totalMarks = body.totalMarks;
      return fulfillJson(route, 200, apiSuccess(examPayload()));
    }
    const resultMatch = /^\/results\/([^/]+)$/.exec(rest);
    if (method === "PUT" && resultMatch) {
      const entry = state.rows.find((candidate) => candidate.studentId === resultMatch[1]);
      if (!entry) return fulfillJson(route, 404, apiError("NOT_FOUND", "Student is not enrolled in this exam."));
      if (nextFieldError) {
        const fieldError = nextFieldError;
        nextFieldError = null;
        return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", [fieldError]));
      }
      const body = parseBody(request) as { marks: number | null; attendance: "PRESENT" | "ABSENT" | null; reason: string | null };
      const total = exam().totalMarks;
      if (exam().type === "WRITTEN" && body.attendance) {
        return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", [{ field: "attendance", message: "Written exams have no attendance." }]));
      }
      if (exam().type === "PHYSICAL" && body.marks !== null && total === null) {
        return fulfillJson(route, 409, apiError("TOTAL_MARKS_REQUIRED", "Set the total marks first."));
      }
      if (body.attendance === "ABSENT" && body.marks !== null) {
        return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", [{ field: "attendance", message: "A student with marks can't be absent." }]));
      }
      if (body.marks !== null && (body.marks < 0 || (total !== null && body.marks > total))) {
        return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", [{ field: "marks", message: `Marks must be between 0 and ${total}.` }]));
      }
      if (entry.marks !== null && body.marks !== entry.marks && !body.reason) {
        return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", [{ field: "reason", message: "A reason is required to change marks." }]));
      }
      entry.marks = body.marks;
      if (body.attendance) entry.attendance = body.attendance;
      // Entering physical marks sets attendance PRESENT (Slice D addendum).
      if (exam().type === "PHYSICAL" && body.marks !== null) entry.attendance = "PRESENT";
      relabel(entry);
      return fulfillJson(route, 200, apiSuccess(entry));
    }
    const docsMatch = /^\/submissions\/([^/]+)\/documents$/.exec(rest);
    if (method === "POST" && docsMatch) {
      const entry = state.rows.find((candidate) => candidate.studentId === docsMatch[1])!;
      const files = multipartFiles(request);
      // The UI sends one file per request (26 MB request ceiling), part `files[]`.
      if (files.length !== 1 || files[0].field !== "files[]") return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Send one file in part files[]."));
      files.forEach((file) => entry.answerDocuments.push({ id: `d-${++counter}`, fileName: file.fileName }));
      relabel(entry);
      return fulfillJson(route, 201, apiSuccess(entry.answerDocuments));
    }
    const docMatch = /^\/submissions\/([^/]+)\/documents\/([^/]+)$/.exec(rest);
    if (docMatch) {
      const entry = state.rows.find((candidate) => candidate.studentId === docMatch[1])!;
      const document = entry.answerDocuments.find((candidate) => candidate.id === docMatch[2]);
      if (!document) return fulfillJson(route, 404, apiError("NOT_FOUND", "Document not found."));
      if (method === "GET") return fulfillJson(route, 200, apiSuccess({ url: `${FILE_HOST}/answer/${document.id}`, fileName: document.fileName }));
      if (method === "DELETE") {
        entry.answerDocuments = entry.answerDocuments.filter((candidate) => candidate.id !== document.id);
        relabel(entry);
        return route.fulfill({ status: 204, body: "" });
      }
    }
    const markedMatch = /^\/results\/([^/]+)\/marked-paper$/.exec(rest);
    if (markedMatch) {
      const entry = state.rows.find((candidate) => candidate.studentId === markedMatch[1])!;
      if (method === "POST") {
        const files = multipartFiles(request);
        if (files.length !== 1 || files[0].field !== "file") return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Attach one file."));
        entry.markedPaper = { fileName: files[0].fileName };
        return fulfillJson(route, 201, apiSuccess(entry.markedPaper));
      }
      if (method === "GET") {
        return entry.markedPaper
          ? fulfillJson(route, 200, apiSuccess({ url: `${FILE_HOST}/marked/${entry.studentId}`, fileName: entry.markedPaper.fileName }))
          : fulfillJson(route, 404, apiError("NOT_FOUND", "No marked paper."));
      }
    }
    const enrollmentMatch = /^\/enrollments\/([^/]+)$/.exec(rest);
    if (method === "DELETE" && enrollmentMatch) {
      const entry = state.rows.find((candidate) => candidate.studentId === enrollmentMatch[1]);
      if (!entry) return fulfillJson(route, 404, apiError("NOT_FOUND", "Not enrolled."));
      if (participated(entry)) return fulfillJson(route, 409, apiError("HAS_ATTEMPT", "The student has an attempt."));
      state.rows = state.rows.filter((candidate) => candidate !== entry);
      return route.fulfill({ status: 204, body: "" });
    }
    const documentMatch = /^\/documents\/(QUESTION_PAPER|MARKING_SCHEME)$/.exec(rest);
    if (method === "GET" && documentMatch) {
      return fulfillJson(route, 200, apiSuccess({ url: `${FILE_HOST}/exam/${documentMatch[1]}`, fileName: "paper.pdf" }));
    }
    return route.fallback();
  });

  return {
    state,
    requests,
    find: (method, pathPart) => requests.filter((item) => item.method === method && item.path.includes(pathPart)),
  };
}

// ---------------------------------------------------------------------------
// Student — written / physical
// ---------------------------------------------------------------------------

export const W_OPEN = "w0000000-0000-0000-0000-000000000001";
export const W_PUBLISHED = "w0000000-0000-0000-0000-000000000002";
export const W_UNPUBLISHED = "w0000000-0000-0000-0000-000000000003";
export const P_PUBLISHED = "w0000000-0000-0000-0000-000000000004";
export const W_PASSWORD = "essay42";

interface StudentWrittenExam {
  id: string;
  type: "WRITTEN" | "PHYSICAL";
  name: string;
  startAt: string;
  endAt: string;
  durationMinutes: number | null;
  totalMarks: number;
  cutoffMarks: number | null;
  published: boolean;
  attempt: { id: string; status: "IN_PROGRESS" | "SUBMITTED" | "EXPIRED"; startedAt: string; deadlineAt: string } | null;
  documents: { id: string; fileName: string; uploadedAt: string }[];
  marks: number | null;
  attendance: "PRESENT" | "ABSENT" | null;
  markedPaper: { fileName: string } | null;
}

function minutes(iso: string, delta: number): string {
  return new Date(at(iso) + delta * 60_000).toISOString();
}

export function sampleStudentWrittenExams(): StudentWrittenExam[] {
  return [
    {
      id: W_OPEN,
      type: "WRITTEN",
      name: "Essay Paper",
      startAt: minutes(SERVER_NOW, -10),
      endAt: minutes(SERVER_NOW, 120),
      durationMinutes: 60,
      totalMarks: 100,
      cutoffMarks: 40,
      published: false,
      attempt: null,
      documents: [],
      marks: null,
      attendance: null,
      markedPaper: null,
    },
    {
      id: W_PUBLISHED,
      type: "WRITTEN",
      name: "Structured Paper",
      startAt: "2026-10-20T03:00:00Z",
      endAt: "2026-10-20T05:00:00Z",
      durationMinutes: 60,
      totalMarks: 100,
      cutoffMarks: 40,
      published: true,
      attempt: { id: "wa-2", status: "EXPIRED", startedAt: "2026-10-20T03:05:00Z", deadlineAt: "2026-10-20T04:05:00Z" },
      documents: [{ id: "md-1", fileName: "my-answers.pdf", uploadedAt: "2026-10-20T03:50:00Z" }],
      marks: 72.5,
      attendance: null,
      markedPaper: { fileName: "marked-structured.pdf" },
    },
    {
      id: W_UNPUBLISHED,
      type: "WRITTEN",
      name: "Unit Essay",
      startAt: "2026-10-22T03:00:00Z",
      endAt: "2026-10-22T05:00:00Z",
      durationMinutes: 60,
      totalMarks: 50,
      cutoffMarks: null,
      published: false,
      attempt: { id: "wa-3", status: "EXPIRED", startedAt: "2026-10-22T03:05:00Z", deadlineAt: "2026-10-22T04:05:00Z" },
      documents: [{ id: "md-2", fileName: "unit-essay.pdf", uploadedAt: "2026-10-22T03:40:00Z" }],
      marks: 30,
      attendance: null,
      markedPaper: null,
    },
    {
      id: P_PUBLISHED,
      type: "PHYSICAL",
      name: "Term Test",
      startAt: "2026-10-15T03:00:00Z",
      endAt: "2026-10-15T06:00:00Z",
      durationMinutes: 180,
      totalMarks: 80,
      cutoffMarks: null,
      published: true,
      attempt: null,
      documents: [],
      marks: 64,
      attendance: "PRESENT",
      markedPaper: null,
    },
  ];
}

export interface StudentWrittenMock {
  state: { exams: StudentWrittenExam[]; now: string };
  requests: RecordedRequest[];
  find: (method: string, pathPart: string) => RecordedRequest[];
  setNow: (iso: string) => void;
}

/** Student session + the written/physical student endpoints (incl. the assumed result endpoints). */
export async function mockStudentWritten(page: Page, options: { exams?: StudentWrittenExam[] } = {}): Promise<StudentWrittenMock> {
  await mockTenantSession(page, "STUDENT");
  const state = { exams: options.exams ?? sampleStudentWrittenExams(), now: SERVER_NOW };
  const requests: RecordedRequest[] = [];
  let counter = 0;

  const statusOf2 = (entry: StudentWrittenExam) =>
    at(state.now) < at(entry.startAt) ? "UPCOMING" : at(state.now) < at(entry.endAt) ? "IN_PROGRESS" : "FINISHED";
  const finalize = (entry: StudentWrittenExam) => {
    if (entry.attempt && entry.attempt.status === "IN_PROGRESS" && at(state.now) >= at(entry.attempt.deadlineAt)) entry.attempt.status = "EXPIRED";
  };
  const response = (entry: StudentWrittenExam) => {
    finalize(entry);
    return {
      id: entry.id,
      type: entry.type,
      name: entry.name,
      courseId: "c1000000-0000-0000-0000-000000000001",
      courseName: "Biology 2027",
      startAt: entry.startAt,
      endAt: entry.endAt,
      durationMinutes: entry.durationMinutes,
      questionCount: null,
      totalMarks: entry.totalMarks,
      status: statusOf2(entry),
      requiresPassword: entry.type === "WRITTEN",
      attempt: entry.attempt ? { id: entry.attempt.id, status: entry.attempt.status, deadlineAt: entry.attempt.deadlineAt } : null,
      resultAvailable: entry.published,
    };
  };
  const attemptPayload = (entry: StudentWrittenExam) => ({
    id: entry.attempt!.id,
    examId: entry.id,
    status: entry.attempt!.status,
    startedAt: entry.attempt!.startedAt,
    deadlineAt: entry.attempt!.deadlineAt,
    serverNow: state.now,
    items: [],
  });

  await page.context().route(`${FILE_HOST}/**`, (route) =>
    route.fulfill({ status: 200, contentType: "application/pdf", body: "%PDF-1.4\n%mock\n" })
  );

  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    const method = request.method();
    if (path.startsWith("/v1/auth/")) return route.fallback();
    requests.push({ method, path: `${path}${url.search}`, body: parseBody(request) });

    if (method === "GET" && path === "/v1/student/exams") {
      const all = state.exams.map(response);
      return fulfillJson(
        route,
        200,
        apiSuccess({
          serverNow: state.now,
          upcoming: all.filter((entry) => entry.status === "UPCOMING"),
          inProgress: all.filter((entry) => entry.status === "IN_PROGRESS"),
          previous: all.filter((entry) => entry.status === "FINISHED"),
        })
      );
    }
    const submitMatch = /^\/v1\/student\/attempts\/([^/]+)\/submit$/.exec(path);
    if (method === "POST" && submitMatch) {
      const entry = state.exams.find((candidate) => candidate.attempt?.id === submitMatch[1]);
      if (!entry) return fulfillJson(route, 404, apiError("NOT_FOUND", "Attempt not found."));
      finalize(entry);
      if (entry.attempt!.status === "IN_PROGRESS") entry.attempt!.status = "SUBMITTED";
      return fulfillJson(route, 200, apiSuccess(attemptPayload(entry)));
    }
    const attemptMatch = /^\/v1\/student\/attempts\/([^/]+)$/.exec(path);
    if (method === "GET" && attemptMatch) {
      const entry = state.exams.find((candidate) => candidate.attempt?.id === attemptMatch[1]);
      if (!entry) return fulfillJson(route, 404, apiError("NOT_FOUND", "Attempt not found."));
      finalize(entry);
      return fulfillJson(route, 200, apiSuccess(attemptPayload(entry)));
    }
    const match = /^\/v1\/student\/exams\/([^/]+)(\/.*)?$/.exec(path);
    if (!match) return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked."));
    const entry = state.exams.find((candidate) => candidate.id === match[1]);
    if (!entry) return fulfillJson(route, 404, apiError("NOT_FOUND", "Exam not found."));
    const rest = match[2] ?? "";
    finalize(entry);

    if (method === "GET" && rest === "") return fulfillJson(route, 200, apiSuccess(response(entry)));
    if (method === "POST" && rest === "/attempts") {
      const body = parseBody(request) as { password: string | null };
      if (entry.type === "PHYSICAL") return fulfillJson(route, 409, apiError("NOT_SUPPORTED", "Physical exams are held in class."));
      if (statusOf2(entry) !== "IN_PROGRESS") return fulfillJson(route, 409, apiError("CLOSED", "The exam is closed."));
      if (body?.password !== W_PASSWORD) return fulfillJson(route, 403, apiError("WRONG_PASSWORD", "Wrong password."));
      if (!entry.attempt) {
        const deadline = Math.min(at(state.now) + (entry.durationMinutes ?? 0) * 60_000, at(entry.endAt));
        entry.attempt = { id: `wa-${++counter}`, status: "IN_PROGRESS", startedAt: state.now, deadlineAt: new Date(deadline).toISOString() };
      }
      return fulfillJson(route, 200, apiSuccess(attemptPayload(entry)));
    }
    if (method === "GET" && rest === "/paper") {
      if (!entry.attempt) return fulfillJson(route, 409, apiError("NOT_STARTED", "Enter the exam password first."));
      if (entry.attempt.status !== "IN_PROGRESS") return fulfillJson(route, 409, apiError(entry.attempt.status === "SUBMITTED" ? "FINALIZED" : "DEADLINE_PASSED", "Your attempt is closed."));
      // Backend shape: `{ url, fileName }` only — the page reads the deadline from GET /student/attempts/{id}.
      return fulfillJson(route, 200, apiSuccess({ url: `${FILE_HOST}/paper/${entry.id}`, fileName: "essay-paper.pdf" }));
    }
    if (method === "GET" && rest === "/my-documents") return fulfillJson(route, 200, apiSuccess(entry.documents));
    if (method === "POST" && rest === "/answer-documents") {
      if (!entry.attempt) return fulfillJson(route, 409, apiError("NOT_STARTED", "Open the paper first."));
      if (entry.attempt.status !== "IN_PROGRESS") return fulfillJson(route, 409, apiError(entry.attempt.status === "SUBMITTED" ? "FINALIZED" : "DEADLINE_PASSED", "Your attempt is closed."));
      const files = multipartFiles(request);
      if (files.length !== 1 || files[0].field !== "files[]") return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Send one file in part files[]."));
      files.forEach((file) => entry.documents.push({ id: `md-${++counter + 10}`, fileName: file.fileName, uploadedAt: state.now }));
      return fulfillJson(route, 201, apiSuccess(entry.documents));
    }
    if (method === "GET" && rest === "/result") {
      if (!entry.published) return fulfillJson(route, 409, apiError("NOT_RELEASED", "Marks are not published yet."));
      return fulfillJson(
        route,
        200,
        apiSuccess({
          examId: entry.id,
          attemptId: entry.attempt?.id ?? null,
          score: entry.marks,
          totalMarks: entry.totalMarks,
          cutoffMarks: entry.cutoffMarks,
          passed: entry.cutoffMarks === null || entry.marks === null ? null : entry.marks >= entry.cutoffMarks,
          answeredCount: null,
          ...(entry.attendance ? { attendance: entry.attendance } : {}),
          ...(entry.markedPaper ? { markedPaper: { url: `${FILE_HOST}/marked/${entry.id}`, fileName: entry.markedPaper.fileName } } : {}),
        })
      );
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
  };
}
