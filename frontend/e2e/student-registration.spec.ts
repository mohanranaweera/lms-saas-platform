import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  DEFAULT_STUDENT_REGISTRATION_POLICY,
  mockJson,
  mockStudentRegistrationPolicy,
} from "./fixtures/auth-mocks";

/**
 * Student self-registration (Wave 3, PAR-03-01) — `/register`
 * (`app/(auth)/register/page.tsx`). Public/unauthenticated route: no login
 * mock needed.
 *
 * The page now pre-fetches `GET
 * /v1/public/tenant-config/student-registration-policy` on mount and
 * renders conditionally from it (see that page's own doc comment), so
 * every test below seeds that endpoint first via
 * `mockStudentRegistrationPolicy()` (`fixtures/auth-mocks.ts`) — mirroring
 * `tenant-config.spec.ts`'s "mock the config read, then assert on the
 * rendered UI" pattern for the (authenticated) Tenant Admin config screens.
 * That helper's default is a fully permissive baseline (registration open,
 * no approval/OTP/extra-field requirements); individual tests override only
 * the flags relevant to that scenario. The two "policy pre-fetch" tests
 * below drive the raw `page.route` directly instead (delay/failure
 * timing `mockJson`/`mockStudentRegistrationPolicy` don't support).
 */

const POLICY_ENDPOINT = "**/v1/public/tenant-config/student-registration-policy";
const REGISTER_ENDPOINT = "**/v1/students/register";
const OTP_SEND_ENDPOINT = "**/v1/students/register/otp/send";
const OTP_VERIFY_ENDPOINT = "**/v1/students/register/otp/verify";

async function fillBaseFields(page: Page, overrides: Partial<Record<string, string>> = {}) {
  await page.getByLabel("Full name").fill(overrides.name ?? "New Student");
  await page.getByLabel("Email").fill(overrides.email ?? "new-student@example-institute.test");
  await page.getByLabel("Password", { exact: true }).fill(overrides.password ?? "correct-horse-battery-staple");
  await page.getByLabel("Confirm password").fill(overrides.confirmPassword ?? "correct-horse-battery-staple");
}

test.describe("student registration — policy pre-fetch", () => {
  test("shows a loading state while the registration policy is being fetched, then the form", async ({
    page,
  }) => {
    await page.route(POLICY_ENDPOINT, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(DEFAULT_STUDENT_REGISTRATION_POLICY)),
      });
    });

    await page.goto("/register");
    await expect(page.getByText("Loading registration options…")).toBeVisible();
    await expect(page.getByLabel("Full name")).toBeVisible();
  });

  test("shows an error state with retry when the registration policy fails to load", async ({ page }) => {
    let attempts = 0;
    await page.route(POLICY_ENDPOINT, async (route) => {
      attempts += 1;
      // The app's QueryClient retries a failed query once automatically
      // (see `components/providers/query-provider.tsx`) before settling
      // into an error state — keep failing through that automatic retry,
      // then succeed once the user clicks "Try again".
      if (attempts <= 2) {
        await route.fulfill({
          status: 500,
          contentType: "application/json",
          body: JSON.stringify(apiError("INTERNAL_ERROR", "Something went wrong.")),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(DEFAULT_STUDENT_REGISTRATION_POLICY)),
      });
    });

    await page.goto("/register");
    await expect(page.getByText("Something went wrong.")).toBeVisible();
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByLabel("Full name")).toBeVisible();
  });
});

test.describe("student registration — accessible labels", () => {
  test("every base field has an associated visible label", async ({ page }) => {
    await mockStudentRegistrationPolicy(page);
    await page.goto("/register");
    await expect(page.getByLabel("Full name")).toBeVisible();
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
    await expect(page.getByLabel("Confirm password")).toBeVisible();
    await expect(page.getByLabel("Guardian name")).toBeVisible();
    await expect(page.getByLabel("School")).toBeVisible();
  });

  test("client-side validation rejects an empty submit with no request made", async ({ page }) => {
    await mockStudentRegistrationPolicy(page);
    let requestMade = false;
    await page.route(REGISTER_ENDPOINT, async (route) => {
      requestMade = true;
      await route.fulfill({ status: 500, body: "should not be called" });
    });
    await page.goto("/register");
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Name is required.")).toBeVisible();
    await expect(page.getByText("Email is required.")).toBeVisible();
    expect(requestMade).toBe(false);
  });

  test("mismatched passwords are rejected client-side", async ({ page }) => {
    await mockStudentRegistrationPolicy(page);
    await page.goto("/register");
    await fillBaseFields(page, { confirmPassword: "a-different-password" });
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByText("Passwords do not match.")).toBeVisible();
  });
});

