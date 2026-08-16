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
  createdAt: string;
  updatedAt: string;
}

interface LessonRecord {
  id: string;
  moduleId: string;
  title: string;
  sequence: number;
  createdAt: string;
  updatedAt: string;
}

interface MockState {
  modules: ModuleRecord[];
  lessonsByModule: Map<string, LessonRecord[]>;
  patchCalls: Array<{ kind: "module" | "lesson"; id: string; body: { title: string; sequence: number } }>;
}

interface MockOptions {
  /**
   * When set, the *first* PATCH call to this module id fails with a 409
   * (simulating step 2 or 3 of the reorder dance colliding), instead of
   * being applied — used to prove the UI surfaces a single error and still
   * refetches the list afterward.
   */
  failFirstModulePatch?: { moduleId: string; status: number; code: string; message: string };
}

function nowIso(): string {
  return new Date().toISOString();
}

function makeModule(id: string, title: string, sequence: number): ModuleRecord {
  return { id, courseId: COURSE_ID, title, sequence, createdAt: nowIso(), updatedAt: nowIso() };
}

function makeLesson(id: string, moduleId: string, title: string, sequence: number): LessonRecord {
  return { id, moduleId, title, sequence, createdAt: nowIso(), updatedAt: nowIso() };
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
    lessons?: Array<{ id: string; title: string; sequence: number }>;
  }>,
  options: MockOptions = {}
): Promise<MockState> {
  const token = fakeJwt({ role: "TEACHER" });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));

  const state: MockState = {
    modules: initialModules.map((m) => makeModule(m.id, m.title, m.sequence)),
    lessonsByModule: new Map(
      initialModules.map((m) => [
        m.id,
        (m.lessons ?? []).map((l) => makeLesson(l.id, m.id, l.title, l.sequence)),
      ])
    ),
    patchCalls: [],
  };

  const failedOnce = new Set<string>();

  await page.route("**/api/v1/courses/**", async (route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname;

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
        const body = request.postDataJSON() as { title: string; sequence: number };
        state.patchCalls.push({ kind: "module", id: moduleId, body });

        if (
          options.failFirstModulePatch?.moduleId === moduleId &&
          !failedOnce.has(moduleId)
        ) {
          failedOnce.add(moduleId);
          const fail = options.failFirstModulePatch;
          await route.fulfill({
            status: fail.status,
            contentType: "application/json",
            body: JSON.stringify(apiError(fail.code, fail.message)),
          });
          return;
        }

        const target = state.modules.find((m) => m.id === moduleId);
        if (target) {
          target.title = body.title;
          target.sequence = body.sequence;
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
        const body = request.postDataJSON() as { title: string; sequence: number };
        const created = makeLesson(`lesson-new-${lessons.length + 1}`, moduleId, body.title, body.sequence);
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
        const body = request.postDataJSON() as { title: string; sequence: number };
        state.patchCalls.push({ kind: "lesson", id: lessonId, body });
        const target = lessons.find((l) => l.id === lessonId);
        if (target) {
          target.title = body.title;
          target.sequence = body.sequence;
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

test.describe("module & lesson editor — loading, empty, and sorting", () => {
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

  test("shows contextual empty-state copy for a module with zero lessons, naming the module", async ({
    page,
  }) => {
    await setupCourseMocks(page, [{ id: "module-1", title: "Cell Biology", sequence: 1, lessons: [] }]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await expect(page.getByText("No lessons yet")).toBeVisible();
    await expect(page.getByText('Add the first lesson to "Cell Biology"', { exact: false })).toBeVisible();
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

test.describe("module & lesson editor — add and rename", () => {
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
    expect(patch?.body).toEqual({ title: "Cell Biology Basics", sequence: 1 });
  });

  test("adding a lesson computes its sequence within that module only", async ({ page }) => {
    const state = await setupCourseMocks(page, [
      {
        id: "module-a",
        title: "Cell Biology",
        sequence: 1,
        lessons: [{ id: "lesson-1", title: "What is a cell?", sequence: 1 }],
      },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    // Scoped for the same reason as the "Add module" test above — the
    // existing lesson row reuses the same "Lesson title" label.
    const addLessonSection = page.getByRole("heading", { name: "Add lesson" }).locator("..");
    await addLessonSection.getByLabel("Lesson title").fill("Cell membranes");
    await addLessonSection.getByRole("button", { name: "Add lesson" }).click();

    expect(await titleInputValues(page, "Lesson title")).toContain("Cell membranes");
    const lessons = state.lessonsByModule.get("module-a") ?? [];
    expect(lessons.find((l) => l.title === "Cell membranes")?.sequence).toBe(2);
  });
});

test.describe("module & lesson editor — safe reorder dance", () => {
  test("moving a module down performs the 3-step temp-sequence dance, not a direct 2-call swap", async ({
    page,
  }) => {
    const state = await setupCourseMocks(page, [
      { id: "module-a", title: "Cell Biology", sequence: 1 },
      { id: "module-b", title: "Ecology", sequence: 2 },
    ]);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);

    await page.getByRole("button", { name: 'Move "Cell Biology" down' }).click();

    // After the whole dance + refetch, the visible order has swapped.
    const titleInputs = page.getByLabel("Module title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Ecology");
    await expect(titleInputs.nth(1)).toHaveValue("Cell Biology");

    // Exactly 3 sequential PATCH calls: mover -> temp, displaced -> mover's
    // old slot, mover -> displaced's old slot. Never a 2-call direct swap
    // (which would 409 against the still-held target sequence).
    const moduleCalls = state.patchCalls.filter((c) => c.kind === "module");
    expect(moduleCalls).toHaveLength(3);
    expect(moduleCalls[0]).toEqual({
      kind: "module",
      id: "module-a",
      body: { title: "Cell Biology", sequence: 1002 },
    });
    expect(moduleCalls[1]).toEqual({
      kind: "module",
      id: "module-b",
      body: { title: "Ecology", sequence: 1 },
    });
    expect(moduleCalls[2]).toEqual({
      kind: "module",
      id: "module-a",
      body: { title: "Cell Biology", sequence: 2 },
    });
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

  test("a failed reorder step surfaces a single error and still refetches the list", async ({ page }) => {
    await setupCourseMocks(
      page,
      [
        { id: "module-a", title: "Cell Biology", sequence: 1 },
        { id: "module-b", title: "Ecology", sequence: 2 },
      ],
      {
        failFirstModulePatch: {
          moduleId: "module-b",
          status: 409,
          code: "CONFLICT",
          message: "Sequence already in use for this course.",
        },
      }
    );

    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await page.getByRole("button", { name: 'Move "Cell Biology" down' }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "Sequence already in use for this course." })
    ).toBeVisible();

    // Step 1 (park the mover on a temp sequence) already succeeded before
    // step 2 (the 409 above) failed, so the refetched list reflects that
    // real, transiently "off by one" server state — "Cell Biology" is now
    // parked on the highest sequence and sorts last. The important behavior
    // under test is that the UI reflects real server state (not stale local
    // state) and re-enables controls instead of leaving them stuck disabled.
    const titleInputs = page.getByLabel("Module title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Ecology");
    await expect(titleInputs.nth(1)).toHaveValue("Cell Biology");
    await expect(page.getByRole("button", { name: 'Move "Cell Biology" up' })).toBeEnabled();
    await expect(page.getByRole("button", { name: 'Move "Cell Biology" down' })).toBeDisabled();
  });
});

test.describe("module & lesson editor — delete", () => {
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

test.describe("module & lesson editor — errors and permissions", () => {
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

test.describe("module & lesson editor — navigation", () => {
  test("has a Back to course link to the edit page", async ({ page }) => {
    await setupCourseMocks(page, []);
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    await expect(page.getByRole("link", { name: "Back to course" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSE_ID}/edit`
    );
  });
});
