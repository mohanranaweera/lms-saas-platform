import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import { EXAM_MCQ_LOCKED, EXAM_MCQ_UPCOMING, EXAM_WRITTEN, mockExamConfig, sampleExams } from "./fixtures/exam-config-mocks";
import { mcqRows, mockExamManagement, writtenRows } from "./fixtures/exam-management-mocks";
import {
  LB_EMPTY,
  LB_TERM,
  emptyMcqStats,
  mcqStats,
  mockExamStats,
  mockLeaderboards,
  mockNotifyMarks,
  mockStudentLeaderboards,
  writtenStatsNoCutoff,
} from "./fixtures/exam-results-mocks";

/**
 * Klass Exam Module — Slice E: Notify Marks (preview, Idempotency-Key replay,
 * resend, 409s), Exam Stats (data, nobody attended, table alternative),
 * staff leaderboards (validation, list, ranks with ties and unpublished exams,
 * delete) and the student leaderboard (own row highlighted). Mocked per
 * `docs/api/exam-management-v2.md` "Slice E" + addendum.
 * Screenshots → `docs/qa/klass-evidence/exams/slice-e-*.png` at 1440/820/390.
 */

const NAV = { timeout: 30_000 };
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
    await page.waitForTimeout(300);
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: `${EVIDENCE_DIR}/slice-e-${name}-${viewport.name}.png`, fullPage });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
}

async function expectNoBrokenValues(page: Page) {
  const text = await page.locator("main").innerText();
  expect(text).not.toMatch(/\bNaN\b/);
  expect(text).not.toMatch(/\bundefined\b/);
  expect(text).not.toMatch(/\bnull\b/);
}

async function openManagement(page: Page, role: "TENANT_ADMIN" | "TEACHER" | "READ_ONLY_AUDITOR", examId = EXAM_MCQ_LOCKED, notify = {}) {
  const config = await mockExamConfig(page, role);
  const mock = await mockNotifyMarks(page, examId, notify);
  // Management reflects the server's published state.
  await mockExamManagement(page, config, examId, { rows: mcqRows(), resultsPublishedAt: () => mock.state.resultsPublishedAt });
  await page.goto(`/tenant-admin/exams/${examId}`, { timeout: 90_000 });
  await expect(page.getByTestId("students-range")).toBeVisible(NAV);
  return mock;
}