test.describe("student registration — unsaved-changes guard on in-app navigation", () => {
  test("clicking 'Sign in' with an untouched form navigates away with no confirmation prompt", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page);
    await page.goto("/register");

    let dialogShown = false;
    page.once("dialog", (dialog) => {
      dialogShown = true;
      void dialog.dismiss();
    });
    await page.getByRole("link", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/login$/);
    expect(dialogShown).toBe(false);
  });

  test("clicking 'Sign in' after filling in the form warns before navigating away, and staying is respected", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page);
    await page.goto("/register");
    await fillBaseFields(page);

    // Decline first — the visitor must stay on `/register` with the form intact.
    page.once("dialog", (dialog) => dialog.dismiss());
    await page.getByRole("link", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/register$/);
    await expect(page.getByLabel("Full name")).toHaveValue("New Student");

    // Confirming proceeds with the navigation.
    page.once("dialog", (dialog) => dialog.accept());
    await page.getByRole("link", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("student registration — public_registration_enabled=false", () => {
  test("the 'registration is not open' state renders immediately from the policy, with no form and no submit attempt", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { publicRegistrationEnabled: false });
    let requestMade = false;
    await page.route(REGISTER_ENDPOINT, async (route) => {
      requestMade = true;
      await route.fulfill({ status: 500, body: "should not be called" });
    });

    await page.goto("/register");

    await expect(page.getByText("Self-registration isn't available")).toBeVisible();
    await expect(
      page.getByText("This institute isn't accepting new student sign-ups right now.", {
        exact: false,
      })
    ).toBeVisible();
    await expect(page.getByLabel("Full name")).not.toBeVisible();
    expect(requestMade).toBe(false);

    await page.getByRole("button", { name: "Back to sign in" }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("student registration — approval_required", () => {
  test("approval_required=true shows the pending-approval hint on the form and the pending-approval banner after submit", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { approvalRequired: true });
    await mockJson(
      page,
      REGISTER_ENDPOINT,
      201,
      apiSuccess({
        studentProfileId: "11111111-1111-1111-1111-111111111111",
        email: "new-student@example-institute.test",
        pendingApproval: true,
      })
    );
    await page.goto("/register");
    await expect(
      page.getByText("Accounts created here require staff approval before you can sign in.")
    ).toBeVisible();

    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Application submitted")).toBeVisible();
    await expect(page.getByText("pending approval", { exact: false })).toBeVisible();
    await expect(
      page.getByText("You'll be able to sign in once a staff member", { exact: false })
    ).toBeVisible();
  });

  test("approval_required=false shows no approval hint and the ready-to-login state after submit", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { approvalRequired: false });
    await mockJson(
      page,
      REGISTER_ENDPOINT,
      201,
      apiSuccess({
        studentProfileId: "22222222-2222-2222-2222-222222222222",
        email: "new-student@example-institute.test",
        pendingApproval: false,
      })
    );
    await page.goto("/register");
    await expect(
      page.getByText("Accounts created here require staff approval before you can sign in.")
    ).toHaveCount(0);

    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Account created")).toBeVisible();
    await expect(page.getByText("You can now sign in.")).toBeVisible();
    await page.getByRole("link", { name: "Back to sign in" }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe("student registration — otp_required=true", () => {
  test("submitting the form reveals the OTP step before registering, and a verified code completes registration", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { otpRequired: true });
    let registerAttempts = 0;
    await page.route(REGISTER_ENDPOINT, async (route) => {
      registerAttempts += 1;
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            studentProfileId: "33333333-3333-3333-3333-333333333333",
            email: "new-student@example-institute.test",
            pendingApproval: false,
          })
        ),
      });
    });
    let otpSendCount = 0;
    await page.route(OTP_SEND_ENDPOINT, async (route) => {
      otpSendCount += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(null)) });
    });
    await mockJson(page, OTP_VERIFY_ENDPOINT, 200, apiSuccess(null));

    await page.goto("/register");
    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();
    await expect(
      page.getByText("We sent a verification code to new-student@example-institute.test.")
    ).toBeVisible();
    expect(otpSendCount).toBe(1);
    expect(registerAttempts).toBe(0);

    await page.getByLabel("Verification code").fill("123456");
    await page.getByRole("button", { name: "Verify and create account" }).click();

    await expect(page.getByText("Account created")).toBeVisible();
    expect(registerAttempts).toBe(1);
  });

  test("an invalid verification code surfaces an inline error and does not attempt registration", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { otpRequired: true });
    await mockJson(page, OTP_SEND_ENDPOINT, 200, apiSuccess(null));
    await mockJson(
      page,
      OTP_VERIFY_ENDPOINT,
      409,
      apiError("CONFLICT", "Invalid or expired verification code")
    );
    let registerAttempts = 0;
    await page.route(REGISTER_ENDPOINT, async (route) => {
      registerAttempts += 1;
      await route.fulfill({ status: 500, body: "should not be called" });
    });

    await page.goto("/register");
    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();

    await page.getByLabel("Verification code").fill("000000");
    await page.getByRole("button", { name: "Verify and create account" }).click();

    await expect(page.getByText("Invalid or expired verification code")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();
    expect(registerAttempts).toBe(0);
  });

  test("'Use a different email' warns about unsaved changes, then returns to the form step (with values retained) once confirmed", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { otpRequired: true });
    await mockJson(page, OTP_SEND_ENDPOINT, 200, apiSuccess(null));

    await page.goto("/register");
    await fillBaseFields(page, { name: "Grace Hopper" });
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();

    // A filled-in form navigating away via an in-app control (not a tab
    // close/reload, which `beforeunload` already covers) must warn before
    // discarding it — see `(auth)/register/page.tsx`'s
    // `confirmDiscardUnsavedChanges`.
    let dialogMessage: string | null = null;
    page.once("dialog", async (dialog) => {
      dialogMessage = dialog.message();
      await dialog.accept();
    });
    await page.getByRole("button", { name: "Use a different email" }).click();

    await expect(page.getByLabel("Full name")).toBeVisible();
    expect(dialogMessage).toContain("unsaved changes");
    // The same `useForm` instance persists across the step change — no data
    // is actually lost, confirming the warning is a defensive UX guard, not
    // a sign the form was reset.
    await expect(page.getByLabel("Full name")).toHaveValue("Grace Hopper");
  });

  test("declining the 'Use a different email' confirmation stays on the OTP step", async ({ page }) => {
    await mockStudentRegistrationPolicy(page, { otpRequired: true });
    await mockJson(page, OTP_SEND_ENDPOINT, 200, apiSuccess(null));

    await page.goto("/register");
    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();

    page.once("dialog", (dialog) => dialog.dismiss());
    await page.getByRole("button", { name: "Use a different email" }).click();

    await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();
  });
});

