import { test, expect } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import {
  courseResponseBody,
  examQuestionResponseBody,
  examResponseBody,
  mockTenantSession,
} from "./fixtures/exams-mocks";

/**
 * MVP-017 "Exams" — scenario 1 (plan §18's Playwright section): "Teacher
 * authoring form (Question Bank + Scheduler) is fully keyboard-navigable";
 * MCQ option reordering and the Scheduler's question-picker ordering use
 * explicit "Move up"/"Move down" buttons, never drag-and-drop — mirrors
 * `material-reorder-keyboard.spec.ts`'s `.focus()` + `page.keyboard.press`
 * pattern (no mouse clicks to drive the move itself).
 */

const COURSE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const EXAM_ID = "e0000000-0000-0000-0000-000000000001";
const QUESTION_A_ID = "q0000000-0000-0000-0000-00000000000a";
const QUESTION_B_ID = "q0000000-0000-0000-0000-00000000000b";
const QUESTION_A_BODY = "What is the capital of France?";
const QUESTION_B_BODY = "What is the boiling point of water in Celsius?";

test.describe("Teacher Question Bank (screen #4) — keyboard-only authoring", () => {
  test("the create-question form and MCQ option reordering are fully keyboard-operable, with no drag-and-drop affordance", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    // Single dispatcher (method-branched) rather than two overlapping
    // `mockJson` globs — both `GET .../questions?page=...` and
    // `POST .../questions` match the same `**/questions*` pattern, and
    // Playwright resolves overlapping routes LIFO, so a naive second
    // `mockJson` registration for the POST case would silently swallow the
    // GET too.
    await page.route(`**/v1/exams/courses/${COURSE_ID}/questions*`, async (route) => {
      if (route.request().method() === "POST") {
        await fulfillJson(
          route,
          200,
          apiSuccess(
            examQuestionResponseBody({
              id: QUESTION_A_ID,
              courseId: COURSE_ID,
              questionType: "MCQ",
              body: QUESTION_A_BODY,
            })
          )
        );
        return;
      }
      await fulfillJson(route, 200, apiPageSuccess([]));
    });

    await page.goto("/teacher/exams/questions");
    await page.getByLabel("Course", { exact: true }).click();
    await page.getByRole("option", { name: "Intro to Biology" }).click();

    // The toolbar toggle is the only "Add question"-labelled control now
    // (post-Fix-7, the empty-state's own CTA reads "Add your first
    // question" instead, so there's no accessible-name collision to
    // disambiguate here).
    await page.getByRole("button", { name: "Add question" }).click();

    // Question text — reachable and typeable via keyboard alone.
    const bodyField = page.getByLabel("Question text");
    await bodyField.focus();
    await expect(bodyField).toBeFocused();
    await page.keyboard.type(QUESTION_A_BODY);

    // Two default MCQ options are already rendered (QUESTION_CREATE_DEFAULT_VALUES).
    const option1 = page.getByLabel("Option 1 text");
    await option1.focus();
    await page.keyboard.type("Paris");
    // Tab from the option-text input lands on its "This option is correct" checkbox next.
    await page.keyboard.press("Tab");
    const option1Correct = page.getByLabel("This option is correct").first();
    await expect(option1Correct).toBeFocused();
    await page.keyboard.press("Space");
    await expect(option1Correct).toBeChecked();

    const option2 = page.getByLabel("Option 2 text");
    await option2.focus();
    await page.keyboard.type("London");

    // Add a third option via the keyboard-activatable "Add option" control.
    const addOptionButton = page.getByRole("button", { name: "Add option" });
    await addOptionButton.focus();
    await page.keyboard.press("Enter");
    const option3 = page.getByLabel("Option 3 text");
    await expect(option3).toBeVisible();
    await option3.focus();
    await page.keyboard.type("Berlin");

    // No drag-and-drop affordance anywhere on this form — reordering is
    // exclusively via the explicit Move up/down buttons asserted below.
    await expect(page.locator('[draggable="true"]')).toHaveCount(0);

    // Reorder option 3 ("Berlin") above option 2 ("London") purely via keyboard.
    const moveUpOption3 = page.getByRole("button", { name: "Move option 3 up" });
    await moveUpOption3.focus();
    await page.keyboard.press("Enter");

    await expect(page.getByLabel("Option 2 text")).toHaveValue("Berlin");
    await expect(page.getByLabel("Option 3 text")).toHaveValue("London");

    // The first option's "Move up" stays present but disabled/unreachable-effect at the boundary.
    await expect(page.getByRole("button", { name: "Move option 1 up" })).toBeDisabled();

    // Submitting the whole form via keyboard alone completes the round trip.
    const submitButton = page.getByRole("button", { name: "Create question" });
    await submitButton.focus();
    await page.keyboard.press("Enter");

    // `onCreated` collapses the form back to the "Add question" trigger on success.
    await expect(page.getByRole("button", { name: "Create question" })).toHaveCount(0);
  });
});

