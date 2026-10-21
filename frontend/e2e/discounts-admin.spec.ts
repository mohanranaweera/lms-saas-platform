import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * DISC-01 Tenant Admin discounts (list / create / edit / toggle / archive).
 * No real backend runs here: an in-memory fake answers `/api/v1/admin/discounts/**`
 * with the documented shapes. Every server rule (DISCOUNTS permission, tenant
 * scoping, validation, code uniqueness, versioning, audit) is proven by the
 * backend tests - these specs prove the UI states, the form, error mapping and
 * role-based visibility. The tenant is never sent by the client.
 */

const ICT = { courseId: "c-ict", name: "2026 A/L ICT", priceLabel: "LKR 3500.00", monthly: true, pricingModel: "MONTHLY", price: 3500, currency: "LKR" };
const BIO = { courseId: "c-bio", name: "2026 A/L Biology", priceLabel: "LKR 2000.00", monthly: true, pricingModel: "MONTHLY", price: 2000, currency: "LKR" };
const PAPER = { courseId: "c-paper", name: "Model Paper Pack", priceLabel: "LKR 1500.00", monthly: false, pricingModel: "ONE_TIME", price: 1500, currency: "LKR" };

function rule(overrides: Record<string, unknown> = {}) {
  return {
    id: "d-1",
    name: "ICT + Bio bundle",
    description: null,
    discountType: "BUNDLE_AMOUNT_OFF",
    active: true,
    status: "ACTIVE",
    courseIds: [ICT.courseId, BIO.courseId],
    courses: [
      { courseId: ICT.courseId, name: ICT.name },
      { courseId: BIO.courseId, name: BIO.name },
    ],
    selectionMode: "ALL",
    minCount: null,
    paymentType: null,
    valueType: "FIXED",
    value: 200,
    valueLabel: "LKR 200.00 OFF",
    rewardCourseIds: [],
    rewardCourses: [],
    freeMonths: null,
    code: null as string | null,
    validFrom: "2026-01-01",
    validTo: "2026-12-31",
    maxUses: 100 as number | null,
    minPurchaseAmount: null,
    currency: "LKR",
    versionNo: 1,
    versionCount: 1,
    usedCount: 3,
    reservedCount: 1,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

type Rule = ReturnType<typeof rule>;

const CODE_RULE = rule({
  id: "d-2",
  name: "Welcome code",
  discountType: "DISCOUNT_CODE",
  active: false,
  status: "INACTIVE",
  valueType: "PERCENTAGE",
  value: 10,
  valueLabel: "10% OFF",
  code: "WELCOME10",
  maxUses: null,
  usedCount: 0,
  reservedCount: 0,
});

interface WriteError {
  status: number;
  code: string;
  message: string;
  fieldErrors?: { field: string; message: string }[];
}

interface FakeState {
  role: string;
  rules: Rule[];
  requests: { method: string; path: string; body: string | null }[];
  forbidden: boolean;
  /** Answer the next POST/PUT with this error, once. */
  nextWriteError: WriteError | null;
}

async function setup(page: Page, role: string, overrides: Partial<FakeState> = {}): Promise<FakeState> {
  const state: FakeState = {
    role,
    rules: [rule(), CODE_RULE],
    requests: [],
    forbidden: false,
    nextWriteError: null,
    ...overrides,
  };
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  // Server-side masking: only DISCOUNTS/CREATE_EDIT (Tenant Admin) sees codes.
  const masked = (entry: Rule) => (state.role === "TENANT_ADMIN" || !entry.code ? entry : { ...entry, code: "******" });

  await page.route("**/api/v1/admin/discounts**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/api\/v1\/admin\/discounts/, "");
    const method = request.method();
    state.requests.push({ method, path, body: request.postData() });
    if (state.forbidden) return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));

    if (path === "" && method === "GET") {
      const status = url.searchParams.get("status") ?? "ALL";
      const items = state.rules.filter((entry) =>
        status === "ACTIVE" ? entry.active : status === "INACTIVE" ? !entry.active : true
      );
      return fulfillJson(route, 200, apiSuccess({
        items: items.map(masked),
        counts: {
          all: state.rules.length,
          active: state.rules.filter((entry) => entry.active).length,
          inactive: state.rules.filter((entry) => !entry.active).length,
        },
      }));
    }
    if (path === "/course-options" && method === "GET") {
      return fulfillJson(route, 200, apiSuccess([ICT, BIO, PAPER]));
    }
    const idMatch = path.match(/^\/([^/]+)$/);
    if ((path === "" && method === "POST") || (idMatch && method === "PUT")) {
      if (state.nextWriteError) {
        const error = state.nextWriteError;
        state.nextWriteError = null;
        return fulfillJson(route, error.status, apiError(error.code, error.message, error.fieldErrors ?? []));
      }
      const body = JSON.parse(request.postData() ?? "{}");
      if (method === "POST") {
        const created = rule({ ...body, id: `d-${state.rules.length + 1}`, status: body.active ? "ACTIVE" : "INACTIVE" });
        state.rules.push(created);
        return fulfillJson(route, 201, apiSuccess(created));
      }
      const target = state.rules.find((entry) => entry.id === idMatch![1]);
      if (!target) return fulfillJson(route, 404, apiError("NOT_FOUND", "Discount not found"));
      Object.assign(target, body, { versionNo: target.versionNo + 1, versionCount: target.versionCount + 1 });
      return fulfillJson(route, 200, apiSuccess(target));
    }
    const activeMatch = path.match(/^\/([^/]+)\/active$/);
    if (activeMatch && method === "PATCH") {
      const target = state.rules.find((entry) => entry.id === activeMatch[1]);
      if (!target) return fulfillJson(route, 404, apiError("NOT_FOUND", "Discount not found"));
      const { active } = JSON.parse(request.postData() ?? "{}");
      Object.assign(target, { active, status: active ? "ACTIVE" : "INACTIVE" });
      return fulfillJson(route, 200, apiSuccess(target));
    }
    if (idMatch && method === "GET") {
      const target = state.rules.find((entry) => entry.id === idMatch[1]);
      if (!target) return fulfillJson(route, 404, apiError("NOT_FOUND", "Discount not found"));
      return fulfillJson(route, 200, apiSuccess(masked(target)));
    }
    if (idMatch && method === "DELETE") {
      state.rules = state.rules.filter((entry) => entry.id !== idMatch[1]);
      // The contract answers 204 No Content (no envelope).
      return route.fulfill({ status: 204, body: "" });
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Unhandled"));
  });
  return state;
}

