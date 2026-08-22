import { test, expect, type Page } from "@playwright/test";
import {
  COURSE_ID,
  LESSON_ID,
  MODULE_ID,
  makeMaterial,
  setupTeacherMaterialsMocks,
  teacherModulesUrl,
} from "./fixtures/materials-mocks";

/**
 * Teacher Materials Manager row — visibility `Select`
 * (`components/courses/material-row.tsx`): fires `PATCH` immediately on
 * change, carrying the row's current `title` + `sequence` alongside the new
 * `visibility` (the endpoint is a full-resource replace), wires
 * `visibilityMutation.isPending` into both the row's shared `disabled` state
 * and the trigger's own `aria-busy`, and persists across a refetch.
 *
 * No real backend runs in this environment — every scenario drives the UI
 * against the stateful `page.route` mock in `fixtures/materials-mocks.ts`,
 * following `course-modules.spec.ts`'s established pattern.
 */

const MATERIAL_ITEM_PATH = `**/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials/*`;

function twoMaterials() {
  return [
    makeMaterial({ id: "material-1", title: "Lecture slides", sequence: 1, visibility: "VISIBLE" }),
    makeMaterial({ id: "material-2", title: "Handout", sequence: 2, visibility: "VISIBLE" }),
  ];
}

/**
 * The visibility `Select`'s own `<Label>` text is
 * `Visibility for &ldquo;{title}&rdquo;` (typographic curly quotes, unlike
 * the Move/Delete buttons' plain straight-quote aria-labels) — matched here
 * via an unanchored regex with `.` standing in for the curly-quote
 * characters rather than typing the literal Unicode glyphs into the test
 * source, per `getByLabel`'s default substring/regex matching.
 */
function visibilityTrigger(page: Page, title: string) {
  return page.getByLabel(new RegExp(`Visibility for .${title}.`));
}

/**
 * Scopes to a single material row via the nearest `<li>` ancestor of the
 * `id` `TitleInlineForm` assigns its own title input
 * (`material-${material.id}-title`, deterministic and stable across a
 * rename). `page.locator("li").filter({ has: ... })` was tried first and
 * rejected: `MaterialsSection`'s lesson item is itself an outer `<li>`
 * wrapping the whole materials `<ol>`, so that outer `<li>` also "has" every
 * material's title input as a descendant and gets matched alongside the
 * intended single-material `<li>` — `xpath=ancestor::li[1]` walks up from
 * the title input to only its nearest (innermost) `<li>` ancestor instead.
 */
function rowFor(page: Page, materialId: string) {
  return page.locator(`#material-${materialId}-title`).locator("xpath=ancestor::li[1]");
}

test.describe("material row — visibility select busy state", () => {
  test("while the visibility PATCH is pending, the row's other controls are disabled (and stay row-scoped)", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    // Gate only the PATCH request, then hand it back to the shared stateful
    // mock via `route.fallback()` — mirrors `material-upload-states.spec.ts`'s
    // manually-released-promise busy-state technique.
    let releasePatch: (() => void) | undefined;
    const patchGate = new Promise<void>((resolve) => {
      releasePatch = resolve;
    });
    await page.route(MATERIAL_ITEM_PATH, async (route) => {
      if (route.request().method() !== "PATCH") {
        await route.fallback();
        return;
      }
      await patchGate;
      await route.fallback();
    });

    await visibilityTrigger(page, "Lecture slides").click();
    await page.getByRole("option", { name: "Hidden" }).click();

    const materialOneRow = rowFor(page, "material-1");
    const materialTwoRow = rowFor(page, "material-2");

    await expect(visibilityTrigger(page, "Lecture slides")).toHaveAttribute("aria-busy", "true");
    await expect(materialOneRow.getByLabel("Material title", { exact: true })).toBeDisabled();
    await expect(materialOneRow.getByRole("button", { name: "Save" })).toBeDisabled();
    await expect(materialOneRow.getByRole("button", { name: "View" })).toBeDisabled();
    await expect(materialOneRow.getByRole("button", { name: 'Move "Lecture slides" down' })).toBeDisabled();
    await expect(
      materialOneRow.getByRole("button", { name: 'Delete material "Lecture slides"' })
    ).toBeDisabled();

    // The busy state is scoped to this row's own mutation instance, not a
    // page-wide lock — the other material's controls stay enabled.
    await expect(materialTwoRow.getByRole("button", { name: 'Move "Handout" up' })).toBeEnabled();

    releasePatch?.();

    await expect(visibilityTrigger(page, "Lecture slides")).not.toHaveAttribute("aria-busy", "true");
    await expect(materialOneRow.getByRole("button", { name: 'Move "Lecture slides" down' })).toBeEnabled();
  });
});

test.describe("material row — visibility persistence", () => {
  test("changing VISIBLE -> HIDDEN PATCHes {title, sequence, visibility} with title/sequence unchanged, and persists across reload", async ({
    page,
  }) => {
    const material = makeMaterial({
      id: "material-1",
      title: "Lecture slides",
      sequence: 3,
      visibility: "VISIBLE",
    });
    const state = await setupTeacherMaterialsMocks(page, [material]);
    await page.goto(teacherModulesUrl());

    await visibilityTrigger(page, "Lecture slides").click();
    await page.getByRole("option", { name: "Hidden" }).click();

    await expect.poll(() => state.patchCalls.length).toBe(1);
    expect(state.patchCalls[0]).toEqual({
      id: "material-1",
      body: { title: "Lecture slides", sequence: 3, visibility: "HIDDEN" },
    });

    // `SelectValue` now maps the raw enum value to its friendly label via a
    // `children` render-prop (mirrors `course-form-fields.tsx`'s status
    // Select), so the trigger shows "Hidden", not the raw "HIDDEN" value.
    await expect(visibilityTrigger(page, "Lecture slides")).toContainText("Hidden");

    // Reload triggers a fresh GET, which now reflects the mutated mock
    // state — proves the change is real backend state, not just local UI
    // optimism.
    await page.reload();
    await expect(visibilityTrigger(page, "Lecture slides")).toContainText("Hidden");
  });
});
