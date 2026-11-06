import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import { openCourseActions } from "./fixtures/klass/course-row-menu";

/**
 * CAL-01 Calendar & Timetable + Edit Class Schedule
 * (docs/specs/class-calendar.md). Every backend call is intercepted; the
 * browser clock is pinned with `page.clock` so "today" (tenant zone
 * Asia/Colombo, UTC+05:30) is Friday 6 November 2026, 10:00. Any unmocked
 * `/api/v1/**` call gets a 404 envelope so nothing reaches a real backend.
 */

const TZ_OFFSET_MIN = 330;
const NOW = "2026-11-06T04:30:00.000Z"; // 10:00 AM in Colombo

function instant(date: string, time: string): string {
  const [y, m, d] = date.split("-").map(Number);
  const [h, mi] = time.split(":").map(Number);
  return new Date(Date.UTC(y, m - 1, d, h, mi) - TZ_OFFSET_MIN * 60_000).toISOString();
}

const COURSES = [
  { id: "course-bio", name: "2027 A/L Biology" },
  { id: "course-maths", name: "Combined Maths" },
  { id: "course-ict", name: "ICT Theory" },
  { id: "course-chem", name: "Chemistry Revision" },
];

function occ(
  course: (typeof COURSES)[number],
  date: string,
  start: string,
  end: string,
  extra: Partial<Record<string, unknown>> = {}
) {
  return {
    id: `r_${course.id}_${date.replace(/-/g, "")}_${start.replace(":", "")}`,
    source: "RECURRING",
    courseId: course.id,
    courseName: course.name,
    courseSlug: course.id,
    start: instant(date, start),
    end: instant(date, end),
    localDate: date,
    startLocalTime: start,
    endLocalTime: end,
    teacherNames: ["Nimal Perera"],
    status: "SCHEDULED",
    cancelled: false,
    sessionId: null,
    sessionTitle: null,
    replacesOccurrenceId: null,
    ...extra,
  };
}

const [BIO, MATHS, ICT, CHEM] = COURSES;
const OCCURRENCES = [
  // Today (Friday 6th): Over, Live now, Scheduled, Cancelled.
  occ(BIO, "2026-11-06", "08:00", "10:00"),
  occ(MATHS, "2026-11-06", "09:30", "11:30", { teacherNames: ["Kamal Silva", "Ruwan Jayasuriya"] }),
  occ(ICT, "2026-11-06", "14:00", "16:00"),
  occ(CHEM, "2026-11-06", "16:00", "17:00", { cancelled: true, status: "CANCELLED" }),
  // Saturday 7th: five classes → 3 chips + "+2 more".
  occ(BIO, "2026-11-07", "07:30", "09:30"),
  occ(MATHS, "2026-11-07", "07:30", "09:30"),
  occ(ICT, "2026-11-07", "10:30", "12:30"),
  occ(CHEM, "2026-11-07", "13:00", "15:00"),
  occ(BIO, "2026-11-07", "15:00", "17:00"),
  // Next month.
  occ(MATHS, "2026-12-10", "08:00", "10:00"),
];

interface CalendarMocks {
  occurrenceRequests: URL[];
}

async function setupCalendar(page: Page, role = "TENANT_ADMIN"): Promise<CalendarMocks> {
  const mocks: CalendarMocks = { occurrenceRequests: [] };
  await page.clock.install({ time: new Date(NOW) });
  // Fallback first (lowest precedence): never let an unmocked call reach a backend.
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify(apiError("NOT_FOUND", "Not mocked")) })
  );
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/calendar/courses", 200, apiSuccess(COURSES));
  await page.route("**/v1/calendar/occurrences?*", async (route: Route) => {
    const url = new URL(route.request().url());
    mocks.occurrenceRequests.push(url);
    const from = url.searchParams.get("from")!;
    const to = url.searchParams.get("to")!;
    const courseId = url.searchParams.get("courseId");
    const occurrences = OCCURRENCES.filter(
      (o) => o.localDate >= from && o.localDate < to && (!courseId || o.courseId === courseId)
    );
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ timezone: "Asia/Colombo", serverNow: NOW, from, to, occurrences })),
    });
  });
  return mocks;
}

