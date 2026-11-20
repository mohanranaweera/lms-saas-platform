import type { Page, Request, Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, type FieldError } from "./auth-mocks";
import { courseResponseBody, mockTenantSession } from "./exams-mocks";

/**
 * Stateful route mocks for the Klass Exam Module Slice A question bank
 * (`docs/api/exam-management-v2.md`). One dispatcher handles every `/v1/**`
 * call so reads reflect earlier writes in the same test. Unknown endpoints
 * get a 404 envelope so the app shell never hangs.
 *
 * No real backend: every response is shaped like the documented
 * `ApiResponse<T>` envelope.
 */

export const COURSE_ID = "c1000000-0000-0000-0000-000000000001";
export const OTHER_COURSE_ID = "c2000000-0000-0000-0000-000000000002";
export const MODULE_ID = "m1000000-0000-0000-0000-000000000001";
export const SECOND_MODULE_ID = "m1000000-0000-0000-0000-000000000002";
export const OTHER_COURSE_MODULE_ID = "m2000000-0000-0000-0000-000000000001";
export const COURSE_NAME = "Biology 2027";
export const MODULE_TITLE = "2027";
export const MEDIA_URL = "/mock-media/diagram.svg";

export interface MockOption {
  id: string;
  sequence: number;
  html: string;
  correct: boolean;
}

