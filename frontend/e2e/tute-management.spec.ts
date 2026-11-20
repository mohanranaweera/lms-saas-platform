import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import { openCourseActions } from "./fixtures/klass/course-row-menu";

/**
 * TUTE-01 Course → Manage Tutes, the create/edit dialog and the tute detail
 * (assigned students). No real backend: `/v1/courses/**`, `/v1/me/permissions`
 * and `/v1/staff/permission-matrix` are answered by an in-memory fake shaped
 * like the backend DTOs (`TuteView`, `FulfillmentView`, ...) inside the
 * `ApiResponse<T>` envelope. All names are synthetic.
 */

const COURSE_ID = "c0000000-0000-0000-0000-000000000001";
const TUTE_ID = "70000000-0000-0000-0000-000000000001";
const LESSON_ID = "10000000-0000-0000-0000-000000000001";

const ALL_TUTE_CAPS = [
  "TUTE_VIEW_TUTES",
  "TUTE_VIEW_TUTE",
  "TUTE_CREATE",
  "TUTE_UPDATE",
  "TUTE_ENROLL",
  "TUTE_UNROLL",
  "TUTE_UPDATE_STATUS",
  "TUTE_DELETE",
  "TUTE_DISPATCH_VIEW",
  "TUTE_DISPATCH_PRINT_LABELS",
];

const COURSE = {
  id: COURSE_ID,
  teacherId: "aaaaaaaa-0000-0000-0000-000000000001",
  name: "Physics 2027",
  slug: "physics-2027",
  category: "Science",
  subject: "Physics",
  stream: null,
  grade: null,
  academicYear: "2027",
  description: null,
  price: 2500,
  accessDurationDays: null,
  enrollmentRule: null,
  status: "PUBLIC",
  pricingModel: "MONTHLY",
  archivedAt: null,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  teachers: [{ id: "aaaaaaaa-0000-0000-0000-000000000001", name: "Teacher Example", primary: true }],
  tags: [],
  linkedCourses: [],
  priceDisplay: { amount: 2500, currency: "LKR", period: "MONTH" },
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

function tute(overrides: Record<string, unknown> = {}) {
  return {
    id: TUTE_ID,
    courseId: COURSE_ID,
    name: "Unit 1 – Mechanics",
    description: "Printed notes",
    quantity: 50,
    reserved: 3,
    dispatched: 4,
    available: 43,
    paymentType: "PAID",
    price: 1500,
    currency: "LKR",
    paidLessonId: LESSON_ID,
    paidLessonTitle: "Revision Day 1",
    status: "ACTIVE",
    archivedAt: null,
    version: 4,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    ...overrides,
  };
}

const SOLD_OUT = tute({
  id: "70000000-0000-0000-0000-000000000002",
  name: "Unit 2 – Waves",
  quantity: 10,
  reserved: 6,
  dispatched: 4,
  available: 0,
  paymentType: "COVERED_BY_CLASS_FEES",
  price: null,
  paidLessonId: null,
  paidLessonTitle: null,
});

const PAID_SESSIONS = [
  { lessonId: LESSON_ID, title: "Revision Day 1", lessonLabel: null, moduleTitle: "Term 1", price: 1000, currency: "LKR", startsAt: null },
  { lessonId: "10000000-0000-0000-0000-000000000002", title: "Revision Day 2", lessonLabel: null, moduleTitle: "Term 1", price: 1000, currency: "LKR", startsAt: null },
];

function fulfillment(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    tuteId: TUTE_ID,
    courseId: COURSE_ID,
    studentId: `s-${id}`,
    studentProfileId: `p-${id}`,
    indexNumber: "0042",
    studentName: "Sample Student A",
    city: "Kandy",
    addressMissing: false,
    status: "PENDING",
    method: "POSTAL",
    source: "MANUAL",
    tuteName: "Unit 1 – Mechanics",
    paymentType: "PAID",
    price: 1500,
    currency: "LKR",
    held: false,
    paymentReversedAt: null,
    holdReleasedAt: null,
    createdAt: "2026-10-02T00:00:00Z",
    dispatchedAt: null,
    cancelledAt: null,
    cancelReason: null,
    ...overrides,
  };
}