test.describe("Notify marks", () => {
  test("preview, send with one Idempotency-Key, replay after a lost response, published state", async ({ page }) => {
    test.setTimeout(120_000);
    const mock = await openManagement(page, "TENANT_ADMIN");
    await expect(page.getByTestId("exam-results-published")).toHaveCount(0);

    await page.getByTestId("exam-notify-marks").click();
    const dialog = page.getByTestId("notify-marks-dialog");
    await expect(dialog.getByTestId("notify-recipients")).toHaveText("3 students");
    await expect(dialog.getByTestId("notify-without-marks")).toHaveText("10 students");
    await expect(dialog.getByTestId("notify-already")).toHaveCount(0);

    // The first response is lost after the server committed: retry reuses the key and gets the original answer.
    mock.state.failNextAfterCommit = true;
    await dialog.getByTestId("notify-confirm").click();
    await expect(dialog.getByTestId("notify-error")).toContainText("You can try again safely");
    await dialog.getByTestId("notify-confirm").click();
    await expect(page.getByTestId("admin-toast")).toContainText("already sent to 3 students");
    await expect(dialog).toBeHidden();

    const sends = mock.find("POST", "/notify-marks");
    expect(sends).toHaveLength(2);
    expect(sends[0].idempotencyKey).toBeTruthy();
    expect(sends[1].idempotencyKey).toBe(sends[0].idempotencyKey);
    expect(sends[0].body).toEqual({});
    expect(mock.state.dispatches.size).toBe(1);

    await expect(page.getByTestId("exam-results-published")).toContainText("Results published on", NAV);
  });

  test("a clean send shows the count; a second send needs “Send again” and a new key", async ({ page }) => {
    test.setTimeout(120_000);
    const mock = await openManagement(page, "TENANT_ADMIN");
    await page.getByTestId("exam-notify-marks").click();
    await page.getByTestId("notify-confirm").click();
    await expect(page.getByTestId("admin-toast")).toContainText("Marks sent to 3 students");

    // Reopen: already notified → the resend checkbox is required.
    await page.getByTestId("exam-notify-marks").click();
    const dialog = page.getByTestId("notify-marks-dialog");
    await expect(dialog.getByTestId("notify-already-text")).toContainText("Already notified on");
    await expect(dialog.getByTestId("notify-confirm")).toHaveText(/Send again/);
    await dialog.getByTestId("notify-confirm").click();
    await expect(dialog.getByTestId("notify-resend-error")).toContainText("Send again");
    expect(mock.find("POST", "/notify-marks")).toHaveLength(1);

    await dialog.getByLabel("Send again").check();
    await dialog.getByTestId("notify-confirm").click();
    await expect(page.getByTestId("admin-toast")).toContainText("Marks sent to 3 students");
    const sends = mock.find("POST", "/notify-marks");
    expect(sends).toHaveLength(2);
    expect(sends[1].body).toEqual({ resend: true });
    expect(sends[1].idempotencyKey).not.toBe(sends[0].idempotencyKey);
    expect(mock.state.dispatches.size).toBe(2);
  });

  test("409 NOT_ENDED, ALREADY_NOTIFIED and NO_RECIPIENTS are explained", async ({ page }) => {
    test.setTimeout(120_000);
    const mock = await openManagement(page, "TENANT_ADMIN");
    await page.getByTestId("exam-notify-marks").click();
    const dialog = page.getByTestId("notify-marks-dialog");
    await expect(dialog.getByTestId("notify-recipients")).toHaveText("3 students");

    mock.state.nextConflict = "NOT_ENDED";
    await dialog.getByTestId("notify-confirm").click();
    await expect(dialog.getByTestId("notify-error")).toContainText("The exam hasn't ended yet");

    // Someone else sent meanwhile: the dialog refreshes and asks for explicit confirmation.
    mock.state.nextConflict = "ALREADY_NOTIFIED";
    await dialog.getByTestId("notify-confirm").click();
    await expect(dialog.getByTestId("notify-error")).toContainText("Students were already notified");
    await expect(dialog.getByTestId("notify-already-text")).toContainText("Already notified on");
    const firstKey = mock.find("POST", "/notify-marks")[1].idempotencyKey;
    await dialog.getByLabel("Send again").check();
    await dialog.getByTestId("notify-confirm").click();
    await expect(page.getByTestId("admin-toast")).toContainText("Marks sent to 3 students");
    const last = mock.find("POST", "/notify-marks").at(-1)!;
    expect(last.body).toEqual({ resend: true });
    expect(last.idempotencyKey).not.toBe(firstKey);

    // No marks at all: nothing to send.
    mock.state.recipients = 0;
    mock.state.alreadyNotifiedAt = null;
    await page.getByTestId("exam-notify-marks").click();
    await expect(dialog.getByTestId("notify-no-recipients")).toContainText("No student has marks yet");
    await expect(dialog.getByTestId("notify-confirm")).toBeDisabled();
  });

  test("screenshot: notify dialog", async ({ page }) => {
    test.setTimeout(120_000);
    await openManagement(page, "TENANT_ADMIN", EXAM_MCQ_LOCKED, { alreadyNotifiedAt: "2026-10-29T10:15:00Z" });
    await page.getByTestId("exam-notify-marks").click();
    await expect(page.getByTestId("notify-already-text")).toBeVisible();
    await capture(page, "notify-dialog", false);
  });

  test("disabled before the exam ends", async ({ page }) => {
    test.setTimeout(120_000);
    await openManagement(page, "TENANT_ADMIN", EXAM_MCQ_UPCOMING, { ended: false });
    await expect(page.getByTestId("exam-notify-marks")).toHaveAttribute("aria-disabled", "true");
    await page.getByTestId("exam-notify-marks").hover();
    await expect(page.getByTestId("tooltip")).toHaveText("Available after the exam ends");
    await page.getByTestId("exam-notify-marks").click({ force: true });
    await expect(page.getByTestId("notify-marks-dialog")).toHaveCount(0);
  });

  test("a read-only role sees no Notify marks button", async ({ page }) => {
    test.setTimeout(120_000);
    await openManagement(page, "READ_ONLY_AUDITOR");
    await expect(page.getByTestId("exam-notify-marks")).toHaveCount(0);
  });

  test("answer documents can't be deleted once results are published", async ({ page }) => {
    test.setTimeout(120_000);
    const exams = sampleExams();
    const config = await mockExamConfig(page, "TEACHER", { exams });
    await mockExamManagement(page, config, EXAM_WRITTEN, { rows: writtenRows(), resultsPublishedAt: () => "2026-10-29T10:15:00Z" });
    await page.goto(`/teacher/exams/${EXAM_WRITTEN}`, { timeout: 90_000 });
    await expect(page.getByTestId("exam-results-published")).toBeVisible(NAV);
    const row = page.getByTestId("students-table").locator("table tbody tr").filter({ hasText: "0001" });
    await row.getByTestId("row-actions-menu").click();
    await page.getByTestId("row-enter-answer-sheet").click();
    const dialog = page.getByTestId("exam-answer-sheet-dialog");
    await expect(dialog.getByTestId("exam-answer-doc")).toHaveCount(2);
    await expect(dialog.getByTestId("exam-answer-doc-delete")).toHaveCount(0);
    await expect(dialog.getByTestId("exam-answer-docs-locked")).toBeVisible();
  });
});

