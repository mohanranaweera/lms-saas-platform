import { test, expect } from "@playwright/test";
import {
  api,
  apiLogin,
  ADMIN_EMAIL,
  createCourse,
  createLesson,
  createModule,
  EVIDENCE_DIR,
  firstTeacherUserId,
  catalogIds,
  loginUi,
  STUDENT_EMAIL,
  TENANT_BASE,
  uniqueSuffix,
} from "./fixtures/klass/klass-api";
import { courseAction, openCourseActions } from "./fixtures/klass/course-row-menu";

/**
 * KLASS live E2E — Tenant Admin course management (CM-A01, CM-A03, CM-A04,
 * CM-A05, EP-C01, EP-C02, EP-C12) against the REAL local backend and the demo
 * tenant (see fixtures/klass/klass-api.ts). Each test creates uniquely named
 * data, so reruns never collide.
 */

test.describe.configure({ mode: "serial" });

let adminToken: string;
let teacherUserId: string;

test.beforeAll(async ({ request }) => {
  adminToken = await apiLogin(request, ADMIN_EMAIL);
  teacherUserId = await firstTeacherUserId(request, adminToken);
});

async function searchCourses(page: import("@playwright/test").Page, term: string) {
  await page.getByLabel("Search courses").fill(term);
  await expect(page.getByTestId("course-row").first()).toBeVisible();
}

