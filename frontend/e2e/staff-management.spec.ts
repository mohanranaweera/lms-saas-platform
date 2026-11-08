import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * STAFF-MGMT-02 — Staff Management (Users | Permissions | Activities) and
 * capability-driven UI gating. No real backend runs here (see
 * `fixtures/auth-mocks.ts`): every `/v1/staff*` and `/v1/me/permissions` call
 * is answered by an in-memory fake shaped like the backend DTOs
 * (`StaffResponse`, `PermissionMatrixView`, `StaffActivity`,
 * `MyPermissionsResponse`) inside the `ApiResponse<T>` envelope.
 */

const ALL_STAFF_CAPS = ["STAFF_VIEW", "STAFF_MANAGE", "STAFF_ASSIGN_ROLES", "STAFF_PERMISSIONS_EDIT", "STAFF_ACTIVITY_VIEW"];

const NO_ACTIONS = { canEdit: false, canChangeRole: false, canDeactivate: false, canReactivate: false, canResendInvite: false };

const ANN = {
  id: "11111111-1111-1111-1111-111111111111",
  firstName: "Ann",
  lastName: "Perera",
  name: "Ann Perera",
  email: "ann@example-institute.test",
  contactNumber: "+94 77 123 4567",
  roleCode: "OPERATOR",
  roleName: "Operator",
  status: "ACTIVE",
  lastLoginAt: "2026-11-01T08:30:00Z",
  actions: { canEdit: true, canChangeRole: true, canDeactivate: true, canReactivate: false, canResendInvite: false },
};

const BEN = {
  id: "22222222-2222-2222-2222-222222222222",
  firstName: "Ben",
  lastName: "Silva",
  name: "Ben Silva",
  email: "ben@example-institute.test",
  contactNumber: "0711234567",
  roleCode: "MANAGER",
  roleName: "Manager",
  status: "INVITED",
  lastLoginAt: null,
  actions: { ...NO_ACTIONS, canEdit: true, canResendInvite: true },
};

const ASSIGNABLE = [
  { code: "OPERATOR", displayName: "Operator" },
  { code: "MANAGER", displayName: "Manager" },
];

function matrix(version: number, operatorAdd: "GRANTED" | "NOT_GRANTED" = "NOT_GRANTED") {
  const teacherUnavailable = { state: "UNAVAILABLE", reason: "Not configurable for teachers" };
  return {
    version,
    roles: [...ASSIGNABLE, { code: "TEACHER", displayName: "Teacher" }],
    groups: [
      {
        key: "STUDENTS",
        label: "Students",
        rows: [
          {
            key: "STUDENTS_VIEW",
            label: "View Students",
            availability: "ENFORCED",
            reason: null,
            cells: {
              OPERATOR: { state: "GRANTED", reason: null },
              MANAGER: { state: "NOT_GRANTED", reason: null },
              TEACHER: teacherUnavailable,
            },
          },
          {
            key: "STUDENTS_ADD",
            label: "Add Student",
            availability: "ENFORCED",
            reason: null,
            cells: {
              OPERATOR: { state: operatorAdd, reason: null },
              MANAGER: { state: "NOT_GRANTED", reason: null },
              TEACHER: teacherUnavailable,
            },
          },
        ],
      },
      {
        key: "STAFF",
        label: "Staff",
        rows: [
          {
            key: "STAFF_VIEW",
            label: "View Staff",
            availability: "LOCKED",
            reason: "Tenant Admin only",
            cells: {
              OPERATOR: { state: "LOCKED", reason: "Tenant Admin only" },
              MANAGER: { state: "LOCKED", reason: "Tenant Admin only" },
              TEACHER: { state: "LOCKED", reason: "Tenant Admin only" },
            },
          },
        ],
      },
    ],
  };
}

const ACTIVITIES = [
  {
    id: "act-1",
    action: "staff.invited",
    actionLabel: "Staff invited",
    actorName: "Tina Admin",
    actorRole: "Tenant Admin",
    targetEntity: "Staff",
    targetReference: "b***@example-institute.test",
    occurredAt: "2026-11-05T09:15:00Z",
    details: { role: "MANAGER" },
  },
  {
    id: "act-2",
    action: "staff.role_permissions_changed",
    actionLabel: "Role permissions changed",
    actorName: "Tina Admin",
    actorRole: "Tenant Admin",
    targetEntity: "Permissions",
    targetReference: "v7",
    occurredAt: "2026-11-04T14:00:00Z",
    details: { fromVersion: 6, toVersion: 7 },
  },
];

