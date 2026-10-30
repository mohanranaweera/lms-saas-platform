import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import {
  EXAM_MCQ_LOCKED,
  EXAM_PHYSICAL,
  EXAM_WRITTEN,
  mockExamConfig,
  sampleExams,
  type MockExam,
} from "./fixtures/exam-config-mocks";
import {
  P_PUBLISHED,
  REVEALED_PASSWORD,
  W_OPEN,
  W_PASSWORD,
  W_PUBLISHED,
  W_UNPUBLISHED,
  mcqRows,
  mockExamManagement,
  mockStudentWritten,
  physicalRows,
  writtenRows,
} from "./fixtures/exam-management-mocks";

/**
 * Klass Exam Module — Slice D: management page (MCQ / written / physical),
 * marking dialogs, removal rules, and the student written flow + published
 * results. Mocked per `docs/api/exam-management-v2.md` "Slice D".
 * Screenshots → `docs/qa/klass-evidence/exams/slice-d-*.png` at 1440/820/390.
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
    await page.waitForTimeout(250);
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: `${EVIDENCE_DIR}/slice-d-${name}-${viewport.name}.png`, fullPage });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
}

function examsWith(overrides: Record<string, Partial<MockExam>>): MockExam[] {
  return sampleExams().map((exam) => (overrides[exam.id] ? { ...exam, ...overrides[exam.id] } : exam));
}

const WRITTEN_OVERRIDES = {
  [EXAM_WRITTEN]: { cutoffMarks: 40, markingScheme: { fileName: "scheme.pdf" }, markingSchemeMediaId: "doc-scheme" },
};
const PHYSICAL_OVERRIDES = {
  [EXAM_PHYSICAL]: { startAt: "2026-10-10T03:00:00Z", endAt: "2026-10-10T06:00:00Z", totalMarks: null },
};

/** Desktop table row by index number (the mobile card list is hidden at 1440). */
function tableRow(page: Page, indexNumber: string) {
  return page.getByTestId("students-table").locator("table tbody tr").filter({ hasText: indexNumber });
}

