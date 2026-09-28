import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * STU-PROF-01 — tenant-configurable student profile fields:
 *   - Tenant Admin settings table (`/tenant-admin/settings/student-fields`),
 *   - the student's own "Additional details" on `/student/profile` and the
 *     "Complete your profile" banner,
 *   - the staff view/edit on the student detail Profile tab.
 * No backend runs here — every call is intercepted via `page.route()`.
 * Authorization is the backend's; these tests prove the UI renders the
 * backend's 403/validation responses correctly.
 */

test.beforeEach(() => {
  test.slow();
});

const SETTINGS_ENDPOINT = "**/v1/tenant-config/student-profile-fields";
const OWN_FIELDS_ENDPOINT = "**/v1/students/me/profile";
const STUDENT_ID = "44444444-4444-4444-4444-444444444444";

async function mockSession(page: Page, role: string) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
}

function setting(
  key: string,
  label: string,
  group: string,
  flags: { enabled: boolean; required: boolean; collectAtRegistration: boolean },
  extra: Partial<{ locked: boolean; sensitive: boolean; type: string }> = {}
) {
  return {
    key,
    label,
    group,
    type: extra.type ?? "TEXT",
    maxLength: 255,
    sensitive: extra.sensitive ?? false,
    locked: extra.locked ?? false,
    ...flags,
  };
}

const ALWAYS = { enabled: true, required: true, collectAtRegistration: true };
const ON = { enabled: true, required: false, collectAtRegistration: true };
const PROFILE_ONLY = { enabled: true, required: false, collectAtRegistration: false };
const OFF = { enabled: false, required: false, collectAtRegistration: false };

const SETTINGS = {
  fields: [
    setting("EMAIL", "Email", "ACCOUNT", ALWAYS, { locked: true }),
    setting("FULL_NAME", "Full name", "ACCOUNT", ALWAYS, { locked: true }),
    setting("ADDRESS_LINE_1", "Address line 1", "ADDRESS", PROFILE_ONLY),
    setting("CITY", "City", "ADDRESS", PROFILE_ONLY),
    setting("NIC_NUMBER", "NIC number", "IDENTITY", OFF, { sensitive: true }),
    setting("SCHOOL", "School", "EDUCATION", ON),
  ],
};

function entry(key: string, label: string, group: string, type: string, required: boolean, value: unknown) {
  return { key, label, group, type, maxLength: 255, sensitive: key === "NIC_NUMBER", required, value };
}