interface FakeOptions {
  role?: string;
  capabilities?: string[];
  staff?: Array<typeof ANN | typeof BEN>;
  listStatus?: number;
  /** Status codes for successive `PUT /permission-matrix` calls (default 200). */
  saveStatuses?: number[];
  /** Status codes for successive `POST /staff` calls (default 201). */
  createStatuses?: number[];
}

interface FakeLog {
  listQueries: URLSearchParams[];
  creates: unknown[];
  saves: unknown[];
  matrixGets: number;
  lifecycle: string[];
  activityQueries: URLSearchParams[];
}

async function fulfill(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

/** Signs in as `role` and installs the in-memory staff API. */
async function setUp(page: Page, options: FakeOptions = {}): Promise<FakeLog> {
  const role = options.role ?? "TENANT_ADMIN";
  const log: FakeLog = { listQueries: [], creates: [], saves: [], matrixGets: 0, lifecycle: [], activityQueries: [] };
  let staff = [...(options.staff ?? [ANN, BEN])];
  let version = 7;
  let operatorAdd: "GRANTED" | "NOT_GRANTED" = "NOT_GRANTED";
  const saveStatuses = [...(options.saveStatuses ?? [])];
  const createStatuses = [...(options.createStatuses ?? [])];

  await page.route(/\/v1\/(staff|me\/permissions)(\/|\?|$)/, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/v1\//, "/");
    const method = request.method();

    if (path === "/me/permissions") {
      return fulfill(route, 200, apiSuccess({ role, capabilities: options.capabilities ?? ALL_STAFF_CAPS, permissionVersion: version }));
    }
    if (path === "/staff" && method === "GET") {
      log.listQueries.push(url.searchParams);
      if (options.listStatus && options.listStatus !== 200) {
        return fulfill(route, options.listStatus, apiError("FORBIDDEN", "You do not have permission to view staff."));
      }
      const q = (url.searchParams.get("q") ?? "").toLowerCase();
      const size = Number(url.searchParams.get("size") ?? "10");
      const pageIndex = Number(url.searchParams.get("page") ?? "0");
      const rows = staff.filter((member) => !q || `${member.name} ${member.email}`.toLowerCase().includes(q));
      return fulfill(
        route,
        200,
        apiSuccess({
          content: rows.slice(pageIndex * size, pageIndex * size + size),
          page: pageIndex,
          size,
          totalElements: rows.length,
          totalPages: Math.ceil(rows.length / size),
        })
      );
    }
    if (path === "/staff" && method === "POST") {
      const body = request.postDataJSON();
      log.creates.push(body);
      const status = createStatuses.shift() ?? 201;
      if (status === 409) {
        return fulfill(route, 409, apiError("CONFLICT", "A staff member with this email already exists."));
      }
      const created = {
        ...BEN,
        id: "33333333-3333-3333-3333-333333333333",
        firstName: body.firstName,
        lastName: body.lastName,
        name: `${body.firstName} ${body.lastName}`,
        email: body.email,
        contactNumber: body.mobileNumber,
        roleCode: body.roleCode,
        roleName: body.roleCode === "MANAGER" ? "Manager" : "Operator",
      };
      staff = [...staff, created];
      return fulfill(route, 201, apiSuccess(created));
    }
    if (path === "/staff/assignable-roles") {
      return fulfill(route, 200, apiSuccess(ASSIGNABLE));
    }
    if (path === "/staff/permission-matrix" && method === "GET") {
      log.matrixGets += 1;
      return fulfill(route, 200, apiSuccess(matrix(version, operatorAdd)));
    }
    if (path === "/staff/permission-matrix" && method === "PUT") {
      const body = request.postDataJSON();
      log.saves.push(body);
      const status = saveStatuses.shift() ?? 200;
      if (status === 409) {
        // Another administrator saved in the meantime.
        version += 1;
        return fulfill(
          route,
          409,
          apiError("PERMISSIONS_STALE", "Permissions were changed by someone else. Reload and try again.")
        );
      }
      for (const change of body.changes as Array<{ roleCode: string; capabilityKey: string; granted: boolean }>) {
        if (change.roleCode === "OPERATOR" && change.capabilityKey === "STUDENTS_ADD") {
          operatorAdd = change.granted ? "GRANTED" : "NOT_GRANTED";
        }
      }
      version += 1;
      return fulfill(route, 200, apiSuccess(matrix(version, operatorAdd)));
    }
    if (path === "/staff/activities") {
      log.activityQueries.push(url.searchParams);
      const q = (url.searchParams.get("q") ?? "").toLowerCase();
      const rows = ACTIVITIES.filter((entry) => !q || entry.actionLabel.toLowerCase().includes(q));
      return fulfill(
        route,
        200,
        apiSuccess({ content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: rows.length ? 1 : 0 })
      );
    }
    const lifecycle = path.match(/^\/staff\/([^/]+)\/(deactivate|reactivate|resend-invite)$/);
    if (lifecycle && method === "POST") {
      log.lifecycle.push(`${lifecycle[2]}:${lifecycle[1]}`);
      const target = staff.find((member) => member.id === lifecycle[1]) ?? ANN;
      return fulfill(route, 200, apiSuccess({ ...target, status: lifecycle[2] === "deactivate" ? "SUSPENDED" : target.status }));
    }
    return route.fallback();
  });

  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  return log;
}