test.describe("Exam management — MCQ", () => {
  test("header, summary cards, result labels, search, pagination, export, reveal, attempt summary, removal", async ({ page }) => {
    test.setTimeout(120_000);
    const config = await mockExamConfig(page, "TENANT_ADMIN");
    const mock = await mockExamManagement(page, config, EXAM_MCQ_LOCKED, { rows: mcqRows() });
    await page.goto(`/tenant-admin/exams/${EXAM_MCQ_LOCKED}`, { timeout: 90_000 });

    // Header
    const header = page.getByTestId("exam-header");
    await expect(page.getByTestId("exam-header-name")).toHaveText("Word Exam", NAV);
    await expect(header.getByTestId("exam-status-chip")).toHaveText("Finished");
    await expect(header).toContainText("(from)");
    await expect(header).toContainText("(to)");
    await expect(page.getByTestId("exam-header-size")).toContainText("4 Questions");
    await expect(page.getByTestId("exam-password-value")).toHaveText("••••••••");
    await expect(header).not.toContainText(REVEALED_PASSWORD);
    await expect(page.getByRole("navigation", { name: /breadcrumb/i })).toContainText("Exams");

    // Summary cards
    await expect(page.getByTestId("summary-enrolled-value")).toHaveText("13");
    await expect(page.getByTestId("summary-enrolled")).toContainText("Students added to the exam");
    await expect(page.getByTestId("summary-attended-value")).toHaveText("3");
    await expect(page.getByTestId("summary-attended-percent")).toHaveText("23.1%");
    await expect(page.getByTestId("summary-attended")).toContainText("Opened the paper");
    await expect(page.getByTestId("summary-completed")).toContainText("Finished the paper with at least one answer");

    // Labels + leading zeros
    await expect(tableRow(page, "0001").getByTestId("result-label")).toHaveAttribute("data-result", "PASSED");
    await expect(tableRow(page, "0002").getByTestId("result-label")).toHaveAttribute("data-result", "FAILED");
    await expect(tableRow(page, "0004").getByTestId("result-label")).toHaveText("Not participated");
    await expect(tableRow(page, "0001").getByTestId("row-index")).toHaveText("0001");

    // Notify marks is Slice E: disabled with an explanation
    await expect(page.getByTestId("exam-notify-marks")).toHaveAttribute("aria-disabled", "true");
    await page.getByTestId("exam-notify-marks").hover();
    await expect(page.getByTestId("tooltip")).toHaveText("Available after results are published");

    // Pagination
    await expect(page.getByTestId("students-range")).toHaveText("1-10 of 13");
    await expect(page.locator("table tbody tr")).toHaveCount(10);
    await page.getByTestId("students-next").click();
    await expect(page.getByTestId("students-range")).toHaveText("11-13 of 13");
    await expect(page.locator("table tbody tr")).toHaveCount(3);
    await expect(page.getByTestId("students-next")).toBeDisabled();
    await page.getByTestId("students-first").click();
    await expect(page.getByTestId("students-range")).toHaveText("1-10 of 13");
    await page.getByTestId("students-last").click();
    await expect(page.getByTestId("students-range")).toHaveText("11-13 of 13");
    await page.getByLabel("Items per page:").selectOption("25");
    await expect(page.getByTestId("students-range")).toHaveText("1-13 of 13");
    expect(mock.find("GET", "size=25").length).toBeGreaterThan(0);

    // Server-side, debounced search
    await page.getByLabel("Search enrolled students").fill("kamala");
    await expect(page.getByTestId("students-range")).toHaveText("1-1 of 1");
    expect(mock.find("GET", "search=kamala").length).toBeGreaterThan(0);
    expect(mock.find("GET", "search=k&").length + mock.find("GET", "search=ka&").length).toBe(0);

    // CSV export with the current search, saved via an authenticated blob download
    const download = page.waitForEvent("download");
    await page.getByTestId("exam-export-csv").click();
    expect((await download).suggestedFilename()).toBe("word-exam-students.csv");
    expect(mock.find("GET", "/export.csv?search=kamala")).toHaveLength(1);

    // Empty search result
    await page.getByLabel("Search enrolled students").fill("nobody");
    await expect(page.getByText("No students match your search")).toBeVisible();
    await page.getByRole("button", { name: "Clear search" }).click();
    await expect(page.getByTestId("students-range")).toHaveText("1-13 of 13");

    // Password reveal: confirm → audited POST → shown in place → hide
    await page.getByTestId("exam-password-reveal").click();
    await expect(page.getByTestId("exam-password-reveal-dialog")).toContainText("audit log");
    expect(mock.find("POST", "/password/reveal")).toHaveLength(0);
    await page.getByTestId("exam-password-reveal-confirm").click();
    await expect(page.getByTestId("exam-password-value")).toHaveText(REVEALED_PASSWORD);
    expect(mock.find("POST", "/password/reveal")).toHaveLength(1);
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
    expect(stored).not.toContain(REVEALED_PASSWORD);
    await page.getByTestId("exam-password-hide").click();
    await expect(page.getByTestId("exam-password-value")).toHaveText("••••••••");

    // MCQ attempt summary (read-only)
    await tableRow(page, "0001").getByTestId("row-attempt-summary").click();
    const summary = page.getByTestId("exam-attempt-summary-dialog");
    await expect(summary.getByTestId("exam-attempt-score")).toHaveText("4 / 5");
    await expect(summary.getByTestId("exam-attempt-answered")).toHaveText("4 of 4 questions");
    await expect(summary.getByTestId("exam-attempt-status")).toHaveText("Submitted");
    await summary.getByRole("button", { name: "Close" }).first().click();
    await expect(summary).toBeHidden();

    // Removal: disabled once attempted; allowed otherwise
    await expect(tableRow(page, "0001").getByTestId("exam-remove-enrollment")).toBeDisabled();
    await expect(tableRow(page, "0004").getByTestId("exam-remove-enrollment")).toBeEnabled();
    await tableRow(page, "0004").getByTestId("exam-remove-enrollment").click();
    await expect(page.getByTestId("admin-toast")).toContainText("removed from this exam");
    await expect(page.getByTestId("students-range")).toHaveText("1-12 of 12");
    expect(mock.find("DELETE", "/enrollments/s-4")).toHaveLength(1);
  });

  test("a read-only role sees the table but no reveal or marking controls", async ({ page }) => {
    test.setTimeout(120_000);
    const config = await mockExamConfig(page, "READ_ONLY_AUDITOR");
    await mockExamManagement(page, config, EXAM_MCQ_LOCKED, { rows: mcqRows() });
    await page.goto(`/tenant-admin/exams/${EXAM_MCQ_LOCKED}`, { timeout: 90_000 });
    await expect(page.getByTestId("students-range")).toHaveText("1-10 of 13", NAV);
    await expect(page.getByTestId("exam-password-reveal")).toHaveCount(0);
    await expect(page.getByTestId("exam-remove-enrollment")).toHaveCount(0);
    await expect(page.getByTestId("exam-enrollment-actions")).toHaveCount(0);
  });

  test("screenshot: MCQ management", async ({ page }) => {
    test.setTimeout(120_000);
    const config = await mockExamConfig(page, "TENANT_ADMIN");
    await mockExamManagement(page, config, EXAM_MCQ_LOCKED, { rows: mcqRows() });
    await page.goto(`/tenant-admin/exams/${EXAM_MCQ_LOCKED}`, { timeout: 90_000 });
    await expect(page.getByTestId("students-range")).toHaveText("1-10 of 13", NAV);
    await capture(page, "management-mcq");
  });
});

