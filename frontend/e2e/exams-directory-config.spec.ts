import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import {
  BIO_ID,
  BIO_NAME,
  CHEM_ID,
  EXAM_MCQ_LOCKED,
  EXAM_MCQ_UPCOMING,
  EXAM_PHYSICAL,
  MOD_A,
  MOD_B,
  mockExamConfig,
} from "./fixtures/exam-config-mocks";

/**
 * Klass Exam Module — Slice B (exam directory + shared create/edit dialog).
 * Mocked API only (fixtures/exam-config-mocks.ts): directory tabs, counts,
 * search, empty states; MCQ create with module quotas + cut-off validation +
 * payment auto-enroll criteria (course-config addendum); written create with
 * document upload; physical create; edit round-trip (password left blank);
 * locked exam; 409 EXAM_LOCKED; delete confirm + 409; teacher route.
 */

const EVIDENCE_DIR = "../docs/qa/klass-evidence/exams";
const NAV = { timeout: 30_000 };
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
    // Dialog evidence starts at the top of the scrollable form body.
    await page.evaluate(() => {
      document.querySelectorAll("[data-testid='exam-form-dialog'] .overflow-y-auto").forEach((node) => node.scrollTo(0, 0));
    });
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: `${EVIDENCE_DIR}/slice-b-${name}-${viewport.name}.png`, fullPage });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
}

const field = (page: Page, name: string) => page.getByTestId(`exam-form-${name}`);
const dialog = (page: Page) => page.getByTestId("exam-form-dialog");

async function openNewExam(page: Page) {
  await page.getByTestId("exam-new").click();
  await expect(dialog(page)).toBeVisible();
  await expect(dialog(page).getByRole("heading", { name: "Add New Exam" })).toBeVisible();
}

async function fillCommon(page: Page, name: string, opts: { password?: boolean } = { password: true }) {
  await field(page, "name").fill(name);
  if (opts.password) await field(page, "password").fill("secret1");
  await field(page, "durationMinutes").fill("45");
  await field(page, "fromDate").fill("2026-11-10");
  await field(page, "toDate").fill("2026-11-10");
  await field(page, "fromTime").fill("09:00");
  await field(page, "toTime").fill("11:00");
}

async function pickModules(page: Page, labels: string[]) {
  await page.getByTestId("exam-form-modules").click();
  const popup = page.getByTestId("exam-form-modules-popup");
  for (const label of labels) await popup.getByRole("checkbox", { name: label }).check();
  await page.keyboard.press("Escape");
  await expect(popup).toBeHidden();
}

