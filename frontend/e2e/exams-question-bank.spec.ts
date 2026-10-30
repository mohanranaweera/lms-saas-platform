import { test, expect, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
import {
  COURSE_ID,
  COURSE_NAME,
  MODULE_ID,
  MODULE_TITLE,
  OTHER_COURSE_ID,
  OTHER_COURSE_MODULE_ID,
  SECOND_MODULE_ID,
  bankUrl,
  mockQuestionBank,
  sampleQuestions,
} from "./fixtures/question-bank-mocks";

/**
 * Klass Exam Module — Slice A (question bank UI): module bank list,
 * create/edit with rich text + math + image, validation (client and server
 * 400 mapping), copy dialog, CSV imports (success, 422 row errors,
 * idempotency-key reuse), delete confirmation, teacher route, and states.
 * Mocked API only (see fixtures/question-bank-mocks.ts).
 */

const EVIDENCE_DIR = "../docs/qa/klass-evidence/exams";
/** Route changes may hit a cold dev-server compile. */
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
    await page.waitForTimeout(150);
    await expectNoHorizontalOverflow(page);
    await page.screenshot({ path: `${EVIDENCE_DIR}/slice-a-${name}-${viewport.name}.png`, fullPage });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
}

function editor(page: Page, testId: string) {
  return page.getByTestId(testId);
}