test.describe("Exam management — written", () => {
  test("answer sheet upload/delete, marks with bounds/reason/server errors, marked paper, documents", async ({ page }) => {
    test.setTimeout(120_000);
    const config = await mockExamConfig(page, "TEACHER", { exams: examsWith(WRITTEN_OVERRIDES) });
    const mock = await mockExamManagement(page, config, EXAM_WRITTEN, {
      rows: writtenRows(),
      nextResultFieldError: { field: "marks", message: "Marks can't be changed after results are published." },
    });
    await page.goto(`/teacher/exams/${EXAM_WRITTEN}`, { timeout: 90_000 });

    await expect(page.getByTestId("exam-header-size")).toContainText("100 marks", NAV);
    await expect(page.getByTestId("summary-submitted")).toContainText("Uploaded an answer sheet");
    await expect(page.getByTestId("summary-submitted-value")).toHaveText("2");
    await expect(page.locator("table thead")).toContainText("Answer Document");
    await expect(page.locator("table thead")).toContainText("Marked Paper Document");
    await expect(tableRow(page, "0001").getByRole("button", { name: /kamala-page1\.pdf/ })).toBeVisible();
    await expect(tableRow(page, "0003")).toContainText("—");

    // Question paper / marking scheme: signed link fetched on click
    const popup = page.waitForEvent("popup");
    await page.getByTestId("exam-question-paper-link").click();
    await (await popup).close();
    expect(mock.find("GET", "/documents/QUESTION_PAPER")).toHaveLength(1);

    // Enter answer sheet for student 0003
    await tableRow(page, "0003").getByTestId("row-actions-menu").click();
    await page.getByTestId("row-enter-answer-sheet").click();
    const sheet = page.getByTestId("exam-answer-sheet-dialog");
    await expect(sheet.getByTestId("exam-answer-docs-empty")).toBeVisible();
    await sheet.getByTestId("exam-answer-sheet-files-input").setInputFiles([
      { name: "notes.txt", mimeType: "text/plain", buffer: Buffer.from("x") },
    ]);
    await expect(sheet.getByTestId("exam-answer-sheet-files-errors")).toContainText("only PDF, PNG or JPEG");
    await sheet.getByTestId("exam-answer-sheet-files-input").setInputFiles([
      { name: "sunil-p1.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4 page1") },
      { name: "sunil-p2.png", mimeType: "image/png", buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47]) },
    ]);
    await expect(sheet.getByTestId("exam-answer-sheet-files-selected").locator("li")).toHaveCount(2);
    await sheet.getByTestId("exam-answer-sheet-upload").click();
    await expect(sheet.getByTestId("exam-answer-doc")).toHaveCount(2);
    await expect(tableRow(page, "0003").getByTestId("result-label")).toHaveAttribute("data-result", "AWAITING_MARKS");
    // One request per file (server request ceiling), part `files[]`.
    expect(mock.find("POST", "/submissions/s-3/documents")).toHaveLength(2);
    await capture(page, "enter-answer-sheet-dialog", false);
    // Delete one (two-step)
    await sheet.getByTestId("exam-answer-doc").first().getByTestId("exam-answer-doc-delete").click();
    await sheet.getByTestId("exam-answer-doc-delete-confirm").click();
    await expect(sheet.getByTestId("exam-answer-doc")).toHaveCount(1);
    expect(mock.find("DELETE", "/submissions/s-3/documents/")).toHaveLength(1);
    await sheet.getByRole("button", { name: "Close" }).last().click();
    await expect(sheet).toBeHidden();

    // Enter marks for 0001: bounds, then a server 400, then success
    await tableRow(page, "0001").getByTestId("row-actions-menu").click();
    await page.getByTestId("row-enter-marks").click();
    const marks = page.getByTestId("exam-marks-dialog");
    await marks.getByTestId("exam-marks-input").fill("150");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks.getByRole("alert").first()).toContainText("can't be more than the total of 100");
    await marks.getByTestId("exam-marks-input").fill("-1");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks.getByRole("alert").first()).toContainText("can't be negative");
    expect(mock.find("PUT", "/results/s-1")).toHaveLength(0);
    await marks.getByTestId("exam-marks-input").fill("80");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks.getByRole("alert").first()).toContainText("Marks can't be changed after results are published.");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks).toBeHidden();
    await expect(tableRow(page, "0001").getByTestId("row-marks")).toHaveText("80");
    await expect(tableRow(page, "0001").getByTestId("result-label")).toHaveAttribute("data-result", "PASSED");
    expect(mock.find("PUT", "/results/s-1").at(-1)?.body).toEqual({ marks: 80, attendance: null, reason: null });

    // Change existing marks (0002: 72.5) requires a reason
    await tableRow(page, "0002").getByTestId("row-actions-menu").click();
    await page.getByTestId("row-enter-marks").click();
    await expect(marks.getByTestId("exam-marks-input")).toHaveValue("72.5");
    await marks.getByTestId("exam-marks-input").fill("30");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks.getByRole("alert").first()).toContainText("Give a reason");
    await marks.getByTestId("exam-marks-reason").fill("Re-marked question 4");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks).toBeHidden();
    await expect(tableRow(page, "0002").getByTestId("result-label")).toHaveAttribute("data-result", "FAILED");
    expect(mock.find("PUT", "/results/s-2").at(-1)?.body).toEqual({ marks: 30, attendance: null, reason: "Re-marked question 4" });

    // Upload marked paper for 0001
    await tableRow(page, "0001").getByTestId("row-actions-menu").click();
    await page.getByTestId("row-upload-marked-paper").click();
    const marked = page.getByTestId("exam-marked-paper-dialog");
    await marked.getByTestId("exam-marked-paper-files-input").setInputFiles({ name: "kamala-marked.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF") });
    await marked.getByTestId("exam-marked-paper-upload").click();
    await expect(marked).toBeHidden();
    await expect(tableRow(page, "0001").getByTestId("row-marked-paper")).toContainText("kamala-marked.pdf");

    // Written rows with submissions cannot be removed
    await expect(tableRow(page, "0001").getByTestId("exam-remove-enrollment")).toBeDisabled();
  });

  test("screenshot: written management", async ({ page }) => {
    test.setTimeout(120_000);
    const config = await mockExamConfig(page, "TENANT_ADMIN", { exams: examsWith(WRITTEN_OVERRIDES) });
    await mockExamManagement(page, config, EXAM_WRITTEN, { rows: writtenRows() });
    await page.goto(`/tenant-admin/exams/${EXAM_WRITTEN}`, { timeout: 90_000 });
    await expect(page.getByTestId("students-range")).toHaveText("1-3 of 3", NAV);
    await capture(page, "management-written");
  });
});