test("CM-A01: course list tabs, search and a registration switch that persists after reload", async ({
  page,
  request,
}) => {
  const tag = uniqueSuffix();
  const active = await createCourse(request, adminToken, {
    name: `KLASS E2E Active ${tag}`,
    teacherIds: [teacherUserId],
    pricingModel: "MONTHLY",
    price: "3500.00",
  });
  const archived = await createCourse(request, adminToken, {
    name: `KLASS E2E Archived ${tag}`,
    teacherIds: [teacherUserId],
    pricingModel: "FREE",
  });
  expect((await api(request, adminToken, "POST", `/courses/${archived.id}/archive`)).status).toBe(200);

  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses`);
  await searchCourses(page, tag);

  const rows = page.getByTestId("course-row");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText(active.name);
  for (const header of ["Name", "Teacher", "Payment Type", "Enabled for Registration"]) {
    await expect(page.getByRole("columnheader", { name: header })).toBeVisible();
  }
  await expect(rows.first()).toContainText("Monthly Fee");
  await page.screenshot({ path: `${EVIDENCE_DIR}/01-admin-course-list.png`, fullPage: true });

  const toggle = rows.first().getByRole("switch", { name: `Enabled for registration: ${active.name}` });
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await toggle.click();
  await expect(page.getByText(`Registration closed for “${active.name}”`)).toBeVisible();
  await expect(toggle).toHaveAttribute("aria-checked", "false");

  // Persisted server-side: survives a full reload.
  await page.reload();
  await searchCourses(page, tag);
  await expect(
    page.getByTestId("course-row").first().getByRole("switch", { name: `Enabled for registration: ${active.name}` })
  ).toHaveAttribute("aria-checked", "false");
  const stored = await api<{ registrationEnabled: boolean }>(request, adminToken, "GET", `/courses/${active.id}`);
  expect(stored.body.data?.registrationEnabled).toBe(false);

  // Archived tab shows only the archived course for this search.
  await page.getByRole("tab", { name: "Archived" }).click();
  await expect(page.getByTestId("course-row")).toHaveCount(1);
  await expect(page.getByTestId("course-row").first()).toContainText(archived.name);
  await expect(page.getByTestId("courses-registration-archived-note")).toBeVisible();
});

test("EP-C01/C02/C12: Add course modal — required errors, cancel creates nothing, valid create persists after reload", async ({
  page,
  request,
}) => {
  const name = `KLASS E2E Created ${uniqueSuffix()}`;
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses`);

  await page.getByTestId("courses-add-course").click();
  const dialog = page.getByTestId("add-course-dialog");
  await expect(dialog.getByRole("heading", { name: "Add New Course" })).toBeVisible();
  await expect(dialog.getByTestId("add-course-cover-default")).toBeVisible();
  await expect(dialog.getByTestId("add-course-toggle-advanced")).toBeVisible();
  await expect(dialog.getByTestId("add-course-cancel")).toBeVisible();
  await page.screenshot({ path: `${EVIDENCE_DIR}/02-add-course-modal.png` });

  // EP-C02: missing Name / Teacher / Payment Type -> inline errors, nothing sent.
  let createCalls = 0;
  page.on("request", (r) => {
    if (r.method() === "POST" && new URL(r.url()).pathname === "/api/v1/courses") createCalls += 1;
  });
  await dialog.getByTestId("add-course-submit").click();
  await expect(dialog.getByText("This field is required.")).toHaveCount(3);
  expect(createCalls).toBe(0);
  await page.screenshot({ path: `${EVIDENCE_DIR}/03-add-course-required-errors.png` });

  // Advanced settings toggle shows/hides the extra fields.
  await dialog.getByTestId("add-course-toggle-advanced").click();
  await expect(dialog.getByTestId("add-course-payment-valid-days")).toBeVisible();
  await dialog.getByTestId("add-course-toggle-advanced").click();
  await expect(dialog.getByTestId("add-course-payment-valid-days")).toBeHidden();

  // EP-C12 (cancel): typing then cancelling creates no course.
  await dialog.getByTestId("add-course-name").fill(name);
  await dialog.getByTestId("add-course-cancel").click();
  await expect(dialog).toBeHidden();
  expect(createCalls).toBe(0);
  const afterCancel = await api<{ content: unknown[] }>(
    request,
    adminToken,
    "GET",
    `/courses?archived=false&search=${encodeURIComponent(name)}`
  );
  expect(afterCancel.body.data?.content ?? []).toHaveLength(0);

  // Valid create.
  await page.getByTestId("courses-add-course").click();
  const fresh = page.getByTestId("add-course-dialog");
  await fresh.getByTestId("add-course-name").fill(name);
  await fresh.getByTestId("add-course-teachers").getByRole("button").first().click();
  await fresh.getByTestId("add-course-teachers").getByRole("checkbox").first().check();
  await fresh.getByTestId("add-course-payment-type").selectOption("MONTHLY");
  await fresh.getByTestId("add-course-price").fill("3500");
  await fresh.getByTestId("add-course-submit").click();
  // T8f: new courses are created as DRAFT and the toast says so.
  await expect(page.getByText("Course created as a draft. Publish it to show it to students.")).toBeVisible();
  await expect(page.getByTestId("course-created-publish-now")).toBeVisible();
  expect(createCalls).toBe(1);

  await page.reload();
  await searchCourses(page, name);
  await expect(page.getByTestId("course-row")).toHaveCount(1);
  await expect(page.getByTestId("course-row").first()).toContainText("Monthly Fee");
  await expect(page.getByTestId("course-row").first().getByTestId("course-status-badge")).toHaveAttribute(
    "data-status",
    "DRAFT"
  );
  const persisted = await api<{ content: { name: string; pricingModel: string; teachers: unknown[] }[] }>(
    request,
    adminToken,
    "GET",
    `/courses?archived=false&search=${encodeURIComponent(name)}`
  );
  expect(persisted.body.data?.content).toHaveLength(1);
  expect(persisted.body.data?.content[0].pricingModel).toBe("MONTHLY");
  expect(persisted.body.data?.content[0].teachers.length).toBeGreaterThanOrEqual(1);
});

