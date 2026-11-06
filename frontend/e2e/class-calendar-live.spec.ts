import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { ADMIN_EMAIL, STUDENT_EMAIL, TENANT_BASE, api, apiLogin, createCourse, firstTeacherUserId, loginUi } from "./fixtures/klass/klass-api";

/**
 * CAL-01 live end-to-end check against the real local backend (opt-in:
 * `CAL_LIVE=1`). Seeds synthetic courses, saves schedules through the real
 * API, reconciles calendar JSON / PDF / public Today's Classes, and captures
 * evidence screenshots into docs/qa/class-calendar-evidence/. The tenant
 * timezone is set to America/New_York for the run (so a single run covers
 * Over / Live now / Scheduled and differs from the machine zone) and is
 * restored afterwards.
 */
test.skip(!process.env.CAL_LIVE, "live backend check; set CAL_LIVE=1");
test.describe.configure({ mode: "serial" });

const ZONE = "America/New_York";
const EVIDENCE = path.resolve(__dirname, "../../docs/qa/class-calendar-evidence");
const tag = Date.now().toString(36);
let token = "";
let originalZone: string | null = null;
const courses: Record<"over" | "live" | "scheduled" | "hidden", { id: string; name: string }> = {} as never;

function nyNow(): { date: string; weekday: string; minutes: number } {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", { timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit", weekday: "long", hour: "2-digit", minute: "2-digit", hourCycle: "h23" })
      .formatToParts(new Date())
      .map((p) => [p.type, p.value])
  );
  return { date: `${parts.year}-${parts.month}-${parts.day}`, weekday: parts.weekday.toUpperCase(), minutes: Number(parts.hour) * 60 + Number(parts.minute) };
}
const hhmm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
const addDays = (iso: string, n: number) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

async function saveSchedule(request: APIRequestContext, courseId: string, slots: { dayOfWeek: string; startTime: string; endTime: string }[], extra: Record<string, unknown> = {}) {
  const current = await api<{ revisionId: string | null }>(request, token, "GET", `/courses/${courseId}/class-schedule`);
  expect(current.status).toBe(200);
  return api(request, token, "PUT", `/courses/${courseId}/class-schedule`, { expectedRevisionId: current.body.data!.revisionId, effectiveFrom: null, effectiveTo: null, slots, ...extra });
}