test.describe("student fields settings (Tenant Admin)", () => {
  test("toggling Enabled clears the dependent flags and Save sends only the changed rows", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    let putBody: { fields: Array<Record<string, unknown>> } | null = null;
    await page.route(SETTINGS_ENDPOINT, async (route) => {
      if (route.request().method() === "PUT") {
        putBody = route.request().postDataJSON();
        const updated = {
          fields: SETTINGS.fields.map((field) => {
            if (field.key === "SCHOOL") return { ...field, ...OFF };
            if (field.key === "NIC_NUMBER") return { ...field, enabled: true, required: true };
            return field;
          }),
        };
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(updated)) });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(SETTINGS)) });
    });

    await page.goto("/tenant-admin/settings/student-fields");
    await expect(page.getByRole("heading", { name: "Student fields" })).toBeVisible();
    await expect(page.getByRole("checkbox", { name: "Email: Enabled" })).toBeDisabled();
    await expect(page.getByRole("checkbox", { name: "Email: Enabled" })).toBeChecked();
    await expect(page.getByRole("checkbox", { name: "NIC number: Required" })).toBeDisabled();

    await page.getByRole("checkbox", { name: "School: Enabled" }).uncheck();
    await expect(page.getByRole("checkbox", { name: "School: Ask at registration" })).not.toBeChecked();
    await expect(page.getByRole("checkbox", { name: "School: Ask at registration" })).toBeDisabled();

    await page.getByRole("checkbox", { name: "NIC number: Enabled" }).check();
    await page.getByRole("checkbox", { name: "NIC number: Required" }).check();

    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByText("Saved.", { exact: true })).toBeVisible();
    expect(putBody).not.toBeNull();
    expect(putBody!.fields).toHaveLength(2);
    expect(putBody!.fields).toEqual(
      expect.arrayContaining([
        { key: "SCHOOL", enabled: false, required: false, collectAtRegistration: false },
        { key: "NIC_NUMBER", enabled: true, required: true, collectAtRegistration: false },
      ])
    );
  });

  test("a Read-only Auditor sees the settings with every checkbox disabled and no Save", async ({ page }) => {
    await mockSession(page, "READ_ONLY_AUDITOR");
    await mockJson(page, SETTINGS_ENDPOINT, 200, apiSuccess(SETTINGS));

    await page.goto("/tenant-admin/settings/student-fields");
    await expect(page.getByRole("checkbox", { name: "School: Enabled" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save changes" })).toHaveCount(0);
    await expect(page.getByText("You have view-only access to institute configuration.")).toBeVisible();
  });

  test("a role the backend denies gets the permission-denied state", async ({ page }) => {
    await mockSession(page, "FINANCE_STAFF");
    await mockJson(page, SETTINGS_ENDPOINT, 403, apiError("FORBIDDEN", "Access denied"));

    await page.goto("/tenant-admin/settings/student-fields");
    await expect(page.getByText(/permission/i).first()).toBeVisible();
    await expect(page.getByRole("checkbox")).toHaveCount(0);
  });
});

test.describe("student additional details (self-service)", () => {
  const PROFILE = {
    id: STUDENT_ID,
    name: "Jamie Rivera",
    email: "jamie.rivera@example-institute.test",
    roleCode: "STUDENT",
    status: "ACTIVE",
  };

  async function mockProfilePage(page: Page) {
    await mockSession(page, "STUDENT");
    await mockJson(page, "**/v1/students/me", 200, apiSuccess(PROFILE));
    await mockJson(page, "**/v1/students/me/billing-address", 200, apiSuccess({ addressLine: null, city: null }));
  }

  test("shows the institute's fields, flags missing required ones, and saves them", async ({ page }) => {
    await mockProfilePage(page);
    const initial = {
      fields: [
        entry("BIRTHDAY", "Birthday", "PERSONAL", "DATE", true, null),
        entry("NIC_NUMBER", "NIC number", "IDENTITY", "TEXT", false, null),
      ],
      missingRequiredFields: ["BIRTHDAY"],
    };
    let putBody: { values: Record<string, unknown> } | null = null;
    await page.route(OWN_FIELDS_ENDPOINT, async (route) => {
      if (route.request().method() === "PUT") {
        putBody = route.request().postDataJSON();
        const saved = {
          fields: [
            entry("BIRTHDAY", "Birthday", "PERSONAL", "DATE", true, "2008-04-01"),
            entry("NIC_NUMBER", "NIC number", "IDENTITY", "TEXT", false, "200812345678"),
          ],
          missingRequiredFields: [],
        };
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(saved)) });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(initial)) });
    });

    await page.goto("/student/profile");
    await expect(page.getByRole("heading", { name: "Additional details" })).toBeVisible();
    await expect(page.getByText("Complete your profile.")).toBeVisible();
    await expect(page.locator('label[for="my-details-NIC_NUMBER"]')).toHaveText("NIC number (optional)");

    await page.getByLabel("NIC number").fill("200812345678");
    await page.getByRole("button", { name: "Save details" }).click();
    await expect(page.getByText("Birthday is required.")).toBeVisible();
    expect(putBody).toBeNull();

    await page.getByLabel("Birthday").fill("2008-04-01");
    await page.getByRole("button", { name: "Save details" }).click();
    await expect.poll(() => putBody).not.toBeNull();
    expect(putBody).toEqual({ values: { BIRTHDAY: "2008-04-01", NIC_NUMBER: "200812345678" } });
    await expect(page.getByText("Complete your profile.")).toHaveCount(0);
  });

  test("a backend field error is shown on the matching input", async ({ page }) => {
    await mockProfilePage(page);
    await page.route(OWN_FIELDS_ENDPOINT, async (route) => {
      if (route.request().method() === "PUT") {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify(
            apiError("VALIDATION_ERROR", "Some profile details are invalid", [
              { field: "values.NIC_NUMBER", message: "NIC number must be at most 20 characters" },
            ])
          ),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            fields: [entry("NIC_NUMBER", "NIC number", "IDENTITY", "TEXT", false, null)],
            missingRequiredFields: [],
          })
        ),
      });
    });

    await page.goto("/student/profile");
    await page.getByLabel("NIC number").fill("1234");
    await page.getByRole("button", { name: "Save details" }).click();
    await expect(page.getByText("NIC number must be at most 20 characters")).toBeVisible();
    await expect(page.getByLabel("NIC number")).toHaveAttribute("aria-invalid", "true");
  });

  test("the billing card is replaced by the address fields when the institute collects them", async ({ page }) => {
    await mockProfilePage(page);
    await mockJson(
      page,
      OWN_FIELDS_ENDPOINT,
      200,
      apiSuccess({
        fields: [
          entry("ADDRESS_LINE_1", "Address line 1", "ADDRESS", "TEXT", false, null),
          entry("CITY", "City", "ADDRESS", "TEXT", false, null),
        ],
        missingRequiredFields: [],
      })
    );

    await page.goto("/student/profile");
    await expect(page.getByLabel("Address line 1")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Billing address" })).toHaveCount(0);
  });

  test("an institute that collects nothing extra shows an explanatory empty state and keeps the billing card", async ({
    page,
  }) => {
    await mockProfilePage(page);
    await mockJson(page, OWN_FIELDS_ENDPOINT, 200, apiSuccess({ fields: [], missingRequiredFields: [] }));

    await page.goto("/student/profile");
    await expect(page.getByText("Nothing else to fill in")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Billing address" })).toBeVisible();
  });
});