test.describe("Teacher Exam Scheduler (screen #5) — keyboard-only question-picker ordering", () => {
  test("adding and reordering linked questions via the picker uses only keyboard-activatable Move up/down controls, no drag-and-drop", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "DRAFT", questions: [] }))
    );
    await mockJson(
      page,
      `**/v1/exams/courses/${COURSE_ID}/questions*`,
      200,
      apiPageSuccess([
        examQuestionResponseBody({ id: QUESTION_A_ID, courseId: COURSE_ID, questionType: "STRUCTURED", body: QUESTION_A_BODY }),
        examQuestionResponseBody({ id: QUESTION_B_ID, courseId: COURSE_ID, questionType: "STRUCTURED", body: QUESTION_B_BODY }),
      ])
    );

    await page.goto(`/teacher/exams/${EXAM_ID}/schedule`);

    await expect(page.getByText("No questions linked yet — scheduling requires at least one.")).toBeVisible();

    const questionARow = page.locator("li", { hasText: QUESTION_A_BODY });
    const questionBRow = page.locator("li", { hasText: QUESTION_B_BODY });

    // Add question A, then question B, via a keyboard-activated "Add to exam" button each time.
    const addA = questionARow.getByRole("button", { name: "Add to exam" });
    await addA.focus();
    await page.keyboard.press("Enter");

    const addB = questionBRow.getByRole("button", { name: "Add to exam" });
    await addB.focus();
    await page.keyboard.press("Enter");

    const inExamHeading = page.getByRole("heading", { name: "Questions in this exam (in order)" });
    const inExamItems = inExamHeading.locator("xpath=..").locator("ul li");
    await expect(inExamItems).toHaveCount(2);
    await expect(inExamItems.nth(0)).toContainText(QUESTION_A_BODY);
    await expect(inExamItems.nth(1)).toContainText(QUESTION_B_BODY);

    // No drag-and-drop affordance anywhere in the picker.
    await expect(page.locator('[draggable="true"]')).toHaveCount(0);

    // Move question B ("boiling point") above question A ("capital of France") via the keyboard alone.
    const moveUpSecond = page.getByRole("button", { name: "Move question 2 up" });
    await moveUpSecond.focus();
    await page.keyboard.press("Enter");

    await expect(inExamItems.nth(0)).toContainText(QUESTION_B_BODY);
    await expect(inExamItems.nth(1)).toContainText(QUESTION_A_BODY);
  });
});

test.describe("Teacher Question Bank (screen #4) — 409 QUESTION_IN_USE on edit", () => {
  test("editing a question already linked to a scheduled/answered exam surfaces the distinct QUESTION_IN_USE message, not a generic failure", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    // MCQ here (not STRUCTURED) simply because this test wants to exercise
    // the "must re-select a correct option" re-entry flow along the way to
    // the 409 — see the dedicated STRUCTURED-edit test below for coverage of
    // the no-options edit path.
    await mockJson(
      page,
      `**/v1/exams/courses/${COURSE_ID}/questions*`,
      200,
      apiPageSuccess([
        examQuestionResponseBody({
          id: QUESTION_A_ID,
          courseId: COURSE_ID,
          questionType: "MCQ",
          body: QUESTION_A_BODY,
          options: [
            { id: "opt00000-0000-0000-0000-000000000001", optionText: "Paris" },
            { id: "opt00000-0000-0000-0000-000000000002", optionText: "London" },
          ],
        }),
      ])
    );
    const conflictMessage = "It is linked to a scheduled exam.";
    await mockJson(
      page,
      `**/v1/exams/questions/${QUESTION_A_ID}`,
      409,
      apiError("QUESTION_IN_USE", conflictMessage)
    );

    await page.goto("/teacher/exams/questions");
    await page.getByLabel("Course", { exact: true }).click();
    await page.getByRole("option", { name: "Intro to Biology" }).click();

    await expect(page.getByText(QUESTION_A_BODY)).toBeVisible();
    await page.getByRole("button", { name: "Edit" }).click();

    // MCQ re-requires re-selecting a correct option — the backend never
    // discloses which option was previously correct (see this form's own
    // "For security…" banner) — otherwise the "at least one option must be
    // marked correct" client-side rule blocks submission before any request
    // is made, same as the dedicated Zod-validation test below.
    await page.getByLabel("This option is correct").first().check();
    await page.getByRole("button", { name: "Save changes" }).click();

    const conflictAlert = page.getByRole("alert").filter({ hasText: "This question can't be edited this way" });
    await expect(conflictAlert).toBeVisible();
    await expect(conflictAlert).toContainText(conflictMessage);
    await expect(conflictAlert).toContainText("It's already linked to a scheduled exam or has been answered.");
  });
});