test.describe("Staff — Users", () => {
  test("renders the users list with Klass columns, status badge and pagination summary", async ({ page }) => {
    await setUp(page);
    await page.goto("/tenant-admin/staff");

    await expect(page.getByRole("heading", { level: 1, name: "Staff" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "Users" })).toHaveAttribute("aria-selected", "true");
    const table = page.getByRole("table");
    for (const header of ["First Name", "Last Name", "Email", "Contact Number", "Role", "Last Login", "Actions"]) {
      await expect(table.getByRole("columnheader", { name: header })).toBeVisible();
    }
    await expect(table.getByText("ann@example-institute.test")).toBeVisible();
    await expect(table.getByText("Operator")).toBeVisible();
    await expect(table.getByText("Invited")).toBeVisible();
    await expect(page.getByTestId("staff-range")).toHaveText("1–2 of 2");
    await expect(page.getByRole("button", { name: "Next page" })).toBeDisabled();
  });

  test("add-user dialog validates fields, shows a 409 as a form error, then invites", async ({ page }) => {
    const log = await setUp(page, { createStatuses: [409, 201] });
    await page.goto("/tenant-admin/staff");

    await page.getByRole("button", { name: "Add User" }).click();
    const dialog = page.getByRole("dialog", { name: "Add New User" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("receive an email with a link to set their own password")).toBeVisible();
    await expect(dialog.getByLabel(/Password/)).toHaveCount(0);

    await dialog.getByRole("button", { name: "Create User" }).click();
    await expect(dialog.getByText("First name is required.")).toBeVisible();
    await expect(dialog.getByText("Email is required.")).toBeVisible();
    await expect(dialog.getByText("Mobile number is required.")).toBeVisible();
    await expect(dialog.getByText("Select a role.")).toBeVisible();
    expect(log.creates).toHaveLength(0);

    await dialog.getByLabel("First Name").fill("Chamari");
    await dialog.getByLabel("Last Name").fill("Jay");
    await dialog.getByLabel("Email").fill("chamari@example-institute.test");
    await dialog.getByLabel("Mobile Number").fill("abc");
    await dialog.getByLabel("Role").selectOption("MANAGER");
    await dialog.getByRole("button", { name: "Create User" }).click();
    await expect(dialog.getByText("Enter a valid phone number.")).toBeVisible();

    await dialog.getByLabel("Mobile Number").fill("+94 71 555 0101");
    await dialog.getByRole("button", { name: "Create User" }).click();
    await expect(dialog.getByTestId("staff-form-error")).toContainText("already exists");

    await dialog.getByRole("button", { name: "Create User" }).click();
    await expect(dialog).toBeHidden();
    expect(log.creates[1]).toEqual({
      firstName: "Chamari",
      lastName: "Jay",
      email: "chamari@example-institute.test",
      mobileNumber: "+94 71 555 0101",
      roleCode: "MANAGER",
    });
    await expect(page.getByTestId("staff-notice")).toHaveText("Invitation sent to chamari@example-institute.test.");
    await expect(page.getByRole("table").getByText("chamari@example-institute.test")).toBeVisible();
  });

  test("row menus show only server-allowed actions; deactivate asks for confirmation", async ({ page }) => {
    const log = await setUp(page);
    await page.goto("/tenant-admin/staff");

    await page.getByRole("button", { name: "Actions for Ann Perera" }).first().click();
    const menu = page.getByRole("menu");
    await expect(menu.getByRole("menuitem", { name: "Edit details" })).toBeVisible();
    await expect(menu.getByRole("menuitem", { name: "Change role" })).toBeVisible();
    await expect(menu.getByRole("menuitem", { name: "Deactivate" })).toBeVisible();
    await expect(menu.getByRole("menuitem", { name: "Reactivate" })).toHaveCount(0);
    await expect(menu.getByRole("menuitem", { name: "Resend invite" })).toHaveCount(0);

    await menu.getByRole("menuitem", { name: "Deactivate" }).click();
    const confirm = page.getByRole("alertdialog");
    await expect(confirm.getByText("Deactivate Ann Perera?")).toBeVisible();
    await confirm.getByRole("button", { name: "Deactivate" }).click();
    await expect(confirm).toBeHidden();
    expect(log.lifecycle).toEqual([`deactivate:${ANN.id}`]);
    // Focus returns to that row's Actions button.
    await expect(page.getByRole("button", { name: "Actions for Ann Perera" }).first()).toBeFocused();

    await page.getByRole("button", { name: "Actions for Ben Silva" }).first().click();
    await expect(page.getByRole("menuitem", { name: "Resend invite" })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: "Change role" })).toHaveCount(0);
    await expect(page.getByRole("menuitem", { name: "Deactivate" })).toHaveCount(0);
  });

  test("an empty search shows a distinct no-match empty state", async ({ page }) => {
    const log = await setUp(page);
    await page.goto("/tenant-admin/staff");
    await expect(page.getByRole("table").getByText("Ann", { exact: true })).toBeVisible();

    await page.getByLabel("Search staff by name, email or phone").fill("zzz");
    await expect(page.getByText('No staff match "zzz"')).toBeVisible();
    expect(log.listQueries.some((params) => params.get("q") === "zzz")).toBe(true);
  });

  test("a 403 on the list renders the permission-denied state", async ({ page }) => {
    await setUp(page, { role: "COURSE_COORDINATOR", capabilities: [], listStatus: 403 });
    await page.goto("/tenant-admin/staff");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission to view this." });
    await expect(denied).toBeVisible();
    await expect(page.getByRole("button", { name: "Add User" })).toHaveCount(0);
  });
});