test.describe("Calendar & Timetable", () => {
  test("month view: tenant today, chips, +N more popover and Sunday-first grid", async ({ page }) => {
    await setupCalendar(page);
    await page.goto("/tenant-admin/calendar");

    await expect(page.getByRole("heading", { name: "Calendar & Timetable" })).toBeVisible();
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("November 2026");
    const days = page.getByTestId("calendar-day");
    await expect(days).toHaveCount(42);
    // Sunday-first: 1 Nov 2026 is a Sunday, so the grid starts on it.
    await expect(days.first()).toHaveAttribute("data-date", "2026-11-01");
    await expect(page.locator('[data-testid="calendar-day"][data-today]')).toHaveAttribute("data-date", "2026-11-06");

    const saturday = page.locator('[data-testid="calendar-day"][data-date="2026-11-07"]');
    await expect(saturday.getByTestId("calendar-chip")).toHaveCount(3);
    await expect(saturday.getByTestId("calendar-chip").first()).toContainText("7:30 AM");
    const more = saturday.getByTestId("calendar-more");
    await expect(more).toHaveText("+2 more");
    await more.click();
    const list = page.getByTestId("calendar-day-list");
    await expect(list).toBeVisible();
    await expect(list.getByRole("heading", { name: "Classes on Saturday, 7 November 2026" })).toBeVisible();
    await expect(list.getByTestId("calendar-chip")).toHaveCount(5);
    await page.keyboard.press("Escape");
    await expect(list).toBeHidden();

    // Cancelled class (4th of today's classes, so behind "+1 more"): struck through with a text label, never colour only.
    await page.locator('[data-testid="calendar-day"][data-date="2026-11-06"]').getByTestId("calendar-more").click();
    const cancelled = page.getByTestId("calendar-day-list").locator('[data-status="CANCELLED"]');
    await expect(cancelled).toContainText("Cancelled");
    await expect(cancelled).toHaveAttribute("aria-label", /Chemistry Revision, Cancelled/);
  });

  test("details dialog matches the clicked occurrence and links to the role's course page", async ({ page }) => {
    await setupCalendar(page);
    await page.goto("/tenant-admin/calendar");

    const today = page.locator('[data-testid="calendar-day"][data-date="2026-11-06"]');
    await expect(today.getByTestId("calendar-chip")).toHaveCount(3);
    // 08:00–10:00 ended exactly at "now" (10:00) → Over; 09:30–11:30 → Live now.
    await expect(today.locator('[data-status="OVER"]')).toHaveCount(1);
    await today.getByRole("button", { name: /Combined Maths, Live now/ }).click();

    const dialog = page.getByTestId("occurrence-details-dialog");
    await expect(dialog.getByRole("heading", { name: "Combined Maths" })).toBeVisible();
    await expect(dialog.getByTestId("occurrence-date")).toHaveText("Friday, 6 November 2026");
    await expect(dialog.getByTestId("occurrence-time")).toHaveText("9:30 AM – 11:30 AM");
    await expect(dialog.getByText("Sri Lanka time (Asia/Colombo)")).toBeVisible();
    await expect(dialog.getByTestId("occurrence-teachers")).toHaveText("Kamal Silva, Ruwan Jayasuriya");
    await expect(dialog.getByTestId("schedule-status-badge")).toContainText("Live now");
    await expect(dialog.getByRole("link", { name: "View course" })).toHaveAttribute("href", "/tenant-admin/courses/course-maths");
    await dialog.getByRole("button", { name: "Close" }).first().click();
    await expect(dialog).toBeHidden();
  });

  test("student details link goes to the student course route", async ({ page }) => {
    await setupCalendar(page, "STUDENT");
    await page.goto("/student/calendar?date=2026-11-06");
    await page.locator('[data-testid="calendar-day"][data-date="2026-11-06"]').getByRole("button", { name: /ICT Theory/ }).click();
    await expect(page.getByTestId("occurrence-details-dialog").getByRole("link", { name: "View course" })).toHaveAttribute(
      "href",
      "/student/courses/course-ict"
    );
  });

  test("month/week/day switching keeps the course filter in the URL and in requests", async ({ page }) => {
    const mocks = await setupCalendar(page);
    await page.goto("/tenant-admin/calendar");
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("November 2026");

    // Course filter → URL + server-side narrowing request.
    await page.getByTestId("calendar-course-filter").click();
    await page.getByRole("option", { name: "Combined Maths" }).click();
    await expect(page).toHaveURL(/course=course-maths/);
    await expect.poll(() => mocks.occurrenceRequests.some((url) => url.searchParams.get("courseId") === "course-maths")).toBe(true);
    await expect(page.locator('[data-testid="calendar-day"][data-date="2026-11-07"]').getByTestId("calendar-chip")).toHaveCount(1);

    await page.getByRole("group", { name: "Calendar view" }).getByRole("button", { name: "Week" }).click();
    await expect(page).toHaveURL(/view=week/);
    await expect(page).toHaveURL(/course=course-maths/);
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("1 – 7 November 2026");
    await expect(page.getByTestId("calendar-week").getByTestId("calendar-event")).toHaveCount(2);
    await expect(page.getByTestId("calendar-now-line")).toHaveCount(1);
    const weekRequest = mocks.occurrenceRequests.at(-1)!;
    expect(weekRequest.searchParams.get("from")).toBe("2026-11-01");
    expect(weekRequest.searchParams.get("to")).toBe("2026-11-08");

    await page.getByRole("button", { name: "Next week" }).click();
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("8 – 14 November 2026");
    await page.getByRole("button", { name: "Previous week" }).click();
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("1 – 7 November 2026");

    await page.getByRole("group", { name: "Calendar view" }).getByRole("button", { name: "Day" }).click();
    await expect(page).toHaveURL(/view=day/);
    await expect(page).toHaveURL(/course=course-maths/);
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("Friday, 6 November 2026");
    await page.getByRole("button", { name: "Next day" }).click();
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("Saturday, 7 November 2026");

    await page.getByTestId("calendar-today").click();
    await expect(page).toHaveURL(/date=2026-11-06/);
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("Friday, 6 November 2026");
    await expect(page.getByTestId("calendar-day-view").getByTestId("calendar-event")).toHaveCount(1);

    // A reload restores the same view and filter from the URL.
    await page.reload();
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("Friday, 6 November 2026");
    await expect(page.getByTestId("calendar-course-filter")).toContainText("Combined Maths");

    await page.getByRole("group", { name: "Calendar view" }).getByRole("button", { name: "Month" }).click();
    await page.getByRole("button", { name: "Next month" }).click();
    await expect(page.getByTestId("calendar-period-heading")).toHaveText("December 2026");
    await expect(page.locator('[data-testid="calendar-day"][data-date="2026-12-10"]').getByTestId("calendar-chip")).toHaveCount(1);
  });

  test("week view splits overlapping classes into columns", async ({ page }) => {
    await setupCalendar(page);
    await page.goto("/tenant-admin/calendar?view=day&date=2026-11-06");
    const events = page.getByTestId("calendar-day-view").getByTestId("calendar-event");
    await expect(events).toHaveCount(4);
    const bio = await events.filter({ hasText: "2027 A/L Biology" }).boundingBox();
    const maths = await events.filter({ hasText: "Combined Maths" }).boundingBox();
    expect(bio && maths).toBeTruthy();
    // Overlapping 08:00–10:00 and 09:30–11:30: side by side, not stacked on top of each other.
    expect(Math.abs(bio!.x - maths!.x)).toBeGreaterThan(20);
  });

  test("week view caps overlapping lanes at 3 and lists the rest behind +N", async ({ page }) => {
    await setupCalendar(page);
    const busy = [BIO, MATHS, ICT, CHEM, BIO].map((course, index) =>
      occ(course, "2026-11-10", "09:00", "11:00", { id: `busy_${index}` })
    );
    await page.route("**/v1/calendar/occurrences?*", (route) => {
      const url = new URL(route.request().url());
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ timezone: "Asia/Colombo", serverNow: NOW, from: url.searchParams.get("from"), to: url.searchParams.get("to"), occurrences: busy })),
      });
    });
    await page.goto("/tenant-admin/calendar?view=week&date=2026-11-10");
    const week = page.getByTestId("calendar-week");
    await expect(week.getByTestId("calendar-event")).toHaveCount(2);
    const more = week.getByTestId("calendar-more");
    await expect(more).toHaveText(/\+3/);
    await expect(more).toHaveAccessibleName(/Show all 5 classes/);
    await more.click();
    // Nothing is silently hidden: the day list shows all five.
    await expect(page.getByTestId("calendar-day-list").getByTestId("calendar-chip")).toHaveCount(5);
  });

  test("empty period and error-with-retry states", async ({ page }) => {
    await setupCalendar(page);
    await page.goto("/tenant-admin/calendar?view=week&date=2026-11-22");
    await expect(page.getByTestId("calendar-empty")).toContainText("No classes in this period.");

    let fail = true;
    await page.route("**/v1/calendar/occurrences?*", async (route) => {
      if (fail) {
        await route.fulfill({ status: 500, contentType: "application/json", body: JSON.stringify(apiError("INTERNAL_ERROR", "Calendar is unavailable.")) });
        return;
      }
      await route.fallback();
    });
    await page.getByRole("button", { name: "Next week" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Calendar is unavailable." })).toBeVisible();
    fail = false;
    await page.getByRole("button", { name: /retry|try again/i }).click();
    await expect(page.getByTestId("calendar-week")).toBeVisible();
  });

  test("download timetable requests the PDF for the current range, course and view", async ({ page }) => {
    await setupCalendar(page);
    let pdfUrl: URL | null = null;
    await page.route("**/v1/calendar/timetable.pdf?*", async (route) => {
      pdfUrl = new URL(route.request().url());
      await route.fulfill({ status: 200, contentType: "application/pdf", body: "%PDF-1.4\n%%EOF" });
    });
    await page.goto("/tenant-admin/calendar?view=week&date=2026-11-06&course=course-bio");
    const download = page.waitForEvent("download");
    await page.getByTestId("calendar-download").click();
    expect((await download).suggestedFilename()).toBe("timetable-2026-11-01-2026-11-08.pdf");
    expect(pdfUrl!.searchParams.get("view")).toBe("WEEK");
    expect(pdfUrl!.searchParams.get("courseId")).toBe("course-bio");
    expect(pdfUrl!.searchParams.get("from")).toBe("2026-11-01");
  });

  test("download failure shows an error toast", async ({ page }) => {
    await setupCalendar(page);
    await mockJson(page, "**/v1/calendar/timetable.pdf?*", 503, apiError("SERVICE_UNAVAILABLE", "Down"));
    await page.goto("/tenant-admin/calendar");
    await page.getByTestId("calendar-download").click();
    await expect(page.getByTestId("admin-toast")).toContainText("Could not download the timetable");
  });

  test("mobile 390×844 defaults to the agenda list with no horizontal overflow", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setupCalendar(page, "STUDENT");
    await page.goto("/student/calendar");
    await expect(page.getByTestId("calendar-agenda")).toBeVisible();
    await expect(page.getByRole("heading", { name: /Friday, 6 November 2026/ })).toBeVisible();
    await expect(page.getByRole("group", { name: "Calendar view" }).getByRole("button", { name: "List" })).toHaveAttribute("aria-pressed", "true");
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);

    // Switching views stays available on mobile.
    await page.getByRole("group", { name: "Calendar view" }).getByRole("button", { name: "Week" }).click();
    await expect(page.getByTestId("calendar-week")).toBeVisible();
    const weekOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(weekOverflow).toBeLessThanOrEqual(0);
  });
});

