import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Teacher "Module & Lesson Editor" (`teacher/courses/[courseId]/modules`).
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every scenario below drives the UI against an in-memory,
 * stateful mock of `/api/v1/courses/**` registered via `page.route`, mirroring
 * the pattern already used for `/v1/auth/**` in `token-refresh.spec.ts`. A
 * single catch-all route dispatches on method + parsed pathname so GET reads
 * reflect prior POST/PATCH/DELETE calls within the same test — this is what
 * makes the reorder-then-refetch flow (module-lesson-editor.tsx) actually
 * observable end-to-end rather than just asserting a request was fired.
 */

const COURSE_ID = "c0000000-0000-0000-0000-000000000001";

interface ModuleRecord {
  id: string;
  courseId: string;
  title: string;
  sequence: number;
  price: string | null;
  createdAt: string;
  updatedAt: string;
}

interface LessonRecord {
  id: string;
  moduleId: string;
  title: string;
  sequence: number;
  startsAt: string | null;
  endsAt: string | null;
  resourceType: string | null;
  paymentType: "COVERED_BY_CLASS_FEES" | "PAID" | "FREE";
  price: string | null;
  description: string | null;
  lessonLabel: string | null;
  billingYear: number | null;
  billingMonth: number | null;
  expired: boolean;
  createdAt: string;
  updatedAt: string;
}

type PatchBody = { title: string; sequence: number; [key: string]: unknown };

interface MockState {
  modules: ModuleRecord[];
  lessonsByModule: Map<string, LessonRecord[]>;
  patchCalls: Array<{ kind: "module" | "lesson"; id: string; body: PatchBody }>;
  moduleOrderCalls: string[][];
  lessonOrderCalls: Array<{ moduleId: string; lessonIds: string[] }>;
}

interface MockOptions {
  failModuleReorder?: { status: number; code: string; message: string };
}

function nowIso(): string {
  return new Date().toISOString();
}

function makeModule(id: string, title: string, sequence: number, price: string | null = null): ModuleRecord {
  return { id, courseId: COURSE_ID, title, sequence, price, createdAt: nowIso(), updatedAt: nowIso() };
}