test.describe("Staff — Permissions", () => {
  test("toggle → unsaved count → save summary → saved", async ({ page }) => {
    const log = await setUp(page);
    await page.goto("/tenant-admin/staff?tab=permissions");

    const cell = page.getByRole("checkbox", { name: "Add Student — Operator" });
    await expect(cell).not.toBeChecked();
    await cell.check();
    await expect(page.getByTestId("unsaved-count")).toHaveText("1 unsaved change");
    // Announced politely, not only shown.
    await expect(page.locator('[role="status"][aria-live="polite"]').filter({ hasText: "1 unsaved change." })).toHaveCount(1);

    await page.getByRole("button", { name: "Save changes" }).click();
    const confirm = page.getByRole("alertdialog");
    await expect(confirm.getByTestId("save-summary")).toContainText("Operator: 1 granted, 0 revoked");
    // The summary is part of the dialog's accessible description (read on open).
    await expect(confirm).toHaveAccessibleDescription(/Operator: 1 granted, 0 revoked/);
    await confirm.getByRole("button", { name: "Save", exact: true }).click();

    await expect(page.getByTestId("unsaved-count")).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: "Permissions saved (1 change)." })).toHaveCount(1);
    expect(log.saves).toEqual([
      { expectedVersion: 7, changes: [{ roleCode: "OPERATOR", capabilityKey: "STUDENTS_ADD", granted: true }] },
    ]);
    await expect(page.getByRole("checkbox", { name: "Add Student — Operator" })).toBeChecked();
  });

  test("group select-all shows the mixed state and only affects editable cells of that role", async ({ page }) => {
    await setUp(page);
    await page.goto("/tenant-admin/staff?tab=permissions");

    const operatorAll = page.getByTestId("select-all-STUDENTS-OPERATOR");
    await expect(operatorAll).toHaveAttribute("aria-checked", "mixed");
    await expect(page.getByTestId("select-all-STUDENTS-MANAGER")).toHaveAttribute("aria-checked", "false");
    // Teacher has no editable Students cells, so no control is rendered.
    await expect(page.getByTestId("select-all-STUDENTS-TEACHER")).toHaveCount(0);

    await operatorAll.click();
    await expect(operatorAll).toHaveAttribute("aria-checked", "true");
    await expect(operatorAll).toContainText("Deselect all");
    await expect(page.getByTestId("unsaved-count")).toHaveText("1 unsaved change");
    await expect(page.getByRole("checkbox", { name: "View Students — Manager" })).not.toBeChecked();

    await operatorAll.click();
    await expect(operatorAll).toHaveAttribute("aria-checked", "false");
    await expect(page.getByTestId("unsaved-count")).toHaveText("1 unsaved change");

    await page.getByRole("button", { name: "Discard" }).click();
    await expect(page.getByTestId("unsaved-count")).toHaveCount(0);
    await expect(operatorAll).toHaveAttribute("aria-checked", "mixed");
  });

  test("locked and unavailable cells are not checkboxes and explain why", async ({ page }) => {
    await setUp(page);
    await page.goto("/tenant-admin/staff?tab=permissions");

    await expect(page.getByRole("checkbox", { name: "View Staff — Operator" })).toHaveCount(0);
    const locked = page.getByTestId("cell-STAFF_VIEW-OPERATOR");
    await expect(locked).toHaveAttribute("data-state", "locked");
    await expect(locked).toContainText("Tenant Admin only");
    // Keyboard-reachable info control, never a checkbox.
    await locked.focus();
    await expect(locked).toBeFocused();
    await locked.click();
    await expect(page.getByTestId("unsaved-count")).toHaveCount(0);

    const unavailable = page.getByTestId("cell-STUDENTS_VIEW-TEACHER");
    await expect(unavailable).toHaveAttribute("data-state", "unavailable");
    await expect(unavailable).toContainText("Not configurable for teachers");
  });

  test("a 409 PERMISSIONS_STALE keeps local edits and offers Reload", async ({ page }) => {
    const log = await setUp(page, { saveStatuses: [409] });
    await page.goto("/tenant-admin/staff?tab=permissions");

    await page.getByRole("checkbox", { name: "View Students — Manager" }).check();
    await page.getByRole("button", { name: "Save changes" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Save", exact: true }).click();

    const stale = page.getByTestId("permissions-stale");
    await expect(stale).toContainText("Another administrator changed permissions — reload to see their changes");
    await expect(page.getByTestId("unsaved-count")).toHaveText("1 unsaved change");
    await expect(page.getByRole("checkbox", { name: "View Students — Manager" })).toBeChecked();

    const getsBefore = log.matrixGets;
    await stale.getByRole("button", { name: "Reload" }).click();
    await expect(stale).toHaveCount(0);
    expect(log.matrixGets).toBeGreaterThan(getsBefore);
    await expect(page.getByTestId("unsaved-count")).toHaveText("1 unsaved change");
  });

  test("leaving through an in-app link with unsaved edits asks for confirmation", async ({ page }) => {
    await setUp(page);
    await page.goto("/tenant-admin/staff?tab=permissions");

    await page.getByRole("checkbox", { name: "Add Student — Operator" }).check();
    await page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Dashboard", exact: true }).click();
    const confirm = page.getByRole("alertdialog");
    await expect(confirm.getByText("Leave without saving?")).toBeVisible();
    await expect(confirm).toContainText("1 unsaved permission change");

    await confirm.getByRole("button", { name: "Stay on page" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/staff\?tab=permissions$/);
    await expect(page.getByTestId("unsaved-count")).toHaveText("1 unsaved change");

    await page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Dashboard", exact: true }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Leave" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/);
  });

  test("a viewer without STAFF_PERMISSIONS_EDIT sees a read-only matrix and no Add User", async ({ page }) => {
    await setUp(page, { role: "READ_ONLY_AUDITOR", capabilities: ["STAFF_VIEW", "STAFF_ACTIVITY_VIEW"] });
    await page.goto("/tenant-admin/staff?tab=permissions");

    await expect(page.getByRole("checkbox", { name: "Add Student — Operator" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save changes" })).toHaveCount(0);
    await expect(page.getByTestId("select-all-STUDENTS-OPERATOR")).toHaveCount(0);

    await page.getByRole("tab", { name: "Users" }).click();
    await expect(page.getByRole("table").getByText("Ann", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Add User" })).toHaveCount(0);
    // No sign-in mobile (or any other) row control a view-only viewer couldn't use.
    await expect(page.getByRole("button", { name: "Actions for Ann Perera" })).toHaveCount(0);
  });
});

test.describe("Staff — Activities", () => {
  test("search sends q to the server and results are grouped by date with expandable details", async ({ page }) => {
    const log = await setUp(page);
    await page.goto("/tenant-admin/staff?tab=activities");

    await expect(page.getByTestId("activity-item")).toHaveCount(2);
    await expect(page.getByRole("heading", { level: 3 })).toHaveCount(2);

    await page.getByLabel("Search activities").fill("invited");
    await expect(page.getByTestId("activity-item")).toHaveCount(1);
    expect(log.activityQueries.some((params) => params.get("q") === "invited")).toBe(true);

    const item = page.getByTestId("activity-item").first();
    await expect(item).toContainText("Staff invited");
    await expect(item).toContainText("Tina Admin · Tenant Admin");
    const toggle = item.getByRole("button", { name: "Details" });
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(item.getByText("MANAGER")).toBeVisible();

    await page.getByLabel("Search activities").fill("nothing-here");
    await expect(page.getByText("No activities match these filters")).toBeVisible();
  });
});

test.describe("Staff — mobile (390×844)", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("each tab renders; the matrix shows one role column at a time", async ({ page }) => {
    await setUp(page);
    await page.goto("/tenant-admin/staff");

    // Below md the DataTable renders cards, not the table.
    await expect(page.getByLabel("Staff users").getByText("ann@example-institute.test")).toBeVisible();
    await expect(page.getByRole("table")).toBeHidden();
    await expect(page.getByRole("button", { name: "Add User" })).toBeVisible();

    await page.getByRole("tab", { name: "Permissions" }).click();
    await expect(page).toHaveURL(/tab=permissions/);
    const roleSelect = page.getByLabel("Role column");
    await expect(roleSelect).toBeVisible();
    await expect(page.getByRole("checkbox", { name: "Add Student — Operator" })).toBeVisible();
    await expect(page.getByRole("checkbox", { name: "Add Student — Manager" })).toBeHidden();
    await roleSelect.selectOption("MANAGER");
    await expect(page.getByRole("checkbox", { name: "Add Student — Manager" })).toBeVisible();
    await expect(page.getByRole("checkbox", { name: "Add Student — Operator" })).toBeHidden();

    await page.getByRole("tab", { name: "Activities" }).click();
    await expect(page.getByTestId("activity-item")).toHaveCount(2);
  });
});

test.describe("Capability-driven portal for OPERATOR", () => {
  test("nav shows only granted areas and the dashboard renders a welcome", async ({ page }) => {
    await setUp(page, { role: "OPERATOR", capabilities: ["STUDENTS_VIEW", "PAYMENTS_VIEW"] });
    await page.goto("/tenant-admin/dashboard");

    await expect(page.getByRole("heading", { name: "Welcome" })).toBeVisible();
    const nav = page.getByRole("navigation", { name: "Primary" });
    await expect(nav.getByRole("link", { name: "Students", exact: true })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Payments", exact: true })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Courses", exact: true })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Staff", exact: true })).toHaveCount(0);
  });
});