/** Opens a course dropdown, ticks the matching courses and closes it. */
async function pickCourses(page: Page, testId: string, names: RegExp[]): Promise<void> {
  await page.getByTestId(testId).click();
  const popup = page.getByTestId(`${testId}-popup`);
  for (const name of names) await popup.getByLabel(name).check();
  await page.keyboard.press("Escape");
  await expect(popup).toBeHidden();
}

test.describe("Tenant Admin discounts", () => {
  test("lists discounts with server counts, status chips and the Finance nav entry", async ({ page }) => {
    await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/payments/discounts");

    await expect(page.getByRole("heading", { name: "Discounts", level: 1 })).toBeVisible();
    await expect(page.getByRole("link", { name: "Discounts", exact: true }).first()).toBeVisible();
    await expect(page.getByTestId("discount-tab-ALL")).toHaveText("All (2)");
    await expect(page.getByTestId("discount-tab-ACTIVE")).toHaveText("Active (1)");
    await expect(page.getByTestId("discount-tab-INACTIVE")).toHaveText("Inactive (1)");

    const cards = page.getByTestId("discount-card");
    await expect(cards).toHaveCount(2);
    await expect(cards.first()).toContainText("ICT + Bio bundle");
    await expect(cards.first()).toContainText("Course Bundle - Amount Off");
    await expect(cards.first().getByTestId("discount-card-value")).toHaveText("LKR 200.00 OFF");
    await expect(cards.first().getByTestId("discount-card-status")).toHaveText("Active");
    await expect(cards.first()).toContainText("3 / 100 (1 pending)");
    await expect(cards.nth(1).getByTestId("discount-card-code")).toHaveText("WELCOME10");

    await page.getByTestId("discount-tab-INACTIVE").click();
    await expect(page.getByTestId("discount-card")).toHaveCount(1);
    await expect(page.getByTestId("discount-card-name")).toHaveText("Welcome code");
  });

  test("an empty tab explains itself and offers a way back", async ({ page }) => {
    await setup(page, "TENANT_ADMIN", { rules: [rule()] });
    await page.goto("/tenant-admin/payments/discounts");
    await page.getByTestId("discount-tab-INACTIVE").click();
    await expect(page.getByText("No inactive discounts")).toBeVisible();
    await page.getByRole("button", { name: "Show all discounts" }).click();
    await expect(page.getByTestId("discount-card")).toHaveCount(1);
  });

  test("no discounts at all offers Create Discount", async ({ page }) => {
    await setup(page, "TENANT_ADMIN", { rules: [] });
    await page.goto("/tenant-admin/payments/discounts");
    await expect(page.getByText("No discounts yet")).toBeVisible();
    await page.getByRole("button", { name: "Create Discount" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/payments\/discounts\/new$/);
  });

  test("toggle sends only {active}; delete confirms, handles 204 and removes the card", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/payments/discounts");
    const first = page.getByTestId("discount-card").first();

    // The switch reflects server state (no optimistic flip), so click and wait for the refetch.
    await first.getByTestId("discount-card-toggle").click();
    await expect(first.getByTestId("discount-card-status")).toHaveText("Inactive");
    await expect(first.getByTestId("discount-card-toggle")).not.toBeChecked();
    const patch = state.requests.find((entry) => entry.method === "PATCH");
    expect(patch?.path).toBe("/d-1/active");
    expect(JSON.parse(patch?.body ?? "{}")).toEqual({ active: false });

    await first.getByTestId("discount-card-delete").click();
    await expect(page.getByRole("alertdialog")).toContainText("Delete ICT + Bio bundle?");
    await page.getByTestId("discount-delete-confirm").click();
    await expect(page.getByTestId("discount-card")).toHaveCount(1);
    await expect(page.getByTestId("discount-action-error")).toHaveCount(0);
    expect(state.requests.some((entry) => entry.method === "DELETE" && entry.path === "/d-1")).toBe(true);
  });

  test("create validates client-side first, then maps server field errors and a taken code", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/payments/discounts/new");
    await expect(page.getByRole("heading", { name: "Create New Discount" })).toBeVisible();

    // Nothing filled: client errors, no request.
    await page.getByTestId("discount-submit").click();
    await expect(page.getByText("Discount name is required.")).toBeVisible();
    await expect(page.getByText("Select at least one required course.")).toBeVisible();
    await expect(page.getByText("Discount value is required.")).toBeVisible();
    expect(state.requests.filter((entry) => entry.method === "POST")).toHaveLength(0);

    // Switch to Discount Code: the code field appears.
    await page.getByTestId("discount-type").click();
    await page.getByRole("option", { name: "Discount Code" }).click();
    await expect(page.getByText("Enter code to get discount")).toBeVisible();

    await page.getByLabel("Discount Name *").fill("Welcome back");
    await pickCourses(page, "discount-required-courses", [/2026 A\/L ICT/]);
    await page.getByLabel("Discount Code *").fill("back-2026");
    await page.getByLabel("Percentage (%)").check();
    await page.getByLabel("Discount Value (%) *").fill("150");
    await page.getByLabel("Valid From *").fill("2026-11-01");
    await page.getByLabel("Valid To *").fill("2026-10-01");
    await page.getByTestId("discount-submit").click();
    await expect(page.getByText("A percentage cannot be more than 100.")).toBeVisible();
    await expect(page.getByText("Valid to must be on or after valid from.")).toBeVisible();

    await page.getByLabel("Discount Value (%) *").fill("15");
    await page.getByLabel("Valid To *").fill("2026-12-31");

    // A server-only rule surfaces on its field.
    state.nextWriteError = {
      status: 422,
      code: "DISCOUNT_VALIDATION",
      message: "Validation failed",
      fieldErrors: [{ field: "courseIds", message: "Draft or archived courses cannot be used in a discount" }],
    };
    await page.getByTestId("discount-submit").click();
    await expect(page.getByText("Draft or archived courses cannot be used in a discount")).toBeVisible();

    state.nextWriteError = { status: 409, code: "DISCOUNT_CODE_TAKEN", message: "Code taken" };
    await page.getByTestId("discount-submit").click();
    await expect(page.getByText("Another discount already uses this code.")).toBeVisible();

    await page.getByTestId("discount-submit").click();
    await expect(page).toHaveURL(/\/tenant-admin\/payments\/discounts$/);

    const posts = state.requests.filter((entry) => entry.method === "POST");
    const body = JSON.parse(posts[posts.length - 1].body ?? "{}");
    expect(body).toMatchObject({
      name: "Welcome back",
      discountType: "DISCOUNT_CODE",
      courseIds: [ICT.courseId],
      selectionMode: "ALL",
      minCount: null,
      paymentType: null,
      valueType: "PERCENTAGE",
      value: "15",
      rewardCourseIds: [],
      freeMonths: null,
      code: "BACK-2026",
      validFrom: "2026-11-01",
      validTo: "2026-12-31",
      maxUses: null,
      active: true,
    });
    expect(Object.keys(body)).not.toContain("tenantId");
  });

  test("free-course rules offer only monthly courses that aren't required, with Free For (Months)", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/payments/discounts/new");
    await page.getByTestId("discount-type").click();
    await page.getByRole("option", { name: "Course Bundle - Free Course" }).click();

    await page.getByLabel("Discount Name *").fill("Bio bonus");
    await pickCourses(page, "discount-required-courses", [/2026 A\/L ICT/]);
    await page.getByTestId("discount-free-courses").click();
    const free = page.getByTestId("discount-free-courses-popup");
    // ICT is required and the paper pack isn't monthly: only Biology is offered.
    await expect(free.getByLabel(/2026 A\/L Biology/)).toBeVisible();
    await expect(free.getByLabel(/2026 A\/L ICT/)).toHaveCount(0);
    await expect(free.getByLabel(/Model Paper Pack/)).toHaveCount(0);
    await free.getByLabel(/2026 A\/L Biology/).check();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("discount-free-courses")).toContainText("2026 A/L Biology");
    await expect(page.getByLabel("Free For (Months) *")).toHaveValue("1");
    await page.getByLabel("Valid From *").fill("2026-01-01");
    await page.getByLabel("Valid To *").fill("2026-12-31");
    await page.getByTestId("discount-submit").click();
    await expect(page).toHaveURL(/\/tenant-admin\/payments\/discounts$/);

    const body = JSON.parse(state.requests.find((entry) => entry.method === "POST")?.body ?? "{}");
    expect(body).toMatchObject({
      discountType: "BUNDLE_FREE_COURSE",
      rewardCourseIds: [BIO.courseId],
      freeMonths: 1,
      valueType: null,
      value: null,
      code: null,
    });
  });

  test("edit locks the discount type and PUTs a new version", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/payments/discounts/d-1");
    await expect(page.getByRole("heading", { name: "Edit Discount" })).toBeVisible();
    await expect(page.getByTestId("discount-version")).toContainText("Version 1 of 1");
    await expect(page.getByTestId("discount-type")).toBeDisabled();
    await expect(page.getByLabel("Discount Name *")).toHaveValue("ICT + Bio bundle");
    await expect(page.getByLabel("Discount Value (LKR) *")).toHaveValue("200.00");

    await page.getByLabel("Discount Value (LKR) *").fill("250");
    await page.getByTestId("discount-submit").click();
    await expect(page).toHaveURL(/\/tenant-admin\/payments\/discounts$/);
    const put = state.requests.find((entry) => entry.method === "PUT");
    expect(put?.path).toBe("/d-1");
    expect(JSON.parse(put?.body ?? "{}")).toMatchObject({ value: "250", discountType: "BUNDLE_AMOUNT_OFF" });
  });

  test("Read-only Auditor sees masked codes and no create, toggle, delete or save controls", async ({ page }) => {
    const state = await setup(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/payments/discounts");
    await expect(page.getByTestId("discount-card")).toHaveCount(2);
    await expect(page.getByTestId("discount-create")).toHaveCount(0);
    await expect(page.getByTestId("discount-card-toggle")).toHaveCount(0);
    await expect(page.getByTestId("discount-card-delete")).toHaveCount(0);
    await expect(page.getByTestId("discount-card-code")).toHaveText("******");

    await page.getByRole("link", { name: "View Welcome code" }).click();
    await expect(page.getByRole("heading", { name: "Discount", level: 1 })).toBeVisible();
    await expect(page.getByLabel("Discount Name *")).toBeDisabled();
    await expect(page.getByTestId("discount-submit")).toHaveCount(0);
    await expect(page.getByText("The code is hidden for your role.")).toBeVisible();
    expect(state.requests.every((entry) => entry.method === "GET")).toBe(true);
  });

  test("a 403 renders the permission-denied state", async ({ page }) => {
    await setup(page, "FINANCE_STAFF", { forbidden: true });
    await page.goto("/tenant-admin/payments/discounts");
    await expect(page.getByTestId("permission-denied-state").or(page.getByText(/permission/i)).first()).toBeVisible();
    await expect(page.getByTestId("discount-card")).toHaveCount(0);
  });

  test("an unknown or other-institute discount id shows the error state, not a form", async ({ page }) => {
    await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/payments/discounts/d-other-tenant");
    await expect(page.getByText("Discount not found")).toBeVisible();
    await expect(page.getByTestId("discount-form")).toHaveCount(0);
  });

  test("roles without DISCOUNTS see no Discounts nav entry", async ({ page }) => {
    await setup(page, "CONTENT_MANAGER");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Dashboard" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Discounts", exact: true })).toHaveCount(0);
  });

  test("course dropdown filters while typing; All ticks only the matching courses", async ({ page }) => {
    await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/payments/discounts/new");
    const trigger = page.getByTestId("discount-required-courses");
    await expect(trigger).toContainText("Select courses");

    await trigger.click();
    const popup = page.getByTestId("discount-required-courses-popup");
    // Focus lands in the search box, so typing filters straight away.
    await expect(page.getByTestId("discount-required-courses-search")).toBeFocused();
    await expect(popup.getByRole("checkbox")).toHaveCount(4); // All + 3 courses
    await page.keyboard.type("a/l");
    await expect(popup.getByLabel(/Model Paper Pack/)).toHaveCount(0);
    await popup.getByLabel("All matching (2)").check();
    await page.keyboard.press("Escape");
    await expect(trigger).toContainText("2026 A/L ICT - LKR 3500.00");
    await expect(trigger).toContainText("2026 A/L Biology - LKR 2000.00");

    // Reopening clears the filter; a query with no match says so.
    await trigger.click();
    await expect(popup.getByRole("checkbox")).toHaveCount(4);
    await expect(popup.getByLabel(/Model Paper Pack/)).not.toBeChecked();
    await page.getByTestId("discount-required-courses-search").fill("chemistry");
    await expect(popup.getByText("Nothing matches")).toBeVisible();
  });
});