test.describe("staff view of a student's additional details", () => {
  const STUDENT = {
    id: STUDENT_ID,
    name: "Jamie Rivera",
    email: "jamie.rivera@example-institute.test",
    roleCode: "STUDENT",
    status: "ACTIVE",
  };
  const STAFF_FIELDS_ENDPOINT = `**/v1/students/${STUDENT_ID}/profile-fields`;
  const FIELDS = {
    fields: [
      entry("SCHOOL", "School", "EDUCATION", "TEXT", true, "Royal College"),
      entry("HAS_SIBLINGS", "Has siblings", "FAMILY", "BOOLEAN", false, true),
    ],
    missingRequiredFields: [],
  };

  async function gotoStudent(page: Page, role: string) {
    await mockSession(page, role);
    await mockJson(page, `**/v1/students/${STUDENT_ID}`, 200, apiSuccess(STUDENT));
    await page.goto(`/tenant-admin/students/${STUDENT_ID}`);
    await expect(page.getByRole("heading", { name: STUDENT.name })).toBeVisible();
  }

  test("a Tenant Admin can correct a student's details", async ({ page }) => {
    let putBody: { values: Record<string, unknown> } | null = null;
    await page.route(STAFF_FIELDS_ENDPOINT, async (route) => {
      if (route.request().method() === "PUT") {
        putBody = route.request().postDataJSON();
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(FIELDS)) });
    });
    await gotoStudent(page, "TENANT_ADMIN");

    // Staff edits aren't held to required-ness, so nothing is marked optional.
    await expect(page.locator(`label[for="student-${STUDENT_ID}-details-SCHOOL"]`)).toHaveText("School");
    await page.getByLabel("School").fill("Ananda College");
    await page.getByLabel("Has siblings").selectOption("false");
    await page.getByRole("button", { name: "Save details" }).click();
    await expect.poll(() => putBody).not.toBeNull();
    expect(putBody).toEqual({ values: { SCHOOL: "Ananda College", HAS_SIBLINGS: false } });
  });

  test("view-only staff see the values read-only", async ({ page }) => {
    await mockJson(page, STAFF_FIELDS_ENDPOINT, 200, apiSuccess(FIELDS));
    await gotoStudent(page, "COURSE_COORDINATOR");

    await expect(page.getByText("Royal College")).toBeVisible();
    await expect(page.getByText("Yes", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Save details" })).toHaveCount(0);
  });

  test("a backend 404 for the student's fields renders an error, not data", async ({ page }) => {
    await mockJson(page, STAFF_FIELDS_ENDPOINT, 404, apiError("NOT_FOUND", "Student account not found"));
    await gotoStudent(page, "TENANT_ADMIN");

    await expect(page.getByText("Student account not found").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Save details" })).toHaveCount(0);
  });
});