test.describe("Exam directory — Tenant Admin", () => {
  test("tabs with count chips, cards, empty text, and server search", async ({ page }) => {
    test.setTimeout(90_000);
    const mock = await mockExamConfig(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/exams");
    await expect(page.getByRole("heading", { level: 1, name: "Exams" })).toBeVisible(NAV);

    const inProgress = page.getByTestId("exam-tab-in-progress");
    await expect(inProgress).toHaveAttribute("aria-selected", "true");
    await expect(page.getByTestId("exam-tab-count-in-progress")).toHaveText("0");
    await expect(page.getByTestId("exam-tab-count-upcoming")).toHaveText("2");
    await expect(page.getByTestId("exam-tab-count-previous")).toHaveText("2");
    await expect(page.getByTestId("exam-directory-empty")).toHaveText("No active exams");
    await expect(page.getByRole("link", { name: "Leaderboard" })).toHaveAttribute("href", "/tenant-admin/exams/leaderboards");

    await page.getByTestId("exam-tab-previous").click();
    const cards = page.getByTestId("exam-card");
    await expect(cards).toHaveCount(2);
    // Previous: ordered by end desc → Word Exam (ends 9 Jun 23:10) before test.
    const written = cards.filter({ hasText: "test" }).filter({ has: page.getByTestId("exam-card-type").getByText("Written") });
    await expect(written.getByTestId("exam-status-chip")).toHaveText("Finished");
    await expect(written.getByTestId("exam-card-course")).toHaveText(BIO_NAME);
    await expect(written.getByTestId("exam-card-when")).toContainText("2 years ago");
    await expect(written.getByTestId("exam-card-extent")).toContainText("30 minutes");
    await expect(written.getByTestId("exam-card-extent")).toContainText("100 marks");
    await expect(written.getByTestId("exam-card-attended")).toHaveText("0 / 3 attended · 0%");
    await expect(written.getByTestId("exam-card-outcome")).toHaveText("0 submitted");
    const mcq = cards.filter({ hasText: "Word Exam" });
    await expect(mcq.getByTestId("exam-card-extent")).toContainText("4 questions");
    await expect(mcq.getByTestId("exam-card-attended")).toHaveText("1 / 2 attended · 50%");
    await expect(mcq.getByTestId("exam-card-outcome")).toHaveText("1 completed");
    await expect(mcq.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "50");
    await expect(mcq.getByRole("link", { name: "Manage Word Exam" })).toHaveAttribute("href", `/tenant-admin/exams/${EXAM_MCQ_LOCKED}`);
    await expect(mcq.getByRole("link", { name: "Stats for Word Exam" })).toHaveAttribute(
      "href",
      `/tenant-admin/exams/${EXAM_MCQ_LOCKED}/stats`
    );
    await capture(page, "directory-previous");

    // Upcoming: physical card has no "completed/submitted" counter.
    await page.getByTestId("exam-tab-upcoming").click();
    await expect(cards).toHaveCount(2);
    const physical = cards.filter({ hasText: "Term Test" });
    await expect(physical.getByTestId("exam-card-type")).toHaveText("Physical");
    await expect(physical.getByTestId("exam-status-chip")).toHaveText("Upcoming");
    await expect(physical.getByTestId("exam-card-when")).toContainText("in 2 weeks");
    await expect(physical.getByTestId("exam-card-outcome")).toHaveCount(0);

    // Server search (debounced): counts follow the server's filtered groups.
    await page.getByRole("searchbox", { name: "Search exams" }).fill("word");
    await expect(page.getByTestId("exam-tab-count-upcoming")).toHaveText("0");
    await expect(page.getByTestId("exam-tab-count-previous")).toHaveText("1");
    await expect(page.getByTestId("exam-directory-empty")).toHaveText("No upcoming exams match “word”.");
    expect(mock.find("GET", "/v1/exams/directory?search=word").length).toBeGreaterThan(0);
    await page.getByTestId("exam-tab-previous").click();
    await expect(cards).toHaveCount(1);
    await page.getByTestId("exam-tab-upcoming").click();
    await page.getByTestId("exam-search-clear").click();
    await expect(page.getByRole("searchbox", { name: "Search exams" })).toHaveValue("");
    await expect(page.getByTestId("exam-tab-count-previous")).toHaveText("2");
    await expect(page.getByTestId("exam-tab-count-upcoming")).toHaveText("2");
  });

  test("403 on the directory renders the permission-denied state", async ({ page }) => {
    await mockExamConfig(page, "TENANT_ADMIN", { directoryStatus: 403 });
    await page.goto("/tenant-admin/exams");
    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission to view this." });
    await expect(denied).toBeVisible(NAV);
    await expect(page.getByTestId("exam-card")).toHaveCount(0);
  });

  test("read-only auditor sees cards but no New exam or card actions", async ({ page }) => {
    await mockExamConfig(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/exams");
    await page.getByTestId("exam-tab-previous").click(NAV);
    await expect(page.getByTestId("exam-card")).toHaveCount(2);
    await expect(page.getByTestId("exam-new")).toHaveCount(0);
    await expect(page.getByTestId("exam-card-menu")).toHaveCount(0);
  });
});

test.describe("Exam dialog — create", () => {
  test("MCQ with two modules: quota sum, availability, cut-off range and payment criteria", async ({ page }) => {
    const mock = await mockExamConfig(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/exams");
    await openNewExam(page);
    await expect(page.getByTestId("exam-form-type-MCQ")).toHaveAttribute("aria-selected", "true");

    // Auto-enroll is only offered once a course is chosen.
    await expect(page.getByTestId("exam-form-auto-enroll")).toHaveCount(0);
    await expect(page.getByTestId("exam-form-marks-helper")).not.toContainText("0 marks");

    await fillCommon(page, "Unit Test 2");
    await field(page, "questionCount").fill("4");
    await field(page, "courseId").selectOption(BIO_ID);
    await pickModules(page, ["2027 (3 questions)", "Test (2 questions)"]);
    await expect(page.getByTestId("exam-form-quota")).toHaveCount(2);

    const quotaA = page.getByRole("spinbutton", { name: "2027 Number of questions from this module" });
    const quotaB = page.getByRole("spinbutton", { name: "Test Number of questions from this module" });
    await quotaA.fill("3");
    await quotaB.fill("3");
    await expect(page.getByTestId("exam-form-quotas")).toContainText("Only 2 questions are available in this module.");
    await quotaB.fill("2");
    await expect(dialog(page).getByRole("alert").filter({ hasText: "The module quotas add up" })).toContainText(
      "The module quotas add up to 5 questions; they must equal the Number of Questions (4)."
    );
    // 3 of 3 from 2027 (4.5) + 1 of 2 from Test (1) → exact.
    await quotaB.fill("1");
    await expect(page.getByTestId("exam-form-marks-helper")).toHaveText("The chosen questions are worth 5.5 marks.");
    await expect(page.getByTestId("exam-form-quota-total")).toHaveText("4 of 4 questions chosen");
    // 2 of 3 (1–2 marks each) + 2 of 2 → a range.
    await quotaA.fill("2");
    await quotaB.fill("2");
    await expect(page.getByTestId("exam-form-marks-helper")).toHaveText("The chosen questions are worth between 4 and 6 marks.");
    await field(page, "cutoffMarks").fill("7");
    await expect(dialog(page).getByText("The chosen questions are worth at most 6 marks; the cut-off can't be higher.")).toBeVisible();
    await field(page, "cutoffMarks").fill("3");
    await expect(dialog(page).getByText("the cut-off can't be higher")).toHaveCount(0);

    await field(page, "shuffleQuestions").check();
    // Enroll all ↔ auto-enroll are mutually exclusive.
    const autoEnroll = page.getByRole("switch", { name: "Auto-enroll students on payment" });
    await expect(page.getByText("Enroll students into this exam as their class-fee payment comes in, instead of one by one.")).toBeVisible();
    await field(page, "enrollAllStudents").check();
    await expect(autoEnroll).toBeDisabled();
    await expect(page.getByTestId("exam-form-auto-enroll-note")).toHaveText('Turn off "Enroll all students" to enroll by payment instead.');
    await field(page, "enrollAllStudents").uncheck();
    await expect(autoEnroll).toBeEnabled();
    await autoEnroll.click();
    await expect(page.getByTestId("exam-form-criteria")).toHaveAttribute("data-kind", "MONTHS");
    await page.getByTestId("exam-form-criteriaMonths-input").fill("2026-10");
    await page.getByTestId("exam-form-criteriaMonths-add").click();
    await expect(page.getByTestId("exam-form-criteriaMonths-chips")).toContainText("October 2026");
    await field(page, "showAnswersWithResults").check();

    await capture(page, "dialog-mcq", false);

    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page)).toBeHidden();
    await expect(page.getByTestId("admin-toast")).toContainText("“Unit Test 2” was created.");
    const posts = mock.find("POST", "/v1/exams");
    expect(posts.filter((item) => item.path === "/v1/exams")).toHaveLength(1);
    const body = posts.find((item) => item.path === "/v1/exams")!.body as Record<string, unknown>;
    expect(body).toMatchObject({
      type: "MCQ",
      name: "Unit Test 2",
      courseId: BIO_ID,
      password: "secret1",
      durationMinutes: 45,
      questionCount: 4,
      cutoffMarks: 3,
      moduleQuotas: [
        { moduleId: MOD_A, questionCount: 2 },
        { moduleId: MOD_B, questionCount: 2 },
      ],
      shuffleQuestions: true,
      shuffleAnswers: false,
      enrollAllStudents: false,
      autoEnrollOnPayment: true,
      paymentCriteria: { kind: "MONTHS", months: ["2026-10"] },
      showAnswersWithResults: true,
      totalMarks: null,
      questionPaperMediaId: null,
    });
    expect(new Date(body.endAt as string).getTime() - new Date(body.startAt as string).getTime()).toBe(2 * 60 * 60 * 1000);

    await page.getByTestId("exam-tab-upcoming").click();
    await expect(page.getByTestId("exam-tab-count-upcoming")).toHaveText("3");
    await expect(page.getByTestId("exam-card").filter({ hasText: "Unit Test 2" })).toBeVisible();
  });

  test("client validation blocks an empty MCQ and a server 400 lands on the field", async ({ page }) => {
    const mock = await mockExamConfig(page, "TENANT_ADMIN", {
      createFieldErrors: [{ field: "name", message: "An exam with this name already exists." }],
    });
    await page.goto("/tenant-admin/exams");
    await openNewExam(page);
    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page).getByText("Name is required.")).toBeVisible();
    await expect(dialog(page).getByText("Exam password is required.")).toBeVisible();
    await expect(dialog(page).getByText("Course is required.")).toBeVisible();
    expect(mock.find("POST", "/v1/exams")).toHaveLength(0);

    await fillCommon(page, "Duplicate");
    await field(page, "questionCount").fill("3");
    await field(page, "cutoffMarks").fill("1");
    await field(page, "courseId").selectOption(BIO_ID);
    await pickModules(page, ["2027 (3 questions)"]);
    await page.getByRole("spinbutton", { name: "2027 Number of questions from this module" }).fill("3");
    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page).getByText("An exam with this name already exists.")).toBeVisible();
    await expect(field(page, "name")).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByTestId("exam-form-error")).toContainText("Please fix the highlighted fields.");
  });

  test("auto-enroll is explained and disabled for a FREE course", async ({ page }) => {
    await mockExamConfig(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/exams");
    await openNewExam(page);
    await field(page, "courseId").selectOption(CHEM_ID);
    await expect(page.getByRole("switch", { name: "Auto-enroll students on payment" })).toBeDisabled();
    await expect(page.getByTestId("exam-form-auto-enroll-note")).toContainText("isn't available for free courses");
  });

  test("written exam with question paper upload", async ({ page }) => {
    const mock = await mockExamConfig(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/exams");
    await openNewExam(page);
    await page.getByTestId("exam-form-type-WRITTEN").click();
    await expect(page.getByTestId("exam-form-type-WRITTEN")).toHaveAttribute("aria-selected", "true");
    await expect(field(page, "questionCount")).toHaveCount(0);

    await fillCommon(page, "Essay Paper");
    await field(page, "courseId").selectOption(BIO_ID);
    await field(page, "totalMarks").fill("100");

    // Wrong type is rejected client-side, before any upload.
    await page.getByTestId("exam-form-questionPaper-input").setInputFiles({
      name: "notes.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("hello"),
    });
    await expect(dialog(page).getByText("Attach a PDF, PNG or JPEG file.")).toBeVisible();
    expect(mock.find("POST", "/v1/exam-documents")).toHaveLength(0);

    await page.getByTestId("exam-form-questionPaper-input").setInputFiles({
      name: "question-paper.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("%PDF-1.4\n"),
    });
    await expect(page.getByTestId("exam-form-questionPaper-file")).toHaveText("question-paper.pdf");
    expect(mock.find("POST", "/v1/exam-documents?purpose=QUESTION_PAPER")).toHaveLength(1);
    await page.getByTestId("exam-form-markingScheme-input").setInputFiles({
      name: "marking-scheme.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("%PDF-1.4\n"),
    });
    await expect(page.getByTestId("exam-form-markingScheme-file")).toHaveText("marking-scheme.pdf");
    await field(page, "enrollAllStudents").check();

    await capture(page, "dialog-written", false);

    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page)).toBeHidden();
    const body = mock.find("POST", "/v1/exams").find((item) => item.path === "/v1/exams")!.body as Record<string, unknown>;
    expect(body).toMatchObject({
      type: "WRITTEN",
      name: "Essay Paper",
      totalMarks: 100,
      questionCount: null,
      moduleQuotas: null,
      enrollAllStudents: true,
      autoEnrollOnPayment: false,
      paymentCriteria: null,
    });
    expect(body.questionPaperMediaId).toMatch(/^doc-/);
    expect(body.markingSchemeMediaId).toMatch(/^doc-/);
  });

  test("physical exam", async ({ page }) => {
    const mock = await mockExamConfig(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/exams");
    await openNewExam(page);
    await page.getByTestId("exam-form-type-PHYSICAL").click();
    await expect(field(page, "password")).toHaveCount(0);
    await expect(page.getByLabel("Scheduled Date")).toBeVisible();
    await expect(page.getByLabel("Scheduled End Time")).toBeVisible();
    await field(page, "courseId").selectOption(BIO_ID);
    await field(page, "name").fill("Practical Test");
    await field(page, "fromDate").fill("2026-12-01");
    await field(page, "fromTime").fill("08:00");
    await field(page, "toDate").fill("2026-12-01");
    await field(page, "toTime").fill("07:00");
    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page).getByText("The exam must end after it starts.")).toBeVisible();
    await field(page, "toTime").fill("10:00");
    await field(page, "durationMinutes").fill("120");

    await capture(page, "dialog-physical", false);

    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page)).toBeHidden();
    const body = mock.find("POST", "/v1/exams").find((item) => item.path === "/v1/exams")!.body as Record<string, unknown>;
    expect(body).toMatchObject({
      type: "PHYSICAL",
      name: "Practical Test",
      password: null,
      durationMinutes: 120,
      enrollAllStudents: true,
      moduleQuotas: null,
      questionPaperMediaId: null,
    });
  });
});

