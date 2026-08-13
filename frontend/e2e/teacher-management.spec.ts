import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  fakeJwt,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * MVP-007 Teacher Management (TCH-1) — Tenant Admin Teacher List / Detail /
 * Add Teacher screens (`(tenant-admin)/tenant-admin/teachers/**`).
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every test mocks `/v1/teachers*` responses shaped exactly
 * like the `ApiResponse<T>` envelope.
 *
 * The whole `(tenant-admin)` route group is wrapped in `RouteGuard`
 * (`components/auth/route-guard.tsx`, `kind="tenant"`), which calls
 * `ensureAccessToken("tenant")` on mount. Since these tests navigate directly
 * to a `/tenant-admin/teachers*` URL rather than logging in through the UI
 * first, every test mocks a successful `POST /v1/auth/refresh` so the guard
 * resolves `ready` and actually renders the page (same pattern as
 * `logout.spec.ts` / `token-refresh.spec.ts`).
 */

interface MockTeacher {
  id: string;
  name: string;
  email: string;
  approvalStatus: "PENDING" | "APPROVED" | "REJECTED";
  accountStatus: "ACTIVE" | "SUSPENDED";
  approvedBy: string | null;
  approvedAt: string | null;
}

function makeTeacher(overrides: Partial<MockTeacher> & { id: string; name: string }): MockTeacher {
  return {
    email: `${overrides.id}@example.test`,
    approvalStatus: "PENDING",
    accountStatus: "ACTIVE",
    approvedBy: null,
    approvedAt: null,
    ...overrides,
  };
}

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

async function mockTeacherList(page: Page, teachers: MockTeacher[]): Promise<void> {
  await mockJson(page, "**/v1/teachers", 200, apiSuccess(teachers));
}

async function mockTeacherDetail(page: Page, teacher: MockTeacher): Promise<void> {
  await mockJson(page, `**/v1/teachers/${teacher.id}`, 200, apiSuccess(teacher));
}

test.describe("teacher list — zero-data empty state", () => {
  test("shows 'No teachers yet' with an Add teacher action when the list is truly empty", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockTeacherList(page, []);

    await page.goto("/tenant-admin/teachers");

    await expect(page.getByText("No teachers yet")).toBeVisible();
    await expect(
      page.getByText("You haven't added any teacher accounts for your institute yet.", {
        exact: false,
      })
    ).toBeVisible();
    // The empty-state action is a real <button>, distinct from the page
    // header's "Add teacher" <Link> (role "link") — assert the button
    // specifically exists.
    await expect(page.getByRole("button", { name: "Add teacher" })).toBeVisible();
  });
});

test.describe("teacher list — filtered-to-zero empty state", () => {
  test("filtering to a status with zero matches shows a distinct 'no match' empty state with a working Clear filters action", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const teachers = [
      makeTeacher({ id: "teacher-1", name: "Kavindi Fernando", approvalStatus: "APPROVED" }),
      makeTeacher({ id: "teacher-2", name: "Ruwan Jayasuriya", approvalStatus: "APPROVED" }),
    ];
    await mockTeacherList(page, teachers);

    await page.goto("/tenant-admin/teachers");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(table.getByText("Kavindi Fernando")).toBeVisible();

    // REJECTED (not PENDING) — PENDING+no-search has its own distinct
    // "pending-queue" empty state, covered separately below.
    await page.getByLabel("Status").selectOption("REJECTED");

    await expect(page.getByText("No teachers match your filter")).toBeVisible();
    await expect(
      page.getByText("No teachers match your current search and status filter", { exact: false })
    ).toBeVisible();
    // Distinct copy from the true zero-data empty state.
    await expect(page.getByText("No teachers yet")).toHaveCount(0);

    await page.getByRole("button", { name: "Clear filters" }).click();

    await expect(table).toBeVisible();
    await expect(table.getByText("Kavindi Fernando")).toBeVisible();
    await expect(table.getByText("Ruwan Jayasuriya")).toBeVisible();
    await expect(page.getByLabel("Status")).toHaveValue("all");
  });
});

test.describe("teacher list — pending-queue empty state", () => {
  test("filtering to PENDING with zero matches shows 'no teachers waiting for approval', distinct from the generic filtered-empty state", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const teachers = [
      makeTeacher({ id: "teacher-1", name: "Kavindi Fernando", approvalStatus: "APPROVED" }),
      makeTeacher({ id: "teacher-2", name: "Ruwan Jayasuriya", approvalStatus: "REJECTED" }),
    ];
    await mockTeacherList(page, teachers);

    await page.goto("/tenant-admin/teachers");
    await page.getByLabel("Status").selectOption("PENDING");

    await expect(page.getByText("No teachers waiting for approval")).toBeVisible();
    await expect(
      page.getByText("There are no pending teacher accounts right now.", { exact: false })
    ).toBeVisible();
    // Distinct from both the zero-data and the generic filtered-empty copy.
    await expect(page.getByText("No teachers yet")).toHaveCount(0);
    await expect(page.getByText("No teachers match your filter")).toHaveCount(0);

    await page.getByRole("button", { name: "View all teachers" }).click();
    await expect(page.getByRole("table").getByText("Kavindi Fernando")).toBeVisible();
    await expect(page.getByLabel("Status")).toHaveValue("all");
  });

  test("adding a search term while PENDING is selected falls back to the generic filtered-empty state, not the pending-queue copy", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const teachers = [
      makeTeacher({ id: "teacher-1", name: "Kavindi Fernando", approvalStatus: "PENDING" }),
    ];
    await mockTeacherList(page, teachers);

    await page.goto("/tenant-admin/teachers");
    await page.getByLabel("Status").selectOption("PENDING");
    await page.getByLabel("Search").fill("no-such-person");

    await expect(page.getByText("No teachers match your filter")).toBeVisible();
    await expect(page.getByText("No teachers waiting for approval")).toHaveCount(0);
  });
});

