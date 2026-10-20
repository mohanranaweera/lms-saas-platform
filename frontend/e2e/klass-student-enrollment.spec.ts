import { test, expect } from "@playwright/test";
import {
  api,
  apiLogin,
  ADMIN_EMAIL,
  catalogIds,
  createCourse,
  createLesson,
  createModule,
  EVIDENCE_DIR,
  firstTeacherUserId,
  loginUi,
  STUDENT_EMAIL,
  TENANT_BASE,
  uniqueSuffix,
} from "./fixtures/klass/klass-api";

/**
 * KLASS live E2E — student discovery, free enrollment and the unpaid content
 * tree (EP-E01, EP-E02, EP-L01, CM-S01, CM-S03, EP-C05) against the REAL
 * local backend, plus API-level checks of the T8b behaviour changes
 * (non-PUBLIC 404, invite links hidden from non-participants, partial PATCH).
 */

test.describe.configure({ mode: "serial" });

let adminToken: string;
let studentToken: string;
let teacherUserId: string;
const tag = uniqueSuffix();
let course: { id: string; name: string };
let octLesson: string;

test.beforeAll(async ({ request }) => {
  adminToken = await apiLogin(request, ADMIN_EMAIL);
  studentToken = await apiLogin(request, STUDENT_EMAIL);
  teacherUserId = await firstTeacherUserId(request, adminToken);
  course = await createCourse(request, adminToken, {
    name: `KLASS E2E Biology ${tag}`,
    teacherIds: [teacherUserId],
    pricingModel: "MONTHLY",
    price: "3500.00",
    enrollmentFee: "500.00",
    whatsappInviteUrl: "https://chat.whatsapp.com/klass-e2e-test",
  });
  const moduleId = await createModule(request, adminToken, course.id, "Theory");
  await createLesson(request, adminToken, course.id, moduleId, {
    title: "September class",
    startsAt: "2026-09-15T10:00:00Z",
  });
  octLesson = await createLesson(request, adminToken, course.id, moduleId, {
    title: "October class",
    startsAt: "2026-10-15T10:00:00Z",
  });
});

test("CM-S01/EP-E02/EP-E01: Available card, enroll confirmation NO changes nothing, YES moves it to Enrolled with no charge", async ({
  page,
  request,
}) => {
  // T8b: invite links are hidden from non-participants.
  const before = await api<{ whatsappInviteUrl: string | null }>(request, studentToken, "GET", `/courses/${course.id}`);
  expect(before.status).toBe(200);
  expect(before.body.data?.whatsappInviteUrl ?? null).toBeNull();

  await loginUi(page, STUDENT_EMAIL);
  await page.goto(`${TENANT_BASE}/student/courses?view=available`);
  await page.getByTestId("catalog-search").fill(tag);
  const card = page.getByTestId("catalog-course-card").filter({ hasText: course.name });
  await expect(card).toBeVisible();
  await expect(card.getByTestId("course-price-badge")).toBeVisible();
  await expect(card.getByRole("link", { name: `View course ${course.name}` })).toBeVisible();
  await page.screenshot({ path: `${EVIDENCE_DIR}/06-student-available-cards.png`, fullPage: true });

  // NO: nothing changes.
  await card.getByRole("button", { name: `Enroll in ${course.name}` }).click();
  const dialog = page.getByTestId("enroll-confirm-dialog");
  await expect(dialog).toContainText(`Enroll in ${course.name}?`);
  await page.screenshot({ path: `${EVIDENCE_DIR}/07-enroll-confirmation.png` });
  await dialog.getByTestId("enroll-confirm-no").click();
  await expect(dialog).toBeHidden();
  expect(await catalogIds(request, studentToken, "AVAILABLE", tag)).toContain(course.id);
  expect(await catalogIds(request, studentToken, "ENROLLED", tag)).not.toContain(course.id);

  // YES: enrolled immediately, free (enrollment fee is informational, EP-C05).
  await card.getByRole("button", { name: `Enroll in ${course.name}` }).click();
  await page.getByTestId("enroll-confirm-dialog").getByTestId("enroll-confirm-yes").click();
  await expect(page.getByText(`You're enrolled in ${course.name}`)).toBeVisible();
  expect(await catalogIds(request, studentToken, "ENROLLED", tag)).toContain(course.id);
  expect(await catalogIds(request, studentToken, "AVAILABLE", tag)).not.toContain(course.id);
  // Nothing to pay was created by enrolling: the composer has no pending item for this course.
  const offers = await api<{ items: { state: string }[] }>(
    request,
    studentToken,
    "GET",
    `/checkout/offers?courseId=${course.id}&scope=MONTHLY`
  );
  expect(offers.status).toBe(200);
  expect((offers.body.data?.items ?? []).every((i) => i.state === "AVAILABLE")).toBe(true);

  await page.getByTestId("catalog-view-enrolled").click();
  await expect(page.getByTestId("catalog-course-card").filter({ hasText: course.name })).toBeVisible();

  // T8b: participants now see the invite link.
  const after = await api<{ whatsappInviteUrl: string | null }>(request, studentToken, "GET", `/courses/${course.id}`);
  expect(after.body.data?.whatsappInviteUrl).toBe("https://chat.whatsapp.com/klass-e2e-test");
});

