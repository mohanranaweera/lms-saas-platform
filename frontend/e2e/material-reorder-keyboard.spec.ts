import { test, expect } from "@playwright/test";
import {
  COURSE_ID,
  LESSON_ID,
  MODULE_ID,
  makeMaterial,
  setupTeacherMaterialsMocks,
  teacherModulesUrl,
} from "./fixtures/materials-mocks";

/**
 * Teacher Materials Manager keyboard-driven reorder
 * (`components/courses/material-row.tsx`'s Move Up/Down buttons +
 * `lib/courses/material-reorder.ts`'s 3-step "park on a temp sequence"
 * dance). Mirrors `course-builder-keyboard.spec.ts`'s pure-keyboard-driven
 * pattern (no mouse clicks to drive the move itself) combined with
 * `course-modules.spec.ts`'s stateful-mock pattern (captured PATCH bodies +
 * a real GET refetch reflecting the mutation).
 */

function twoMaterials() {
  return [
    makeMaterial({ id: "material-1", title: "Lecture slides", sequence: 1 }),
    makeMaterial({ id: "material-2", title: "Handout", sequence: 2 }),
  ];
}

test.describe("material reorder — keyboard reachability", () => {
  test("each material row exposes Tab-reachable Move up/down buttons with the real aria-labels", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    const moveUpSlides = page.getByRole("button", { name: 'Move "Lecture slides" up' });
    const moveDownSlides = page.getByRole("button", { name: 'Move "Lecture slides" down' });
    const moveUpHandout = page.getByRole("button", { name: 'Move "Handout" up' });
    const moveDownHandout = page.getByRole("button", { name: 'Move "Handout" down' });

    await moveDownSlides.focus();
    await expect(moveDownSlides).toBeFocused();

    await expect(moveUpSlides).toBeVisible();
    await expect(moveDownHandout).toBeVisible();
    await expect(moveUpHandout).toBeVisible();
  });
});

test.describe("material reorder — the 3-step temp-sequence dance", () => {
  test("pressing Enter on Move down for the first item swaps it with the second, and PATCH bodies carry title + sequence + visibility", async ({
    page,
  }) => {
    const state = await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    const moveDown = page.getByRole("button", { name: 'Move "Lecture slides" down' });
    await moveDown.focus();
    await expect(moveDown).toBeFocused();
    await page.keyboard.press("Enter");

    // After the whole dance + refetch, the visible order has swapped.
    const titleInputs = page.getByLabel("Material title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Handout");
    await expect(titleInputs.nth(1)).toHaveValue("Lecture slides");

    // Exactly 3 sequential PATCH calls, never a 2-call direct swap: mover ->
    // temp sequence, displaced -> mover's old slot, mover -> displaced's old
    // slot. Every call resends the full `MaterialUpdateRequest` shape
    // (title, sequence, visibility) since the endpoint is a full-resource
    // replace, not a sequence-only patch.
    expect(state.patchCalls).toHaveLength(3);
    expect(state.patchCalls[0]).toEqual({
      id: "material-1",
      body: { title: "Lecture slides", sequence: 1002, visibility: "VISIBLE" },
    });
    expect(state.patchCalls[1]).toEqual({
      id: "material-2",
      body: { title: "Handout", sequence: 1, visibility: "VISIBLE" },
    });
    expect(state.patchCalls[2]).toEqual({
      id: "material-1",
      body: { title: "Lecture slides", sequence: 2, visibility: "VISIBLE" },
    });
  });

  test("reorder state persists after a reload, reflecting the mutated mock state via a fresh GET", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    const moveDown = page.getByRole("button", { name: 'Move "Lecture slides" down' });
    await moveDown.focus();
    await page.keyboard.press("Enter");

    const titleInputs = page.getByLabel("Material title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Handout");
    await expect(titleInputs.nth(1)).toHaveValue("Lecture slides");

    await page.reload();

    await expect(titleInputs.nth(0)).toHaveValue("Handout");
    await expect(titleInputs.nth(1)).toHaveValue("Lecture slides");
  });
});

test.describe("material reorder — boundary controls", () => {
  test("the first item's Move up and the last item's Move down remain present and focusable, but disabled", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    const moveUpFirst = page.getByRole("button", { name: 'Move "Lecture slides" up' });
    const moveDownLast = page.getByRole("button", { name: 'Move "Handout" down' });
    const moveDownFirst = page.getByRole("button", { name: 'Move "Lecture slides" down' });
    const moveUpLast = page.getByRole("button", { name: 'Move "Handout" up' });

    await expect(moveUpFirst).toBeVisible();
    await expect(moveUpFirst).toBeDisabled();
    await expect(moveDownLast).toBeVisible();
    await expect(moveDownLast).toBeDisabled();

    await expect(moveDownFirst).toBeEnabled();
    await expect(moveUpLast).toBeEnabled();
  });
});

test.describe("material reorder — upload form disabled while a reorder is in flight", () => {
  test("the upload form's fieldset is disabled for the whole reorder window, and re-enables once it settles", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    // Gate only the first PATCH of the 3-step dance, then hand it back to the
    // shared stateful mock via `route.fallback()` — same manually-released
    // -promise technique `material-upload-states.spec.ts` uses for the
    // upload form's own busy state, applied here to `MaterialsSection`'s
    // `reorderBusyIds`, which `disabled={reorderBusyIds.size > 0}` threads
    // into `MaterialUploadForm`.
    let releasePatch: (() => void) | undefined;
    const patchGate = new Promise<void>((resolve) => {
      releasePatch = resolve;
    });
    let gateConsumed = false;
    await page.route(
      `**/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials/*`,
      async (route) => {
        if (route.request().method() !== "PATCH") {
          await route.fallback();
          return;
        }
        if (!gateConsumed) {
          gateConsumed = true;
          await patchGate;
        }
        await route.fallback();
      }
    );

    await expect(page.locator("fieldset")).not.toHaveAttribute("disabled", "");

    const moveDown = page.getByRole("button", { name: 'Move "Lecture slides" down' });
    await moveDown.focus();
    await page.keyboard.press("Enter");

    await expect(page.locator("fieldset")).toHaveAttribute("disabled", "");

    releasePatch?.();

    await expect(page.locator("fieldset")).not.toHaveAttribute("disabled", "");
    const titleInputs = page.getByLabel("Material title", { exact: true });
    await expect(titleInputs.nth(0)).toHaveValue("Handout");
    await expect(titleInputs.nth(1)).toHaveValue("Lecture slides");
  });
});