test.describe("add teacher — creates a PENDING teacher and redirects to its detail page", () => {
  test("submitting the form redirects to the new teacher's detail page with the pending-approval notice and badge", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");

    const created = makeTeacher({
      id: "teacher-new-1",
      name: "Dinithi Wickramasinghe",
      email: "dinithi.w@example.test",
      approvalStatus: "PENDING",
    });

    await mockJson(page, "**/v1/teachers", 200, apiSuccess(created));
    await mockTeacherDetail(page, created);

    await page.goto("/tenant-admin/teachers/new");

    await page.getByLabel("Full name").fill(created.name);
    await page.getByLabel("Email").fill(created.email);
    await page.getByLabel("Temporary password").fill("correct-horse-battery-staple");

    await page.getByRole("button", { name: "Add teacher" }).click();

    await expect(page).toHaveURL(
      new RegExp(`/tenant-admin/teachers/${created.id}\\?created=true$`)
    );
    await expect(
      page.getByText("Teacher account created — pending your approval.")
    ).toBeVisible();
    // The detail page shows the PENDING badge twice (card header + the
    // approval-status description row) — assert at least one is visible.
    await expect(page.getByText("Pending approval").first()).toBeVisible();
  });
});

test.describe("add teacher — duplicate email surfaces as a field-level error", () => {
  test("a 409 CONFLICT response from POST /v1/teachers is mapped to the email field, not a page-level banner", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/teachers",
      409,
      apiError("CONFLICT", "A teacher account with this email already exists")
    );

    await page.goto("/tenant-admin/teachers/new");

    await page.getByLabel("Full name").fill("Tharindu Bandara");
    await page.getByLabel("Email").fill("tharindu.b@example.test");
    await page.getByLabel("Temporary password").fill("correct-horse-battery-staple");

    await page.getByRole("button", { name: "Add teacher" }).click();

    const emailField = page.getByLabel("Email");
    await expect(
      page.getByText("A teacher account with this email already exists")
    ).toBeVisible();
    await expect(emailField).toHaveAttribute("aria-invalid", "true");
    // Mapped to the field, not the page-level `ErrorState` banner (which
    // this form only renders for unmapped/non-field errors) — that banner
    // has its own "Try again" retry affordance the field-level error does
    // not, so its absence is the distinguishing signal (can't just count
    // role="alert" elements: Next.js's built-in route announcer is itself
    // an always-present, contentless `role="alert"` region).
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
    // The form is still usable — no navigation away, no permission-denied
    // state (a 409 is a conflict, not an authorization failure).
    await expect(page).toHaveURL(/\/tenant-admin\/teachers\/new$/);
    await expect(page.getByRole("button", { name: "Add teacher" })).toBeVisible();
  });
});

test.describe("approve/reject aria-labels — structural, not visual", () => {
  test("each PENDING row's Approve/Reject controls have a distinct, instance-specific aria-label", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const teacherA = makeTeacher({ id: "teacher-a", name: "Asha Perera" });
    const teacherB = makeTeacher({ id: "teacher-b", name: "Nadun Silva" });
    await mockTeacherList(page, [teacherA, teacherB]);

    await page.goto("/tenant-admin/teachers");

    const approveA = page.getByRole("button", { name: `Approve teacher ${teacherA.name}` });
    const approveB = page.getByRole("button", { name: `Approve teacher ${teacherB.name}` });
    const rejectA = page.getByRole("button", { name: `Reject teacher ${teacherA.name}` });
    const rejectB = page.getByRole("button", { name: `Reject teacher ${teacherB.name}` });

    // Each accessible name resolves to exactly one control...
    await expect(approveA).toHaveCount(1);
    await expect(approveB).toHaveCount(1);
    await expect(rejectA).toHaveCount(1);
    await expect(rejectB).toHaveCount(1);

    // ...and they are genuinely different elements wired to different rows:
    // opening teacherA's Approve dialog names teacherA specifically, not
    // teacherB, and vice versa.
    await approveA.click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByRole("heading", { name: `Approve ${teacherA.name}?` })).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).not.toBeVisible();

    await approveB.click();
    await expect(dialog.getByRole("heading", { name: `Approve ${teacherB.name}?` })).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();
  });
});