const FULFILLMENTS = [
  fulfillment("f1"),
  fulfillment("f2", { indexNumber: "0007", studentName: "Sample Student B", method: "COLLECTION", source: "PAYMENT" }),
  fulfillment("f3", { indexNumber: "0110", studentName: "Sample Student C", held: true, paymentReversedAt: "2026-10-05T00:00:00Z", source: "PAYMENT", addressMissing: true, city: null }),
  fulfillment("f4", { indexNumber: "0003", studentName: "Sample Student D", status: "DISPATCHED", dispatchedAt: "2026-10-04T00:00:00Z" }),
];

interface Options {
  role?: string;
  capabilities?: string[];
  tutes?: ReturnType<typeof tute>[];
  /** Successive responses for POST/PUT tute (status + body); default success. */
  saveResponses?: Array<{ status: number; body: unknown }>;
  assignResponses?: Array<{ status: number; body: unknown }>;
}

interface Log {
  listQueries: URLSearchParams[];
  creates: unknown[];
  updates: unknown[];
  deletes: string[];
  syncs: number;
  assigns: unknown[];
  cancels: unknown[];
  statuses: unknown[];
}

async function fulfill(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function errorWithDetails(code: string, message: string, details: Record<string, unknown>) {
  return { ...apiError(code, message), error: { code, message, fieldErrors: [], details } };
}

function page<T>(content: T[], pageIndex: number, size: number, total = content.length) {
  return apiSuccess({ content, page: pageIndex, size, totalElements: total, totalPages: Math.ceil(total / size) });
}

async function setUp(pw: Page, options: Options = {}): Promise<Log> {
  const role = options.role ?? "TENANT_ADMIN";
  const log: Log = { listQueries: [], creates: [], updates: [], deletes: [], syncs: 0, assigns: [], cancels: [], statuses: [] };
  let tutes = [...(options.tutes ?? [tute(), SOLD_OUT])];
  const saveResponses = [...(options.saveResponses ?? [])];
  const assignResponses = [...(options.assignResponses ?? [])];

  await mockJson(pw, "**/v1/course-tags", 200, apiSuccess([]));
  await mockJson(pw, "**/v1/teachers", 200, apiSuccess([]));
  await pw.route(/\/v1\/(me\/permissions|courses|staff)(\/|\?|$)/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/v1\//, "/");
    const method = request.method();

    if (path === "/me/permissions") {
      return fulfill(route, 200, apiSuccess({ role, capabilities: options.capabilities ?? ALL_TUTE_CAPS, permissionVersion: 1 }));
    }
    if (path === "/courses" && method === "GET") return fulfill(route, 200, page([COURSE], 0, 100));
    if (path === `/courses/${COURSE_ID}` && method === "GET") return fulfill(route, 200, apiSuccess(COURSE));

    const base = `/courses/${COURSE_ID}/tutes`;
    if (path === base && method === "GET") {
      log.listQueries.push(url.searchParams);
      const q = (url.searchParams.get("search") ?? "").toLowerCase();
      const size = Number(url.searchParams.get("size") ?? "10");
      const rows = tutes.filter((row) => !q || row.name.toLowerCase().includes(q));
      return fulfill(route, 200, page(rows, 0, size));
    }
    if (path === `${base}/paid-sessions`) {
      const q = (url.searchParams.get("search") ?? "").toLowerCase();
      return fulfill(route, 200, apiSuccess(PAID_SESSIONS.filter((s) => !q || s.title.toLowerCase().includes(q))));
    }
    if (path === base && method === "POST") {
      const body = request.postDataJSON();
      log.creates.push(body);
      const next = saveResponses.shift();
      if (next) return fulfill(route, next.status, next.body);
      const created = tute({ ...body, id: "70000000-0000-0000-0000-000000000009", reserved: 0, dispatched: 0, available: body.quantity, version: 0 });
      tutes = [...tutes, created];
      return fulfill(route, 201, apiSuccess(created));
    }
    const one = path.match(new RegExp(`^${base}/([^/]+)$`));
    if (one && method === "GET") {
      const found = tutes.find((row) => row.id === one[1]);
      return found ? fulfill(route, 200, apiSuccess(found)) : fulfill(route, 404, apiError("NOT_FOUND", "Tute not found."));
    }
    if (one && method === "PUT") {
      const body = request.postDataJSON();
      log.updates.push(body);
      const next = saveResponses.shift();
      if (next) return fulfill(route, next.status, next.body);
      const updated = { ...tutes.find((row) => row.id === one[1])!, ...body, version: body.version + 1 };
      tutes = tutes.map((row) => (row.id === one[1] ? updated : row));
      return fulfill(route, 200, apiSuccess(updated));
    }
    if (one && method === "DELETE") {
      log.deletes.push(one[1]);
      tutes = tutes.filter((row) => row.id !== one[1]);
      return fulfill(route, 200, apiSuccess({ result: "ARCHIVED" }));
    }
    if (path === `${base}/${TUTE_ID}/sync-payments`) {
      log.syncs += 1;
      return fulfill(route, 200, apiSuccess({ added: 2, alreadyAssigned: 5, skippedOutOfStock: 1, held: 0 }));
    }
    if (path === `${base}/${TUTE_ID}/fulfillments` && method === "GET") {
      const status = url.searchParams.get("status");
      const rows = FULFILLMENTS.filter((row) => !status || row.status === status);
      return fulfill(route, 200, page(rows, 0, Number(url.searchParams.get("size") ?? "10")));
    }
    if (path === `${base}/${TUTE_ID}/eligible-students`) {
      return fulfill(
        route,
        200,
        apiSuccess([
          { studentId: "s-new-1", studentProfileId: "p-new-1", indexNumber: "0101", name: "Sample Student E", alreadyAssigned: false },
          { studentId: "s-new-2", studentProfileId: "p-new-2", indexNumber: "0102", name: "Sample Student F", alreadyAssigned: false },
          { studentId: "s-f1", studentProfileId: "p-f1", indexNumber: "0042", name: "Sample Student A", alreadyAssigned: true },
        ])
      );
    }
    if (path === `${base}/${TUTE_ID}/fulfillments` && method === "POST") {
      log.assigns.push(request.postDataJSON());
      const next = assignResponses.shift();
      if (next) return fulfill(route, next.status, next.body);
      return fulfill(route, 200, apiSuccess({ added: 2, alreadyAssigned: 0, available: 41 }));
    }
    const action = path.match(new RegExp(`^${base}/${TUTE_ID}/fulfillments/([^/]+)/(cancel|status)$`));
    if (action && method === "POST") {
      const body = request.postDataJSON();
      (action[2] === "cancel" ? log.cancels : log.statuses).push({ id: action[1], ...body });
      return fulfill(route, 200, apiSuccess(FULFILLMENTS.find((row) => row.id === action[1])));
    }
    return route.fallback();
  });
  await mockJson(pw, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  return log;
}

async function openTutes(pw: Page) {
  await pw.goto(`/tenant-admin/courses/${COURSE_ID}/tutes`);
  await expect(pw.getByRole("heading", { level: 1, name: "Tutes of Physics 2027" })).toBeVisible();
}

test.describe("Course → Manage Tutes", () => {
  test("course row menu has Manage Tutes after Edit Class Schedule and opens the tutes page", async ({ page: pw }) => {
    await setUp(pw);
    await pw.goto("/tenant-admin/courses");
    const menu = await openCourseActions(pw, pw.getByTestId("course-row").filter({ hasText: "Physics 2027" }));
    const items = await menu.getByRole("menuitem").allInnerTexts();
    const schedule = items.findIndex((text) => text.includes("Edit Class Schedule"));
    const tutes = items.findIndex((text) => text.includes("Manage Tutes"));
    const archive = items.findIndex((text) => text.includes("Archive"));
    expect(tutes).toBe(schedule + 1);
    expect(tutes).toBeLessThan(archive);

    await menu.getByTestId("course-action-tutes").click();
    await expect(pw).toHaveURL(new RegExp(`/tenant-admin/courses/${COURSE_ID}/tutes$`));
    await expect(pw.getByRole("heading", { level: 1, name: "Tutes of Physics 2027" })).toBeVisible();
    const crumbs = pw.getByRole("navigation", { name: "Breadcrumb" });
    await expect(crumbs.getByRole("link", { name: "Courses" })).toBeVisible();
    await expect(crumbs.getByText("Physics 2027")).toHaveAttribute("aria-current", "page");

    const table = pw.getByTestId("tutes-table");
    for (const header of ["Name", "Quantity", "Payment Type", "Sold Through", "Price (LKR)", "Actions"]) {
      await expect(table.getByRole("columnheader", { name: header })).toBeVisible();
    }
    const first = pw.getByTestId("tute-row").filter({ hasText: "Unit 1 – Mechanics" });
    await expect(first).toContainText("43 available");
    await expect(first).toContainText("Paid");
    await expect(first).toContainText("Revision Day 1");
    await expect(first).toContainText("LKR 1,500.00");
    const soldOut = pw.getByTestId("tute-row").filter({ hasText: "Unit 2 – Waves" });
    await expect(soldOut).toContainText("Covered by class fees");
    await expect(soldOut.getByTestId("tute-available")).toContainText("0 available (out of stock)");
  });

  test("search is debounced and sent to the server; empty result reads No tutes match", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openTutes(pw);
    await pw.getByLabel("Search tutes").fill("zzz");
    await expect(pw.getByTestId("tutes-empty")).toHaveText("No tutes match “zzz”.");
    expect(log.listQueries.at(-1)?.get("search")).toBe("zzz");
    expect(log.listQueries.at(-1)?.get("includeArchived")).toBe("false");
  });

  test("dialog field order, Paid reveals and requires Price, leaving Paid clears price and session", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openTutes(pw);
    await pw.getByRole("button", { name: "Add Tute" }).click();
    const dialog = pw.getByRole("dialog", { name: "Add New Tute" });
    await expect(dialog).toBeVisible();

    const order = async () =>
      dialog.locator("input, select, textarea").evaluateAll((els) => els.map((el) => (el as HTMLElement).dataset.testid));
    expect(await order()).toEqual(["tute-form-name", "tute-form-quantity", "tute-form-session", "tute-form-payment-type", "tute-form-description"]);
    await expect(dialog.getByLabel("Price (LKR)")).toHaveCount(0);
    await expect(dialog.getByText("Paid sessions apply only to Paid tutes")).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeVisible();

    await dialog.getByRole("button", { name: "Create Tute" }).click();
    await expect(dialog.getByText("Tute name is required.")).toBeVisible();
    await expect(dialog.getByText("Quantity is required.")).toBeVisible();
    await expect(dialog.getByText("Select a payment type.")).toBeVisible();

    await dialog.getByLabel("Tute Name").fill("Unit 3 – Optics");
    await dialog.getByLabel("Quantity").fill("1.5");
    await dialog.getByLabel("Payment Type").selectOption("PAID");
    expect(await order()).toEqual([
      "tute-form-name",
      "tute-form-quantity",
      "tute-form-session",
      "tute-form-payment-type",
      "tute-form-price",
      "tute-form-description",
    ]);
    await dialog.getByRole("button", { name: "Create Tute" }).click();
    await expect(dialog.getByText("Quantity must be a whole number (0 or more).")).toBeVisible();
    await expect(dialog.getByText("Price is required for Paid tutes.")).toBeVisible();
    expect(log.creates).toHaveLength(0);

    // Keyboard-operable session combobox.
    const combo = dialog.getByRole("combobox", { name: /Sell through a paid session/ });
    await combo.focus();
    await combo.fill("Day 2");
    await expect(dialog.getByRole("option", { name: /Revision Day 2/ })).toBeVisible();
    await combo.press("ArrowDown");
    await combo.press("Enter");
    await expect(combo).toHaveValue("Revision Day 2");
    await dialog.getByLabel("Price (LKR)").fill("750");

    // Switching away from Paid clears price and session.
    await dialog.getByLabel("Payment Type").selectOption("FREE");
    await expect(dialog.getByLabel("Price (LKR)")).toHaveCount(0);
    await expect(combo).toHaveValue("");
    await dialog.getByLabel("Payment Type").selectOption("PAID");
    await expect(dialog.getByLabel("Price (LKR)")).toHaveValue("");
    await dialog.getByLabel("Payment Type").selectOption("FREE");

    await dialog.getByLabel("Quantity").fill("20");
    await dialog.getByRole("button", { name: "Create Tute" }).click();
    await expect(dialog).toBeHidden();
    expect(log.creates[0]).toEqual({
      name: "Unit 3 – Optics",
      quantity: 20,
      paidLessonId: null,
      paymentType: "FREE",
      price: null,
      description: null,
    });
  });

  test("server field errors map onto the fields", async ({ page: pw }) => {
    const log = await setUp(pw, {
      saveResponses: [
        {
          status: 400,
          body: apiError("VALIDATION_ERROR", "Validation failed", [
            { field: "name", message: "A tute with this name already exists." },
            { field: "quantity", message: "Quantity is too large." },
          ]),
        },
        { status: 400, body: apiError("TUTE_SESSION_NOT_ELIGIBLE", "Choose a paid session of this course.") },
      ],
    });
    await openTutes(pw);
    await pw.getByRole("button", { name: "Add Tute" }).click();
    const dialog = pw.getByRole("dialog", { name: "Add New Tute" });
    await dialog.getByLabel("Tute Name").fill("Unit 1 – Mechanics");
    await dialog.getByLabel("Quantity").fill("10");
    await dialog.getByLabel("Payment Type").selectOption("PAID");
    await dialog.getByLabel("Price (LKR)").fill("100");
    await dialog.getByRole("button", { name: "Create Tute" }).click();
    await expect(dialog.getByLabel("Tute Name")).toHaveAttribute("aria-invalid", "true");
    await expect(dialog.getByText("A tute with this name already exists.")).toBeVisible();
    await expect(dialog.getByText("Quantity is too large.")).toBeVisible();

    await dialog.getByRole("button", { name: "Create Tute" }).click();
    await expect(dialog.getByText("Choose a paid session of this course.")).toBeVisible();
    await expect(dialog.getByRole("combobox", { name: /Sell through a paid session/ })).toHaveAttribute("aria-invalid", "true");
    expect(log.creates).toHaveLength(2);
    expect(log.creates[0]).toMatchObject({ paymentType: "PAID", price: 100 });
  });

  test("edit reads Save Changes, sends version and handles quantity / version conflicts", async ({ page: pw }) => {
    const log = await setUp(pw, {
      saveResponses: [
        { status: 409, body: errorWithDetails("TUTE_QUANTITY_BELOW_ALLOCATED", "Quantity below allocated.", { allocated: 7 }) },
        { status: 409, body: apiError("TUTE_VERSION_CONFLICT", "Version conflict.") },
      ],
    });
    await openTutes(pw);
    await pw.getByRole("button", { name: "Edit Unit 1 – Mechanics" }).click();
    const dialog = pw.getByRole("dialog", { name: "Edit Tute" });
    await expect(dialog.getByLabel("Tute Name")).toHaveValue("Unit 1 – Mechanics");
    await expect(dialog.getByLabel("Quantity")).toHaveValue("50");
    await expect(dialog.getByLabel("Price (LKR)")).toHaveValue("1500.00");
    await expect(dialog.getByRole("combobox", { name: /Sell through a paid session/ })).toHaveValue("Revision Day 1");

    await dialog.getByLabel("Quantity").fill("5");
    await dialog.getByRole("button", { name: "Save Changes" }).click();
    await expect(dialog.getByText("7 copies are already assigned or dispatched, so quantity can't be lower than 7.")).toBeVisible();

    await dialog.getByLabel("Quantity").fill("60");
    await dialog.getByRole("button", { name: "Save Changes" }).click();
    await expect(dialog.getByTestId("tute-form-error")).toContainText("This tute was changed by someone else — reload");
    await dialog.getByRole("button", { name: "Reload" }).click();
    await expect(dialog.getByTestId("tute-form-error")).toHaveCount(0);

    await dialog.getByLabel("Description").fill("Updated notes");
    await dialog.getByRole("button", { name: "Save Changes" }).click();
    await expect(dialog).toBeHidden();
    expect(log.updates).toHaveLength(3);
    expect(log.updates[0]).toMatchObject({ quantity: 5, version: 4, paymentType: "PAID", price: 1500, paidLessonId: LESSON_ID });
    expect(log.updates[2]).toMatchObject({ description: "Updated notes", version: 4 });
    await expect(pw.getByTestId("admin-toast")).toContainText("saved");
  });

  test("delete explains archiving and shows the result", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openTutes(pw);
    await pw.getByRole("button", { name: "Delete Unit 1 – Mechanics" }).click();
    const confirm = pw.getByTestId("confirm-delete-dialog");
    await expect(confirm).toContainText("archived instead");
    await confirm.getByRole("button", { name: "Delete tute" }).click();
    await expect(pw.getByTestId("admin-toast")).toContainText("archived instead of deleted");
    expect(log.deletes).toEqual([TUTE_ID]);
  });

  test("view-only capability hides Add, Edit, Delete and View", async ({ page: pw }) => {
    await setUp(pw, { role: "OPERATOR", capabilities: ["TUTE_VIEW_TUTES", "COURSES_VIEW"] });
    await openTutes(pw);
    await expect(pw.getByTestId("tute-row")).toHaveCount(2);
    await expect(pw.getByRole("button", { name: "Add Tute" })).toHaveCount(0);
    await expect(pw.getByTestId("tute-action-edit")).toHaveCount(0);
    await expect(pw.getByTestId("tute-action-delete")).toHaveCount(0);
    await expect(pw.getByTestId("tute-action-view")).toHaveCount(0);
  });

  test("without TUTE_VIEW_TUTES the course menu has no Manage Tutes", async ({ page: pw }) => {
    await setUp(pw, { role: "OPERATOR", capabilities: ["COURSES_VIEW"] });
    await pw.goto("/tenant-admin/courses");
    const menu = await openCourseActions(pw, pw.getByTestId("course-row").filter({ hasText: "Physics 2027" }));
    await expect(menu.getByText("Edit Class Schedule")).toBeVisible();
    await expect(menu.getByTestId("course-action-tutes")).toHaveCount(0);
  });

  test("a 403 from the list renders the permission-denied state", async ({ page: pw }) => {
    await setUp(pw);
    await pw.route(/\/v1\/courses\/[^/]+\/tutes\?/, (route) =>
      fulfill(route, 403, apiError("FORBIDDEN", "You do not have permission to view tutes."))
    );
    await pw.goto(`/tenant-admin/courses/${COURSE_ID}/tutes`);
    await expect(pw.getByText("You don't have permission to view this.")).toBeVisible();
  });
});