// ---------------------------------------------------------------------------
// Edit Class Schedule
// ---------------------------------------------------------------------------

const COURSE = {
  id: "11111111-1111-1111-1111-111111111111",
  teacherId: "aaaaaaaa-1111-2222-3333-444444444444",
  name: "Biology 2027",
  slug: "biology-2027",
  category: "Science",
  subject: "Biology",
  stream: null,
  grade: null,
  academicYear: "2027",
  description: null,
  price: 1500,
  accessDurationDays: null,
  enrollmentRule: null,
  status: "PUBLIC",
  pricingModel: "MONTHLY",
  archivedAt: null,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-02T00:00:00Z",
  resolvedAmount: 1500,
  currency: "LKR",
  requiresManualQuote: false,
  teachers: [{ id: "aaaaaaaa-1111-2222-3333-444444444444", name: "Teacher Alpha", primary: true }],
  tags: [],
  linkedCourses: [],
  priceDisplay: { amount: 1500, currency: "LKR", period: "MONTH" },
  enrollmentFee: "0.00",
  paymentValidDays: null,
  commissionRate: null,
  whatsappInviteUrl: null,
  telegramInviteUrl: null,
  coverImageUrl: null,
  registrationEnabled: true,
  displayOrder: null,
  timezone: "Asia/Colombo",
};

