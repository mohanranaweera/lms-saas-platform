import { test, expect, type Page } from "@playwright/test";
import {
  api,
  apiLogin,
  ADMIN_EMAIL,
  createCourse,
  createLesson,
  createModule,
  EVIDENCE_DIR,
  firstTeacherUserId,
  loginUi,
  PAYMENT_DEFAULTS,
  PAYMENT_FIXTURE,
  setPaymentConfig,
  STUDENT_EMAIL,
  TENANT_BASE,
  uniquePdf,
  uniqueSuffix,
} from "./fixtures/klass/klass-api";

/**
 * KLASS live E2E — payment composer, manual slip and approval against the
 * REAL local backend (EP-M01, EP-M02, EP-M03, EP-P04, EP-P05, CM-P01, CM-P04,
 * CM-S04, CM-S06) plus the T8b 409s (ORDER_PAYMENT_IN_PROGRESS,
 * USE_PAYMENT_COMPOSER).
 *
 * Tenant config changed (demo tenant only) and restored in afterAll:
 *   PAYMENT facility_fee_mode NONE -> PER_LINE, facility_fee_amount 0 -> 100,
 *   bank_name/bank_branch/bank_account_holder/bank_account_number null -> obviously
 *   fake test values ("Test Bank", "000-TEST", ...). See PAYMENT_DEFAULTS.
 */

test.describe.configure({ mode: "serial" });

let adminToken: string;
let studentToken: string;
const tag = uniqueSuffix();
const reference = `KLASS-E2E-${tag}`.toUpperCase();
let course: { id: string; name: string };
let lessons: { aug: string; sep: string; oct1: string; oct2: string };
let slip: { id: string; orderId: string } | null = null;

function digits(text: string | null): string {
  return (text ?? "").replace(/[^0-9]/g, "");
}

function moneyDigits(amount: string | number): string {
  return Number(amount).toFixed(2).replace(/[^0-9]/g, "");
}

test.beforeAll(async ({ request }) => {
  adminToken = await apiLogin(request, ADMIN_EMAIL);
  studentToken = await apiLogin(request, STUDENT_EMAIL);
  await setPaymentConfig(request, adminToken, PAYMENT_FIXTURE);
  const teacher = await firstTeacherUserId(request, adminToken);
  course = await createCourse(request, adminToken, {
    name: `KLASS E2E ICT ${tag}`,
    teacherIds: [teacher],
    pricingModel: "MONTHLY",
    price: "3500.00",
  });
  const moduleId = await createModule(request, adminToken, course.id, "ICT Theory");
  lessons = {
    aug: await createLesson(request, adminToken, course.id, moduleId, { title: "August class", startsAt: "2026-08-15T10:00:00Z" }),
    sep: await createLesson(request, adminToken, course.id, moduleId, { title: "September class", startsAt: "2026-09-15T10:00:00Z" }),
    oct1: await createLesson(request, adminToken, course.id, moduleId, { title: "October class 1", startsAt: "2026-10-07T10:00:00Z" }),
    oct2: await createLesson(request, adminToken, course.id, moduleId, { title: "October class 2", startsAt: "2026-10-14T10:00:00Z" }),
  };
  expect((await api(request, studentToken, "POST", `/enrollments/my/courses/${course.id}`)).status).toBe(200);
});

test.afterAll(async ({ request }) => {
  // Restore the facility fee to the recorded defaults. The tenant-config API
  // rejects null for STRING keys and has no reset route, so the four test-only
  // bank fields (obviously fake values) cannot be cleared through the API and
  // are reported for manual revert (see KLASS-T6 report).
  const token = adminToken ?? (await apiLogin(request, ADMIN_EMAIL));
  await setPaymentConfig(request, token, {
    facility_fee_mode: PAYMENT_DEFAULTS.facility_fee_mode,
    facility_fee_amount: PAYMENT_DEFAULTS.facility_fee_amount,
  });
  const restored = await api<{ key: string; value: unknown }[]>(request, token, "GET", "/tenant-config/PAYMENT");
  const values = Object.fromEntries((restored.body.data ?? []).map((e) => [e.key, e.value]));
  expect(values).toMatchObject({ facility_fee_mode: "NONE", facility_fee_amount: 0 });
});

async function openCourse(page: Page) {
  await page.goto(`${TENANT_BASE}/student/courses/${course.id}`);
  await expect(page.getByTestId("course-detail-title")).toHaveText(course.name);
}

function period(page: Page, key: string) {
  return page.locator(`[data-testid="content-period"][data-period-key="${key}"]`);
}

