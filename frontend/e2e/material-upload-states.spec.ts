import { test, expect, type Page } from "@playwright/test";
import { apiSuccess } from "./fixtures/auth-mocks";
import {
  COURSE_ID,
  LESSON_ID,
  MODULE_ID,
  makeMaterial,
  setupTeacherMaterialsMocks,
  teacherModulesUrl,
} from "./fixtures/materials-mocks";

/**
 * Teacher Materials Manager upload form
 * (`components/courses/material-upload-form.tsx`, nested inside
 * `CourseLessonItem` on `teacher/courses/[courseId]/modules`).
 *
 * No real backend runs in this environment — every scenario drives the UI
 * against the stateful `page.route` mock in `fixtures/materials-mocks.ts`,
 * following `course-modules.spec.ts`'s established pattern.
 */

const MATERIALS_PATH = `**/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`;

/**
 * Playwright's `Page` has no `getByDisplayValue` (that's a Testing Library
 * API) — controlled-input values must be read via `inputValue()` on each
 * matched locator instead. Mirrors `course-modules.spec.ts`'s helper of the
 * same name.
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

async function selectFile(page: Page, filename = "lecture-notes.pdf") {
  await page.locator('input[type="file"]').setInputFiles({
    name: filename,
    mimeType: "application/pdf",
    buffer: Buffer.from("fake pdf content"),
  });
}

/**
 * Scoped to the upload form's own wrapper (`h5 "Upload material"`'s parent)
 * rather than `page.getByRole("alert")` bare — Next.js's dev-mode overlay
 * renders its own unrelated `role="alert"` element on every page in this
 * dev-server-backed test environment, so an unscoped query is not reliable
 * for a "how many alerts are showing" assertion.
 */
function uploadFormAlerts(page: Page) {
  return page.getByRole("heading", { name: "Upload material" }).locator("..").getByRole("alert");
}

test.describe("material upload form — drag-over state", () => {
  test("drag-over toggles the visual border state and the aria-live status text, and reverts on drag-leave", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, []);
    await page.goto(teacherModulesUrl());
    await expect(page.getByRole("heading", { name: "Upload material" })).toBeVisible();

    const dropzone = page.getByText("Drag and drop a file here, or").locator("..");
    const liveStatus = dropzone.locator('[role="status"]');

    await expect(dropzone).not.toHaveClass(/border-primary/);
    await expect(liveStatus).toHaveText("");

    await dropzone.dispatchEvent("dragover");
    await expect(dropzone).toHaveClass(/border-primary/);
    await expect(liveStatus).toHaveText("File ready to drop");

    await dropzone.dispatchEvent("dragleave");
    await expect(dropzone).not.toHaveClass(/border-primary/);
    await expect(liveStatus).toHaveText("");
  });
});

test.describe("material upload form — busy state", () => {
  test("the form is disabled and shows an Uploading… busy indicator while the mocked POST is in flight", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, []);
    await page.goto(teacherModulesUrl());

    // A manually-released gate (rather than a fixed `setTimeout`) makes the
    // "still busy" assertions below deterministic instead of racing a fixed
    // delay against Playwright's own action/assertion overhead.
    let releaseUpload: (() => void) | undefined;
    const uploadGate = new Promise<void>((resolve) => {
      releaseUpload = resolve;
    });
    await page.route(MATERIALS_PATH, async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      await uploadGate;
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess(makeMaterial({ id: "material-x", title: "Lecture notes", sequence: 1 }))
        ),
      });
    });

    await page.getByLabel("Title", { exact: true }).fill("Lecture notes");
    await selectFile(page);
    await page.getByRole("button", { name: "Upload material" }).click();

    await expect(page.getByRole("button", { name: "Uploading…" })).toBeVisible();
    // `toBeDisabled()` doesn't reliably reflect a `<fieldset disabled>`'s own
    // disabled state across browsers/Playwright versions (it's most
    // consistent for BUTTON/INPUT/SELECT/TEXTAREA) — checking the attribute
    // directly is what the task actually specifies (`fieldset[disabled]`).
    await expect(page.locator("fieldset")).toHaveAttribute("disabled", "");
    await expect(page.getByLabel("Title", { exact: true })).toBeDisabled();

    // Release the gate — confirms the busy state is transient and the form
    // re-enables once the mocked POST actually resolves, not stuck disabled.
    releaseUpload?.();
    await expect(page.getByRole("button", { name: "Upload material" })).toBeVisible();
    await expect(page.locator("fieldset")).not.toHaveAttribute("disabled", "");
  });
});