test.describe("Question bank — Tenant Admin", () => {
  test("modules list links to the bank via Manage Questions (with tooltip)", async ({ page }) => {
    await mockQuestionBank(page, "TENANT_ADMIN");
    await page.goto(`/tenant-admin/courses/${COURSE_ID}/modules`);
    const firstRow = page.getByTestId("module-row").first();
    const manage = firstRow.getByRole("link", { name: `Manage questions of ${MODULE_TITLE}` });
    await expect(manage).toBeVisible();
    await manage.hover();
    await expect(page.getByTestId("tooltip")).toHaveText("Manage Questions");
    await manage.click();
    await expect(page).toHaveURL(new RegExp(`/modules/${MODULE_ID}/questions$`), NAV);
    await expect(page.getByRole("heading", { level: 1, name: `Questions in ${MODULE_TITLE}` })).toBeVisible();
  });

  test("bank list: chips, rendered rich body, expand, answers, explanation, show/hide answers", async ({ page }) => {
    await mockQuestionBank(page, "TENANT_ADMIN");
    await page.goto(bankUrl("tenant-admin"));

    await expect(page.getByRole("heading", { level: 1, name: `Questions in ${MODULE_TITLE}` })).toBeVisible();
    const crumbs = page.getByRole("navigation", { name: "Breadcrumb" });
    await expect(crumbs.getByRole("link", { name: "Courses" })).toBeVisible();
    await expect(crumbs.getByRole("link", { name: COURSE_NAME })).toBeVisible();
    await expect(crumbs.getByText(`Questions in ${MODULE_TITLE}`)).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("tab", { name: "All questions" })).toHaveAttribute("aria-selected", "true");
    await expect(page.getByRole("tab", { name: "Imports" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Copy question" })).toBeEnabled();
    await expect(page.getByRole("link", { name: "Create question" })).toBeVisible();

    const rows = page.getByTestId("question-row");
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0).getByTestId("question-number")).toHaveText("Q1");
    await expect(rows.nth(0).getByTestId("question-marks-chip")).toHaveText("1 mark");
    await expect(rows.nth(1).getByTestId("question-marks-chip")).toHaveText("2 marks");
    await expect(rows.nth(2).getByTestId("question-marks-chip")).toHaveText("1.5 marks");
    // Image comes from the server-provided signed src; math is KaTeX-rendered.
    await expect(rows.nth(1).locator("img[data-media-id='media-1']")).toBeVisible();
    await expect(rows.nth(2).locator(".katex").first()).toBeVisible();

    const toggle = rows.nth(0).getByRole("button", { name: "Expand question 1" });
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await toggle.click();
    await expect(rows.nth(0).getByRole("button", { name: "Collapse question 1" })).toHaveAttribute("aria-expanded", "true");
    const answers = rows.nth(0).getByTestId("question-answer");
    await expect(answers).toHaveCount(5);
    await expect(answers.nth(0)).toContainText("Answer 1");
    await expect(answers.nth(0)).toContainText("Growth");
    await expect(answers.nth(4)).toHaveAttribute("data-correct", "true");
    await expect(answers.nth(4)).toContainText("Correct answer");
    await expect(rows.nth(0).getByTestId("question-details")).toContainText("Explanation");
    await expect(rows.nth(0).getByTestId("question-details")).toContainText("Evolution is change in heritable traits");

    const showHide = rows.nth(0).getByTestId("question-toggle-answers");
    await expect(showHide).toHaveText(/Hide answers/i);
    await expect(showHide).toHaveAttribute("aria-pressed", "true");
    await showHide.click();
    await expect(showHide).toHaveText(/Show answers/i);
    await expect(answers.nth(4)).not.toHaveAttribute("data-correct", "true");
    await showHide.click();
    await expect(answers.nth(4)).toHaveAttribute("data-correct", "true");

    // Expand the image + math rows too for the evidence screenshot.
    await rows.nth(2).getByRole("button", { name: "Expand question 3" }).click();
    await expect(rows.nth(2).getByTestId("question-details").locator(".katex-display")).toBeVisible();
    await capture(page, "bank");
  });

  test("create a question with formatting, math and an uploaded image", async ({ page }) => {
    const mock = await mockQuestionBank(page, "TENANT_ADMIN");
    await page.goto(bankUrl("tenant-admin"));
    await page.getByRole("link", { name: "Create question" }).click();
    await expect(page).toHaveURL(/\/questions\/new$/, NAV);
    await expect(page.getByRole("heading", { level: 1, name: `Add a Question to ${COURSE_NAME}` })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Breadcrumb" }).getByText("Create")).toHaveAttribute("aria-current", "page");
    await expect(page.getByLabel("Marks")).toHaveValue("1");

    // Question body: bold via toolbar, then plain text, then inline math.
    const body = editor(page, "question-body-editor");
    await expect(body).toHaveAttribute("role", "textbox");
    await body.click();
    const bold = page.getByTestId("question-body-editor-tool-bold");
    await bold.click();
    await expect(bold).toHaveAttribute("aria-pressed", "true");
    await page.keyboard.type("Quadratic");
    await bold.click();
    await expect(bold).toHaveAttribute("aria-pressed", "false");
    await page.keyboard.type(" roots of ");
    await page.getByTestId("question-body-editor-tool-math").click();
    const latexInput = page.getByTestId("math-latex-input");
    await expect(latexInput).toBeFocused();
    await latexInput.fill("x^2 - 5x + 6");
    await page.getByTestId("math-insert-submit").click();
    await expect(body.locator(".katex")).toBeVisible();

    // Image upload through POST /exam-media.
    await page.getByTestId("question-body-editor-image-input").setInputFiles({
      name: "diagram.png",
      mimeType: "image/png",
      buffer: Buffer.from("89504e470d0a1a0a", "hex"),
    });
    await expect(body.locator("img[data-media-id]")).toBeVisible();
    expect(mock.find("POST", "/v1/exam-media")).toHaveLength(1);

    // Toolbar keyboard support: arrow keys move between tools.
    await bold.focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByTestId("question-body-editor-tool-italic")).toBeFocused();

    await page.getByLabel("Marks").fill("2.5");

    // Answers: 2 by default, add a 3rd, mark answer 2 correct (exclusive).
    await editor(page, "answer-editor-1").click();
    await page.keyboard.type("x = 1, 6");
    await editor(page, "answer-editor-2").click();
    await page.keyboard.type("x = 2, 3");
    await page.getByTestId("answer-add").click();
    await expect(page.getByTestId("answer-block")).toHaveCount(3);
    await editor(page, "answer-editor-3").click();
    await page.keyboard.type("x = -2, -3");
    const correct1 = page.getByRole("checkbox", { name: "Answer 1 is the correct answer" });
    const correct2 = page.getByRole("checkbox", { name: "Answer 2 is the correct answer" });
    await expect(correct1).toBeChecked();
    await correct2.check();
    await expect(correct2).toBeChecked();
    await expect(correct1).not.toBeChecked();
    await expect(page.getByTestId("answer-correct-chip")).toHaveCount(1);

    await editor(page, "question-explanation-editor").click();
    await page.keyboard.type("Factorise: (x-2)(x-3).");

    await page.setViewportSize({ width: 1440, height: 900 });
    await capture(page, "editor");

    await page.getByRole("button", { name: "Save question" }).click();
    await expect(page).toHaveURL(new RegExp(`/modules/${MODULE_ID}/questions$`), NAV);
    await expect(page.getByTestId("admin-toast")).toContainText("Question created.");

    const posts = mock.find("POST", `/modules/${MODULE_ID}/questions`).filter((item) => !item.path.includes("copy"));
    expect(posts).toHaveLength(1);
    const sent = posts[0].body as {
      bodyHtml: string;
      marks: number;
      explanationHtml: string | null;
      options: Array<{ id: string | null; html: string; correct: boolean }>;
    };
    expect(sent.bodyHtml).toContain("<strong>Quadratic</strong>");
    expect(sent.bodyHtml).toContain('data-math="inline"');
    expect(sent.bodyHtml).toContain('data-latex="x^2 - 5x + 6"');
    expect(sent.bodyHtml).toMatch(/<img data-media-id="media-\d+">/);
    expect(sent.bodyHtml).not.toContain("src=");
    expect(sent.marks).toBe(2.5);
    expect(sent.explanationHtml).toContain("Factorise");
    expect(sent.options.map((item) => item.correct)).toEqual([false, true, false]);
    expect(sent.options.every((item) => item.id === null)).toBe(true);
    await expect(page.getByTestId("question-row")).toHaveCount(4);
  });

  test("client validation mirrors the server, and server 400 field errors map to fields", async ({ page }) => {
    const mock = await mockQuestionBank(page, "TENANT_ADMIN", {
      upsertFieldErrors: [
        { field: "options[1].html", message: "An image is not available - upload it again" },
        { field: "marks", message: "Marks must have at most 2 decimals" },
      ],
    });
    await page.goto(`${bankUrl("tenant-admin")}/new`);
    await expect(page.getByRole("heading", { level: 1, name: `Add a Question to ${COURSE_NAME}` })).toBeVisible();

    await page.getByLabel("Marks").fill("0");
    await page.getByRole("checkbox", { name: "Answer 1 is the correct answer" }).uncheck();
    await page.getByRole("button", { name: "Save question" }).click();
    await expect(page.getByText("Question is required.")).toBeVisible();
    await expect(page.getByText("Marks must be greater than 0.")).toBeVisible();
    await expect(page.getByText("Answer 1 is required.")).toBeVisible();
    await expect(page.getByText("Answer 2 is required.")).toBeVisible();
    await expect(page.getByTestId("answers-error")).toHaveText("Select the correct answer.");
    await expect(editor(page, "question-body-editor")).toHaveAttribute("aria-invalid", "true");
    expect(mock.find("POST", "/questions")).toHaveLength(0);

    for (const [value, message] of [
      ["101", "Marks must be 100 or less."],
      ["1.234", "Marks can have at most 2 decimal places."],
      ["abc", "Marks must be a number."],
    ] as const) {
      await page.getByLabel("Marks").fill(value);
      await expect(page.getByText(message)).toBeVisible();
    }
    // Image-only / math-only content counts as non-empty.
    await editor(page, "question-body-editor").click();
    await page.getByTestId("question-body-editor-tool-math").click();
    await page.getByTestId("math-latex-input").fill("E = mc^2");
    await page.getByTestId("math-latex-input").press("Enter");
    await page.getByLabel("Marks").fill("1");
    await editor(page, "answer-editor-1").click();
    await page.keyboard.type("Yes");
    await editor(page, "answer-editor-2").click();
    await page.keyboard.type("No");
    await page.getByRole("checkbox", { name: "Answer 2 is the correct answer" }).check();
    await expect(page.getByTestId("answers-error")).toHaveCount(0);

    await page.getByRole("button", { name: "Save question" }).click();
    await expect(page.getByText("An image is not available - upload it again")).toBeVisible();
    await expect(page.getByText("Marks must have at most 2 decimals")).toBeVisible();
    await expect(editor(page, "answer-editor-2")).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByTestId("question-form-error")).toBeVisible();
    await expect(page).toHaveURL(/\/questions\/new$/, NAV);
    const sent = mock.find("POST", "/questions")[0].body as { bodyHtml: string };
    expect(sent.bodyHtml).toContain('data-latex="E = mc^2"');
  });

  test("edit round-trip keeps option ids and updates the bank", async ({ page }) => {
    const mock = await mockQuestionBank(page, "TENANT_ADMIN");
    const question = sampleQuestions()[0];
    await page.goto(bankUrl("tenant-admin"));
    const row = page.getByTestId("question-row").first();
    await row.getByRole("button", { name: "Expand question 1" }).click();
    await row.getByRole("link", { name: "Edit question 1" }).click();
    await expect(page).toHaveURL(new RegExp(`/questions/${question.id}/edit$`), NAV);
    await expect(page.getByRole("heading", { level: 1, name: `Edit a Question in ${COURSE_NAME}` })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Breadcrumb" }).getByText("Edit")).toHaveAttribute("aria-current", "page");

    await expect(page.getByLabel("Marks")).toHaveValue("1");
    await expect(page.getByTestId("answer-block")).toHaveCount(5);
    await expect(editor(page, "answer-editor-1")).toContainText("Growth");
    await expect(page.getByRole("checkbox", { name: "Answer 5 is the correct answer" })).toBeChecked();
    await expect(editor(page, "question-explanation-editor")).toContainText("Evolution is change");

    await page.getByLabel("Marks").fill("3");
    await page.getByRole("button", { name: "Delete answer 2" }).click();
    await expect(page.getByTestId("answer-block")).toHaveCount(4);
    await expect(editor(page, "answer-editor-2")).toContainText("Adaptation");
    await page.getByTestId("answer-add").click();
    await editor(page, "answer-editor-5").click();
    await page.keyboard.type("Natural selection");
    await page.getByRole("button", { name: "Save question" }).click();

    await expect(page).toHaveURL(new RegExp(`/modules/${MODULE_ID}/questions$`), NAV);
    await expect(page.getByTestId("admin-toast")).toContainText("Question saved.");
    const put = mock.find("PUT", `/v1/exam-questions/${question.id}`);
    expect(put).toHaveLength(1);
    const sent = put[0].body as { marks: number; options: Array<{ id: string | null; correct: boolean }> };
    expect(sent.marks).toBe(3);
    expect(sent.options.map((item) => item.id)).toEqual(["o1-1", "o1-3", "o1-4", "o1-5", null]);
    expect(sent.options.map((item) => item.correct)).toEqual([false, false, false, true, false]);
    await expect(page.getByTestId("question-row").first().getByTestId("question-marks-chip")).toHaveText("3 marks");
  });

  test("copy dialog copies all questions to a module of another course", async ({ page }) => {
    const mock = await mockQuestionBank(page, "TENANT_ADMIN");
    await page.goto(bankUrl("tenant-admin"));
    await page.getByRole("button", { name: "Copy question" }).click();
    const dialog = page.getByTestId("copy-questions-dialog");
    await expect(dialog.getByRole("heading", { name: "Copy questions to another module" })).toBeVisible();
    await expect(dialog).toContainText("All 3 questions");

    const course = dialog.getByLabel("Course");
    const moduleSelect = dialog.getByLabel("Module");
    await expect(course).toHaveValue(COURSE_ID);
    // The current module is excluded.
    await expect(moduleSelect.locator("option")).toHaveText(["Select a module", "Test"]);
    await dialog.getByRole("button", { name: "Copy" }).click();
    await expect(dialog.getByText("Module is required.")).toBeVisible();

    await course.selectOption(OTHER_COURSE_ID);
    await expect(moduleSelect.locator("option")).toHaveText(["Select a module", "Organic"]);
    await moduleSelect.selectOption(OTHER_COURSE_MODULE_ID);
    await capture(page, "copy-dialog", false);
    await dialog.getByRole("button", { name: "Copy" }).click();

    await expect(dialog).toBeHidden();
    await expect(page.getByTestId("admin-toast")).toContainText("Copied 3 questions to “Organic”.");
    const copies = mock.find("POST", `/modules/${MODULE_ID}/questions/copy`);
    expect(copies).toHaveLength(1);
    expect(copies[0].body).toEqual({ targetCourseId: OTHER_COURSE_ID, targetModuleId: OTHER_COURSE_MODULE_ID });
  });

  test("imports tab: history, template download, 422 row errors, retry reuses the idempotency key", async ({ page }) => {
    const mock = await mockQuestionBank(page, "TENANT_ADMIN", {
      importOutcomes: ["422", "201"],
      importErrors: [
        { row: 3, column: "answer 2", message: "The answer must not be empty" },
        { row: 7, column: "marks", message: "Marks must be greater than 0" },
      ],
    });
    await page.goto(bankUrl("tenant-admin"));
    await page.getByRole("tab", { name: "Imports" }).click();
    const table = page.getByTestId("imports-table");
    for (const header of ["File Name", "Imported Date", "Number of Questions", "Actions"]) {
      await expect(table.getByRole("columnheader", { name: header })).toBeVisible();
    }
    await expect(table).toContainText("biology-unit-1.csv");
    await expect(table).toContainText("1–2 of 2");

    await page.getByTestId("imports-search").fill("unit-2");
    await expect(table).toContainText("1–1 of 1");
    await expect(table).not.toContainText("biology-unit-1.csv");
    await page.getByTestId("imports-search").fill("");
    await expect(table).toContainText("1–2 of 2");

    await table.getByRole("button", { name: "View details of biology-unit-1.csv" }).click();
    await expect(page.getByTestId("import-details")).toContainText("Asha Perera");
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("import-details")).toBeHidden();
    await capture(page, "imports");

    await page.getByRole("button", { name: "Import questions" }).first().click();
    const dialog = page.getByTestId("import-questions-dialog");
    await expect(dialog).toContainText("Please select the CSV file which contains the questions to import.");
    await dialog.getByRole("button", { name: "download the sample template" }).click();
    await expect.poll(() => mock.find("GET", "/v1/question-imports/template.csv").length).toBe(1);

    await dialog.getByRole("button", { name: "Import" }).click();
    await expect(dialog.getByText("File is required.")).toBeVisible();

    const csv = { name: "questions.csv", mimeType: "text/csv", buffer: Buffer.from("question,correct answer,answer 2\nQ,A,B\n") };
    await dialog.getByLabel("CSV file").setInputFiles(csv);
    await dialog.getByRole("button", { name: "Import" }).click();
    const rowErrors = dialog.getByTestId("import-row-errors");
    await expect(rowErrors.getByRole("listitem")).toHaveText([
      "Row 3, answer 2: The answer must not be empty",
      "Row 7, marks: Marks must be greater than 0",
    ]);
    await expect(dialog.getByTestId("import-error")).toContainText("Nothing was imported");
    await capture(page, "import-dialog-errors", false);

    // Retry the same file: the same Idempotency-Key is sent.
    await dialog.getByRole("button", { name: "Import" }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId("admin-toast")).toContainText("Imported 2 questions from questions.csv.");
    const posts = mock.find("POST", "/question-imports");
    expect(posts).toHaveLength(2);
    const firstKey = posts[0].headers["idempotency-key"];
    expect(firstKey).toBeTruthy();
    expect(posts[1].headers["idempotency-key"]).toBe(firstKey);
    await expect(page.getByTestId("imports-table")).toContainText("1–3 of 3");
  });

  test("delete asks for confirmation and removes the question", async ({ page }) => {
    const mock = await mockQuestionBank(page, "TENANT_ADMIN");
    await page.goto(bankUrl("tenant-admin"));
    const rows = page.getByTestId("question-row");
    await expect(rows).toHaveCount(3);
    await rows.nth(1).getByRole("button", { name: "Expand question 2" }).click();
    await rows.nth(1).getByRole("button", { name: "Delete question 2" }).click();
    const dialog = page.getByTestId("confirm-delete-dialog");
    await expect(dialog).toContainText("Delete question 2?");
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toBeHidden();
    expect(mock.find("DELETE", "/v1/exam-questions/")).toHaveLength(0);

    await rows.nth(1).getByRole("button", { name: "Delete question 2" }).click();
    await dialog.getByRole("button", { name: "Delete question" }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId("admin-toast")).toContainText("Question 2 deleted.");
    await expect(rows).toHaveCount(2);
    expect(mock.find("DELETE", "/v1/exam-questions/q0000000-0000-0000-0000-000000000002")).toHaveLength(1);
  });

  test("empty module shows a contextual empty state with a create action", async ({ page }) => {
    await mockQuestionBank(page, "TENANT_ADMIN");
    await page.goto(bankUrl("tenant-admin", SECOND_MODULE_ID));
    await expect(page.getByRole("heading", { level: 1, name: "Questions in Test" })).toBeVisible();
    await expect(page.getByText("No questions in this module yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Copy question" })).toBeDisabled();
    await page.getByRole("button", { name: "Create question" }).click();
    await expect(page).toHaveURL(new RegExp(`/modules/${SECOND_MODULE_ID}/questions/new$`), NAV);
  });

  test("403 from the bank renders the permission-denied state", async ({ page }) => {
    await mockQuestionBank(page, "TENANT_ADMIN", {
      listFailure: { status: 403, code: "FORBIDDEN", message: "You do not have access to this course's questions." },
    });
    await page.goto(bankUrl("tenant-admin"));
    await expect(page.getByText(/permission|access/i).first()).toBeVisible();
    await expect(page.getByTestId("question-row")).toHaveCount(0);
  });

  test("unknown module renders an error state", async ({ page }) => {
    await mockQuestionBank(page, "TENANT_ADMIN");
    await page.goto(bankUrl("tenant-admin", "m9999999-0000-0000-0000-000000000000"));
    await expect(page.getByText("This module was not found in the course.")).toBeVisible();
  });
});

test.describe("Question bank — Teacher", () => {
  test("teacher modules page links to the teacher bank route", async ({ page }) => {
    await mockQuestionBank(page, "TEACHER");
    await page.goto(`/teacher/courses/${COURSE_ID}/modules`);
    const manage = page.getByRole("link", { name: `Manage questions of "${MODULE_TITLE}"` });
    await expect(manage).toBeVisible();
    await manage.click();
    await expect(page).toHaveURL(new RegExp(`/teacher/courses/${COURSE_ID}/modules/${MODULE_ID}/questions$`), NAV);
    await expect(page.getByTestId("question-row")).toHaveCount(3);
    await expect(page.getByRole("navigation", { name: "Breadcrumb" }).getByRole("link", { name: "Courses" })).toHaveAttribute(
      "href",
      "/teacher/courses"
    );
    await page.getByRole("link", { name: "Create question" }).click();
    await expect(page).toHaveURL(new RegExp(`/teacher/courses/${COURSE_ID}/modules/${MODULE_ID}/questions/new$`), NAV);
    await expect(page.getByRole("heading", { level: 1, name: `Add a Question to ${COURSE_NAME}` })).toBeVisible();
  });
});
