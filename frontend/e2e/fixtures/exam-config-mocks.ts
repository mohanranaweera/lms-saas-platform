import type { Page, Request, Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, type FieldError } from "./auth-mocks";
import { courseResponseBody, mockTenantSession } from "./exams-mocks";

/**
 * Stateful route mocks for the Klass Exam Module Slice B (exam configuration,
 * directory, paper) — `docs/api/exam-management-v2.md` incl. the
 * course-config addendum. One dispatcher handles every `/v1/**` call so reads
 * reflect earlier writes. Status grouping uses a fixed `serverNow`.
 */

export const SERVER_NOW = "2026-10-30T08:30:00Z";
export const BIO_ID = "c1000000-0000-0000-0000-000000000001";
export const CHEM_ID = "c2000000-0000-0000-0000-000000000002";
export const BIO_NAME = "Biology 2027";
export const CHEM_NAME = "Chemistry 2027";
export const MOD_A = "m1000000-0000-0000-0000-000000000001";
export const MOD_B = "m1000000-0000-0000-0000-000000000002";
export const MOD_CHEM = "m2000000-0000-0000-0000-000000000001";

export const EXAM_WRITTEN = "e0000000-0000-0000-0000-000000000001";
export const EXAM_MCQ_LOCKED = "e0000000-0000-0000-0000-000000000002";
export const EXAM_MCQ_UPCOMING = "e0000000-0000-0000-0000-000000000003";
export const EXAM_PHYSICAL = "e0000000-0000-0000-0000-000000000004";

type ExamType = "MCQ" | "WRITTEN" | "PHYSICAL";

export interface MockExam {
  id: string;
  type: ExamType;
  name: string;
  courseId: string;
  courseName: string;
  hasPassword: boolean;
  durationMinutes: number | null;
  startAt: string;
  endAt: string;
  questionCount: number | null;
  paperQuestionCount: number | null;
  cutoffMarks: number | null;
  moduleQuotas: { moduleId: string; moduleName: string; questionCount: number }[] | null;
  shuffleQuestions: boolean;
  shuffleAnswers: boolean;
  enrollAllStudents: boolean;
  autoEnrollOnPayment: boolean;
  paymentCriteria: unknown;
  showAnswersWithResults: boolean;
  totalMarks: number | null;
  questionPaperMediaId: string | null;
  markingSchemeMediaId: string | null;
  questionPaper: { fileName: string } | null;
  markingScheme: { fileName: string } | null;
  locked: boolean;
  createdAt: string;
  updatedAt: string;
  // card counters
  enrolled: number;
  attended: number;
  completed: number;
  submitted: number;
}

interface RecordedRequest {
  method: string;
  path: string;
  body: unknown;
}

export interface ExamConfigMockOptions {
  exams?: MockExam[];
  /** 403 on the directory. */
  directoryStatus?: number;
  /** Exams whose DELETE answers 409. */
  deleteConflictIds?: string[];
  /** Next PUT answers 409 `EXAM_LOCKED`. */
  updateLockedOnce?: boolean;
  /** Next POST /exams answers 400 with these field errors. */
  createFieldErrors?: FieldError[];
}

export interface ExamConfigMock {
  state: { exams: MockExam[] };
  requests: RecordedRequest[];
  find: (method: string, pathPart: string) => RecordedRequest[];
}

const MODULES: Record<string, { id: string; title: string; sequence: number }[]> = {
  [BIO_ID]: [
    { id: MOD_A, title: "2027", sequence: 1 },
    { id: MOD_B, title: "Test", sequence: 2 },
  ],
  [CHEM_ID]: [{ id: MOD_CHEM, title: "Organic", sequence: 1 }],
};

/** questionCount / totalMarks / min / max per module. */
const COUNTS: Record<string, { questionCount: number; totalMarks: number; minMarks: number | null; maxMarks: number | null }> = {
  [MOD_A]: { questionCount: 3, totalMarks: 4.5, minMarks: 1, maxMarks: 2 },
  [MOD_B]: { questionCount: 2, totalMarks: 2, minMarks: 1, maxMarks: 1 },
  [MOD_CHEM]: { questionCount: 0, totalMarks: 0, minMarks: null, maxMarks: null },
};

const CONFIG: Record<string, { pricingModel: string; paymentCriteriaKind: string | null }> = {
  [BIO_ID]: { pricingModel: "MONTHLY", paymentCriteriaKind: "MONTHS" },
  [CHEM_ID]: { pricingModel: "FREE", paymentCriteriaKind: null },
};

