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
 * Teacher Materials Manager row — delete (`AlertDialog`-confirmed, mirroring
 * `course-modules.spec.ts`'s lesson-delete test structure exactly) and
 * inline rename (`TitleInlineForm`, resending the row's current `sequence` +
 * `visibility` alongside the new `title` since `PATCH .../materials/{id}`
 * is a full-resource replace).
 *
 * No real backend runs in this environment — every scenario drives the UI
 * against the stateful `page.route` mock in `fixtures/materials-mocks.ts`.
 */

const MATERIAL_ITEM_PATH = `**/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials/*`;

/**
 * Scopes to a single material row via the nearest `<li>` ancestor of the
 * `id` `TitleInlineForm` assigns its own title input
 * (`material-${material.id}-title`) — this teacher page also renders "Save"
 * buttons for the module and lesson rename forms (`course-modules.spec.ts`'s
 * own subject), so an unscoped `getByRole("button", { name: "Save" })` is
 * ambiguous once a material row is present. `xpath=ancestor::li[1]` (rather
 * than `page.locator("li").filter({ has: ... })`) is required here:
 * `MaterialsSection`'s lesson item is itself an outer `<li>` wrapping the
 * whole materials `<ol>`, so a `has`-based filter also matches that outer
 * `<li>` (which "has" every material's title input as a descendant) — only
 * walking up from the title input to its nearest `<li>` ancestor picks the
 * single intended row.
 */
function rowFor(page: Page, materialId: string) {
  return page.locator(`#material-${materialId}-title`).locator("xpath=ancestor::li[1]");
}

function twoMaterials() {
  return [
    makeMaterial({ id: "material-1", title: "Lecture slides", sequence: 1 }),
    makeMaterial({ id: "material-2", title: "Handout", sequence: 2 }),
  ];
}

test.describe("material row — delete", () => {
  test("delete requires confirmation naming the material, and DELETEs only the confirmed material's id", async ({
    page,
  }) => {
    const state = await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    const deletedIds: string[] = [];
    await page.route(MATERIAL_ITEM_PATH, async (route) => {
      if (route.request().method() === "DELETE") {
        deletedIds.push(new URL(route.request().url()).pathname.split("/").pop()!);
      }
      await route.fallback();
    });

    await page.getByRole("button", { name: 'Delete material "Lecture slides"' }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("Lecture slides")).toBeVisible();

    await dialog.getByRole("button", { name: "Delete material" }).click();
    await expect(dialog).not.toBeVisible();

    await expect(page.getByLabel("Material title", { exact: true })).toHaveCount(1);
    await expect(page.getByLabel("Material title", { exact: true })).toHaveValue("Handout");
    expect(deletedIds).toEqual(["material-1"]);
    expect(state.materials.map((m) => m.id)).toEqual(["material-2"]);
  });

  test("cancelling the delete dialog leaves the material in place and fires no DELETE request", async ({
    page,
  }) => {
    const state = await setupTeacherMaterialsMocks(page, twoMaterials());
    await page.goto(teacherModulesUrl());

    let deleteCalled = false;
    await page.route(MATERIAL_ITEM_PATH, async (route) => {
      if (route.request().method() === "DELETE") deleteCalled = true;
      await route.fallback();
    });

    await page.getByRole("button", { name: 'Delete material "Lecture slides"' }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Cancel" }).click();

    await expect(page.getByRole("alertdialog")).not.toBeVisible();
    await expect(page.getByLabel("Material title", { exact: true })).toHaveCount(2);
    await expect(page.getByLabel("Material title", { exact: true }).first()).toHaveValue("Lecture slides");
    expect(deleteCalled).toBe(false);
    expect(state.materials).toHaveLength(2);
  });
});

test.describe("material row — rename", () => {
  test("submitting a new title PATCHes {title, sequence, visibility} with sequence/visibility unchanged, and the row reflects it without a reload", async ({
    page,
  }) => {
    const material = makeMaterial({
      id: "material-1",
      title: "Lecture slides",
      sequence: 3,
      visibility: "HIDDEN",
    });
    const state = await setupTeacherMaterialsMocks(page, [material]);
    await page.goto(teacherModulesUrl());

    const row = rowFor(page, "material-1");
    const titleInput = row.getByLabel("Material title", { exact: true });
    const saveButton = row.getByRole("button", { name: "Save" });
    await expect(saveButton).toBeDisabled();

    await titleInput.fill("Lecture slides v2");
    await expect(saveButton).toBeEnabled();
    await saveButton.click();

    await expect(titleInput).toHaveValue("Lecture slides v2");
    expect(state.patchCalls).toHaveLength(1);
    expect(state.patchCalls[0]).toEqual({
      id: "material-1",
      body: { title: "Lecture slides v2", sequence: 3, visibility: "HIDDEN" },
    });
  });
});