test.describe("Exam dialog — edit", () => {
  async function openEdit(page: Page, examName: string, tab: "upcoming" | "previous") {
    await page.getByTestId(`exam-tab-${tab}`).click(NAV);
    const card = page.getByTestId("exam-card").filter({ hasText: examName });
    await card.getByRole("button", { name: `Actions for ${examName}` }).click();
    await page.getByTestId("exam-action-edit").click();
    await expect(dialog(page).getByRole("heading", { name: "Edit Exam" })).toBeVisible();
  }

  test("round-trip with the password left blank, and redraw", async ({ page }) => {
    const mock = await mockExamConfig(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/exams");
    await openEdit(page, "Unit Test 1", "upcoming");

    await expect(field(page, "name")).toHaveValue("Unit Test 1");
    await expect(field(page, "password")).toHaveValue("");
    await expect(dialog(page).getByText("Leave blank to keep the current password")).toBeVisible();
    await expect(field(page, "courseId")).toHaveValue(BIO_ID);
    await expect(field(page, "questionCount")).toHaveValue("3");
    await expect(field(page, "cutoffMarks")).toHaveValue("2");
    await expect(page.getByRole("spinbutton", { name: "2027 Number of questions from this module" })).toHaveValue("3");
    await expect(page.getByTestId("exam-form-marks-helper")).toHaveText("The chosen questions are worth 4.5 marks.");
    await expect(field(page, "shuffleQuestions")).toBeChecked();
    await expect(page.getByTestId("exam-form-type-WRITTEN")).toBeDisabled();
    await expect(page.getByTestId("exam-form-type-PHYSICAL")).toBeDisabled();

    await page.getByTestId("exam-form-redraw").click();
    await expect(page.getByTestId("exam-form-redraw-status")).toHaveText("A new set of 3 questions worth 4.5 marks was drawn.");
    expect(mock.find("POST", `/v1/exams/${EXAM_MCQ_UPCOMING}/paper/redraw`)).toHaveLength(1);

    await field(page, "name").fill("Unit Test 1 (revised)");
    await field(page, "cutoffMarks").fill("3");
    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page)).toBeHidden();
    await expect(page.getByTestId("admin-toast")).toContainText("was saved");
    const put = mock.find("PUT", `/v1/exams/${EXAM_MCQ_UPCOMING}`);
    expect(put).toHaveLength(1);
    expect(put[0].body).toMatchObject({ name: "Unit Test 1 (revised)", password: null, cutoffMarks: 3, questionCount: 3 });

    await openEdit(page, "Unit Test 1 (revised)", "upcoming");
    await expect(field(page, "name")).toHaveValue("Unit Test 1 (revised)");
    await expect(field(page, "cutoffMarks")).toHaveValue("3");
    await expect(field(page, "password")).toHaveValue("");
  });

  test("a locked exam disables structural fields", async ({ page }) => {
    const mock = await mockExamConfig(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/exams");
    await openEdit(page, "Word Exam", "previous");
    await expect(page.getByTestId("exam-form-locked-note")).toBeVisible();
    for (const name of ["courseId", "password", "durationMinutes", "fromDate", "fromTime", "questionCount", "shuffleQuestions"]) {
      await expect(field(page, name)).toBeDisabled();
    }
    await expect(page.getByTestId("exam-form-modules")).toBeDisabled();
    await expect(page.getByRole("spinbutton", { name: "2027 Number of questions from this module" })).toBeDisabled();
    await expect(page.getByTestId("exam-form-redraw")).toHaveCount(0);
    await expect(field(page, "name")).toBeEnabled();
    await expect(field(page, "cutoffMarks")).toBeEnabled();
    await expect(field(page, "toTime")).toBeEnabled();

    await field(page, "name").fill("Word Exam (final)");
    await page.getByTestId("exam-form-submit").click();
    await expect(dialog(page)).toBeHidden();
    const body = mock.find("PUT", `/v1/exams/${EXAM_MCQ_LOCKED}`)[0].body as Record<string, unknown>;
    expect(body).toMatchObject({ name: "Word Exam (final)", password: null, questionCount: 4 });
  });

  test("409 EXAM_LOCKED shows the lock message and locks the form", async ({ page }) => {
    await mockExamConfig(page, "TENANT_ADMIN", { updateLockedOnce: true });
    await page.goto("/tenant-admin/exams");
    await openEdit(page, "Unit Test 1", "upcoming");
    await field(page, "questionCount").fill("2");
    await page.getByRole("spinbutton", { name: "2027 Number of questions from this module" }).fill("2");
    await page.getByTestId("exam-form-submit").click();
    await expect(page.getByTestId("exam-form-error")).toContainText("Students have already started this exam");
    await expect(page.getByTestId("exam-form-locked-note")).toBeVisible();
    await expect(field(page, "questionCount")).toBeDisabled();
    await expect(field(page, "questionCount")).toHaveValue("3");
    await expect(dialog(page)).toBeVisible();
  });
});