function makeLesson(id: string, moduleId: string, title: string, sequence: number): LessonRecord {
  return {
    id,
    moduleId,
    title,
    sequence,
    startsAt: null,
    endsAt: null,
    resourceType: null,
    paymentType: "COVERED_BY_CLASS_FEES",
    price: null,
    description: null,
    lessonLabel: null,
    billingYear: null,
    billingMonth: null,
    expired: false,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
}

/**
 * Playwright's `Page` has no `getByDisplayValue` (that's a Testing Library
 * API) — controlled-input values must be read via `inputValue()` on each
 * matched locator instead.
 */
async function titleInputValues(page: Page, label: string): Promise<string[]> {
  const inputs = page.getByLabel(label, { exact: true });
  const count = await inputs.count();
  const values: string[] = [];
  for (let i = 0; i < count; i += 1) {
    values.push(await inputs.nth(i).inputValue());
  }
  return values;
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
    pricingModel: "ONE_TIME",
    archivedAt: null,
    resolvedAmount: 1999,
    currency: "USD",
    requiresManualQuote: false,
    registrationEnabled: true,
    enrollmentFee: null,
    coverImageUrl: null,
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    paymentValidDays: null,
    commissionRate: null,
    teachers: [{ id: "teacher-1", name: "Teacher One", primary: true }],
    tags: [],
    linkedCourses: [],
    displayOrder: null,
    priceDisplay: { amount: 1999, currency: "USD", period: null },
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
}

/**
 * Registers the auth-refresh mock plus a single stateful mock for every
 * `/api/v1/courses/**` call this page makes, seeded with `initialModules`
 * (each optionally carrying its own lessons). Returns the mutable state
 * object so individual tests can assert on captured PATCH bodies/order.
 */
async function setupCourseMocks(
  page: Page,
  initialModules: Array<{
    id: string;
    title: string;
    sequence: number;
    price?: string | null;
    lessons?: Array<{ id: string; title: string; sequence: number } & Partial<LessonRecord>>;
  }>,
  options: MockOptions = {}
): Promise<MockState> {
  const token = fakeJwt({ role: "TEACHER" });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));

  const state: MockState = {
    modules: initialModules.map((m) => makeModule(m.id, m.title, m.sequence, m.price ?? null)),
    lessonsByModule: new Map(
      initialModules.map((m) => [
        m.id,
        (m.lessons ?? []).map((l) => ({ ...makeLesson(l.id, m.id, l.title, l.sequence), ...l, moduleId: m.id })),
      ])
    ),
    patchCalls: [],
    moduleOrderCalls: [],
    lessonOrderCalls: [],
  };

  await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess([]));
  await page.route("**/api/v1/courses/*/modules/*/lessons/*/materials", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
  });
  await page.route("**/api/v1/courses/*/lessons/*/media", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
  });

  await page.route("**/api/v1/courses/**", async (route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname;

    if (path.endsWith("/api/v1/courses/lesson-resource-types") && method === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess([
          { type: "DOCUMENT", label: "Document", support: "INTEGRATED", help: "Choose a document.", liveClass: false },
          { type: "SECURE_VIDEO", label: "Player Video", support: "INTEGRATED", help: "Choose a ready video.", liveClass: false },
          { type: "IMAGE", label: "Image", support: "INTEGRATED", help: "Choose an image.", liveClass: false },
          { type: "VIDEO_LINK", label: "Video Link", support: "LINK_ONLY", help: "Paste an https link.", liveClass: false },
          { type: "ZOOM_MEETING", label: "Zoom Meeting", support: "INTEGRATED", help: "Zoom integration required.", liveClass: true },
          { type: "ZOOM_RECORDING", label: "Zoom Recording", support: "UNSUPPORTED", help: "Not available yet.", liveClass: false },
        ])),
      });
      return;
    }

    // Handles the plain course-detail fetch (`useCourse`) too — this
    // catch-all is registered once per test and is the only mock for
    // anything under `/api/v1/courses/**`, course detail included.
    const courseMatch = path.match(/\/api\/v1\/courses\/[^/]+$/);
    if (courseMatch && method === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(courseResponseBody())),
      });
      return;
    }

    const moduleOrderMatch = path.match(/\/api\/v1\/courses\/[^/]+\/modules\/order$/);
    if (moduleOrderMatch && method === "PUT") {
      const body = request.postDataJSON() as { moduleIds: string[] };
      state.moduleOrderCalls.push(body.moduleIds);
      if (options.failModuleReorder) {
        await route.fulfill({
          status: options.failModuleReorder.status,
          contentType: "application/json",
          body: JSON.stringify(apiError(options.failModuleReorder.code, options.failModuleReorder.message)),
        });
        return;
      }
      state.modules = body.moduleIds.map((id, index) => {
        const item = state.modules.find((module) => module.id === id)!;
        return { ...item, sequence: index + 1, updatedAt: nowIso() };
      });
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(state.modules)) });
      return;
    }

    const modulesMatch = path.match(/\/api\/v1\/courses\/[^/]+\/modules$/);
    if (modulesMatch) {
      if (method === "GET") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(state.modules)),
        });
        return;
      }
      if (method === "POST") {
        const body = request.postDataJSON() as { title: string; sequence: number };
        const created = makeModule(`module-new-${state.modules.length + 1}`, body.title, body.sequence);
        state.modules.push(created);
        state.lessonsByModule.set(created.id, []);
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(created)),
        });
        return;
      }
    }

    const moduleItemMatch = path.match(/\/api\/v1\/courses\/[^/]+\/modules\/([^/]+)$/);
    if (moduleItemMatch) {
      const moduleId = moduleItemMatch[1];
      if (method === "PATCH") {
        const body = request.postDataJSON() as PatchBody;
        state.patchCalls.push({ kind: "module", id: moduleId, body });

        const target = state.modules.find((m) => m.id === moduleId);
        if (target) {
          target.title = body.title;
          target.sequence = body.sequence;
          target.price = typeof body.price === "string" ? body.price : null;
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
        state.modules = state.modules.filter((m) => m.id !== moduleId);
        state.lessonsByModule.delete(moduleId);
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(null)),
        });
        return;
      }
    }

    const lessonOrderMatch = path.match(/\/api\/v1\/courses\/[^/]+\/modules\/([^/]+)\/lessons\/order$/);
    if (lessonOrderMatch && method === "PUT") {
      const moduleId = lessonOrderMatch[1];
      const { lessonIds } = request.postDataJSON() as { lessonIds: string[] };
      state.lessonOrderCalls.push({ moduleId, lessonIds });
      const lessons = state.lessonsByModule.get(moduleId) ?? [];
      const ordered = lessonIds.map((id, index) => ({ ...lessons.find((lesson) => lesson.id === id)!, sequence: index + 1 }));
      state.lessonsByModule.set(moduleId, ordered);
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(ordered)) });
      return;
    }

    const lessonsMatch = path.match(/\/api\/v1\/courses\/[^/]+\/modules\/([^/]+)\/lessons$/);
    if (lessonsMatch) {
      const moduleId = lessonsMatch[1];
      const lessons = state.lessonsByModule.get(moduleId) ?? [];
      if (method === "GET") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(lessons)),
        });
        return;
      }
      if (method === "POST") {
        const body = request.postDataJSON() as PatchBody;
        const created = { ...makeLesson(`lesson-new-${lessons.length + 1}`, moduleId, body.title, body.sequence), ...body } as LessonRecord;
        lessons.push(created);
        state.lessonsByModule.set(moduleId, lessons);
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(created)),
        });
        return;
      }
    }

    const lessonItemMatch = path.match(/\/api\/v1\/courses\/[^/]+\/modules\/([^/]+)\/lessons\/([^/]+)$/);
    if (lessonItemMatch) {
      const moduleId = lessonItemMatch[1];
      const lessonId = lessonItemMatch[2];
      const lessons = state.lessonsByModule.get(moduleId) ?? [];
      if (method === "PATCH") {
        const body = request.postDataJSON() as PatchBody;
        state.patchCalls.push({ kind: "lesson", id: lessonId, body });
        const target = lessons.find((l) => l.id === lessonId);
        if (target) Object.assign(target, body, { updatedAt: nowIso() });
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(target)),
        });
        return;
      }
      if (method === "DELETE") {
        state.lessonsByModule.set(
          moduleId,
          lessons.filter((l) => l.id !== lessonId)
        );
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(null)),
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

