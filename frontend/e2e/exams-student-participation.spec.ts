import { test, expect, type Page } from "@playwright/test";
import {
  EXAM_OPEN,
  EXAM_PREVIOUS,
  EXAM_PREVIOUS_HIDDEN,
  EXAM_RESUME,
  EXAM_SUBMITTED,
  PASSWORD,
  SERVER_NOW,
  mockExamEnrollment,
  mockStudentExams,
  sampleAttempts,
} from "./fixtures/student-exam-mocks";
import { mockExamConfig, sampleExams, EXAM_MCQ_UPCOMING, EXAM_MCQ_LOCKED } from "./fixtures/exam-config-mocks";

/**
 * Klass Exam Module — Slice C (student participation + staff enrollment),
 * `docs/api/exam-management-v2.md` "Slice C". Every backend call is mocked by
 * a stateful dispatcher; the deadline test drives the browser clock with
 * `page.clock` instead of sleeping.
 */

async function startExam(page: Page, examId: string, password = PASSWORD) {
  await page.goto(`/student/exams/${examId}`);
  await page.getByLabel("Exam password").fill(password);
  await page.getByTestId("exam-start-button").click();
}

function optionTexts(page: Page) {
  return page.getByTestId("attempt-option").allInnerTexts();
}

test.describe("Student exam list", () => {
  test("groups enrolled exams into In progress / Upcoming / Previous with the right next step", async ({ page }) => {
    await mockStudentExams(page);
    await page.goto("/student/exams");

    await expect(page.getByRole("heading", { name: "My exams" })).toBeVisible();
    await expect(page.getByTestId("student-exams-count-in-progress")).toContainText("4");
    await expect(page.getByTestId("student-exams-count-upcoming")).toContainText("1");
    await expect(page.getByTestId("student-exams-count-previous")).toContainText("2");

    const panel = page.getByTestId("student-exams-panel-in-progress");
    const card = (name: string) => panel.getByTestId("student-exam-card").filter({ hasText: name });
    await expect(card("Cell Biology Quiz").getByTestId("exam-cta-start")).toHaveAttribute("href", `/student/exams/${EXAM_OPEN}`);
    await expect(card("Cell Biology Quiz")).toContainText("3 questions");
    await expect(card("Cell Biology Quiz")).toContainText("30 minutes");
    await expect(card("Cell Biology Quiz").getByTestId("attempt-status")).toHaveText("Not started");
    await expect(card("Genetics Sprint").getByTestId("exam-cta-resume")).toHaveAttribute("href", `/student/exams/${EXAM_RESUME}/take`);
    await expect(card("Genetics Sprint").getByTestId("attempt-status")).toHaveText("In progress");
    await expect(card("Ecology Check").getByTestId("exam-cta-pending")).toContainText("Results available after");
    await expect(card("Ecology Check").getByTestId("attempt-status")).toHaveText("Submitted");
    await expect(card("Essay Paper").getByTestId("exam-cta-details")).toBeVisible();

    await page.getByTestId("student-exams-tab-upcoming").click();
    const upcoming = page.getByTestId("student-exams-panel-upcoming").getByTestId("student-exam-card");
    await expect(upcoming).toHaveCount(1);
    await expect(upcoming).toContainText("Unit Test 2");
    await expect(upcoming).toContainText("in 5 days");

    await page.getByTestId("student-exams-tab-previous").click();
    const previous = page.getByTestId("student-exams-panel-previous");
    await expect(previous.getByTestId("student-exam-card").filter({ hasText: "Term Revision MCQ" }).getByTestId("exam-cta-result")).toHaveAttribute(
      "href",
      `/student/exams/${EXAM_PREVIOUS}/results`
    );
  });

  test("shows a single explanatory empty state when the student has no exam enrollments", async ({ page }) => {
    await mockStudentExams(page, { exams: [], attempts: [] });
    await page.goto("/student/exams");
    await expect(page.getByText("You aren't enrolled in any exams yet")).toBeVisible();
  });
});