test.describe("Exam stats", () => {
  test("MCQ stats: tiles, population, chart with tooltip + table, missed questions, breakdown", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TENANT_ADMIN");
    await mockExamStats(page, EXAM_MCQ_LOCKED, mcqStats());
    await page.goto(`/tenant-admin/exams/${EXAM_MCQ_LOCKED}/stats`, { timeout: 90_000 });

    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Exam Stats", NAV);
    await expect(page.getByRole("navigation", { name: "Breadcrumb" })).toContainText("Word Exam");
    await expect(page.getByTestId("stat-average-value")).toHaveText("3.33");
    await expect(page.getByTestId("stat-max-value")).toHaveText("5");
    await expect(page.getByTestId("stat-min-value")).toHaveText("1");
    await expect(page.getByTestId("stat-passed-value")).toHaveText("2 of 3 students");
    await expect(page.getByTestId("stats-population")).toContainText("Based on 3 graded of 3 attended (13 enrolled)");

    await expect(page.getByTestId("distribution-bar")).toHaveCount(4);
    await expect(page.getByTestId("distribution-legend")).toContainText("Correct");
    await expect(page.getByTestId("distribution-legend")).toContainText("Unanswered");
    const bar = page.locator('[data-testid="distribution-bar"][data-sequence="3"]');
    await expect(bar).toHaveAttribute("aria-label", "Question 3: 1 correct, 1 incorrect, 1 unanswered (3 responses)");
    await bar.focus();
    await expect(page.getByTestId("distribution-tooltip")).toContainText("Question 3");
    await page.keyboard.press("ArrowRight");
    await expect(page.getByTestId("distribution-tooltip")).toContainText("Question 4");

    await page.getByTestId("distribution-view-table").click();
    await expect(page.getByTestId("distribution-view-table")).toHaveAttribute("aria-pressed", "true");
    const table = page.getByTestId("distribution-table");
    await expect(table.locator("tbody tr")).toHaveCount(4);
    await expect(table.locator("tbody tr").nth(1)).toContainText("2");
    await page.getByTestId("distribution-view-chart").click();

    await expect(page.getByTestId("stats-missed-item")).toHaveCount(3);
    await expect(page.getByTestId("stats-missed-item").first()).toContainText("Missed by 2 of 3");
    await expect(page.getByTestId("stats-breakdown-count")).toHaveText("4 Questions");

    // Frequently missed → opens the breakdown item
    await page.getByTestId("stats-missed-item").first().getByRole("button", { name: "Question 2" }).click();
    const q2 = page.locator('[data-testid="stats-question"][data-sequence="2"]');
    await expect(q2.getByTestId("stats-question-toggle")).toHaveAttribute("aria-expanded", "true");
    await expect(q2.getByTestId("stats-question-percent")).toHaveText("33.3% correct");
    await expect(q2).toContainText("Which gas do plants absorb?");
    await q2.getByTestId("stats-view-options").click();
    await expect(q2.getByTestId("stats-option")).toHaveCount(2);
    const correct = q2.locator('[data-testid="stats-option"][data-correct="true"]');
    await expect(correct).toContainText("Carbon dioxide");
    await expect(correct.getByTestId("stats-option-correct")).toHaveText("Correct answer");
    await expect(q2.getByTestId("stats-option-count").first()).toHaveText("2 selections");
    await expectNoBrokenValues(page);
  });

  test("screenshot: MCQ stats", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TENANT_ADMIN");
    await mockExamStats(page, EXAM_MCQ_LOCKED, mcqStats());
    await page.goto(`/tenant-admin/exams/${EXAM_MCQ_LOCKED}/stats`, { timeout: 90_000 });
    await expect(page.getByTestId("distribution-bar")).toHaveCount(4, NAV);
    await page.locator('[data-testid="stats-question"][data-sequence="3"]').getByTestId("stats-question-toggle").click();
    await page.locator('[data-testid="stats-question"][data-sequence="3"]').getByTestId("stats-view-options").click();
    await capture(page, "stats-mcq");
  });

  test("nobody attended: dashes with explanations, no NaN/undefined, empty state", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TEACHER");
    await mockExamStats(page, EXAM_MCQ_LOCKED, emptyMcqStats());
    await page.goto(`/teacher/exams/${EXAM_MCQ_LOCKED}/stats`, { timeout: 90_000 });
    await expect(page.getByTestId("stat-average-value")).toHaveText("—", NAV);
    await expect(page.getByTestId("stat-average-caption")).toHaveText("No one has attended yet");
    await expect(page.getByTestId("stat-passed-value")).toHaveText("—");
    await expect(page.getByTestId("stats-population")).toContainText("Based on 0 graded of 0 attended (13 enrolled)");
    await expect(page.getByText("No one has attended this exam yet")).toBeVisible();
    await expect(page.getByTestId("distribution-bar")).toHaveCount(0);
    await expectNoBrokenValues(page);
  });

  test("written exam without a cut-off; stats 403 shows permission denied", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TENANT_ADMIN");
    await mockExamStats(page, EXAM_WRITTEN, writtenStatsNoCutoff());
    await page.goto(`/tenant-admin/exams/${EXAM_WRITTEN}/stats`, { timeout: 90_000 });
    await expect(page.getByTestId("stat-passed-value")).toHaveText("No cut-off set", NAV);
    await expect(page.getByTestId("stat-average-value")).toHaveText("72.5");
    await expect(page.getByTestId("stats-awaiting")).toContainText("1 attended student still needs marks");
    await expect(page.getByTestId("stats-type-note")).toContainText("no question-by-question breakdown");
    await expectNoBrokenValues(page);

    await page.unroute(`**/v1/exams/${EXAM_WRITTEN}/stats`);
    await mockExamStats(page, EXAM_WRITTEN, null, 403);
    await page.reload();
    await expect(page.getByText(/permission/i).first()).toBeVisible(NAV);
  });
});