async function shot(page: Page, name: string, settleMs = 0) {
  if (settleMs) await page.waitForTimeout(settleMs); // let dialog enter animations finish (evidence only)
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`), fullPage: false });
}

async function noHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "horizontal page overflow").toBeLessThanOrEqual(1);
}

test.beforeAll(async ({ request }) => {
  token = await apiLogin(request, ADMIN_EMAIL);
  const general = await api<{ key: string; value: unknown }[]>(request, token, "GET", "/tenant-config/GENERAL");
  originalZone = ((general.body.data ?? []).find((p) => p.key === "default_timezone")?.value as string) ?? null;
  expect((await api(request, token, "PUT", "/tenant-config/GENERAL", { default_timezone: ZONE })).status).toBe(200);

  await sweepSyntheticCourses(request, new Set());
  const teacher = await firstTeacherUserId(request, token);
  for (const [key, label] of [["over", "Morning Chemistry"], ["live", "Combined Maths — Theory & Revision for the 2027 Advanced Level Examination"], ["scheduled", "සිංහල Literature"], ["hidden", "Private Physics"]] as const) {
    courses[key] = await createCourse(request, token, { name: `CAL ${label} ${tag}`, teacherIds: [teacher], pricingModel: "FREE", ...(key === "hidden" ? { status: "PRIVATE" } : {}) });
  }

  const { weekday, minutes } = nyNow();
  // Keep every slot inside today's local day.
  expect(minutes, "run between 03:00 and 20:00 New York time").toBeGreaterThan(180);
  expect(minutes).toBeLessThan(20 * 60);
  const ok = async (key: keyof typeof courses, slots: { dayOfWeek: string; startTime: string; endTime: string }[]) =>
    expect((await saveSchedule(request, courses[key].id, slots)).status).toBe(200);
  await ok("over", [{ dayOfWeek: weekday, startTime: hhmm(minutes - 170), endTime: hhmm(minutes - 110) }, { dayOfWeek: "MONDAY", startTime: "07:30", endTime: "09:30" }, { dayOfWeek: "SATURDAY", startTime: "08:00", endTime: "10:00" }]);
  await ok("live", [{ dayOfWeek: weekday, startTime: hhmm(minutes - 30), endTime: hhmm(minutes + 90) }, { dayOfWeek: "WEDNESDAY", startTime: "16:00", endTime: "18:00" }, { dayOfWeek: "SATURDAY", startTime: "08:00", endTime: "10:00" }, { dayOfWeek: "SATURDAY", startTime: "13:00", endTime: "15:00" }]);
  await ok("scheduled", [{ dayOfWeek: weekday, startTime: hhmm(minutes + 120), endTime: hhmm(minutes + 180) }, { dayOfWeek: "SATURDAY", startTime: "09:00", endTime: "11:00" }, { dayOfWeek: "SATURDAY", startTime: "15:30", endTime: "17:00" }]);
  await ok("hidden", [{ dayOfWeek: weekday, startTime: hhmm(minutes - 30), endTime: hhmm(minutes + 30) }, { dayOfWeek: "SATURDAY", startTime: "10:00", endTime: "12:00" }]);
});

/** Clears (new empty revision) and archives every synthetic course this spec ever created. */
async function sweepSyntheticCourses(request: APIRequestContext, keep: Set<string>) {
  const list = await api<{ content: { id: string; name: string }[] }>(request, token, "GET", "/courses?search=CAL%20&size=100");
  for (const course of list.body.data?.content ?? []) {
    if (keep.has(course.id) || !/^CAL .+ [a-z0-9]{6,}$/.test(course.name)) continue;
    const current = await api<{ revisionId: string | null; slots: unknown[] }>(request, token, "GET", `/courses/${course.id}/class-schedule`);
    if (current.status === 200 && current.body.data!.slots.length > 0) {
      await api(request, token, "PUT", `/courses/${course.id}/class-schedule`, { expectedRevisionId: current.body.data!.revisionId, slots: [] });
    }
    await api(request, token, "POST", `/courses/${course.id}/archive`);
  }
}

test.afterAll(async ({ request }) => {
  if (!token) return;
  await api(request, token, "PUT", "/tenant-config/GENERAL", { default_timezone: originalZone ?? "Asia/Colombo" });
  if (!process.env.CAL_KEEP) await sweepSyntheticCourses(request, new Set());
});

test("API: reopen, validation, conflict, scope and public projection", async ({ request }) => {
  const reopened = await api<{ slots: unknown[]; timezone: string; canEdit: boolean }>(request, token, "GET", `/courses/${courses.live.id}/class-schedule`);
  expect(reopened.body.data!.slots).toHaveLength(4);
  expect(reopened.body.data!.timezone).toBe(ZONE);

  const overnight = await saveSchedule(request, courses.over.id, [{ dayOfWeek: "MONDAY", startTime: "22:00", endTime: "01:00" }]);
  expect(overnight.status).toBe(400);
  expect(JSON.stringify(overnight.body)).toContain("Overnight");
  const dup = await saveSchedule(request, courses.over.id, [{ dayOfWeek: "MONDAY", startTime: "07:30", endTime: "09:30" }, { dayOfWeek: "MONDAY", startTime: "07:30", endTime: "09:30" }]);
  expect(dup.status).toBe(400);
  const badRange = await saveSchedule(request, courses.over.id, [], { effectiveFrom: "2026-12-10", effectiveTo: "2026-12-01" });
  expect(badRange.status).toBe(400);
  const stale = await api(request, token, "PUT", `/courses/${courses.over.id}/class-schedule`, { expectedRevisionId: null, slots: [] });
  expect(stale.status).toBe(409);

  const { date } = nyNow();
  const today = await api<{ occurrences: { courseId: string; status: string; id: string }[]; timezone: string }>(request, token, "GET", `/calendar/occurrences?from=${date}&to=${addDays(date, 1)}`);
  expect(today.status).toBe(200);
  const mine = today.body.data!.occurrences.filter((o) => Object.values(courses).some((c) => c.id === o.courseId));
  const statusOf = (k: keyof typeof courses) => mine.find((o) => o.courseId === courses[k].id)?.status;
  expect([statusOf("over"), statusOf("live"), statusOf("scheduled"), statusOf("hidden")]).toEqual(["OVER", "LIVE", "SCHEDULED", "LIVE"]);
  expect(new Set(mine.map((o) => o.id)).size).toBe(mine.length);

  const pub = await request.get(`${TENANT_BASE}/api/v1/public/calendar/today`);
  const pubText = await pub.text();
  const pubBody = JSON.parse(pubText).data as { enabled: boolean; timezone: string; occurrences: { courseName: string; status: string }[] };
  expect(pubBody.timezone).toBe(ZONE);
  const pubMine = pubBody.occurrences.filter((o) => o.courseName.endsWith(tag));
  expect(pubMine.map((o) => o.status).sort()).toEqual(["LIVE", "OVER", "SCHEDULED"]);
  expect(pubText).not.toContain(courses.hidden.name);
  expect(pubText).not.toMatch(/joinUrl|meeting|passcode|providerReference|courseId|email|phone/i);

  // Student: read-only, scoped to own courses — the fresh synthetic courses are not theirs.
  const studentToken = await apiLogin(request, STUDENT_EMAIL);
  const studentCal = await api<{ occurrences: { courseId: string }[] }>(request, studentToken, "GET", `/calendar/occurrences?from=${date}&to=${addDays(date, 1)}`);
  expect(studentCal.status).toBe(200);
  expect(studentCal.body.data!.occurrences.filter((o) => Object.values(courses).some((c) => c.id === o.courseId))).toHaveLength(0);
  expect((await api(request, studentToken, "GET", `/calendar/occurrences?from=${date}&to=${addDays(date, 1)}&courseId=${courses.live.id}`)).status).toBe(404);
  expect((await api(request, studentToken, "GET", `/courses/${courses.live.id}/class-schedule`)).status).toBe(404);
  expect((await api(request, studentToken, "PUT", `/courses/${courses.live.id}/class-schedule`, { expectedRevisionId: null, slots: [] })).status).toBeGreaterThanOrEqual(403);

  // Anonymous: no bearer → 401 on authenticated calendar.
  expect((await request.get(`${TENANT_BASE}/api/v1/calendar/occurrences?from=${date}&to=${addDays(date, 1)}`)).status()).toBe(401);
});

test("PDF reconciles with the calendar JSON for the same month and filter", async ({ request }) => {
  const { date } = nyNow();
  const first = `${date.slice(0, 8)}01`;
  const firstDow = new Date(`${first}T00:00:00Z`).getUTCDay();
  const from = addDays(first, -firstDow);
  const to = addDays(from, 42);
  const json = await api<{ occurrences: { courseId: string }[] }>(request, token, "GET", `/calendar/occurrences?from=${from}&to=${to}&courseId=${courses.live.id}`);
  expect(json.status).toBe(200);
  const pdf = await request.fetch(`${TENANT_BASE}/api/v1/calendar/timetable.pdf?from=${from}&to=${to}&courseId=${courses.live.id}&view=MONTH`, { headers: { Authorization: `Bearer ${token}` } });
  expect(pdf.status()).toBe(200);
  expect(pdf.headers()["content-type"]).toContain("application/pdf");
  const bytes = await pdf.body();
  expect(bytes.subarray(0, 4).toString()).toBe("%PDF");
  fs.mkdirSync(EVIDENCE, { recursive: true });
  fs.writeFileSync(path.join(EVIDENCE, "timetable-month-single-course.pdf"), bytes);
  fs.writeFileSync(path.join(EVIDENCE, "timetable-month-single-course.count.txt"), String(json.body.data!.occurrences.length));
  const all = await request.fetch(`${TENANT_BASE}/api/v1/calendar/timetable.pdf?from=${from}&to=${to}&view=MONTH`, { headers: { Authorization: `Bearer ${token}` } });
  fs.writeFileSync(path.join(EVIDENCE, "timetable-month-all-courses.pdf"), await all.body());
  const empty = await request.fetch(`${TENANT_BASE}/api/v1/calendar/timetable.pdf?from=2031-01-05&to=2031-01-12&courseId=${courses.live.id}&view=WEEK`, { headers: { Authorization: `Bearer ${token}` } });
  expect(empty.status()).toBe(200);
  fs.writeFileSync(path.join(EVIDENCE, "timetable-week-empty.pdf"), await empty.body());
});

for (const vp of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "tablet", width: 768, height: 1024 },
  { name: "mobile", width: 390, height: 844 },
]) {
  test(`UI ${vp.name}: Today's Classes on public home`, async ({ page }) => {
    await page.setViewportSize(vp);
    await page.goto(`${TENANT_BASE}/`);
    const section = page.getByRole("region", { name: /today's classes/i }).or(page.locator("section", { hasText: "Today's Classes" })).first();
    await expect(section).toBeVisible({ timeout: 30_000 });
    await expect(section.getByRole("heading", { name: courses.over.name })).toBeVisible();
    await expect(section.getByText(courses.hidden.name)).toHaveCount(0);
    await section.scrollIntoViewIfNeeded();
    await page.evaluate(() => window.scrollBy(0, -80));
    await shot(page, `home-todays-classes-${vp.name}`);
    await noHorizontalOverflow(page);
  });

  test(`UI ${vp.name}: admin calendar views`, async ({ page }) => {
    await page.setViewportSize(vp);
    await loginUi(page, ADMIN_EMAIL);
    const { date } = nyNow();
    for (const view of ["month", "week", "day"] as const) {
      await page.goto(`${TENANT_BASE}/tenant-admin/calendar?view=${view}&date=${date}`);
      await expect(page.getByRole("heading", { name: /calendar & timetable/i })).toBeVisible({ timeout: 30_000 });
      // Below `sm` the month grid shows per-day indicators (chip text is in the day's agenda), so only assert text elsewhere.
      if (vp.name === "mobile" && view === "month") await page.waitForLoadState("networkidle");
      else await expect(page.getByText(/CAL Morning Chemistry/).first()).toBeVisible({ timeout: 30_000 });
      await shot(page, `calendar-${view}-${vp.name}`);
      await noHorizontalOverflow(page);
    }
    if (vp.name === "mobile") {
      await page.goto(`${TENANT_BASE}/tenant-admin/calendar?date=${date}`);
      await expect(page.getByText(/CAL Morning Chemistry/).first()).toBeVisible({ timeout: 30_000 });
      await shot(page, `calendar-default-mobile`);
      await noHorizontalOverflow(page);
    }
  });
}