test("EP-M01: the empty composer shows zero totals and no pay action", async ({ page }) => {
  await loginUi(page, STUDENT_EMAIL);
  await openCourse(page);
  await page.getByTestId("global-pay-button").click();
  const composer = page.getByTestId("payment-composer");
  await expect(composer).toBeVisible();
  await expect(composer.getByTestId("composer-summary-empty")).toBeVisible();
  expect(digits(await composer.getByTestId("composer-total").textContent())).toMatch(/^0+$/);
  expect(digits(await composer.getByTestId("composer-subtotal").textContent())).toMatch(/^0+$/);
  await expect(composer.getByTestId("composer-proceed-online")).toHaveCount(0);
  await expect(composer.getByTestId("composer-submit-slip")).toHaveCount(0);
  await expect(composer.getByTestId("composer-method-slip")).toHaveCount(0);
});

test("EP-M02/CM-P01/CM-P04/EP-M03: period Pay now preselects October; two months -> two lines and server-quoted totals; remove recalculates", async ({
  page,
  request,
}) => {
  await loginUi(page, STUDENT_EMAIL);
  await openCourse(page);

  await period(page, "2026-10").getByTestId("period-pay-now").click();
  const composer = page.getByTestId("payment-composer");
  await expect(composer).toBeVisible();
  // CM-P04: the October entry point targets the October billing period.
  await expect(composer.locator('[data-testid="composer-month"][data-month="10"]')).toHaveAttribute("aria-pressed", "true");
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(1);

  await composer.locator('[data-testid="composer-month"][data-month="9"]').click();
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(2);

  const quote = await api<{ subtotal: string; facilityFeeTotal: string; total: string; lines: unknown[] }>(
    request,
    studentToken,
    "POST",
    "/checkout/quote",
    { courseId: course.id, items: [{ scope: "MONTHLY", year: 2026, month: 9 }, { scope: "MONTHLY", year: 2026, month: 10 }] }
  );
  expect(quote.status).toBe(200);
  expect(Number(quote.body.data!.total)).toBe(7200);
  expect(Number(quote.body.data!.subtotal)).toBe(7000);
  expect(Number(quote.body.data!.facilityFeeTotal)).toBe(200);
  await expect.poll(async () => digits(await composer.getByTestId("composer-total").textContent())).toBe(moneyDigits(quote.body.data!.total));
  expect(digits(await composer.getByTestId("composer-subtotal").textContent())).toBe(moneyDigits(quote.body.data!.subtotal));
  expect(digits(await composer.getByTestId("composer-facility-fee").textContent())).toBe(
    moneyDigits(quote.body.data!.facilityFeeTotal)
  );
  await expect(composer.getByTestId("composer-fee-note")).toBeVisible();
  await page.screenshot({ path: `${EVIDENCE_DIR}/09-composer-two-line-summary.png` });

  // EP-M03: removing a line re-quotes (3500 + 100 fee).
  await composer.getByTestId("composer-summary-remove").first().click();
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(1);
  await expect.poll(async () => digits(await composer.getByTestId("composer-total").textContent())).toBe(moneyDigits(3600));
  // The removed month is deselected in the month grid too; re-adding it and
  // toggling it off/on again never duplicates a line.
  const sep = composer.locator('[data-testid="composer-month"][data-month="9"]');
  const oct = composer.locator('[data-testid="composer-month"][data-month="10"]');
  const removed = (await sep.getAttribute("aria-pressed")) === "true" ? oct : sep;
  await expect(removed).toHaveAttribute("aria-pressed", "false");
  await removed.click();
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(2);
  await removed.click();
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(1);
  await removed.click();
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(2);
  await expect.poll(async () => digits(await composer.getByTestId("composer-total").textContent())).toBe(moneyDigits(7200));
});