test.describe("Tute detail", () => {
  async function openDetail(pw: Page) {
    await pw.goto(`/tenant-admin/courses/${COURSE_ID}/tutes/${TUTE_ID}`);
    await expect(pw.getByRole("heading", { level: 1, name: /Unit 1 – Mechanics/ })).toBeVisible();
  }

  test("stock summary, sync payments result and assigned students", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openDetail(pw);
    await expect(pw.getByTestId("tute-stock-quantity")).toHaveText("50");
    await expect(pw.getByTestId("tute-stock-reserved")).toHaveText("3");
    await expect(pw.getByTestId("tute-stock-dispatched")).toHaveText("4");
    await expect(pw.getByTestId("tute-stock-available")).toHaveText("43");

    await pw.getByRole("button", { name: "Sync payments" }).click();
    await expect(pw.getByTestId("tute-sync-result")).toContainText("2 added, 5 already assigned, 1 skipped (out of stock), 0 on hold");
    expect(log.syncs).toBe(1);

    const rows = pw.getByTestId("fulfillment-row");
    await expect(rows).toHaveCount(4);
    await expect(rows.filter({ hasText: "Sample Student A" }).getByRole("link", { name: "0042" })).toHaveAttribute("href", "/tenant-admin/students/p-f1");
    await expect(rows.filter({ hasText: "Sample Student C" }).getByTestId("fulfillment-hold")).toContainText("Payment reversed");
    await expect(rows.filter({ hasText: "Sample Student C" }).getByTestId("fulfillment-no-address")).toBeVisible();
    await expect(rows.filter({ hasText: "Sample Student D" }).getByTestId("fulfillment-status")).toHaveText("Dispatched");
  });

  test("assign students: note, disabled already-assigned, out-of-stock 409, then success", async ({ page: pw }) => {
    const log = await setUp(pw, {
      assignResponses: [
        { status: 409, body: errorWithDetails("TUTE_OUT_OF_STOCK", "Out of stock.", { available: 1, requested: 2 }) },
      ],
    });
    await openDetail(pw);
    await pw.getByRole("button", { name: "Assign students" }).click();
    const dialog = pw.getByRole("dialog", { name: /Assign students/ });
    await expect(dialog.getByText("Assigning a tute never charges the student or unlocks paid lessons.")).toBeVisible();
    await expect(dialog.getByRole("checkbox", { name: /Sample Student A/ })).toBeDisabled();
    await expect(dialog.getByRole("radio", { name: /Postal/ })).toBeChecked();
    await dialog.getByRole("checkbox", { name: /Sample Student E/ }).check();
    await dialog.getByRole("checkbox", { name: /Sample Student F/ }).check();
    await dialog.getByRole("radio", { name: /Collection/ }).check();
    await dialog.getByRole("button", { name: "Assign 2 students" }).click();
    await expect(dialog.getByTestId("assign-students-error")).toContainText("1 available, 2 requested");

    await dialog.getByRole("button", { name: "Assign 2 students" }).click();
    await expect(dialog).toBeHidden();
    expect(log.assigns[1]).toEqual({ studentIds: ["s-new-1", "s-new-2"], method: "COLLECTION" });
  });

  test("unroll and release hold require a reason; handed over only for collection", async ({ page: pw }) => {
    const log = await setUp(pw);
    await openDetail(pw);
    const rowA = pw.getByTestId("fulfillment-row").filter({ hasText: "Sample Student A" });
    await rowA.getByRole("button", { name: "Actions for Sample Student A" }).click();
    const menu = pw.getByTestId("fulfillment-actions-content");
    await expect(menu.getByTestId("fulfillment-action-handed-over")).toHaveCount(0);
    await menu.getByTestId("fulfillment-action-cancel").click();
    const unroll = pw.getByTestId("unroll-dialog");
    await unroll.getByRole("button", { name: "Cancel copy" }).click();
    await expect(unroll.getByText("A reason is required.")).toBeVisible();
    await unroll.getByLabel("Reason").fill("Student left the class");
    await unroll.getByRole("button", { name: "Cancel copy" }).click();
    await expect(unroll).toBeHidden();
    expect(log.cancels[0]).toEqual({ id: "f1", reason: "Student left the class" });

    const rowB = pw.getByTestId("fulfillment-row").filter({ hasText: "Sample Student B" });
    await rowB.getByRole("button", { name: "Actions for Sample Student B" }).click();
    await pw.getByTestId("fulfillment-action-handed-over").click();
    await expect.poll(() => log.statuses.length).toBe(1);
    expect(log.statuses[0]).toEqual({ id: "f2", action: "MARK_HANDED_OVER" });

    const rowC = pw.getByTestId("fulfillment-row").filter({ hasText: "Sample Student C" });
    await rowC.getByRole("button", { name: "Actions for Sample Student C" }).click();
    await pw.getByTestId("fulfillment-action-release").click();
    const release = pw.getByTestId("release-hold-dialog");
    await release.getByRole("button", { name: "Release hold" }).click();
    await expect(release.getByText("A reason is required.")).toBeVisible();
    await release.getByLabel("Reason").fill("Refund reversed by bank");
    await release.getByRole("button", { name: "Release hold" }).click();
    await expect(release).toBeHidden();
    expect(log.statuses[1]).toEqual({ id: "f3", action: "RELEASE_HOLD", reason: "Refund reversed by bank" });

    // Dispatched rows have no actions.
    const rowD = pw.getByTestId("fulfillment-row").filter({ hasText: "Sample Student D" });
    await expect(rowD.getByTestId("fulfillment-actions")).toHaveCount(0);
  });

  test("without enroll / update capabilities the Sync and Assign buttons are hidden", async ({ page: pw }) => {
    await setUp(pw, { role: "MANAGER", capabilities: ["TUTE_VIEW_TUTES", "TUTE_VIEW_TUTE"] });
    await openDetail(pw);
    await expect(pw.getByTestId("fulfillment-row")).toHaveCount(4);
    await expect(pw.getByRole("button", { name: "Sync payments" })).toHaveCount(0);
    await expect(pw.getByRole("button", { name: "Assign students" })).toHaveCount(0);
    await expect(pw.getByTestId("fulfillment-actions")).toHaveCount(0);
  });
});