test.describe("student registration — otp_required=false", () => {
  test("submitting the form registers immediately, with no OTP step ever shown", async ({ page }) => {
    await mockStudentRegistrationPolicy(page, { otpRequired: false });
    await mockJson(
      page,
      REGISTER_ENDPOINT,
      201,
      apiSuccess({
        studentProfileId: "44444444-4444-4444-4444-444444444444",
        email: "new-student@example-institute.test",
        pendingApproval: false,
      })
    );

    await page.goto("/register");
    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Account created")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Verify your email" })).not.toBeVisible();
  });
});

test.describe("student registration — conditional required fields", () => {
  test("fields the policy marks required are labeled without '(optional)' and validated client-side", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { requireGuardianInfo: true, requireSchool: true });
    let requestMade = false;
    await page.route(REGISTER_ENDPOINT, async (route) => {
      requestMade = true;
      await route.fulfill({ status: 500, body: "should not be called" });
    });

    await page.goto("/register");

    await expect(page.locator('label[for="register-guardianName"]')).toHaveText("Guardian name");
    await expect(page.locator('label[for="register-guardianPhone"]')).toHaveText("Guardian phone");
    await expect(page.locator('label[for="register-school"]')).toHaveText("School");
    // grade/stream/mobile stay optional under this policy permutation.
    await expect(page.locator('label[for="register-grade"]')).toHaveText("Grade (optional)");
    await expect(page.locator('label[for="register-stream"]')).toHaveText("Stream (optional)");
    await expect(page.locator('label[for="register-mobile"]')).toHaveText("Mobile number (optional)");

    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Guardian name is required.")).toBeVisible();
    await expect(page.getByText("Guardian phone is required.")).toBeVisible();
    await expect(page.getByText("School is required.")).toBeVisible();
    expect(requestMade).toBe(false);
  });

  test("fields the policy leaves optional are labeled '(optional)' and can be submitted blank", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page); // every additional field optional
    await mockJson(
      page,
      REGISTER_ENDPOINT,
      201,
      apiSuccess({
        studentProfileId: "55555555-5555-5555-5555-555555555555",
        email: "new-student@example-institute.test",
        pendingApproval: false,
      })
    );

    await page.goto("/register");
    await expect(page.locator('label[for="register-guardianName"]')).toHaveText("Guardian name (optional)");
    await expect(page.locator('label[for="register-school"]')).toHaveText("School (optional)");
    await expect(page.locator('label[for="register-grade"]')).toHaveText("Grade (optional)");
    await expect(page.locator('label[for="register-stream"]')).toHaveText("Stream (optional)");
    await expect(page.locator('label[for="register-mobile"]')).toHaveText("Mobile number (optional)");

    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Account created")).toBeVisible();
  });

  test("requireMobile=true requires only the mobile field, leaving guardian/school/grade/stream optional", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page, { requireMobile: true });
    let requestMade = false;
    await page.route(REGISTER_ENDPOINT, async (route) => {
      requestMade = true;
      await route.fulfill({ status: 500, body: "should not be called" });
    });

    await page.goto("/register");
    await expect(page.locator('label[for="register-mobile"]')).toHaveText("Mobile number");
    await expect(page.locator('label[for="register-guardianName"]')).toHaveText("Guardian name (optional)");

    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Mobile number is required.")).toBeVisible();
    expect(requestMade).toBe(false);
  });
});