test("CM-A03: Add module persists after reload; cancel creates nothing", async ({ page, request }) => {
  const course = await createCourse(request, adminToken, {
    name: `KLASS E2E Modules ${uniqueSuffix()}`,
    teacherIds: [teacherUserId],
    pricingModel: "MONTHLY",
    price: "3500.00",
  });
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses/${course.id}/modules`);
  await expect(page.getByTestId("modules-heading")).toBeVisible();

  // Cancel creates nothing.
  await page.getByTestId("modules-add-module").first().click();
  const dialog = page.getByTestId("module-form-dialog");
  await dialog.getByTestId("module-form-name").fill("Cancelled module");
  await dialog.getByTestId("module-form-cancel").click();
  await expect(dialog).toBeHidden();
  // Name required.
  await page.getByTestId("modules-add-module").first().click();
  await page.getByTestId("module-form-dialog").getByTestId("module-form-submit").click();
  await expect(page.getByTestId("module-form-dialog").getByText("This field is required.")).toBeVisible();
  await page.getByTestId("module-form-dialog").getByTestId("module-form-name").fill("October Theory");
  await page.getByTestId("module-form-dialog").getByTestId("module-form-submit").click();
  await expect(page.getByTestId("module-form-dialog")).toBeHidden();

  await page.reload();
  await expect(page.getByTestId("modules-list")).toContainText("October Theory");
  await expect(page.getByTestId("modules-list")).not.toContainText("Cancelled module");
  await page.screenshot({ path: `${EVIDENCE_DIR}/04-admin-modules.png`, fullPage: true });
  const modules = await api<{ title: string }[]>(request, adminToken, "GET", `/courses/${course.id}/modules`);
  expect((modules.body.data ?? []).map((m) => m.title)).toEqual(["October Theory"]);
});

test("CM-A04/A05: Add session — validation, additional settings toggle, end-before-start error, create persists", async ({
  page,
  request,
}) => {
  const course = await createCourse(request, adminToken, {
    name: `KLASS E2E Sessions ${uniqueSuffix()}`,
    teacherIds: [teacherUserId],
    pricingModel: "MONTHLY",
    price: "3500.00",
  });
  const moduleId = await createModule(request, adminToken, course.id, "October Theory");
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses/${course.id}/modules/${moduleId}/sessions`);
  await expect(page.getByTestId("sessions-heading")).toBeVisible();

  await page.getByTestId("sessions-add-session").first().click();
  const dialog = page.getByTestId("session-form-dialog");
  await expect(dialog.getByRole("heading", { name: "Add New Session" })).toBeVisible();
  await expect(dialog.getByTestId("session-form-timezone")).toBeVisible();

  // Required-field validation (backend never called).
  await dialog.getByTestId("session-form-submit").click();
  expect(await dialog.getByText("This field is required.").count()).toBeGreaterThanOrEqual(3);

  // Additional settings toggle.
  await dialog.getByTestId("session-form-toggle-advanced").click();
  await expect(dialog.getByTestId("session-form-lesson")).toBeVisible();
  await expect(dialog.getByLabel("Session", { exact: true })).toBeVisible();
  await expect(dialog.getByTestId("session-form-toggle-advanced")).toHaveText(/Hide additional settings/);
  await dialog.getByTestId("session-form-toggle-advanced").click();
  await expect(dialog.getByTestId("session-form-lesson")).toBeHidden();

  // End before start.
  await dialog.getByTestId("session-form-name").fill("October class 1");
  await dialog.getByTestId("session-form-from-date").fill("2026-10-07");
  await dialog.getByTestId("session-form-from-time").fill("16:20");
  await dialog.getByTestId("session-form-to-date").fill("2026-10-07");
  await dialog.getByTestId("session-form-to-time").fill("16:00");
  await dialog.getByTestId("session-form-resource-type").selectOption("ZOOM_MEETING");
  await dialog.getByTestId("session-form-payment-type").selectOption("COVERED_BY_CLASS_FEES");
  await expect(dialog.getByTestId("session-form-billing-month")).toHaveCount(0);
  await dialog.getByTestId("session-form-submit").click();
  await expect(dialog.getByText("The end must be after the start.")).toBeVisible();
  await page.screenshot({ path: `${EVIDENCE_DIR}/05-add-session-form.png` });

  await dialog.getByTestId("session-form-to-time").fill("18:00");
  await dialog.getByTestId("session-form-submit").click();
  await expect(dialog).toBeHidden();

  await page.reload();
  await expect(page.getByTestId("sessions-list")).toContainText("October class 1");
  const lessons = await api<{ title: string; startsAt: string; endsAt: string; billingMonth: number; resourceType: string }[]>(
    request,
    adminToken,
    "GET",
    `/courses/${course.id}/modules/${moduleId}/lessons`
  );
  const created = (lessons.body.data ?? []).find((l) => l.title === "October class 1");
  expect(created).toBeTruthy();
  expect(created!.resourceType).toBe("ZOOM_MEETING");
  expect(created!.billingMonth).toBe(10);
  // CM-A05: 16:20 -> 18:00 in the tenant timezone is a 100-minute window.
  expect(new Date(created!.endsAt).getTime() - new Date(created!.startsAt).getTime()).toBe(100 * 60_000);

  // A lesson created via the API shows up in the same scoped list (hierarchy, CM-A02).
  await createLesson(request, adminToken, course.id, moduleId, { title: "API created session" });
  await page.reload();
  await expect(page.getByTestId("sessions-list")).toContainText("API created session");
});