test.describe("Starting an exam", () => {
  test("wrong password shows a field error; repeated failures show the rate-limit message", async ({ page }) => {
    const mock = await mockStudentExams(page, { rateLimitAfter: 2 });
    await page.goto(`/student/exams/${EXAM_OPEN}`);
    await expect(page.getByTestId("exam-rules")).toContainText("once");
    await expect(page.getByTestId("exam-rules")).toContainText("submitted automatically");

    await page.getByTestId("exam-start-button").click();
    await expect(page.getByText("Enter the exam password your teacher gave you.")).toBeVisible();
    expect(mock.find("POST", "/attempts")).toHaveLength(0);

    await page.getByLabel("Exam password").fill("nope");
    await page.getByTestId("exam-start-button").click();
    await expect(page.getByText("That password is incorrect.", { exact: false })).toBeVisible();
    await expect(page.getByLabel("Exam password")).toHaveAttribute("aria-invalid", "true");
    await expect(page).toHaveURL(new RegExp(`/student/exams/${EXAM_OPEN}$`));

    await page.getByTestId("exam-start-button").click();
    await page.getByTestId("exam-start-button").click();
    await expect(page.getByText("Too many incorrect passwords", { exact: false })).toBeVisible();
  });

  test("a closed exam reports CLOSED instead of starting", async ({ page }) => {
    await mockStudentExams(page, { startConflict: "CLOSED" });
    await startExam(page, EXAM_OPEN);
    await expect(page.getByTestId("exam-start-error")).toContainText("This exam has closed");
  });
});