function schedule(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    courseId: COURSE.id,
    courseName: COURSE.name,
    timezone: "Asia/Colombo",
    revisionId: "rev-1",
    effectiveFrom: null,
    effectiveTo: null,
    updatedAt: "2026-11-01T03:00:00Z",
    updatedByName: "Admin One",
    canEdit: true,
    slots: [{ id: "slot-1", dayOfWeek: "MONDAY", startTime: "08:00", endTime: "10:00" }],
    ...overrides,
  };
}

interface ScheduleMock {
  current: ReturnType<typeof schedule>;
  puts: Record<string, unknown>[];
  putResponse: ((body: Record<string, unknown>) => { status: number; body: unknown }) | null;
}

async function setupSchedule(page: Page, role = "TENANT_ADMIN", initial = schedule()): Promise<ScheduleMock> {
  const mock: ScheduleMock = { current: initial, puts: [], putResponse: null };
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify(apiError("NOT_FOUND", "Not mocked")) })
  );
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await page.route(
    (url) => url.pathname.endsWith("/v1/courses"),
    (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiPageSuccess([COURSE])) })
  );
  await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
  await page.route(`**/v1/courses/${COURSE.id}/class-schedule`, async (route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      mock.puts.push(body);
      if (mock.putResponse) {
        const response = mock.putResponse(body);
        await route.fulfill({ status: response.status, contentType: "application/json", body: JSON.stringify(response.body) });
        return;
      }
      const slots = (body.slots as { dayOfWeek: string; startTime: string; endTime: string }[]).map((slot, i) => ({ id: `saved-${i}`, ...slot }));
      mock.current = schedule({
        revisionId: `rev-${mock.puts.length + 1}`,
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
        slots,
      });
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(mock.current)) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(mock.current)) });
  });
  return mock;
}