const COURSES = [
  courseResponseBody({ id: BIO_ID, name: BIO_NAME, slug: "biology-2027", pricingModel: "MONTHLY" }),
  courseResponseBody({ id: CHEM_ID, name: CHEM_NAME, slug: "chemistry-2027", pricingModel: "FREE" }),
];

function base(overrides: Partial<MockExam> & Pick<MockExam, "id" | "type" | "name" | "startAt" | "endAt">): MockExam {
  return {
    courseId: BIO_ID,
    courseName: BIO_NAME,
    hasPassword: overrides.type !== "PHYSICAL",
    durationMinutes: 30,
    questionCount: null,
    paperQuestionCount: null,
    cutoffMarks: null,
    moduleQuotas: null,
    shuffleQuestions: false,
    shuffleAnswers: false,
    enrollAllStudents: false,
    autoEnrollOnPayment: false,
    paymentCriteria: null,
    showAnswersWithResults: false,
    totalMarks: null,
    questionPaperMediaId: null,
    markingSchemeMediaId: null,
    questionPaper: null,
    markingScheme: null,
    locked: false,
    createdAt: SERVER_NOW,
    updatedAt: SERVER_NOW,
    enrolled: 0,
    attended: 0,
    completed: 0,
    submitted: 0,
    ...overrides,
  };
}

export function sampleExams(): MockExam[] {
  return [
    base({
      id: EXAM_WRITTEN,
      type: "WRITTEN",
      name: "test",
      startAt: "2024-06-09T03:28:00Z",
      endAt: "2024-06-09T05:28:00Z",
      totalMarks: 100,
      questionPaperMediaId: "doc-paper-1",
      questionPaper: { fileName: "paper.pdf" },
      enrolled: 3,
    }),
    base({
      id: EXAM_MCQ_LOCKED,
      type: "MCQ",
      name: "Word Exam",
      startAt: "2024-06-08T21:10:00Z",
      endAt: "2024-06-09T23:10:00Z",
      questionCount: 4,
      paperQuestionCount: 4,
      cutoffMarks: 2,
      totalMarks: 5,
      moduleQuotas: [
        { moduleId: MOD_A, moduleName: "2027", questionCount: 2 },
        { moduleId: MOD_B, moduleName: "Test", questionCount: 2 },
      ],
      locked: true,
      enrolled: 2,
      attended: 1,
      completed: 1,
    }),
    base({
      id: EXAM_MCQ_UPCOMING,
      type: "MCQ",
      name: "Unit Test 1",
      startAt: "2026-11-05T03:30:00Z",
      endAt: "2026-11-05T05:30:00Z",
      questionCount: 3,
      paperQuestionCount: 3,
      cutoffMarks: 2,
      totalMarks: 4.5,
      moduleQuotas: [{ moduleId: MOD_A, moduleName: "2027", questionCount: 3 }],
      shuffleQuestions: true,
      enrolled: 12,
    }),
    base({
      id: EXAM_PHYSICAL,
      type: "PHYSICAL",
      name: "Term Test",
      startAt: "2026-11-20T03:00:00Z",
      endAt: "2026-11-20T06:00:00Z",
      durationMinutes: 180,
      enrollAllStudents: true,
      enrolled: 20,
    }),
  ];
}

function statusOf(exam: MockExam): "UPCOMING" | "IN_PROGRESS" | "FINISHED" {
  const now = new Date(SERVER_NOW).getTime();
  if (now < new Date(exam.startAt).getTime()) return "UPCOMING";
  if (now < new Date(exam.endAt).getTime()) return "IN_PROGRESS";
  return "FINISHED";
}

function detailOf(exam: MockExam) {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { enrolled, attended, completed, submitted, ...detail } = exam;
  return { ...detail, status: statusOf(exam) };
}