test.describe("Taking an exam", () => {
  test("start → answer → reload resumes the same question/option order and the saved answers", async ({ page }) => {
    const mock = await mockStudentExams(page);
    await startExam(page, EXAM_OPEN);
    await expect(page).toHaveURL(new RegExp(`/student/exams/${EXAM_OPEN}/take$`));
    await expect(page.getByTestId("attempt-exam-name")).toHaveText("Cell Biology Quiz");

    // Persisted order: DNA question first (item 3), then item 1 with reversed options.
    await expect(page.getByTestId("attempt-question-body")).toContainText("DNA");
    await page.getByLabel("Option B:").check();
    await expect(page.getByTestId("attempt-save-status")).toHaveText("Saved");
    await page.getByTestId("attempt-next").click();
    await expect(page.getByTestId("attempt-question-body")).toContainText("ATP");
    const order = await optionTexts(page);
    expect(order.map((text) => text.trim().split("\n").pop()?.trim())).toEqual(["Golgi body", "Ribosome", "Mitochondrion"]);
    await page.getByText("Ribosome").click();
    await expect(page.getByTestId("attempt-save-status")).toHaveText("Saved");

    const saves = mock.find("PUT", "/items/");
    expect(saves).toHaveLength(2);
    const revisions = saves.map((save) => (save.body as { revision: number }).revision);
    expect(revisions[1]).toBeGreaterThan(revisions[0]);

    await page.reload();
    await expect(page.getByTestId("attempt-question-body")).toContainText("DNA");
    await expect(page.getByLabel("Option B:")).toBeChecked();
    await expect(page.getByTestId("attempt-answered-count")).toHaveText("2 of 3 answered");
    await page.getByTestId("attempt-view-all").click();
    await expect(page.getByTestId("attempt-question")).toHaveCount(3);
    const bodies = await page.getByTestId("attempt-question-body").allInnerTexts();
    expect(bodies[0]).toContain("DNA");
    expect(bodies[1]).toContain("ATP");
    await expect(page.locator(`input[value="1-1-b"]`)).toBeChecked();

    // After reload the counter continues above every revision already used.
    await page.locator(`label[data-option-id="1-2-b"]`).click();
    await expect(page.getByTestId("attempt-save-status")).toHaveText("Saved");
    const next = mock.find("PUT", "/items/").at(-1)!.body as { revision: number };
    expect(next.revision).toBeGreaterThan(Math.max(...revisions));

    // Navigator shows answered/unanswered for every question.
    await expect(page.getByRole("button", { name: "Question 1, answered" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Question 3, answered" })).toBeVisible();
    // No correct answers anywhere in the taking UI.
    await expect(page.getByText("Correct answer")).toHaveCount(0);
    await expect(page.locator("[data-correct]")).toHaveCount(0);
  });

  test("a resumed attempt continues revisions above the server's revision; stale responses are silent", async ({ page }) => {
    const mock = await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_RESUME}/take`);
    await expect(page.locator('input[value="2-1-b"]')).toBeChecked();

    mock.setStaleNextSave();
    await page.locator('label[data-option-id="2-1-c"]').click();
    await expect(page.getByTestId("attempt-save-status")).toHaveText("Saved");
    await expect(page.getByTestId("attempt-session").getByRole("alert")).toHaveCount(0);
    const first = mock.find("PUT", "/items/2-1")[0].body as { optionId: string; revision: number };
    expect(first).toEqual({ optionId: "2-1-c", revision: 8 });

    // The stale response reported a newer stored revision (107); the next save goes above it so it isn't lost.
    await page.locator('label[data-option-id="2-1-a"]').click();
    await expect(page.getByTestId("attempt-save-status")).toHaveText("Saved");
    expect((mock.find("PUT", "/items/2-1")[1].body as { revision: number }).revision).toBe(108);
    expect(mock.state.attempts.find((entry) => entry.id === "a-resume")!.selections["2-1"]).toEqual({ optionId: "2-1-a", revision: 108 });
  });

  test("a failed save shows 'Offline — retrying' and retries with the same revision until it succeeds", async ({ page }) => {
    const mock = await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_RESUME}/take`);
    await expect(page.getByTestId("attempt-exam-name")).toBeVisible();
    mock.setFailSaves(1);
    await page.locator('label[data-option-id="2-1-a"]').click();
    await expect(page.getByTestId("attempt-save-status")).toHaveText("Offline — retrying");
    await expect(page.getByTestId("attempt-save-status")).toHaveText("Saved", { timeout: 5_000 });
    const stored = mock.state.attempts.find((entry) => entry.id === "a-resume")!.selections["2-1"];
    expect(stored.optionId).toBe("2-1-a");
    // The retry reused the revision instead of inventing a new one.
    expect(stored.revision).toBe(8);
  });

  test("409 FINALIZED on save moves to the submitted screen", async ({ page }) => {
    const mock = await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_RESUME}/take`);
    await expect(page.getByTestId("attempt-exam-name")).toBeVisible();
    mock.setFinalizedOnSave();
    await page.locator('label[data-option-id="2-1-a"]').click();
    await expect(page).toHaveURL(new RegExp(`/student/exams/${EXAM_RESUME}/results\\?finished=closed$`));
    await expect(page.getByTestId("attempt-finished-banner")).toContainText("already been submitted");
  });

  test("submit asks for confirmation with the unanswered count", async ({ page }) => {
    const mock = await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_RESUME}/take`);
    await expect(page.getByTestId("attempt-answered-count")).toHaveText("1 of 3 answered");
    await page.getByTestId("attempt-submit-open").click();
    const dialog = page.getByTestId("attempt-submit-dialog");
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("attempt-submit-summary")).toContainText("You have 2 unanswered questions out of 3");

    await dialog.getByRole("button", { name: "Keep working" }).click();
    await expect(dialog).toBeHidden();
    expect(mock.find("POST", "/submit")).toHaveLength(0);

    await page.getByTestId("attempt-submit-open").click();
    await page.getByTestId("attempt-submit-confirm").click();
    await expect(page).toHaveURL(new RegExp(`/student/exams/${EXAM_RESUME}/results\\?finished=submitted$`));
    await expect(page.getByTestId("attempt-finished-banner")).toHaveText("Your answers were submitted.");
    await expect(page.getByTestId("result-not-released")).toContainText("Results will be available after");
    expect(mock.find("POST", "/submit")).toHaveLength(1);
  });

  test("the countdown follows the server clock and auto-submits at the deadline", async ({ page }) => {
    // The browser clock is 10 minutes fast; the timer must still follow serverNow.
    await page.clock.install({ time: new Date(new Date(SERVER_NOW).getTime() + 10 * 60_000) });
    const attempts = sampleAttempts();
    const resume = attempts.find((entry) => entry.id === "a-resume")!;
    resume.deadlineAt = new Date(new Date(SERVER_NOW).getTime() + 90_000).toISOString();
    const mock = await mockStudentExams(page, { attempts });
    await page.goto(`/student/exams/${EXAM_RESUME}/take`);

    const timer = page.getByTestId("attempt-timer");
    await expect(timer).toContainText(/1:(30|29|28)/);
    await page.clock.runFor(35_000);
    await expect(page.getByText("Less than 1 minute remaining.")).toBeAttached();

    mock.setNow(new Date(new Date(SERVER_NOW).getTime() + 91_000).toISOString());
    await page.clock.runFor(60_000);
    await expect(page).toHaveURL(new RegExp(`/student/exams/${EXAM_RESUME}/results\\?finished=timeout$`));
    await expect(page.getByTestId("attempt-finished-banner")).toHaveText("Time is up — your answers were submitted.");
    await expect(page.getByTestId("attempt-status")).toHaveText("Auto-submitted at the deadline");
  });
});