test.describe("Leaderboards — staff", () => {
  test("list, create validation (name, ≥2 exams), create, edit, delete", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TENANT_ADMIN");
    const mock = await mockLeaderboards(page);
    await page.goto("/tenant-admin/exams/leaderboards", { timeout: 90_000 });
    const list = page.getByTestId("leaderboard-list");
    const termRow = list.locator("table tbody tr").filter({ hasText: "Term 1 ranking" });
    await expect(termRow).toBeVisible(NAV);
    await expect(termRow.getByTestId("leaderboard-exam-count")).toHaveText("3 exams");
    await expect(termRow.getByTestId("leaderboard-visibility")).toHaveText("Visible to students");
    await expect(list.locator("table tbody tr").filter({ hasText: "November mocks" }).getByTestId("leaderboard-visibility")).toHaveText("Staff only");

    // Validation: name + at least 2 exams
    await page.getByTestId("leaderboard-add").click();
    const dialog = page.getByTestId("leaderboard-dialog");
    await dialog.getByTestId("leaderboard-save").click();
    await expect(dialog).toContainText("Enter a name for the leaderboard.");
    await expect(dialog).toContainText("Choose at least 2 exams.");
    await dialog.getByLabel("Name").fill("Mid-year");
    await dialog.getByTestId("leaderboard-exams").click();
    await page.getByTestId("leaderboard-exams-popup").getByLabel(/^Word Exam/).check();
    await page.keyboard.press("Escape");
    await dialog.getByTestId("leaderboard-save").click();
    await expect(dialog).toContainText("Choose at least 2 exams.");
    expect(mock.find("POST", "/exam-leaderboards")).toHaveLength(0);

    await dialog.getByTestId("leaderboard-exams").click();
    await page.getByTestId("leaderboard-exams-popup").getByLabel(/^test ·/).check();
    await page.keyboard.press("Escape");
    await dialog.getByRole("switch", { name: "Visible to students" }).click();
    await dialog.getByTestId("leaderboard-save").click();
    await expect(page.getByTestId("admin-toast")).toContainText("Leaderboard “Mid-year” added.");
    const created = mock.find("POST", "/exam-leaderboards")[0].body as { name: string; examIds: string[]; published: boolean };
    expect(created).toEqual({ name: "Mid-year", examIds: [EXAM_MCQ_LOCKED, EXAM_WRITTEN], published: true });
    await expect(list.locator("table tbody tr").filter({ hasText: "Mid-year" })).toBeVisible();

    // Edit loads the current exams
    await list.locator("table tbody tr").filter({ hasText: "November mocks" }).getByTestId("leaderboard-edit").click();
    await expect(page.getByTestId("leaderboard-dialog").getByLabel("Name")).toHaveValue("November mocks");
    await page.getByTestId("leaderboard-dialog").getByLabel("Name").fill("November mock exams");
    await page.getByTestId("leaderboard-save").click();
    await expect(page.getByTestId("admin-toast")).toContainText("updated");
    expect((mock.find("PUT", `/exam-leaderboards/${LB_EMPTY}`)[0].body as { examIds: string[] }).examIds).toHaveLength(2);

    // Delete with confirmation
    await list.locator("table tbody tr").filter({ hasText: "Mid-year" }).getByTestId("leaderboard-delete").click();
    await expect(page.getByTestId("confirm-delete-dialog")).toContainText("Only the leaderboard is removed");
    await page.getByTestId("confirm-delete-submit").click();
    await expect(page.getByTestId("admin-toast")).toContainText("deleted");
    await expect(list.locator("table tbody tr").filter({ hasText: "Mid-year" })).toHaveCount(0);
    expect(mock.find("DELETE", "/exam-leaderboards/")).toHaveLength(1);
  });

  test("screenshot: leaderboard list", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TENANT_ADMIN");
    await mockLeaderboards(page);
    await page.goto("/tenant-admin/exams/leaderboards", { timeout: 90_000 });
    await expect(page.getByTestId("leaderboard-list")).toBeVisible(NAV);
    await capture(page, "leaderboard-list");
  });

  test("ranks: ties share rank, medals, unpublished exam flagged, search, paging; empty state", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TEACHER");
    const mock = await mockLeaderboards(page);
    await page.goto(`/teacher/exams/leaderboards/${LB_TERM}`, { timeout: 90_000 });
    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Term 1 ranking", NAV);
    await expect(page.getByTestId("leaderboard-exam")).toHaveCount(3);
    await expect(page.locator('[data-testid="leaderboard-exam"][data-published="false"]')).toContainText("Marks not published");

    const rows = page.getByTestId("ranks-table").locator("tbody tr");
    await expect(rows).toHaveCount(20);
    await expect(rows.nth(0).getByTestId("rank")).toHaveAttribute("data-rank", "1");
    await expect(rows.nth(1).getByTestId("rank")).toHaveAttribute("data-rank", "1");
    await expect(rows.nth(1).getByTestId("rank-tied")).toHaveText("tie");
    await expect(rows.nth(2).getByTestId("rank")).toHaveAttribute("data-rank", "3");
    await expect(rows.nth(4).getByTestId("rank")).toHaveAttribute("data-rank", "5");
    await expect(rows.nth(0).getByTestId("rank-medal")).toBeVisible();
    await expect(rows.nth(2).getByTestId("rank-medal")).toBeVisible();
    await expect(rows.nth(4).getByTestId("rank-medal")).toHaveCount(0);
    await expect(rows.nth(0).getByTestId("rank-total")).toHaveText("100 / 105");
    await expect(rows.nth(0).getByTestId("rank-share")).toHaveText("95.2%");
    await expect(rows.nth(4).getByTestId("rank-exam-marks").nth(1)).toHaveText("—");
    // Per-exam columns only for published exams
    await expect(page.getByTestId("ranks-table").locator("thead th")).toHaveCount(7);

    await expect(page.getByTestId("ranks-range")).toHaveText("1-20 of 23");
    await page.getByTestId("ranks-next").click();
    await expect(page.getByTestId("ranks-range")).toHaveText("21-23 of 23");
    await page.getByLabel("Search students").fill("amaya");
    await expect(page.getByTestId("ranks-range")).toHaveText("1-1 of 1");
    expect(mock.find("GET", "search=amaya").length).toBeGreaterThan(0);
    await page.getByLabel("Search students").fill("zzz");
    await expect(page.getByText("No students match your search")).toBeVisible();

    await page.goto(`/teacher/exams/leaderboards/${LB_EMPTY}`);
    await expect(page.getByText("Ranks will appear once marks are entered.")).toBeVisible(NAV);
    await expectNoBrokenValues(page);
  });

  test("screenshot: leaderboard ranks", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TENANT_ADMIN");
    await mockLeaderboards(page);
    await page.goto(`/tenant-admin/exams/leaderboards/${LB_TERM}`, { timeout: 90_000 });
    await expect(page.getByTestId("ranks-range")).toHaveText("1-20 of 23", NAV);
    await capture(page, "leaderboard-ranks");
  });

  test("a read-only role can view but not add, edit or delete", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "READ_ONLY_AUDITOR");
    await mockLeaderboards(page);
    await page.goto("/tenant-admin/exams/leaderboards", { timeout: 90_000 });
    await expect(page.getByTestId("leaderboard-list")).toBeVisible(NAV);
    await expect(page.getByTestId("leaderboard-add")).toHaveCount(0);
    await expect(page.getByTestId("leaderboard-edit")).toHaveCount(0);
    await expect(page.getByTestId("leaderboard-delete")).toHaveCount(0);
    await expect(page.getByTestId("leaderboard-view").first()).toBeVisible();
  });
});