function cardOf(exam: MockExam) {
  return {
    id: exam.id,
    type: exam.type,
    status: statusOf(exam),
    name: exam.name,
    courseId: exam.courseId,
    courseName: exam.courseName,
    startAt: exam.startAt,
    endAt: exam.endAt,
    durationMinutes: exam.durationMinutes,
    questionCount: exam.type === "MCQ" ? exam.questionCount : null,
    totalMarks: exam.totalMarks,
    enrolled: exam.enrolled,
    attended: exam.attended,
    completed: exam.completed,
    submitted: exam.submitted,
  };
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

type Upsert = {
  type: ExamType;
  name: string;
  courseId: string;
  password: string | null;
  durationMinutes: number | null;
  startAt: string;
  endAt: string;
  questionCount: number | null;
  cutoffMarks: number | null;
  moduleQuotas: { moduleId: string; questionCount: number }[] | null;
  shuffleQuestions: boolean;
  shuffleAnswers: boolean;
  enrollAllStudents: boolean;
  autoEnrollOnPayment: boolean;
  paymentCriteria: unknown;
  showAnswersWithResults: boolean;
  totalMarks: number | null;
  questionPaperMediaId: string | null;
  markingSchemeMediaId: string | null;
};

function moduleName(id: string): string {
  return Object.values(MODULES).flat().find((entry) => entry.id === id)?.title ?? "Module";
}

/** "Draws" the paper: whole-module quotas use the module total, others 1 mark each. */
function paperTotal(quotas: { moduleId: string; questionCount: number }[]): number {
  return quotas.reduce((sum, quota) => {
    const info = COUNTS[quota.moduleId];
    if (!info) return sum;
    return sum + (quota.questionCount >= info.questionCount ? info.totalMarks : quota.questionCount * (info.minMarks ?? 1));
  }, 0);
}

function applyUpsert(target: MockExam, body: Upsert, documents: Map<string, string>): MockExam {
  const quotas = body.moduleQuotas ?? null;
  return {
    ...target,
    type: body.type,
    name: body.name,
    courseId: body.courseId,
    courseName: COURSES.find((course) => course.id === body.courseId)?.name ?? "Course",
    hasPassword: body.password ? true : target.hasPassword && body.type !== "PHYSICAL",
    durationMinutes: body.durationMinutes,
    startAt: body.startAt,
    endAt: body.endAt,
    questionCount: body.questionCount,
    paperQuestionCount: body.questionCount,
    cutoffMarks: body.cutoffMarks,
    moduleQuotas: quotas ? quotas.map((quota) => ({ ...quota, moduleName: moduleName(quota.moduleId) })) : null,
    shuffleQuestions: body.shuffleQuestions,
    shuffleAnswers: body.shuffleAnswers,
    enrollAllStudents: body.enrollAllStudents,
    autoEnrollOnPayment: body.autoEnrollOnPayment,
    paymentCriteria: body.paymentCriteria,
    showAnswersWithResults: body.showAnswersWithResults,
    totalMarks: body.type === "MCQ" && quotas ? paperTotal(quotas) : body.totalMarks,
    questionPaperMediaId: body.questionPaperMediaId,
    markingSchemeMediaId: body.markingSchemeMediaId,
    questionPaper: body.questionPaperMediaId ? { fileName: documents.get(body.questionPaperMediaId) ?? "paper.pdf" } : null,
    markingScheme: body.markingSchemeMediaId ? { fileName: documents.get(body.markingSchemeMediaId) ?? "scheme.pdf" } : null,
    updatedAt: SERVER_NOW,
  };
}

/** Installs the session + every Slice B endpoint mock. Call before `page.goto`. */
export async function mockExamConfig(
  page: Page,
  role: "TENANT_ADMIN" | "TEACHER" | "READ_ONLY_AUDITOR",
  options: ExamConfigMockOptions = {}
): Promise<ExamConfigMock> {
  await mockTenantSession(page, role);
  const state = { exams: options.exams ?? sampleExams() };
  const requests: RecordedRequest[] = [];
  const documents = new Map<string, string>();
  let lockedOnce = options.updateLockedOnce ?? false;
  let createErrors = options.createFieldErrors ?? null;
  let counter = 100;

  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    const method = request.method();
    if (path.startsWith("/v1/auth/")) return route.fallback();
    requests.push({ method, path: `${path}${url.search}`, body: parseBody(request) });

    // Courses + modules + question counts
    if (method === "GET" && path === "/v1/courses") return fulfillJson(route, 200, apiPageSuccess(COURSES));
    const modulesMatch = /^\/v1\/courses\/([^/]+)\/modules$/.exec(path);
    if (method === "GET" && modulesMatch) {
      return fulfillJson(
        route,
        200,
        apiSuccess((MODULES[modulesMatch[1]] ?? []).map((entry) => ({ ...entry, courseId: modulesMatch[1], price: null })))
      );
    }
    const countsMatch = /^\/v1\/courses\/([^/]+)\/modules\/question-counts$/.exec(path);
    if (method === "GET" && countsMatch) {
      return fulfillJson(
        route,
        200,
        apiSuccess(
          (MODULES[countsMatch[1]] ?? []).map((entry) => ({
            moduleId: entry.id,
            moduleName: entry.title,
            ...COUNTS[entry.id],
          }))
        )
      );
    }

    // Slice B
    if (method === "GET" && path === "/v1/exams/directory") {
      if (options.directoryStatus) {
        return fulfillJson(route, options.directoryStatus, apiError("FORBIDDEN", "You do not have permission to perform this action"));
      }
      const search = (url.searchParams.get("search") ?? "").toLowerCase();
      const matches = state.exams.filter(
        (exam) => !search || exam.name.toLowerCase().includes(search) || exam.courseName.toLowerCase().includes(search)
      );
      const by = (status: string) => matches.filter((exam) => statusOf(exam) === status);
      return fulfillJson(
        route,
        200,
        apiSuccess({
          serverNow: SERVER_NOW,
          inProgress: by("IN_PROGRESS").sort((a, b) => a.endAt.localeCompare(b.endAt)).map(cardOf),
          upcoming: by("UPCOMING").sort((a, b) => a.startAt.localeCompare(b.startAt)).map(cardOf),
          previous: by("FINISHED").sort((a, b) => b.endAt.localeCompare(a.endAt)).map(cardOf),
        })
      );
    }
    const configMatch = /^\/v1\/exams\/course-config\/([^/]+)$/.exec(path);
    if (method === "GET" && configMatch) {
      const config = CONFIG[configMatch[1]];
      return config
        ? fulfillJson(route, 200, apiSuccess({ courseId: configMatch[1], ...config }))
        : fulfillJson(route, 404, apiError("NOT_FOUND", "Course not found."));
    }
    if (method === "POST" && path === "/v1/exam-documents") {
      const id = `doc-${++counter}`;
      const purpose = url.searchParams.get("purpose");
      const fileName = purpose === "MARKING_SCHEME" ? "marking-scheme.pdf" : "question-paper.pdf";
      documents.set(id, fileName);
      return fulfillJson(route, 201, apiSuccess({ mediaId: id, fileName }));
    }
    if (method === "POST" && path === "/v1/exams") {
      if (createErrors) {
        const errors = createErrors;
        createErrors = null;
        return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", errors));
      }
      const body = parseBody(request) as Upsert;
      const created = applyUpsert(
        base({ id: `e0000000-0000-0000-0000-000000000${++counter}`, type: body.type, name: body.name, startAt: body.startAt, endAt: body.endAt }),
        body,
        documents
      );
      created.hasPassword = Boolean(body.password);
      state.exams.push(created);
      return fulfillJson(route, 201, apiSuccess(detailOf(created)));
    }
    const redrawMatch = /^\/v1\/exams\/([^/]+)\/paper\/redraw$/.exec(path);
    if (method === "POST" && redrawMatch) {
      const exam = state.exams.find((entry) => entry.id === redrawMatch[1]);
      if (!exam) return fulfillJson(route, 404, apiError("NOT_FOUND", "Exam not found."));
      if (exam.locked) return fulfillJson(route, 409, apiError("EXAM_LOCKED", "The exam is locked."));
      return fulfillJson(route, 200, apiSuccess(detailOf(exam)));
    }
    const examMatch = /^\/v1\/exams\/([^/]+)$/.exec(path);
    if (examMatch) {
      const index = state.exams.findIndex((entry) => entry.id === examMatch[1]);
      if (index === -1) return fulfillJson(route, 404, apiError("NOT_FOUND", "Exam not found."));
      const exam = state.exams[index];
      if (method === "GET") return fulfillJson(route, 200, apiSuccess(detailOf(exam)));
      if (method === "DELETE") {
        if ((options.deleteConflictIds ?? []).includes(exam.id)) {
          return fulfillJson(route, 409, apiError("CONFLICT", "Attempts exist for this exam."));
        }
        state.exams.splice(index, 1);
        return route.fulfill({ status: 204, body: "" });
      }
      if (method === "PUT") {
        if (lockedOnce) {
          lockedOnce = false;
          exam.locked = true;
          return fulfillJson(route, 409, apiError("EXAM_LOCKED", "A student has started this exam."));
        }
        const updated = applyUpsert(exam, parseBody(request) as Upsert, documents);
        state.exams[index] = updated;
        return fulfillJson(route, 200, apiSuccess(detailOf(updated)));
      }
    }

    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked."));
  });

  return {
    state,
    requests,
    find: (method, pathPart) => requests.filter((item) => item.method === method && item.path.includes(pathPart)),
  };
}