test.describe("material upload form — failures", () => {
  test("a mocked upload failure surfaces inside a role=alert region with the server's message, and moves focus to it", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, [], {
      uploadFailures: [
        { status: 415, code: "UNSUPPORTED_MEDIA_TYPE", message: "xyz-unsupported-media-message" },
      ],
    });
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Title", { exact: true }).fill("Lecture notes");
    await selectFile(page);
    await page.getByRole("button", { name: "Upload material" }).click();

    const alert = uploadFormAlerts(page).filter({ hasText: "xyz-unsupported-media-message" });
    await expect(alert).toBeVisible();
    await expect(alert).toBeFocused();
  });

  test("a second failed attempt replaces rather than stacks the alert", async ({ page }) => {
    await setupTeacherMaterialsMocks(page, [], {
      uploadFailures: [
        { status: 415, code: "UNSUPPORTED_MEDIA_TYPE", message: "xyz-first-failure-message" },
        { status: 413, code: "PAYLOAD_TOO_LARGE", message: "xyz-second-failure-message" },
      ],
    });
    await page.goto(teacherModulesUrl());

    const titleInput = page.getByLabel("Title", { exact: true });
    await titleInput.fill("Lecture notes");
    await selectFile(page, "a.pdf");
    await page.getByRole("button", { name: "Upload material" }).click();
    await expect(uploadFormAlerts(page).filter({ hasText: "xyz-first-failure-message" })).toBeVisible();

    await titleInput.fill("Lecture notes v2");
    await selectFile(page, "b.pdf");
    await page.getByRole("button", { name: "Upload material" }).click();

    await expect(uploadFormAlerts(page).filter({ hasText: "xyz-second-failure-message" })).toBeVisible();
    await expect(uploadFormAlerts(page)).toHaveCount(1);
    await expect(page.getByText("xyz-first-failure-message")).toHaveCount(0);
  });
});

test.describe("material upload form — accessible helper text", () => {
  test("the file field states accepted formats and the maximum size", async ({ page }) => {
    await setupTeacherMaterialsMocks(page, []);
    await page.goto(teacherModulesUrl());

    await expect(
      page.getByText("Accepted formats: PDF, PNG, JPEG, GIF, or plain text (.txt). Maximum size: 25 MB.")
    ).toBeVisible();
  });
});

test.describe("material upload form — success", () => {
  test("a successful mocked upload appends the new material to the list without a full page reload", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, [
      makeMaterial({ id: "material-1", title: "Existing handout", sequence: 1 }),
    ]);
    await page.goto(teacherModulesUrl());
    const urlBeforeUpload = page.url();

    await expect(page.getByLabel("Material title", { exact: true }).first()).toHaveValue(
      "Existing handout"
    );

    await page.getByLabel("Title", { exact: true }).fill("New slides");
    await selectFile(page, "slides.pdf");
    await page.getByRole("button", { name: "Upload material" }).click();

    await expect(page.getByLabel("Material title", { exact: true })).toHaveCount(2);
    const titles = await titleInputValues(page, "Material title");
    expect(titles).toContain("New slides");
    expect(titles).toContain("Existing handout");
    expect(page.url()).toBe(urlBeforeUpload);

    // The upload form clears itself back to empty after success.
    await expect(page.getByLabel("Title", { exact: true })).toHaveValue("");
  });
});