test.describe("Teacher Question Bank (screen #4) — editing a STRUCTURED question", () => {
  test("editing a STRUCTURED question's body and saving fires the update request and shows success, with no options ever rendered", async ({
    page,
  }) => {
    // Regression test for a real bug: `buildQuestionEditSchema`'s base
    // `options` field used to validate every option's `optionText` as
    // non-empty unconditionally, even though `QuestionEditForm` seeds two
    // blank placeholder options into `defaultValues` for STRUCTURED
    // questions (which have zero real options and never render
    // `QuestionOptionsEditor`). That made "Save changes" silently fail
    // client-side Zod validation on fields the user never sees, for every
    // STRUCTURED question, every time. Fixed by moving the non-empty
    // `optionText` check into the MCQ-only `superRefine` invariant.
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/courses/${COURSE_ID}/questions*`,
      200,
      apiPageSuccess([
        examQuestionResponseBody({
          id: QUESTION_A_ID,
          courseId: COURSE_ID,
          questionType: "STRUCTURED",
          body: QUESTION_A_BODY,
          options: [],
        }),
      ])
    );
    const updatedBody = "What is the capital city of France?";
    let updateRequestBody: unknown;
    let updateCalls = 0;
    await page.route(`**/v1/exams/questions/${QUESTION_A_ID}`, async (route) => {
      if (route.request().method() === "PUT") {
        updateCalls++;
        updateRequestBody = route.request().postDataJSON();
        await fulfillJson(
          route,
          200,
          apiSuccess(
            examQuestionResponseBody({
              id: QUESTION_A_ID,
              courseId: COURSE_ID,
              questionType: "STRUCTURED",
              body: updatedBody,
              options: [],
            })
          )
        );
        return;
      }
      await route.continue();
    });

    await page.goto("/teacher/exams/questions");
    await page.getByLabel("Course", { exact: true }).click();
    await page.getByRole("option", { name: "Intro to Biology" }).click();

    await expect(page.getByText(QUESTION_A_BODY)).toBeVisible();
    await page.getByRole("button", { name: "Edit" }).click();

    // No option fields/controls are ever rendered for a STRUCTURED question.
    await expect(page.getByLabel("Option 1 text")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Add option" })).toHaveCount(0);

    const bodyField = page.getByLabel("Question text");
    await bodyField.fill(updatedBody);

    const savingStatus = page.getByRole("status").filter({ hasText: "Saving question…" });
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(savingStatus).toBeAttached();
    // Success collapses the edit form back to the read-only question row.
    await expect(page.getByRole("button", { name: "Save changes" })).toHaveCount(0);
    expect(updateCalls).toBe(1);
    expect(updateRequestBody).toEqual({ body: updatedBody, options: null });
  });
});

test.describe("Teacher Question Bank (screen #4) — client-side Zod validation blocks the network call", () => {
  test("submitting an MCQ question with no option marked correct shows the validation error and fires no create request", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    let createQuestionCalls = 0;
    await page.route(`**/v1/exams/courses/${COURSE_ID}/questions*`, async (route) => {
      if (route.request().method() === "POST") {
        createQuestionCalls++;
        await fulfillJson(route, 200, apiSuccess(examQuestionResponseBody({ courseId: COURSE_ID })));
        return;
      }
      await fulfillJson(route, 200, apiPageSuccess([]));
    });

    await page.goto("/teacher/exams/questions");
    await page.getByLabel("Course", { exact: true }).click();
    await page.getByRole("option", { name: "Intro to Biology" }).click();
    await page.getByRole("button", { name: "Add question" }).click();

    await page.getByLabel("Question text").fill(QUESTION_A_BODY);
    await page.getByLabel("Option 1 text").fill("Paris");
    await page.getByLabel("Option 2 text").fill("London");
    // Deliberately leave every "This option is correct" checkbox unchecked.

    await page.getByRole("button", { name: "Create question" }).click();

    const validationError = page.getByRole("alert").filter({ hasText: "At least one option must be marked correct." });
    await expect(validationError).toBeVisible();
    expect(createQuestionCalls).toBe(0);
    // The form must still be open/unsubmitted — the success path collapses it back to the trigger button.
    await expect(page.getByRole("button", { name: "Create question" })).toBeVisible();
  });
});

test.describe("Teacher Question Bank (screen #4) — useCourseQuestions loading state", () => {
  test("LoadingState renders while the question bank list is in flight", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(`**/v1/exams/courses/${COURSE_ID}/questions*`, async (route) => {
      if (route.request().method() !== "GET") {
        await fulfillJson(route, 200, apiSuccess(examQuestionResponseBody({ courseId: COURSE_ID })));
        return;
      }
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([]));
    });

    await page.goto("/teacher/exams/questions");
    await page.getByLabel("Course", { exact: true }).click();
    await page.getByRole("option", { name: "Intro to Biology" }).click();

    const loading = page.getByRole("status").filter({ hasText: "Loading the question bank…" });
    await expect(loading).toBeVisible();
    await expect(loading).toHaveAttribute("aria-busy", "true");

    release?.();
    await expect(loading).toHaveCount(0);
  });
});