test.describe("Exam management — physical", () => {
  test("total marks first, attendance toggle, marks", async ({ page }) => {
    test.setTimeout(120_000);
    const config = await mockExamConfig(page, "TENANT_ADMIN", { exams: examsWith(PHYSICAL_OVERRIDES) });
    const mock = await mockExamManagement(page, config, EXAM_PHYSICAL, { rows: physicalRows() });
    await page.goto(`/tenant-admin/exams/${EXAM_PHYSICAL}`, { timeout: 90_000 });

    await expect(page.getByTestId("exam-header-size")).toContainText("Total marks not set", NAV);
    await expect(page.getByTestId("exam-total-marks-hint")).toBeVisible();
    await expect(page.getByTestId("summary-attended")).toContainText("Present");
    await expect(page.locator("table thead")).toContainText("Attendance");
    // Enroll-all exam: no enroll button
    await expect(page.getByTestId("exam-enroll-open")).toHaveCount(0);

    // Marks need a total first
    await tableRow(page, "0001").getByTestId("row-actions-menu").click();
    await expect(page.getByTestId("row-enter-marks")).toHaveAttribute("aria-disabled", "true");
    await page.keyboard.press("Escape");

    // Attendance
    await tableRow(page, "0001").getByTestId("attendance-present").click();
    await expect(tableRow(page, "0001").getByTestId("attendance-present")).toHaveAttribute("aria-pressed", "true");
    expect(mock.find("PUT", "/results/s-1").at(-1)?.body).toEqual({ marks: null, attendance: "PRESENT", reason: null });
    await tableRow(page, "0002").getByTestId("attendance-absent").click();
    await expect(tableRow(page, "0002").getByTestId("result-label")).toHaveAttribute("data-result", "ABSENT");
    await expect(page.getByTestId("summary-attended-value")).toHaveText("1");

    // Set total marks
    await page.getByTestId("exam-set-total-marks").click();
    const total = page.getByTestId("exam-total-marks-dialog");
    await total.getByTestId("exam-total-marks-input").fill("0");
    await total.getByTestId("exam-total-marks-save").click();
    await expect(total.getByRole("alert").first()).toContainText("between 1 and 10000");
    await total.getByTestId("exam-total-marks-input").fill("80");
    await total.getByTestId("exam-total-marks-save").click();
    await expect(total).toBeHidden();
    await expect(page.getByTestId("exam-header-size")).toContainText("80 marks");
    expect(mock.find("PUT", "/total-marks").at(-1)?.body).toEqual({ totalMarks: 80 });

    // Marks
    await tableRow(page, "0001").getByTestId("row-actions-menu").click();
    await page.getByTestId("row-enter-marks").click();
    const marks = page.getByTestId("exam-marks-dialog");
    await marks.getByTestId("exam-marks-input").fill("81");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks.getByRole("alert").first()).toContainText("total of 80");
    await marks.getByTestId("exam-marks-input").fill("70");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks).toBeHidden();
    await expect(tableRow(page, "0001").getByTestId("row-marks")).toHaveText("70");
    await expect(tableRow(page, "0001").getByTestId("result-label")).toHaveAttribute("data-result", "MARKED");
    expect(mock.find("PUT", "/results/s-1").at(-1)?.body).toEqual({ marks: 70, attendance: null, reason: null });
    // A student with marks can't be marked absent (server rule mirrored in the toggle).
    await expect(tableRow(page, "0001").getByTestId("attendance-absent")).toBeDisabled();
    // Marks on a student without attendance: the server sets PRESENT.
    await tableRow(page, "0003").getByTestId("row-actions-menu").click();
    await page.getByTestId("row-enter-marks").click();
    await marks.getByTestId("exam-marks-input").fill("40");
    await marks.getByTestId("exam-marks-save").click();
    await expect(marks).toBeHidden();
    await expect(tableRow(page, "0003").getByTestId("attendance-present")).toHaveAttribute("aria-pressed", "true");

    await capture(page, "management-physical");
  });
});