async function openEditorFromMenu(page: Page) {
  await page.goto("/tenant-admin/courses");
  const row = page.getByTestId("course-row").filter({ hasText: COURSE.name });
  const menu = await openCourseActions(page, row);
  await menu.getByTestId("course-action-class-schedule").click();
  const dialog = page.getByTestId("edit-class-schedule-dialog");
  await expect(dialog).toBeVisible();
  return dialog;
}

test.describe("Edit Class Schedule", () => {
  test("add / remove / validate / save, then reopen shows the saved schedule", async ({ page }) => {
    const mock = await setupSchedule(page);
    const dialog = await openEditorFromMenu(page);

    await expect(dialog.getByRole("heading", { name: "Edit Class Schedule" })).toBeVisible();
    await expect(dialog).toContainText("Edit the class schedule for Biology 2027. These times will be shown on the student and public page.");
    await expect(dialog.getByRole("textbox", { name: "Start time for Monday class 1" })).toHaveValue("08:00");
    await expect(dialog.getByRole("textbox", { name: "End time for Monday class 1" })).toHaveValue("10:00");
    await expect(dialog.getByText("Optional. Classes are shown only on dates within this range (inclusive), in Sri Lanka time (Asia/Colombo).")).toBeVisible();

    // Add day menu lists Monday → Sunday.
    await dialog.getByTestId("schedule-add-day").click();
    const menu = page.getByTestId("schedule-add-day-menu");
    await expect(menu.getByRole("menuitem")).toHaveText(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]);
    await menu.getByRole("menuitem", { name: "Wednesday" }).click();

    // Required times.
    await dialog.getByTestId("schedule-save").click();
    await expect(dialog.getByText("Start time is required.")).toBeVisible();
    await expect(dialog.getByText("End time is required.")).toBeVisible();
    expect(mock.puts).toHaveLength(0);

    // Overnight is rejected with the explicit message.
    await dialog.getByRole("textbox", { name: "Start time for Wednesday class 1" }).fill("20:00");
    await dialog.getByRole("textbox", { name: "End time for Wednesday class 1" }).fill("06:00");
    await expect(dialog.getByText("End time must be after start time. Overnight classes aren't supported — split them into two rows.")).toBeVisible();
    await dialog.getByRole("textbox", { name: "End time for Wednesday class 1" }).fill("21:30");
    await expect(dialog.getByText(/Overnight classes/)).toHaveCount(0);

    // A second Monday row overlapping the first.
    await dialog.getByTestId("schedule-add-day").click();
    await page.getByTestId("schedule-add-day-menu").getByRole("menuitem", { name: "Monday" }).click();
    await dialog.getByRole("textbox", { name: "Start time for Monday class 2" }).fill("09:00");
    await dialog.getByRole("textbox", { name: "End time for Monday class 2" }).fill("11:00");
    await expect(dialog.getByTestId("schedule-row-error")).toContainText("Overlaps another Monday class (8:00 AM – 10:00 AM)");
    // Exact duplicate.
    await dialog.getByRole("textbox", { name: "Start time for Monday class 2" }).fill("08:00");
    await dialog.getByRole("textbox", { name: "End time for Monday class 2" }).fill("10:00");
    await expect(dialog.getByTestId("schedule-row-error")).toContainText("duplicate");
    // Several rows per weekday are allowed when they don't overlap.
    await dialog.getByRole("textbox", { name: "Start time for Monday class 2" }).fill("14:00");
    await dialog.getByRole("textbox", { name: "End time for Monday class 2" }).fill("15:30");
    await expect(dialog.getByTestId("schedule-row-error")).toHaveCount(0);

    // From must be ≤ To.
    await dialog.getByLabel("From date (optional)").fill("2027-03-31");
    await dialog.getByLabel("To date (optional)").fill("2026-11-01");
    await expect(dialog.getByText("To date must be on or after From date.")).toBeVisible();
    await dialog.getByLabel("To date (optional)").fill("2027-04-30");

    // Remove has an accessible name.
    await dialog.getByRole("button", { name: "Remove Wednesday class 1" }).click();
    await expect(dialog.getByRole("textbox", { name: "Start time for Wednesday class 1" })).toHaveCount(0);

    await dialog.getByTestId("schedule-save").click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId("admin-toast")).toContainText("Class schedule saved for “Biology 2027”.");
    expect(mock.puts).toHaveLength(1);
    expect(mock.puts[0]).toEqual({
      expectedRevisionId: "rev-1",
      effectiveFrom: "2027-03-31",
      effectiveTo: "2027-04-30",
      slots: [
        { dayOfWeek: "MONDAY", startTime: "08:00", endTime: "10:00" },
        { dayOfWeek: "MONDAY", startTime: "14:00", endTime: "15:30" },
      ],
    });

    // Reopen: the form shows the saved server copy.
    const row = page.getByTestId("course-row").filter({ hasText: COURSE.name });
    await (await openCourseActions(page, row)).getByTestId("course-action-class-schedule").click();
    const reopened = page.getByTestId("edit-class-schedule-dialog");
    await expect(reopened.getByRole("textbox", { name: "Start time for Monday class 2" })).toHaveValue("14:00");
    await expect(reopened.getByLabel("From date (optional)")).toHaveValue("2027-03-31");
  });

  test("Cancel discards unsaved edits; removing every row explains what saving does", async ({ page }) => {
    const mock = await setupSchedule(page);
    const dialog = await openEditorFromMenu(page);
    await dialog.getByRole("button", { name: "Remove Monday class 1" }).click();
    await expect(dialog.getByTestId("schedule-empty")).toContainText("Saving with no class times clears all future recurring classes");
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toBeHidden();
    expect(mock.puts).toHaveLength(0);

    const row = page.getByTestId("course-row").filter({ hasText: COURSE.name });
    await (await openCourseActions(page, row)).getByTestId("course-action-class-schedule").click();
    await expect(page.getByTestId("edit-class-schedule-dialog").getByRole("textbox", { name: "Start time for Monday class 1" })).toHaveValue("08:00");
  });

  test("server VALIDATION_ERROR messages are shown on the matching row", async ({ page }) => {
    const mock = await setupSchedule(page);
    mock.putResponse = () => ({
      status: 400,
      body: apiError("VALIDATION_ERROR", "Validation failed", [{ field: "slots[0].endTime", message: "Server rejected this end time." }]),
    });
    const dialog = await openEditorFromMenu(page);
    await dialog.getByTestId("schedule-save").click();
    await expect(dialog.getByText("Server rejected this end time.")).toBeVisible();
    await expect(dialog.getByRole("textbox", { name: "End time for Monday class 1" })).toHaveAttribute("aria-invalid", "true");
  });

  test("409 SCHEDULE_CONFLICT offers Reload, which loads the latest version", async ({ page }) => {
    const mock = await setupSchedule(page);
    mock.putResponse = () => {
      mock.current = schedule({ revisionId: "rev-9", slots: [{ id: "s9", dayOfWeek: "FRIDAY", startTime: "16:00", endTime: "18:00" }] });
      return {
        status: 409,
        body: apiError("SCHEDULE_CONFLICT", "This schedule was changed by someone else. Reload to see the latest version."),
      };
    };
    const dialog = await openEditorFromMenu(page);
    await dialog.getByTestId("schedule-save").click();
    const conflict = dialog.getByTestId("schedule-conflict");
    await expect(conflict).toContainText("This schedule was changed by someone else.");
    await conflict.getByRole("button", { name: "Reload" }).click();
    await expect(dialog.getByRole("textbox", { name: "Start time for Friday class 1" })).toHaveValue("16:00");
    await expect(dialog.getByTestId("schedule-conflict")).toHaveCount(0);

    mock.putResponse = null;
    await dialog.getByTestId("schedule-save").click();
    await expect(dialog).toBeHidden();
    expect(mock.puts.at(-1)!.expectedRevisionId).toBe("rev-9");
  });

  test("teacher without edit rights sees a read-only schedule", async ({ page }) => {
    await setupSchedule(page, "TEACHER", schedule({ canEdit: false }));
    await page.goto("/teacher/courses");
    await page.getByRole("button", { name: `Class schedule for ${COURSE.name}` }).click();
    const dialog = page.getByTestId("edit-class-schedule-dialog");
    await expect(dialog.getByText(/only the course's teacher or an administrator can change it/)).toBeVisible();
    await expect(dialog.getByRole("list", { name: "Weekly class times" })).toContainText("8:00 AM – 10:00 AM");
    await expect(dialog.getByTestId("schedule-save")).toHaveCount(0);
    await expect(dialog.getByTestId("schedule-add-day")).toHaveCount(0);
  });

  test("teacher with edit rights can save from the course list", async ({ page }) => {
    const mock = await setupSchedule(page, "TEACHER");
    await page.goto("/teacher/courses");
    await page.getByRole("button", { name: `Class schedule for ${COURSE.name}` }).click();
    const dialog = page.getByTestId("edit-class-schedule-dialog");
    await dialog.getByRole("textbox", { name: "End time for Monday class 1" }).fill("10:30");
    await dialog.getByTestId("schedule-save").click();
    await expect(dialog).toBeHidden();
    expect(mock.puts[0].slots).toEqual([{ dayOfWeek: "MONDAY", startTime: "08:00", endTime: "10:30" }]);
  });
});