test.describe("approve mutation — success closes the dialog and updates the row", () => {
  test("confirming Approve updates the row's badge and hides the now-stale row actions, without a page reload", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const pending = makeTeacher({ id: "teacher-approve-ok", name: "Sanduni Ekanayake" });

    // Stateful list handler: returns PENDING until the approve call lands,
    // then APPROVED — this is what actually proves the row updates via the
    // `["teachers"]` query invalidation on mutation success (`teachers.ts`),
    // not just that the mutation request itself succeeded.
    let approved = false;
    await page.route("**/v1/teachers", async (route) => {
      const current = approved
        ? { ...pending, approvalStatus: "APPROVED" as const, accountStatus: "ACTIVE" as const }
        : pending;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess([current])),
      });
    });

    let approveCallCount = 0;
    await page.route(`**/v1/teachers/${pending.id}/approve`, async (route) => {
      approveCallCount += 1;
      approved = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            ...pending,
            approvalStatus: "APPROVED",
            accountStatus: "ACTIVE",
            approvedBy: "admin-1",
            approvedAt: new Date().toISOString(),
          })
        ),
      });
    });

    await page.goto("/tenant-admin/teachers");
    const table = page.getByRole("table");
    await expect(table.getByText(pending.name)).toBeVisible();
    await expect(table.getByText("Pending approval")).toBeVisible();

    await page.getByRole("button", { name: `Approve teacher ${pending.name}` }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Approve", exact: true }).click();

    // Dialog closes itself on success (no manual Cancel needed) and the
    // approve call fired exactly once.
    await expect(dialog).not.toBeVisible();
    expect(approveCallCount).toBe(1);

    // The row reflects the new status without a page reload, and — since
    // row actions are PENDING-only — the now-decided row no longer offers
    // Approve/Reject.
    await expect(table.getByText("Approved", { exact: true })).toBeVisible();
    await expect(table.getByText("Pending approval")).toHaveCount(0);
    await expect(
      page.getByRole("button", { name: `Approve teacher ${pending.name}` })
    ).toHaveCount(0);
  });
});

test.describe("Course Coordinator session — Approve/Reject controls are hidden", () => {
  test("no Approve/Reject control renders anywhere on the list or detail page", async ({
    page,
  }) => {
    await mockTenantSession(page, "COURSE_COORDINATOR");
    const pending = makeTeacher({ id: "teacher-cc-1", name: "Imesha Rathnayake" });
    await mockTeacherList(page, [pending]);
    await mockTeacherDetail(page, pending);

    await page.goto("/tenant-admin/teachers");
    await expect(page.getByRole("table").getByText(pending.name)).toBeVisible();
    await expect(page.getByRole("button", { name: /^Approve teacher/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Reject teacher/ })).toHaveCount(0);

    await page.goto(`/tenant-admin/teachers/${pending.id}`);
    await expect(page.getByText(pending.name).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject", exact: true })).toHaveCount(0);
  });
});

test.describe("approve mutation — a real 403 is surfaced, not silently swallowed", () => {
  test("a 403 FORBIDDEN response from POST /v1/teachers/{id}/approve renders an inline error and keeps the dialog open", async ({
    page,
  }) => {
    // This test drives the mutation from a TENANT_ADMIN session (the only
    // session for which the Approve control renders at all, per the UI-hiding
    // test above) and mocks the network call itself to return 403. This
    // proves the *client-side* handling of a real 403 response (error
    // surfaced, no silent "looks like it worked" state) — it is NOT a
    // substitute for backend enforcement of the literal Tenant Admin role
    // requirement, which this mocked-network environment cannot exercise
    // (see the same caveat in `fixtures/auth-mocks.ts` and
    // `platform-admin-tenants.spec.ts`).
    await mockTenantSession(page, "TENANT_ADMIN");
    const pending = makeTeacher({ id: "teacher-403", name: "Chamod Gunasekara" });
    await mockTeacherList(page, [pending]);

    await page.goto("/tenant-admin/teachers");

    await page.getByRole("button", { name: `Approve teacher ${pending.name}` }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    await mockJson(
      page,
      `**/v1/teachers/${pending.id}/approve`,
      403,
      apiError("FORBIDDEN", "You do not have permission to approve teachers.")
    );

    await dialog.getByRole("button", { name: "Approve", exact: true }).click();

    await expect(
      dialog.getByText("You do not have permission to approve teachers.")
    ).toBeVisible();
    // Not silently closed/treated as success — the dialog (and its confirm
    // control) is still present so the user can see the failure.
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Approve", exact: true })).toBeVisible();
  });
});

test.describe("teacher detail — 404 handling", () => {
  test("a 404 renders a dedicated 'Teacher not found' message, not a generic retry", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/teachers/does-not-exist",
      404,
      apiError("NOT_FOUND", "Teacher account not found")
    );

    await page.goto("/tenant-admin/teachers/does-not-exist");

    await expect(page.getByText("Teacher not found")).toBeVisible();
    await expect(
      page.getByText("This teacher account doesn't exist, or you don't have access to it.")
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Back to teachers" })).toBeVisible();
    // No generic error-state retry affordance for this dedicated 404 state.
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);

    await page.getByRole("button", { name: "Back to teachers" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/teachers$/);
  });
});
