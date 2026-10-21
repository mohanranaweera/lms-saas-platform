import { expect, type Locator, type Page } from "@playwright/test";

/**
 * Course row actions (R2 / G5) live in an accessible overflow menu: trigger
 * `course-actions-menu` (aria-label "Actions for <name>") and a PORTALED popup
 * `course-actions-menu-content` (role="menu"), so items must be looked up on
 * the page, not inside the row.
 */
export async function openCourseActions(page: Page, row: Locator): Promise<Locator> {
  await row.getByTestId("course-actions-menu").click();
  const menu = page.getByTestId("course-actions-menu-content");
  await expect(menu).toBeVisible();
  return menu;
}

/** Opens the row menu and activates the item with the given `course-action-*` test id. */
export async function courseAction(page: Page, row: Locator, testId: string): Promise<void> {
  const menu = await openCourseActions(page, row);
  await menu.getByTestId(testId).click();
}