test("EP-L01/CM-S03: enrolled but unpaid — month folders with Pay now, metadata visible, launch denied server-side", async ({
  page,
  request,
}) => {
  await loginUi(page, STUDENT_EMAIL);
  await page.goto(`${TENANT_BASE}/student/courses/${course.id}`);
  await expect(page.getByTestId("course-detail-title")).toHaveText(course.name);

  const periods = page.getByTestId("content-period");
  await expect(periods).toHaveCount(2);
  await expect(periods.nth(0)).toHaveAttribute("data-period-key", "2026-10");
  await expect(periods.nth(1)).toHaveAttribute("data-period-key", "2026-09");
  await expect(periods.nth(0).getByTestId("period-pay-now")).toBeVisible();
  await expect(page.getByTestId("course-content-progress")).toHaveText("0 of 2 viewed");

  await periods.nth(0).getByTestId("content-period-toggle").click();
  await periods.nth(0).getByTestId("content-module-toggle").click();
  const lessonRow = page.getByTestId("content-lesson").filter({ hasText: "October class" });
  await expect(lessonRow).toHaveAttribute("data-access-status", "PAYMENT_REQUIRED");
  await expect(lessonRow.getByTestId("content-lesson-open")).toHaveCount(0);
  await expect(lessonRow.getByTestId("lesson-pay-now")).toBeVisible();
  await page.screenshot({ path: `${EVIDENCE_DIR}/08-unpaid-content-tree.png`, fullPage: true });

  // The server refuses the launch (no UI path needed to prove it).
  const launch = await api(request, studentToken, "POST", `/courses/${course.id}/lessons/${octLesson}/launch`);
  expect(launch.status).toBe(403);
  expect(launch.body.code ?? launch.body.error?.code).toBe("PAYMENT_REQUIRED");
  await expect(page.getByTestId("course-content-progress")).toHaveText("0 of 2 viewed");
});

test("T8b: a non-PUBLIC course is 404 to a non-participant student; PATCH accepts a partial body", async ({ request }) => {
  const hidden = await createCourse(request, adminToken, {
    name: `KLASS E2E Private ${uniqueSuffix()}`,
    teacherIds: [teacherUserId],
    pricingModel: "FREE",
    status: "PRIVATE",
  });
  const moduleId = await createModule(request, adminToken, hidden.id, "Hidden");
  const lesson = await createLesson(request, adminToken, hidden.id, moduleId, { title: "Hidden lesson", paymentType: "FREE" });

  expect((await api(request, studentToken, "GET", `/courses/${hidden.id}/content`)).status).toBe(404);
  expect((await api(request, studentToken, "POST", `/courses/${hidden.id}/lessons/${lesson}/launch`)).status).toBe(404);
  expect((await api(request, studentToken, "POST", `/enrollments/my/courses/${hidden.id}`)).status).toBe(404);

  const patched = await api<{ description: string; name: string }>(request, adminToken, "PATCH", `/courses/${hidden.id}`, {
    description: "Partial update only",
  });
  expect(patched.status, JSON.stringify(patched.body)).toBe(200);
  expect(patched.body.data?.description).toBe("Partial update only");
  expect(patched.body.data?.name).toBe(hidden.name);
});