test.describe("Results", () => {
  test("released results show score, pass/fail, the student's choice, the correct option and the explanation", async ({ page }) => {
    await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_PREVIOUS}/results`);
    await expect(page.getByTestId("result-score")).toHaveText("1");
    await expect(page.getByTestId("result-total")).toHaveText("5");
    await expect(page.getByTestId("result-outcome")).toHaveText("Not passed");
    await expect(page.getByTestId("result-cutoff")).toHaveText("3 marks");
    await expect(page.getByTestId("result-answered")).toHaveText("2 of 3 questions");

    const items = page.getByTestId("result-item");
    await expect(items).toHaveCount(3);
    await expect(items.nth(0).getByTestId("result-item-outcome")).toContainText("Correct");
    const second = items.nth(1);
    await expect(second.getByTestId("result-item-outcome")).toContainText("Incorrect");
    await expect(second.locator('[data-selected="true"]')).toContainText("9");
    await expect(second.locator('[data-selected="true"]')).toContainText("Your answer");
    await expect(second.locator('[data-correct="true"]')).toContainText("3");
    await expect(second.locator('[data-correct="true"]')).toContainText("Correct answer");
    await expect(second.getByTestId("result-explanation")).toContainText("positive square root");
    await expect(items.nth(2).getByTestId("result-item-outcome")).toContainText("Not answered");
  });

  test("released score without answers when the exam hides them", async ({ page }) => {
    await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_PREVIOUS_HIDDEN}/results`);
    await expect(page.getByTestId("result-score")).toHaveText("2");
    await expect(page.getByTestId("result-outcome")).toHaveCount(0);
    await expect(page.getByTestId("result-answers-hidden")).toBeVisible();
    await expect(page.locator("[data-correct]")).toHaveCount(0);
  });

  test("before the window closes the result is not released and nothing correct is rendered", async ({ page }) => {
    const mock = await mockStudentExams(page);
    await page.goto(`/student/exams/${EXAM_SUBMITTED}/results`);
    await expect(page.getByTestId("result-not-released")).toContainText("Results will be available after");
    await expect(page.getByTestId("result-score")).toHaveCount(0);
    await expect(page.getByText("Correct answer")).toHaveCount(0);
    await expect(page.locator("[data-correct]")).toHaveCount(0);
    expect(mock.find("GET", "/result")).toHaveLength(1);
  });
});

test.describe("Staff exam enrollment", () => {
  test("enroll dialog searches candidates, multi-selects and reports counts", async ({ page }) => {
    const exams = sampleExams();
    await mockExamConfig(page, "TENANT_ADMIN", { exams });
    const enrollment = await mockExamEnrollment(page, EXAM_MCQ_UPCOMING, { alreadyEnrolledIds: ["s-3"] });
    await page.goto(`/tenant-admin/exams/${EXAM_MCQ_UPCOMING}`);

    await expect(page.getByTestId("exam-enrollment-actions")).toBeVisible();
    await expect(page.getByTestId("exam-sync-payments")).toHaveCount(0);
    await page.getByTestId("exam-enroll-open").click();
    const dialog = page.getByTestId("enroll-students-dialog");
    await expect(dialog.getByTestId("enroll-candidate")).toHaveCount(2);

    await dialog.getByLabel("Search students").fill("kamala");
    await expect(dialog.getByTestId("enroll-candidate")).toHaveCount(1);
    await dialog.getByTestId("enroll-candidate").filter({ hasText: "Kamala Silva" }).click();
    await dialog.getByLabel("Search students").fill("");
    await expect(dialog.getByTestId("enroll-candidate")).toHaveCount(2);
    await dialog.getByTestId("enroll-candidate").filter({ hasText: "Nimal Perera" }).click();
    await expect(dialog.getByTestId("enroll-selected-count")).toHaveText("2 selected");
    await dialog.getByTestId("enroll-submit").click();

    await expect(dialog).toBeHidden();
    await expect(page.getByTestId("admin-toast")).toHaveText(/2 students enrolled\./);
    const post = enrollment.requests.find((request) => request.method === "POST");
    expect((post?.body as { studentIds: string[] }).studentIds.sort()).toEqual(["s-1", "s-2"]);
  });

  test("sync payments is offered for auto-enroll exams and reports how many were added", async ({ page }) => {
    const exams = sampleExams().map((exam) =>
      exam.id === EXAM_MCQ_LOCKED ? { ...exam, autoEnrollOnPayment: true, paymentCriteria: { kind: "MONTHS", months: ["2026-10"] } } : exam
    );
    await mockExamConfig(page, "TENANT_ADMIN", { exams });
    await mockExamEnrollment(page, EXAM_MCQ_LOCKED, { syncAdded: 3 });
    await page.goto(`/tenant-admin/exams/${EXAM_MCQ_LOCKED}`);
    await expect(page.getByTestId("auto-enroll-badge")).toBeVisible();
    await page.getByTestId("exam-sync-payments").click();
    await expect(page.getByTestId("admin-toast")).toHaveText(/3 students enrolled from confirmed payments\./);
  });
});