test.describe("Leaderboards — student", () => {
  test("lists published leaderboards and highlights the student's own row", async ({ page }) => {
    test.setTimeout(120_000);
    await mockStudentLeaderboards(page);
    await page.goto("/student/exams/leaderboards", { timeout: 90_000 });
    await expect(page.getByTestId("student-leaderboard")).toHaveCount(1, NAV);
    await expect(page.getByTestId("student-leaderboard")).toContainText("Term 1 ranking");
    await page.getByTestId("student-leaderboard").click();

    await expect(page.getByRole("heading", { level: 1 })).toHaveText("Term 1 ranking", NAV);
    const own = page.getByTestId("ranks-table").locator('tbody tr[data-self="true"]');
    await expect(own).toHaveCount(1);
    await expect(own).toHaveAttribute("aria-current", "true");
    await expect(own.getByTestId("rank-you")).toHaveText("You");
    await expect(own.getByTestId("rank-name")).toHaveText("Amaya Jayasuriya");
    await expect(page.getByTestId("student-own-rank")).toContainText("3");
    // Masked columns are not shown to students
    await expect(page.getByTestId("ranks-table").locator("thead")).not.toContainText("Index Number");
    await expectNoBrokenValues(page);
  });

  test("screenshot: student leaderboard", async ({ page }) => {
    test.setTimeout(120_000);
    await mockStudentLeaderboards(page);
    await page.goto(`/student/exams/leaderboards/${LB_TERM}`, { timeout: 90_000 });
    await expect(page.getByTestId("ranks-range")).toBeVisible(NAV);
    await capture(page, "student-leaderboard");
  });

  test("empty list explains why", async ({ page }) => {
    test.setTimeout(120_000);
    await mockStudentLeaderboards(page, { boards: [] });
    await page.goto("/student/exams/leaderboards", { timeout: 90_000 });
    await expect(page.getByText("No leaderboards yet")).toBeVisible(NAV);
  });
});
