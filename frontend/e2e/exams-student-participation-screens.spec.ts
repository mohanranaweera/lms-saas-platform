import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { EXAM_PREVIOUS, EXAM_RESUME, mockStudentExams } from "./fixtures/student-exam-mocks";

/**
 * Klass Exam Module — Slice C visual evidence (student list, take page,
 * submit dialog, released result) at 1440 / 820 / 390, with a no-horizontal-
 * overflow check at each width. Klass has no student screenshots; this UI is
 * our own design (documented product assumption).
 */

const EVIDENCE_DIR = "../docs/qa/klass-evidence/exams";
const VIEWPORTS = [
  { name: "1440", width: 1440, height: 900 },
  { name: "820", width: 820, height: 1180 },
  { name: "390", width: 390, height: 844 },
] as const;

test.beforeAll(() => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
});

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}

async function capture(page: Page, name: string, fullPage = true) {
  for (const viewport of VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.waitForTimeout(200);
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: `${EVIDENCE_DIR}/slice-c-${name}-${viewport.name}.png`, fullPage });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
}

test.describe("Slice C screenshots", () => {
  test("student exam list", async ({ page }) => {
    await mockStudentExams(page);
    await page.goto("/student/exams");
    await expect(page.getByTestId("student-exams-panel-in-progress").getByTestId("student-exam-card")).toHaveCount(4);
    await capture(page, "student-list");
  });

  test("take page and submit dialog", async ({ page }) => {
    await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_RESUME}/take`);
    await expect(page.getByTestId("attempt-question")).toHaveCount(1);
    await page.locator('label[data-option-id="2-1-c"]').click();
    await page.getByTestId("attempt-next").click();
    await expect(page.getByTestId("attempt-question-body")).toContainText("Solve");
    await page.waitForTimeout(300);
    await capture(page, "take");

    await page.getByTestId("attempt-submit-open").click();
    await expect(page.getByTestId("attempt-submit-dialog")).toBeVisible();
    await capture(page, "submit-dialog", false);
  });

  test("released result", async ({ page }) => {
    await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_PREVIOUS}/results`);
    await expect(page.getByTestId("result-item")).toHaveCount(3);
    await page.waitForTimeout(300);
    await capture(page, "result-released");
  });
});