// ---------------------------------------------------------------------------
// T8f: Draft / Published visibility (a new course was missing from the
// student's Available Courses because it was still a DRAFT).
// ---------------------------------------------------------------------------

function courseRow(page: import("@playwright/test").Page, name: string) {
  return page.getByTestId("course-row").filter({ hasText: name });
}

test("T8f: a new course is Draft and hidden from students; Publish now (toast) publishes it and it appears in Available", async ({
  page,
  request,
  browser,
}) => {
  const name = `KLASS E2E Publish ${uniqueSuffix()}`;
  const studentToken = await apiLogin(request, STUDENT_EMAIL);
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses`);

  await page.getByTestId("courses-add-course").click();
  const dialog = page.getByTestId("add-course-dialog");
  await dialog.getByTestId("add-course-name").fill(name);
  await dialog.getByTestId("add-course-teachers").getByRole("button").first().click();
  await dialog.getByTestId("add-course-teachers").getByRole("checkbox").first().check();
  await dialog.getByTestId("add-course-payment-type").selectOption("FREE");
  await dialog.getByTestId("add-course-submit").click();
  await expect(page.getByText("Course created as a draft. Publish it to show it to students.")).toBeVisible();

  await page.getByLabel("Search courses").fill(name);
  const badge = courseRow(page, name).getByTestId("course-status-badge");
  await expect(badge).toHaveAttribute("data-status", "DRAFT");
  await expect(badge).toHaveText(/Draft/);
  await page.screenshot({ path: `${EVIDENCE_DIR}/15-admin-new-course-draft.png`, fullPage: true });

  const created = await api<{ content: { id: string; status: string }[] }>(
    request,
    adminToken,
    "GET",
    `/courses?archived=false&search=${encodeURIComponent(name)}`
  );
  const courseId = created.body.data!.content[0].id;
  expect(created.body.data!.content[0].status).toBe("DRAFT");
  // Absent from the student's Available Courses while Draft, and not enrollable.
  expect(await catalogIds(request, studentToken, "AVAILABLE", name)).not.toContain(courseId);
  expect((await api(request, studentToken, "POST", `/enrollments/my/courses/${courseId}`)).status).toBe(404);

  // Publish from the toast action.
  await page.getByTestId("course-created-publish-now").click();
  await expect(badge).toHaveAttribute("data-status", "PUBLIC");
  await expect(badge).toHaveText(/Published/);
  // The overflow menu now offers Unpublish instead of Publish.
  const menu = await openCourseActions(page, courseRow(page, name));
  await expect(menu.getByTestId("course-action-unpublish")).toBeVisible();
  await expect(menu.getByTestId("course-action-publish")).toHaveCount(0);
  await page.keyboard.press("Escape");
  expect((await api<{ status: string }>(request, adminToken, "GET", `/courses/${courseId}`)).body.data?.status).toBe(
    "PUBLIC"
  );

  // Now visible to the student in Available Courses (API and UI).
  expect(await catalogIds(request, studentToken, "AVAILABLE", name)).toContain(courseId);
  const studentContext = await browser.newContext();
  const studentPage = await studentContext.newPage();
  try {
    await loginUi(studentPage, STUDENT_EMAIL);
    await studentPage.goto(`${TENANT_BASE}/student/courses?view=available`);
    await studentPage.getByTestId("catalog-search").fill(name);
    await expect(studentPage.getByTestId("catalog-course-card").filter({ hasText: name })).toBeVisible();
    await studentPage.screenshot({ path: `${EVIDENCE_DIR}/16-student-available-after-publish.png`, fullPage: true });
  } finally {
    await studentContext.close();
  }
});

test("T8f: row Publish makes a Draft course Published; Unpublish asks for confirmation and returns it to Draft, hidden again", async ({
  page,
  request,
}) => {
  const studentToken = await apiLogin(request, STUDENT_EMAIL);
  const course = await createCourse(request, adminToken, {
    name: `KLASS E2E Row Publish ${uniqueSuffix()}`,
    teacherIds: [teacherUserId],
    pricingModel: "FREE",
    status: "DRAFT",
  });
  expect(await catalogIds(request, studentToken, "AVAILABLE", course.name)).not.toContain(course.id);

  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses`);
  await searchCourses(page, course.name);
  const row = courseRow(page, course.name);
  await expect(row.getByTestId("course-status-badge")).toHaveAttribute("data-status", "DRAFT");

  // Row Publish.
  await courseAction(page, row, "course-action-publish");
  await expect(page.getByText(`“${course.name}” published. Students can now see it in Available Courses.`)).toBeVisible();
  await expect(row.getByTestId("course-status-badge")).toHaveAttribute("data-status", "PUBLIC");
  expect(await catalogIds(request, studentToken, "AVAILABLE", course.name)).toContain(course.id);

  // Unpublish: cancelling the confirmation changes nothing.
  await courseAction(page, row, "course-action-unpublish");
  const confirm = page.getByTestId("confirm-unpublish-dialog");
  await expect(confirm).toBeVisible();
  await expect(confirm).toContainText("The course returns to Draft.");
  await page.screenshot({ path: `${EVIDENCE_DIR}/17-confirm-unpublish-dialog.png` });
  await confirm.getByRole("button", { name: /cancel/i }).click();
  await expect(confirm).toBeHidden();
  await expect(row.getByTestId("course-status-badge")).toHaveAttribute("data-status", "PUBLIC");
  expect((await api<{ status: string }>(request, adminToken, "GET", `/courses/${course.id}`)).body.data?.status).toBe(
    "PUBLIC"
  );

  // Confirming returns it to Draft and hides it from students again.
  await courseAction(page, row, "course-action-unpublish");
  await page.getByTestId("confirm-unpublish-dialog").getByTestId("confirm-unpublish-submit").click();
  await expect(page.getByTestId("confirm-unpublish-dialog")).toBeHidden();
  await expect(page.getByTestId("confirm-unpublish-error")).toHaveCount(0);
  await expect(page.getByText(`“${course.name}” unpublished and moved back to Draft.`)).toBeVisible();
  await expect(row.getByTestId("course-status-badge")).toHaveAttribute("data-status", "DRAFT");
  expect((await api<{ status: string }>(request, adminToken, "GET", `/courses/${course.id}`)).body.data?.status).toBe(
    "DRAFT"
  );
  expect(await catalogIds(request, studentToken, "AVAILABLE", course.name)).not.toContain(course.id);

  // Survives a reload.
  await page.reload();
  await searchCourses(page, course.name);
  await expect(courseRow(page, course.name).getByTestId("course-status-badge")).toHaveAttribute("data-status", "DRAFT");
});