test.describe("Exam delete", () => {
  test("confirm deletes; a 409 is explained and the action disabled", async ({ page }) => {
    const mock = await mockExamConfig(page, "TENANT_ADMIN", { deleteConflictIds: [EXAM_MCQ_LOCKED] });
    await page.goto("/tenant-admin/exams");
    await page.getByTestId("exam-tab-upcoming").click(NAV);
    const physical = page.getByTestId("exam-card").filter({ hasText: "Term Test" });
    await physical.getByRole("button", { name: "Actions for Term Test" }).click();
    await page.getByTestId("exam-action-delete").click();
    await expect(page.getByTestId("confirm-delete-dialog")).toContainText("“Term Test” will be removed");
    await page.getByTestId("confirm-delete-submit").click();
    await expect(page.getByTestId("confirm-delete-dialog")).toBeHidden();
    await expect(page.getByTestId("exam-tab-count-upcoming")).toHaveText("1");
    expect(mock.find("DELETE", `/v1/exams/${EXAM_PHYSICAL}`)).toHaveLength(1);

    await page.getByTestId("exam-tab-previous").click();
    const word = page.getByTestId("exam-card").filter({ hasText: "Word Exam" });
    await word.getByRole("button", { name: "Actions for Word Exam" }).click();
    await page.getByTestId("exam-action-delete").click();
    await page.getByTestId("confirm-delete-submit").click();
    await expect(page.getByTestId("confirm-delete-error")).toContainText("Students have already taken this exam");
    await page.getByRole("button", { name: "Cancel" }).click();
    await word.getByRole("button", { name: "Actions for Word Exam" }).click();
    await expect(page.getByTestId("exam-action-delete")).toHaveAttribute("aria-disabled", "true");
    await expect(page.getByTestId("exam-delete-blocked")).toBeVisible();
  });
});