test("EP-P04: slip submission -> pending approval; no access; a second payment attempt and the legacy order path are refused", async ({
  page,
  request,
}) => {
  await loginUi(page, STUDENT_EMAIL);
  await openCourse(page);
  await period(page, "2026-10").getByTestId("period-pay-now").click();
  const composer = page.getByTestId("payment-composer");
  await composer.locator('[data-testid="composer-month"][data-month="9"]').click();
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(2);
  await expect.poll(async () => digits(await composer.getByTestId("composer-total").textContent())).toBe(moneyDigits(7200));

  await composer.getByTestId("composer-method-slip").click();
  await expect(composer.getByTestId("composer-bank-details")).toContainText("Test Bank");
  await expect(composer.getByTestId("composer-account-number")).toHaveText("000-TEST");
  await composer.getByTestId("composer-slip-reference").fill(reference);
  await composer.getByTestId("composer-slip-file").setInputFiles({
    name: `slip-${tag}.pdf`,
    mimeType: "application/pdf",
    buffer: uniquePdf(tag),
  });
  await composer.getByTestId("composer-submit-slip").click();
  await expect(composer.getByTestId("composer-slip-submitted")).toContainText("Submitted — pending approval");
  await page.screenshot({ path: `${EVIDENCE_DIR}/10-slip-submitted-pending.png` });
  await composer.getByTestId("composer-done").click();

  await page.reload();
  await expect(period(page, "2026-10").getByTestId("period-status-pending")).toBeVisible();
  await expect(period(page, "2026-09").getByTestId("period-status-pending")).toBeVisible();
  await expect(period(page, "2026-08").getByTestId("period-pay-now")).toBeVisible();
  await page.screenshot({ path: `${EVIDENCE_DIR}/11-content-tree-pending.png`, fullPage: true });

  const pendingLaunch = await api(request, studentToken, "POST", `/courses/${course.id}/lessons/${lessons.oct1}/launch`);
  expect(pendingLaunch.status).toBe(403);
  expect(pendingLaunch.body.code ?? pendingLaunch.body.error?.code).toBe("PAYMENT_PENDING");

  const queue = await api<{ content: { id: string; orderId: string; referenceNumber: string; status: string }[] }>(
    request,
    adminToken,
    "GET",
    "/payment-slips/review-queue?size=100"
  );
  const row = (queue.body.data?.content ?? []).find((s) => s.referenceNumber === reference);
  expect(row, "submitted slip is in the review queue").toBeTruthy();
  slip = { id: row!.id, orderId: row!.orderId };

  // T8b: a second payment on a line order with live evidence is refused.
  const second = await api(request, studentToken, "POST", `/orders/${slip.orderId}/payments`, {});
  expect(second.status).toBe(409);
  expect(second.body.error?.code).toBe("ORDER_PAYMENT_IN_PROGRESS");
  // T8b: the legacy whole-course order path refuses MONTHLY courses.
  const legacy = await api(request, studentToken, "POST", "/orders", { courseId: course.id });
  expect(legacy.status).toBe(409);
  expect(legacy.body.error?.code).toBe("USE_PAYMENT_COMPOSER");
});

test("EP-P05/CM-S04/CM-S06: admin approves in the slip review UI -> Sep/Oct paid, Aug still required; progress only on launch", async ({
  page,
  browser,
  request,
}) => {
  expect(slip, "depends on the slip submitted by the previous test").not.toBeNull();

  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  try {
    await loginUi(adminPage, ADMIN_EMAIL);
    await adminPage.goto(`${TENANT_BASE}/tenant-admin/payments/slip-review/${slip!.id}`);
    await expect(adminPage.getByText(reference).first()).toBeVisible();
    await adminPage.getByRole("button", { name: "Approve", exact: true }).first().click();
    const dialog = adminPage.getByRole("alertdialog");
    await expect(dialog).toContainText(`Approve slip ${reference}`);
    await dialog.getByRole("button", { name: "Approve", exact: true }).click();
    await expect(dialog).toBeHidden();
    await expect.poll(async () => (await api<{ status: string }>(request, adminToken, "GET", `/payment-slips/${slip!.id}`)).body.data?.status).toBe(
      "APPROVED"
    );
    await adminPage.screenshot({ path: `${EVIDENCE_DIR}/12-admin-slip-approved.png`, fullPage: true });
  } finally {
    await adminContext.close();
  }

  await loginUi(page, STUDENT_EMAIL);
  await openCourse(page);
  await expect(period(page, "2026-10").getByTestId("period-status-paid")).toBeVisible();
  await expect(period(page, "2026-09").getByTestId("period-status-paid")).toBeVisible();
  // CM-S04: the unrelated August period is still unpaid.
  await expect(period(page, "2026-08").getByTestId("period-pay-now")).toBeVisible();

  // CM-S06: expanding folders records nothing.
  await expect(page.getByTestId("course-content-progress")).toHaveText("0 of 4 viewed");
  await period(page, "2026-10").getByTestId("content-period-toggle").click();
  await period(page, "2026-10").getByTestId("content-module-toggle").click();
  const oct1 = page.getByTestId("content-lesson").filter({ hasText: "October class 1" });
  await expect(oct1).toHaveAttribute("data-access-status", "ALLOWED");
  await expect(page.getByTestId("course-content-progress")).toHaveText("0 of 4 viewed");
  await page.screenshot({ path: `${EVIDENCE_DIR}/13-approved-paid-state.png`, fullPage: true });

  // Opening the lesson is a server launch, which records the view.
  await oct1.getByTestId("content-lesson-open").click();
  await page.reload();
  await expect(page.getByTestId("course-content-progress")).toHaveText("1 of 4 viewed");

  const augLaunch = await api(request, studentToken, "POST", `/courses/${course.id}/lessons/${lessons.aug}/launch`);
  expect(augLaunch.status).toBe(403);
  expect(augLaunch.body.code ?? augLaunch.body.error?.code).toBe("PAYMENT_REQUIRED");
  expect((await api(request, studentToken, "POST", `/courses/${course.id}/lessons/${lessons.sep}/launch`)).status).toBe(200);
});