// ---------------------------------------------------------------------------
// R2 (G2/G3/G4/G5): course edit round-trip, teacher multi-select, row menu.
// ---------------------------------------------------------------------------

/** A valid 1x1 PNG (content-sniffed by the backend cover endpoint). */
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "base64"
);

async function openAdvanced(page: import("@playwright/test").Page) {
  const toggle = page.getByTestId("edit-course-toggle-advanced");
  if ((await toggle.getAttribute("aria-expanded")) !== "true") await toggle.click();
}

test("R2-G3/G4: edit round-trip — enrollment fee, two tags (comma + Enter) and cover persist; hidden legacy fields are not wiped", async ({
  page,
  request,
}) => {
  const tag = uniqueSuffix();
  const course = await createCourse(request, adminToken, {
    name: `KLASS E2E Edit ${tag}`,
    teacherIds: [teacherUserId],
    pricingModel: "MONTHLY",
    price: "3500.00",
    subject: "Biology",
    stream: "Science",
    grade: "Grade 12",
    academicYear: "2027",
  });
  const before = await api<Record<string, unknown>>(request, adminToken, "GET", `/courses/${course.id}`);
  expect(before.body.data).toMatchObject({ subject: "Biology", stream: "Science", grade: "Grade 12", academicYear: "2027" });

  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses/${course.id}`);
  await expect(page.getByTestId("edit-course-name")).toHaveValue(course.name);
  // No slug anywhere on the edit screen.
  await expect(page.locator('[id*="slug"], [data-testid*="slug"]')).toHaveCount(0);

  await page.getByTestId("edit-course-enrollment-fee").fill("750");
  await openAdvanced(page);
  const tagInput = page.getByTestId("course-tags-input").getByRole("textbox");
  const tagA = `e2e-${tag}-comma`;
  const tagB = `e2e-${tag}-enter`;
  await tagInput.fill(`${tagA},`);
  await tagInput.fill(tagB);
  await tagInput.press("Enter");
  await expect(page.getByTestId("course-tags-input")).toContainText(tagA);
  await expect(page.getByTestId("course-tags-input")).toContainText(tagB);
  await page.getByTestId("edit-course-cover-input").setInputFiles({
    name: "cover.png",
    mimeType: "image/png",
    buffer: PNG_1X1,
  });
  await expect(page.getByTestId("edit-course-cover-preview")).toBeVisible();
  await page.getByTestId("edit-course-submit").click();
  await expect(page.getByText("Course details saved.")).toBeVisible();
  await page.screenshot({ path: `${EVIDENCE_DIR}/18-course-edit-saved.png`, fullPage: true });

  // Persisted: survives a reload ...
  await page.reload();
  await expect(page.getByTestId("edit-course-enrollment-fee")).toHaveValue(/750/);
  await openAdvanced(page);
  await expect(page.getByTestId("course-tags-input")).toContainText(tagA);
  await expect(page.getByTestId("course-tags-input")).toContainText(tagB);
  await expect(page.getByTestId("edit-course-cover-preview")).toBeVisible();

  // ... and server-side, with the hidden legacy fields untouched.
  const after = await api<{
    enrollmentFee: string;
    tags: { name: string }[];
    coverImageUrl: string | null;
    subject: string;
    stream: string;
    grade: string;
    academicYear: string;
    slug: string;
  }>(request, adminToken, "GET", `/courses/${course.id}`);
  const data = after.body.data!;
  expect(Number(data.enrollmentFee)).toBe(750);
  expect(data.tags.map((t) => t.name)).toEqual(expect.arrayContaining([tagA, tagB]));
  expect(data.coverImageUrl).toBeTruthy();
  expect(data).toMatchObject({ subject: "Biology", stream: "Science", grade: "Grade 12", academicYear: "2027" });
  expect(data.slug).toBe((before.body.data as { slug: string }).slug);
});

test("R2-G2: tenant admin adds a second teacher and changes the primary with the multi-select and radio", async ({
  page,
  request,
}) => {
  const teachers = await api<{ userId: string; name: string; approvalStatus: string; accountStatus: string }[]>(
    request,
    adminToken,
    "GET",
    "/teachers"
  );
  const active = (teachers.body.data ?? []).filter((t) => t.approvalStatus === "APPROVED" && t.accountStatus === "ACTIVE");
  test.skip(active.length < 2, "The demo tenant needs two approved, active teachers");
  const [first, second] = active;
  const course = await createCourse(request, adminToken, {
    name: `KLASS E2E Teachers ${uniqueSuffix()}`,
    teacherIds: [first.userId],
    pricingModel: "FREE",
  });

  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses/${course.id}`);
  const picker = page.getByTestId("edit-course-teachers");
  await picker.getByRole("button").first().click();
  await picker.getByRole("checkbox", { name: second.name, exact: true }).check();
  const primary = page.getByTestId("edit-course-primary-teacher");
  await expect(primary.getByTestId("edit-course-primary-teacher-option")).toHaveCount(2);
  await primary.getByRole("radio", { name: second.name, exact: true }).check();
  await page.getByTestId("edit-course-submit").click();
  await expect(page.getByText("Course details saved.")).toBeVisible();

  const saved = await api<{ teacherId: string; teachers: { id: string; primary: boolean }[] }>(
    request,
    adminToken,
    "GET",
    `/courses/${course.id}`
  );
  expect(saved.body.data?.teacherId).toBe(second.userId);
  expect(saved.body.data?.teachers.map((t) => [t.id, t.primary])).toEqual(
    expect.arrayContaining([
      [second.userId, true],
      [first.userId, false],
    ])
  );
  await page.reload();
  await expect(
    page.getByTestId("edit-course-primary-teacher").getByRole("radio", { name: second.name, exact: true })
  ).toBeChecked();
});