export interface MockQuestion {
  id: string;
  courseId: string;
  moduleId: string;
  sequence: number;
  bodyHtml: string;
  marks: number;
  explanationHtml: string | null;
  options: MockOption[];
  importId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MockImport {
  id: string;
  fileName: string;
  importedAt: string;
  questionCount: number;
  importedByName: string | null;
}

interface RecordedRequest {
  method: string;
  path: string;
  body: unknown;
  headers: Record<string, string>;
}

export interface QuestionBankMockOptions {
  questions?: MockQuestion[];
  imports?: MockImport[];
  /** Status + body for the question list GET (e.g. 403). */
  listFailure?: { status: number; code: string; message: string };
  /** Next N question create/update calls fail with this 400. */
  upsertFieldErrors?: FieldError[];
  /** Import responses, consumed in order: "422" rejects with `importErrors`, "201" succeeds. */
  importOutcomes?: Array<"422" | "201">;
  importErrors?: Array<{ row: number; column: string; message: string }>;
}

export interface QuestionBankMock {
  state: { questions: MockQuestion[]; imports: MockImport[] };
  requests: RecordedRequest[];
  find: (method: string, pathPart: string) => RecordedRequest[];
}

const now = "2026-10-30T08:30:00Z";

function option(id: string, sequence: number, html: string, correct = false): MockOption {
  return { id, sequence, html, correct };
}

export function sampleQuestions(): MockQuestion[] {
  return [
    {
      id: "q0000000-0000-0000-0000-000000000001",
      courseId: COURSE_ID,
      moduleId: MODULE_ID,
      sequence: 1,
      bodyHtml: "<p>The property “Ability of organisms to change over time as a result of genetic modification”</p>",
      marks: 1,
      explanationHtml: "<p>Evolution is change in heritable traits over <strong>generations</strong>.</p>",
      options: [
        option("o1-1", 1, "<p>Growth</p>"),
        option("o1-2", 2, "<p>Irritability</p>"),
        option("o1-3", 3, "<p>Adaptation</p>"),
        option("o1-4", 4, "<p>Heredity</p>"),
        option("o1-5", 5, "<p>Evolution</p>", true),
      ],
      importId: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: "q0000000-0000-0000-0000-000000000002",
      courseId: COURSE_ID,
      moduleId: MODULE_ID,
      sequence: 2,
      bodyHtml: `<img data-media-id="media-1" src="${MEDIA_URL}"><p>Which one of the following statements is correct regarding enzymes?</p>`,
      marks: 2,
      explanationHtml: null,
      options: [
        option("o2-1", 1, "<p>Some enzymes alter the nature of the end products of reaction.</p>"),
        option("o2-2", 2, "<p>The active site of enzymes is made up with many amino acids.</p>", true),
        option("o2-3", 3, "<p>ATP acts as an allosteric inhibitor to some enzymes.</p>"),
      ],
      importId: null,
      createdAt: now,
      updatedAt: now,
    },
    {
      id: "q0000000-0000-0000-0000-000000000003",
      courseId: COURSE_ID,
      moduleId: MODULE_ID,
      sequence: 3,
      bodyHtml: '<p>Solve for the roots: <span data-math="inline" data-latex="x^2 - 5x + 6 = 0">x^2 - 5x + 6 = 0</span></p>',
      marks: 1.5,
      explanationHtml: '<div data-math="block" data-latex="x = \\frac{5 \\pm 1}{2}">x</div>',
      options: [
        option("o3-1", 1, '<p><span data-math="inline" data-latex="x = 2, 3">x</span></p>', true),
        option("o3-2", 2, "<p>x = 1, 6</p>"),
      ],
      importId: null,
      createdAt: now,
      updatedAt: now,
    },
  ];
}

export function sampleImports(): MockImport[] {
  return [
    { id: "imp-1", fileName: "biology-unit-1.csv", importedAt: "2026-10-29T10:15:00Z", questionCount: 40, importedByName: "Asha Perera" },
    { id: "imp-2", fileName: "biology-unit-2.csv", importedAt: "2026-10-20T09:00:00Z", questionCount: 25, importedByName: "Asha Perera" },
  ];
}

const DIAGRAM_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="180" viewBox="0 0 300 180">
<rect width="300" height="180" fill="#f5f5f4" stroke="#d6d3d1"/>
<path d="M60 160 C70 60 110 30 130 30 C150 30 180 70 190 160 Z" fill="#e7e5e4" stroke="#57534e" stroke-width="2"/>
<g stroke="#292524" stroke-width="2"><line x1="135" y1="45" x2="260" y2="40"/><line x1="140" y1="80" x2="260" y2="75"/><line x1="150" y1="115" x2="260" y2="110"/><line x1="170" y1="145" x2="260" y2="145"/></g>
<g font-family="serif" font-size="16" fill="#292524"><text x="268" y="45">a</text><text x="268" y="80">b</text><text x="268" y="115">c</text><text x="268" y="150">d</text></g>
</svg>`;

function moduleBody(id: string, courseId: string, title: string, sequence: number) {
  return { id, courseId, title, sequence, price: null, createdAt: now, updatedAt: now };
}

const COURSES = [
  courseResponseBody({ id: COURSE_ID, name: COURSE_NAME, slug: "biology-2027" }),
  courseResponseBody({ id: OTHER_COURSE_ID, name: "Chemistry 2027", slug: "chemistry-2027" }),
];

const MODULES: Record<string, ReturnType<typeof moduleBody>[]> = {
  [COURSE_ID]: [moduleBody(MODULE_ID, COURSE_ID, MODULE_TITLE, 1), moduleBody(SECOND_MODULE_ID, COURSE_ID, "Test", 2)],
  [OTHER_COURSE_ID]: [moduleBody(OTHER_COURSE_MODULE_ID, OTHER_COURSE_ID, "Organic", 1)],
};

function parseBody(request: Request): unknown {
  const raw = request.postData();
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

/** Installs the session + every bank endpoint mock. Call before `page.goto`. */
export async function mockQuestionBank(
  page: Page,
  role: "TENANT_ADMIN" | "TEACHER",
  options: QuestionBankMockOptions = {}
): Promise<QuestionBankMock> {
  await mockTenantSession(page, role);
  const state = { questions: options.questions ?? sampleQuestions(), imports: options.imports ?? sampleImports() };
  const requests: RecordedRequest[] = [];
  const upsertErrors = options.upsertFieldErrors ? [options.upsertFieldErrors] : [];
  const importOutcomes = [...(options.importOutcomes ?? [])];
  let idCounter = 100;

  await page.route("**/mock-media/**", (route) =>
    route.fulfill({ status: 200, contentType: "image/svg+xml", body: DIAGRAM_SVG })
  );

  await page.route("**/v1/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, "");
    const method = request.method();
    if (path.startsWith("/v1/auth/")) return route.fallback();
    requests.push({ method, path: `${path}${url.search}`, body: parseBody(request), headers: request.headers() });

    // Courses
    if (method === "GET" && path === "/v1/courses") return fulfillJson(route, 200, apiPageSuccess(COURSES));
    const courseMatch = /^\/v1\/courses\/([^/]+)$/.exec(path);
    if (method === "GET" && courseMatch) {
      const course = COURSES.find((item) => item.id === courseMatch[1]);
      return course ? fulfillJson(route, 200, apiSuccess(course)) : fulfillJson(route, 404, apiError("NOT_FOUND", "Course not found."));
    }
    const modulesMatch = /^\/v1\/courses\/([^/]+)\/modules$/.exec(path);
    if (method === "GET" && modulesMatch) return fulfillJson(route, 200, apiSuccess(MODULES[modulesMatch[1]] ?? []));
    const lessonsMatch = /^\/v1\/courses\/[^/]+\/modules\/[^/]+\/lessons$/.exec(path);
    if (method === "GET" && lessonsMatch) return fulfillJson(route, 200, apiSuccess([]));

    // Question counts
    const countsMatch = /^\/v1\/courses\/([^/]+)\/modules\/question-counts$/.exec(path);
    if (method === "GET" && countsMatch) {
      const modules = MODULES[countsMatch[1]] ?? [];
      return fulfillJson(
        route,
        200,
        apiSuccess(
          modules.map((item) => {
            const questions = state.questions.filter((question) => question.moduleId === item.id);
            return {
              moduleId: item.id,
              moduleName: item.title,
              questionCount: questions.length,
              totalMarks: questions.reduce((sum, question) => sum + question.marks, 0),
            };
          })
        )
      );
    }

    // Copy
    const copyMatch = /^\/v1\/courses\/([^/]+)\/modules\/([^/]+)\/questions\/copy$/.exec(path);
    if (method === "POST" && copyMatch) {
      const copied = state.questions.filter((question) => question.moduleId === copyMatch[2]).length;
      return fulfillJson(route, 200, apiSuccess({ copied }));
    }

    // Module questions
    const questionsMatch = /^\/v1\/courses\/([^/]+)\/modules\/([^/]+)\/questions$/.exec(path);
    if (questionsMatch && method === "GET") {
      if (options.listFailure) {
        return fulfillJson(route, options.listFailure.status, apiError(options.listFailure.code, options.listFailure.message));
      }
      const content = state.questions
        .filter((question) => question.moduleId === questionsMatch[2])
        .sort((a, b) => a.sequence - b.sequence);
      return fulfillJson(route, 200, apiPageSuccess(content, { size: 50 }));
    }
    if (questionsMatch && method === "POST") {
      const failure = upsertErrors.shift();
      if (failure) return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", failure));
      const body = parseBody(request) as { bodyHtml: string; marks: number; explanationHtml: string | null; options: Array<{ html: string; correct: boolean }> };
      const id = `q0000000-0000-0000-0000-000000000${++idCounter}`;
      const created: MockQuestion = {
        id,
        courseId: questionsMatch[1],
        moduleId: questionsMatch[2],
        sequence: state.questions.length + 1,
        bodyHtml: body.bodyHtml,
        marks: body.marks,
        explanationHtml: body.explanationHtml,
        options: body.options.map((item, index) => option(`${id}-o${index + 1}`, index + 1, item.html, item.correct)),
        importId: null,
        createdAt: now,
        updatedAt: now,
      };
      state.questions.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }

    // Single question
    const questionMatch = /^\/v1\/exam-questions\/([^/]+)$/.exec(path);
    if (questionMatch) {
      const index = state.questions.findIndex((question) => question.id === questionMatch[1]);
      if (index === -1) return fulfillJson(route, 404, apiError("NOT_FOUND", "Question not found."));
      if (method === "GET") return fulfillJson(route, 200, apiSuccess(state.questions[index]));
      if (method === "DELETE") {
        state.questions.splice(index, 1);
        return route.fulfill({ status: 204, body: "" });
      }
      if (method === "PUT") {
        const failure = upsertErrors.shift();
        if (failure) return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Validation failed.", failure));
        const body = parseBody(request) as { bodyHtml: string; marks: number; explanationHtml: string | null; options: Array<{ id: string | null; html: string; correct: boolean }> };
        const updated: MockQuestion = {
          ...state.questions[index],
          bodyHtml: body.bodyHtml,
          marks: body.marks,
          explanationHtml: body.explanationHtml,
          options: body.options.map((item, position) =>
            option(item.id ?? `${questionMatch[1]}-new-${position + 1}`, position + 1, item.html, item.correct)
          ),
          updatedAt: now,
        };
        state.questions[index] = updated;
        return fulfillJson(route, 200, apiSuccess(updated));
      }
    }

    // Media
    if (method === "POST" && path === "/v1/exam-media") {
      return fulfillJson(route, 201, apiSuccess({ mediaId: `media-${++idCounter}`, url: MEDIA_URL }));
    }

    // Imports
    if (method === "GET" && path === "/v1/question-imports/template.csv") {
      return route.fulfill({
        status: 200,
        contentType: "text/csv; charset=utf-8",
        body: "﻿question,correct answer,answer 2,answer 3,answer 4\n",
      });
    }
    const importsMatch = /^\/v1\/courses\/([^/]+)\/modules\/([^/]+)\/question-imports$/.exec(path);
    if (importsMatch && method === "GET") {
      const search = (url.searchParams.get("search") ?? "").toLowerCase();
      const pageIndex = Number(url.searchParams.get("page") ?? 0);
      const size = Number(url.searchParams.get("size") ?? 10);
      const filtered = state.imports.filter((item) => item.fileName.toLowerCase().includes(search));
      const content = filtered.slice(pageIndex * size, pageIndex * size + size);
      return fulfillJson(
        route,
        200,
        apiPageSuccess(content, {
          page: pageIndex,
          size,
          totalElements: filtered.length,
          totalPages: Math.ceil(filtered.length / size),
        })
      );
    }
    if (importsMatch && method === "POST") {
      const outcome = importOutcomes.shift() ?? "201";
      if (outcome === "422") {
        return fulfillJson(route, 422, {
          success: false,
          data: null,
          error: {
            code: "IMPORT_REJECTED",
            message: "The file has invalid rows.",
            fieldErrors: [],
            details: { errors: options.importErrors ?? [] },
          },
          timestamp: now,
          traceId: "trace-import",
        });
      }
      const created: MockImport = {
        id: `imp-${++idCounter}`,
        fileName: "questions.csv",
        importedAt: now,
        questionCount: 2,
        importedByName: "Asha Perera",
      };
      state.imports.unshift(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }

    // Anything else (app-shell extras): a plain 404 the app already handles.
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked."));
  });

  return {
    state,
    requests,
    find: (method, pathPart) => requests.filter((item) => item.method === method && item.path.includes(pathPart)),
  };
}

export const bankUrl = (portal: "tenant-admin" | "teacher", moduleId = MODULE_ID) =>
  `/${portal}/courses/${COURSE_ID}/modules/${moduleId}/questions`;