test("UI: event details, course filter, schedule editor and appearance switch", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginUi(page, ADMIN_EMAIL);
  const { date } = nyNow();
  await page.goto(`${TENANT_BASE}/tenant-admin/calendar?view=week&date=${date}&course=${courses.live.id}`);
  const chip = page.getByRole("button", { name: new RegExp(`CAL Combined Maths`) }).first();
  await expect(chip).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/CAL Morning Chemistry/)).toHaveCount(0);
  await chip.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Combined Maths");
  await expect(dialog).toContainText(/New York|America\/New_York/);
  await shot(page, "event-details-dialog", 500);
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: /^month$/i }).click();
  await expect(page).toHaveURL(new RegExp(`course=${courses.live.id}`));

  await page.goto(`${TENANT_BASE}/tenant-admin/courses/${courses.live.id}/schedule`);
  await expect(page.getByText("08:00").or(page.getByText("8:00")).first()).toBeVisible({ timeout: 30_000 });
  const edit = page.getByRole("button", { name: /edit class schedule|edit schedule/i }).first();
  await edit.click();
  await expect(page.getByRole("dialog")).toContainText("Edit Class Schedule");
  await shot(page, "schedule-editor-dialog", 500);
  await page.getByRole("button", { name: /^cancel$/i }).click();

  await page.goto(`${TENANT_BASE}/tenant-admin/settings/appearance`);
  const toggle = page.getByRole("switch", { name: /today's classes/i });
  await expect(toggle).toBeVisible({ timeout: 30_000 });
  await toggle.scrollIntoViewIfNeeded();
  await shot(page, "appearance-show-todays-classes");
});