test("R2-G5: the course row menu is fully operable by keyboard (open, move, activate, Escape returns focus)", async ({
  page,
  request,
}) => {
  const course = await createCourse(request, adminToken, {
    name: `KLASS E2E Menu ${uniqueSuffix()}`,
    teacherIds: [teacherUserId],
    pricingModel: "FREE",
  });
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/courses`);
  await searchCourses(page, course.name);
  const trigger = page
    .getByTestId("course-row")
    .filter({ hasText: course.name })
    .getByRole("button", { name: `Actions for ${course.name}` });

  // Open with the keyboard, then Escape closes and returns focus to the trigger.
  await trigger.focus();
  await page.keyboard.press("Enter");
  const menu = page.getByTestId("course-actions-menu-content");
  await expect(menu).toBeVisible();
  await expect(menu.getByRole("menuitem")).toHaveCount(7);
  // Element capture: a viewport capture taken while the menu is open renders blank (reported in R2-T6a).
  await menu.screenshot({ path: `${EVIDENCE_DIR}/19-course-row-menu.png` });
  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();

  // Arrow keys move through the items; Enter activates "Edit class schedule".
  await page.keyboard.press("ArrowDown");
  await expect(menu).toBeVisible();
  const items = menu.getByRole("menuitem");
  for (let attempt = 0; attempt < 8; attempt += 1) {
    if ((await items.filter({ hasText: "Edit class schedule" }).getAttribute("data-highlighted")) !== null) break;
    await page.keyboard.press("ArrowDown");
  }
  await expect(items.filter({ hasText: "Edit class schedule" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${course.id}/sessions$`));
});