test.describe("student registration — genuine submit-time failures", () => {
  test("a 400 naming fields the frontend didn't already require maps each error inline (backend remains authoritative even when the client schema disagrees)", async ({
    page,
  }) => {
    await mockStudentRegistrationPolicy(page); // client believes these fields are optional
    await mockJson(
      page,
      REGISTER_ENDPOINT,
      400,
      apiError("VALIDATION_ERROR", "Registration is missing required fields for this institute", [
        { field: "guardianName", message: "Guardian name is required" },
        { field: "guardianPhone", message: "Guardian phone is required" },
        { field: "school", message: "School is required" },
      ])
    );
    await page.goto("/register");
    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("Guardian name is required")).toBeVisible();
    await expect(page.getByText("Guardian phone is required")).toBeVisible();
    await expect(page.getByText("School is required")).toBeVisible();
    await expect(page.getByLabel("Guardian name")).toHaveAttribute("aria-invalid", "true");
  });

  test("a duplicate-email conflict maps to the email field", async ({ page }) => {
    await mockStudentRegistrationPolicy(page);
    await mockJson(
      page,
      REGISTER_ENDPOINT,
      409,
      apiError("CONFLICT", "An account with this email already exists")
    );
    await page.goto("/register");
    await fillBaseFields(page);
    await page.getByRole("button", { name: "Create account" }).click();

    const emailField = page.getByLabel("Email");
    await expect(page.getByText("An account with this email already exists")).toBeVisible();
    await expect(emailField).toHaveAttribute("aria-invalid", "true");
  });
});