test.describe("Staff → Permissions: Tute group", () => {
  test("the catalog-driven Tute group is editable for Operator, Manager and Teacher", async ({ page: pw }) => {
    const roles = [
      { code: "OPERATOR", displayName: "Operator" },
      { code: "MANAGER", displayName: "Manager" },
      { code: "TEACHER", displayName: "Teacher" },
    ];
    const rowsSpec: Array<[string, string]> = [
      ["TUTE_VIEW_TUTES", "View Tutes"],
      ["TUTE_DISPATCH_VIEW", "View Tute Dispatch"],
      ["TUTE_DISPATCH_PRINT_LABELS", "Print Dispatch Labels"],
    ];
    const matrix = {
      version: 3,
      roles,
      groups: [
        {
          key: "TUTE",
          label: "Tute",
          rows: rowsSpec.map(([key, label]) => ({
            key,
            label,
            availability: "ENFORCED",
            reason: null,
            cells: Object.fromEntries(roles.map((role) => [role.code, { state: "NOT_GRANTED", reason: null }])),
          })),
        },
      ],
    };
    await setUp(pw, { capabilities: ["STAFF_VIEW", "STAFF_PERMISSIONS_EDIT", ...ALL_TUTE_CAPS] });
    await pw.route(/\/v1\/staff(\/|\?|$)/, async (route) => {
      const path = new URL(route.request().url()).pathname.replace(/^.*\/v1\//, "/");
      if (path === "/staff/permission-matrix") return fulfill(route, 200, apiSuccess(matrix));
      if (path === "/staff/assignable-roles") return fulfill(route, 200, apiSuccess(roles.slice(0, 2)));
      if (path === "/staff") return fulfill(route, 200, page([], 0, 10));
      return fulfill(route, 200, page([], 0, 20));
    });
    await pw.goto("/tenant-admin/staff?tab=permissions");
    for (const [, label] of rowsSpec) {
      for (const role of ["Operator", "Manager", "Teacher"]) {
        const cell = pw.getByRole("checkbox", { name: `${label} — ${role}` });
        await expect(cell).toBeVisible();
        await expect(cell).toBeEnabled();
      }
    }
  });
});