test.describe("module & session editor — loading, empty, and sorting", () => {
  test("shows a loading state before modules resolve", async ({ page }) => {
    const token = fakeJwt({ role: "TEACHER" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}`, 200, apiSuccess(courseResponseBody()));
    await page.route(`**/api/v1/courses/${COURSE_ID}/modules`, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 400));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess([])),
      });
    });

    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await expect(page.getByRole("status").filter({ hasText: "Loading modules" })).toBeVisible();
  });

  test("shows contextual empty-state copy with zero modules, distinct from the generic default", async ({
    page,
  }) => {
    await setupCourseMocks(page, []);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await expect(page.getByText("No modules yet")).toBeVisible();
    await expect(
      page.getByText("Add your first module to start structuring this course", { exact: false })
    ).toBeVisible();
    await expect(page.getByLabel("Module title")).toBeVisible();
  });

  test("shows contextual empty-state copy for a module with zero sessions, naming the module", async ({
    page,
  }) => {
    await setupCourseMocks(page, [{ id: "module-1", title: "Cell Biology", sequence: 1, lessons: [] }]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await expect(page.getByText("No sessions yet")).toBeVisible();
    await expect(page.getByText('Add the first session to "Cell Biology"', { exact: false })).toBeVisible();
  });

  test("renders modules sorted by sequence even though the backend returns them unordered", async ({
    page,
  }) => {
    await setupCourseMocks(page, [
      { id: "module-c", title: "Genetics", sequence: 3 },
      { id: "module-a", title: "Cell Biology", sequence: 1 },
      { id: "module-b", title: "Ecology", sequence: 2 },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    const titleInputs = page.getByLabel("Module title", { exact: true });
    await expect(titleInputs).toHaveCount(4); // 3 modules + the "Add module" field
    await expect(titleInputs.nth(0)).toHaveValue("Cell Biology");
    await expect(titleInputs.nth(1)).toHaveValue("Ecology");
    await expect(titleInputs.nth(2)).toHaveValue("Genetics");
  });
});

test.describe("module & session editor — add and rename", () => {
  test("adding a module computes sequence as (max existing + 1), never a user-entered value", async ({
    page,
  }) => {
    const state = await setupCourseMocks(page, [
      { id: "module-a", title: "Cell Biology", sequence: 1 },
      { id: "module-b", title: "Ecology", sequence: 5 },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    // Scoped to the "Add module" section's own container — `getByLabel("Module
    // title")` alone would be ambiguous once existing modules (which reuse the
    // same visible label for their rename fields) are on the page.
    const addModuleSection = page.getByRole("heading", { name: "Add module" }).locator("..");
    await addModuleSection.getByLabel("Module title").fill("Genetics");
    await addModuleSection.getByRole("button", { name: "Add module" }).click();

    expect(await titleInputValues(page, "Module title")).toContain("Genetics");
    expect(state.modules.find((m) => m.title === "Genetics")?.sequence).toBe(6);
  });

  test("renaming a module keeps its sequence unchanged and disables Save until edited", async ({ page }) => {
    const state = await setupCourseMocks(page, [{ id: "module-a", title: "Cell Biology", sequence: 1 }]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    const titleInput = page.getByLabel("Module title", { exact: true }).first();
    const saveButton = page.getByRole("button", { name: "Save" });
    await expect(saveButton).toBeDisabled();

    await titleInput.fill("Cell Biology Basics");
    await expect(saveButton).toBeEnabled();
    await saveButton.click();

    await expect(titleInput).toHaveValue("Cell Biology Basics");
    const patch = state.patchCalls.find((c) => c.kind === "module" && c.id === "module-a");
    expect(patch?.body).toEqual({ title: "Cell Biology Basics", sequence: 1, price: null });
  });

  test("adding a session uses the full form and computes sequence within that module", async ({ page }) => {
    const state = await setupCourseMocks(page, [
      {
        id: "module-a",
        title: "Cell Biology",
        sequence: 1,
        lessons: [{ id: "lesson-1", title: "What is a cell?", sequence: 1 }],
      },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await page.getByRole("button", { name: "Add session" }).click();
    const dialog = page.getByTestId("session-form-dialog");
    await dialog.getByTestId("session-form-name").fill("Cell membranes");
    await dialog.getByTestId("session-form-from-date").fill("2026-10-17");
    await dialog.getByTestId("session-form-from-time").fill("10:00");
    await dialog.getByTestId("session-form-resource-type").selectOption("VIDEO_LINK");
    await dialog.getByTestId("session-form-payment-type").selectOption("FREE");
    await dialog.getByTestId("session-form-submit").click();

    await expect(dialog).toBeHidden();
    expect(await titleInputValues(page, "Session title")).toContain("Cell membranes");
    const sessions = state.lessonsByModule.get("module-a") ?? [];
    expect(sessions.find((item) => item.title === "Cell membranes")?.sequence).toBe(2);
  });
});

test.describe("module & session editor — metadata preservation", () => {
  test("inline session rename retains availability, payment and billing metadata", async ({ page }) => {
    const state = await setupCourseMocks(page, [{
      id: "module-a", title: "Cell Biology", sequence: 1,
      lessons: [{
        id: "lesson-1", title: "Paid class", sequence: 1,
        startsAt: "2026-10-07T10:00:23.123Z", endsAt: "2026-10-31T17:59:45.678Z",
        resourceType: "VIDEO_LINK", paymentType: "PAID", price: "245.50",
        description: "Existing description", lessonLabel: "Unit 5", billingYear: 2026, billingMonth: 10,
      }],
    }]);
    const original = { ...state.lessonsByModule.get("module-a")![0] };
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    const sessionInput = page.getByLabel("Session title", { exact: true });
    await sessionInput.fill("Renamed class");
    await sessionInput.locator("xpath=ancestor::form").getByRole("button", { name: "Save", exact: true }).click();
    await expect(sessionInput).toHaveValue("Renamed class");
    expect(state.patchCalls.find((call) => call.kind === "lesson")?.body).toEqual({
      title: "Renamed class", sequence: 1,
      startsAt: original.startsAt, endsAt: original.endsAt,
      resourceType: original.resourceType, paymentType: original.paymentType, price: original.price,
      description: original.description, lessonLabel: original.lessonLabel,
      billingYear: original.billingYear, billingMonth: original.billingMonth,
    });
  });

  test("session order uses PUT and does not replace metadata; module rename retains price", async ({ page }) => {
    const state = await setupCourseMocks(page, [{
      id: "module-a", title: "Priced module", sequence: 1, price: "1200.00",
      lessons: [
        { id: "lesson-1", title: "First", sequence: 1, startsAt: "2026-10-07T10:00:00Z", resourceType: "VIDEO_LINK", paymentType: "PAID", price: "100.00" },
        { id: "lesson-2", title: "Second", sequence: 2, endsAt: "2026-12-31T23:59:00Z" },
      ],
    }]);
    const original = state.lessonsByModule.get("module-a")!.map((item) => ({ ...item }));
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await page.getByRole("button", { name: 'Move "First" down' }).click();
    await expect(page.getByLabel("Session title", { exact: true }).first()).toHaveValue("Second");
    expect(state.lessonOrderCalls).toEqual([{ moduleId: "module-a", lessonIds: ["lesson-2", "lesson-1"] }]);
    expect(state.patchCalls).toHaveLength(0);
    for (const item of original) {
      const stored = state.lessonsByModule.get("module-a")!.find((candidate) => candidate.id === item.id)!;
      expect({ ...stored, sequence: item.sequence }).toEqual(item);
    }
    const input = page.getByLabel("Module title", { exact: true }).first();
    await input.fill("Renamed priced module");
    await input.locator("xpath=ancestor::form").getByRole("button", { name: "Save", exact: true }).click();
    await expect.poll(() => state.patchCalls.length).toBe(1);
    expect(state.patchCalls[0].body).toEqual({ title: "Renamed priced module", sequence: 1, price: "1200.00" });
  });
});

test.describe("module & session editor — atomic reorder", () => {
  test("moving a module down uses the dedicated order endpoint", async ({ page }) => {
    const state = await setupCourseMocks(page, [
      { id: "module-a", title: "Cell Biology", sequence: 1 },
      { id: "module-b", title: "Ecology", sequence: 2 },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await page.getByRole("button", { name: 'Move "Cell Biology" down' }).click();

    const titleInputs = page.getByLabel("Module title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Ecology");
    await expect(titleInputs.nth(1)).toHaveValue("Cell Biology");
    expect(state.moduleOrderCalls).toEqual([["module-b", "module-a"]]);
    expect(state.patchCalls.filter((call) => call.kind === "module")).toHaveLength(0);
  });

  test("Move Up is disabled on the first item and Move Down is disabled on the last item", async ({
    page,
  }) => {
    await setupCourseMocks(page, [
      { id: "module-a", title: "Cell Biology", sequence: 1 },
      { id: "module-b", title: "Ecology", sequence: 2 },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await expect(page.getByRole("button", { name: 'Move "Cell Biology" up' })).toBeDisabled();
    await expect(page.getByRole("button", { name: 'Move "Ecology" down' })).toBeDisabled();
    await expect(page.getByRole("button", { name: 'Move "Cell Biology" down' })).toBeEnabled();
    await expect(page.getByRole("button", { name: 'Move "Ecology" up' })).toBeEnabled();
  });

  test("move controls are fully keyboard-operable", async ({ page }) => {
    await setupCourseMocks(page, [
      { id: "module-a", title: "Cell Biology", sequence: 1 },
      { id: "module-b", title: "Ecology", sequence: 2 },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    const moveDown = page.getByRole("button", { name: 'Move "Cell Biology" down' });
    await moveDown.focus();
    await expect(moveDown).toBeFocused();
    await page.keyboard.press("Enter");

    const titleInputs = page.getByLabel("Module title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Ecology");
  });

  test("a failed atomic reorder surfaces one error and keeps server order", async ({ page }) => {
    await setupCourseMocks(
      page,
      [
        { id: "module-a", title: "Cell Biology", sequence: 1 },
        { id: "module-b", title: "Ecology", sequence: 2 },
      ],
      {
        failModuleReorder: {
          status: 409,
          code: "CONFLICT",
          message: "Module order could not be saved.",
        },
      }
    );

    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await page.getByRole("button", { name: 'Move "Cell Biology" down' }).click();

    await expect(page.getByRole("alert").filter({ hasText: "Module order could not be saved." })).toBeVisible();
    const titleInputs = page.getByLabel("Module title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Cell Biology");
    await expect(titleInputs.nth(1)).toHaveValue("Ecology");
    await expect(page.getByRole("button", { name: 'Move "Cell Biology" down' })).toBeEnabled();
  });
});

test.describe("module & session editor — delete", () => {
  test("delete requires confirmation naming the module, and removes it on confirm", async ({ page }) => {
    await setupCourseMocks(page, [{ id: "module-a", title: "Cell Biology", sequence: 1 }]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await page.getByRole("button", { name: 'Delete module "Cell Biology"' }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("Cell Biology")).toBeVisible();

    await dialog.getByRole("button", { name: "Delete module" }).click();
    await expect(dialog).not.toBeVisible();
    await expect(page.getByText("No modules yet")).toBeVisible();
  });

  test("cancelling the delete dialog leaves the module in place", async ({ page }) => {
    await setupCourseMocks(page, [{ id: "module-a", title: "Cell Biology", sequence: 1 }]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await page.getByRole("button", { name: 'Delete module "Cell Biology"' }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Cancel" }).click();

    await expect(page.getByRole("alertdialog")).not.toBeVisible();
    await expect(page.getByLabel("Module title", { exact: true }).first()).toHaveValue("Cell Biology");
  });
});

test.describe("module & session editor — errors and permissions", () => {
  test("a permission-denied (403) response on the modules fetch renders the shared permission-denied state", async ({
    page,
  }) => {
    const token = fakeJwt({ role: "TEACHER" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}`, 200, apiSuccess(courseResponseBody()));
    await mockJson(
      page,
      `**/api/v1/courses/${COURSE_ID}/modules`,
      403,
      apiError("FORBIDDEN", "You do not have access to this course's modules.")
    );

    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to your dashboard" })).toBeVisible();
  });

  test("a generic server error on the modules fetch renders the shared error state with retry", async ({
    page,
  }) => {
    const token = fakeJwt({ role: "TEACHER" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}`, 200, apiSuccess(courseResponseBody()));
    await mockJson(
      page,
      `**/api/v1/courses/${COURSE_ID}/modules`,
      500,
      apiError("INTERNAL_ERROR", "Something went wrong on our end.")
    );

    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await expect(page.getByRole("alert").filter({ hasText: "Something went wrong on our end." })).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });
});

test.describe("module & session editor — navigation", () => {
  test("has a Back to course link to the edit page", async ({ page }) => {
    await setupCourseMocks(page, []);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await expect(page.getByRole("link", { name: "Back to course" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSE_ID}/edit`
    );
  });
});