test.describe("Exams — Teacher", () => {
  test("teacher directory, nav entry, legacy tools and the manage/stats/leaderboard routes", async ({ page }) => {
    test.setTimeout(120_000);
    await mockExamConfig(page, "TEACHER");
    await page.goto("/teacher/exams", { timeout: 90_000 });
    await expect(page.getByRole("heading", { level: 1, name: "Exams" })).toBeVisible(NAV);
    await expect(page.getByRole("link", { name: "Exams", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("link", { name: "Course question bank (legacy)" })).toHaveAttribute("href", "/teacher/exams/questions");
    await expect(page.getByRole("link", { name: "Marking queue" })).toHaveAttribute("href", "/teacher/exams/marking");
    await expect(page.getByTestId("exam-new")).toBeVisible();

    await page.getByTestId("exam-tab-upcoming").click();
    await page.getByRole("link", { name: "Manage Unit Test 1" }).click();
    await expect(page).toHaveURL(new RegExp(`/teacher/exams/${EXAM_MCQ_UPCOMING}$`), NAV);
    await expect(page.getByTestId("exam-header-name")).toHaveText("Unit Test 1");
    await expect(page.getByTestId("exam-header")).toContainText("3 Questions");
    await expect(page.getByTestId("exam-header")).not.toContainText("secret");
    await page.getByTestId("exam-stats-link").click();
    await expect(page).toHaveURL(new RegExp(`/teacher/exams/${EXAM_MCQ_UPCOMING}/stats$`), NAV);
    await expect(page.getByRole("heading", { level: 1, name: "Exam statistics" })).toBeVisible();

    await page.goto("/teacher/exams/leaderboards");
    await expect(page.getByRole("heading", { level: 1, name: "Leaderboards" })).toBeVisible(NAV);
  });
});