test.describe("Student written exam", () => {
  test("open paper with password, countdown, upload, deadline passed", async ({ page, context }) => {
    test.setTimeout(120_000);
    const mock = await mockStudentWritten(page);
    await page.goto(`/student/exams/${W_OPEN}`, { timeout: 90_000 });
    await expect(page.getByTestId("written-rules")).toBeVisible(NAV);
    expect(mock.find("GET", "/paper")).toHaveLength(0);

    await page.getByLabel("Exam password").fill("wrong");
    await page.getByTestId("written-open-paper").click();
    await expect(page.getByText("That password is incorrect")).toBeVisible();
    expect(mock.find("GET", "/paper")).toHaveLength(0);

    await page.getByLabel("Exam password").fill(W_PASSWORD);
    const popup = context.waitForEvent("page");
    await page.getByTestId("written-open-paper").click();
    await (await popup).close();
    await expect(page.getByTestId("written-in-progress")).toBeVisible();
    await expect(page.getByTestId("written-countdown")).toHaveText(/^(1:00:00|59:\d\d)$/);
    expect(mock.find("GET", "/paper")).toHaveLength(1);
    await expect(page.getByTestId("written-my-uploads-empty")).toBeVisible();

    await page.getByTestId("written-files-input").setInputFiles([
      { name: "answers-p1.jpg", mimeType: "image/jpeg", buffer: Buffer.from([0xff, 0xd8, 0xff]) },
      { name: "answers-p2.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF") },
    ]);
    await page.getByTestId("written-upload-submit").click();
    await expect(page.getByTestId("written-upload-success")).toHaveText("2 files uploaded.");
    await expect(page.getByTestId("written-my-upload")).toHaveCount(2);
    expect(mock.find("POST", "/answer-documents")).toHaveLength(2);
    await page.waitForTimeout(400);
    await capture(page, "student-written-take");

    // The server's deadline passes: the next upload is refused (409) and uploads close.
    mock.setNow("2026-10-30T09:31:00Z");
    await page.getByTestId("written-files-input").setInputFiles({ name: "late.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF") });
    await page.getByTestId("written-upload-submit").click();
    await expect(page.getByTestId("written-deadline-passed")).toContainText("The deadline for this exam has passed");
    await expect(page.getByTestId("written-my-upload")).toHaveCount(2);
    await expect(page.getByTestId("written-upload-submit")).toHaveCount(0);

    // Reload: still closed (server attempt EXPIRED)
    await page.reload();
    await expect(page.getByTestId("written-deadline-passed")).toBeVisible(NAV);
  });

  test("finish and submit closes uploads; the server attempt stays closed after reload", async ({ page, context }) => {
    test.setTimeout(120_000);
    const mock = await mockStudentWritten(page);
    await page.goto(`/student/exams/${W_OPEN}`, { timeout: 90_000 });
    await page.getByLabel("Exam password").fill(W_PASSWORD, NAV);
    const popup = context.waitForEvent("page");
    await page.getByTestId("written-open-paper").click();
    await (await popup).close();
    await page.getByTestId("written-files-input").setInputFiles({ name: "all-pages.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF") });
    await page.getByTestId("written-upload-submit").click();
    await expect(page.getByTestId("written-my-upload")).toHaveCount(1);
    await page.getByTestId("written-finish-open").click();
    await expect(page.getByTestId("written-finish-dialog")).toContainText("can't upload more");
    await page.getByTestId("written-finish-confirm").click();
    await expect(page.getByTestId("written-deadline-passed")).toHaveAttribute("data-reason", "submitted");
    expect(mock.find("POST", "/submit")).toHaveLength(1);
    await page.reload();
    await expect(page.getByTestId("written-deadline-passed")).toHaveAttribute("data-reason", "submitted", NAV);
  });

  test("published written result shows marks, pass and the marked paper; unpublished shows the wait message", async ({ page, context }) => {
    test.setTimeout(120_000);
    const mock = await mockStudentWritten(page);
    await page.goto(`/student/exams/${W_PUBLISHED}/results`, { timeout: 90_000 });
    await expect(page.getByTestId("result-score")).toHaveText("72.5", NAV);
    await expect(page.getByTestId("result-total")).toHaveText("100");
    await expect(page.getByTestId("result-outcome")).toHaveText("Passed");
    const popup = context.waitForEvent("page");
    await page.getByTestId("result-marked-paper").click();
    await (await popup).close();
    expect(mock.find("GET", `/student/exams/${W_PUBLISHED}/result`).length).toBeGreaterThanOrEqual(2);

    await page.goto(`/student/exams/${W_UNPUBLISHED}/results`);
    await expect(page.getByTestId("result-not-published")).toContainText("Marks will be available after they are published", NAV);
    await expect(page.getByTestId("result-score")).toHaveCount(0);
    expect(mock.find("GET", `/student/exams/${W_UNPUBLISHED}/result`)).toHaveLength(0);

    await page.goto(`/student/exams/${P_PUBLISHED}/results`);
    await expect(page.getByTestId("result-score")).toHaveText("64", NAV);
    await expect(page.getByTestId("result-attendance")).toHaveText("Present");

    // Physical details page shows schedule + result link
    await page.goto(`/student/exams/${P_PUBLISHED}`);
    await expect(page.getByTestId("physical-exam-info")).toContainText("held in class", NAV);
    await expect(page.getByTestId("physical-result-link")).toBeVisible();
  });
});
