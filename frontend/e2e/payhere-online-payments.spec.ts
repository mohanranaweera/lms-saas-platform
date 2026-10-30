import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * PAY-GW-01 / ADR-017 — tenant PayHere online payments (frontend).
 *
 * Mock-only: the backend's signing, notification verification, tenant
 * isolation and chargeback handling are proven by
 * `PayHerePaymentGatewayIntegrationTest`. These tests prove the UI only ever
 * submits the backend-signed fields to PayHere's own origin, never shows a
 * merchant secret, and offers online payment only when the backend says so.
 */

const COURSE = {
  id: "course-ph-1",
  teacherId: "teacher-1",
  name: "2027 Chemistry Theory Class",
  slug: "2027-chemistry-theory-class",
  category: "Chemistry",
  subject: null,
  stream: null,
  grade: null,
  academicYear: null,
  description: null,
  price: 4500,
  accessDurationDays: 30,
  enrollmentRule: null,
  status: "PUBLIC" as const,
  pricingModel: "ONE_TIME" as const,
  resolvedAmount: 4500,
  currency: "LKR",
  requiresManualQuote: false,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const ORDER = {
  id: "order-ph-1",
  studentId: "student-1",
  courseId: COURSE.id,
  amount: 4500,
  currency: "LKR",
  billingPeriodId: null,
  status: "PLACED",
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const SIGNED_FIELDS = {
  merchant_id: "1211149",
  order_id: "PH-abc",
  amount: "4500.00",
  currency: "LKR",
  address: "No. 1, Galle Road",
  city: "Colombo",
  hash: "05C9D2339760D3A9BE3192CEA772DE61",
};

async function mockSession(page: Page, role: string): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
}

async function mockCheckout(
  page: Page,
  options: { online: boolean; address: boolean; formAction?: string; bankSlip?: boolean }
): Promise<void> {
  await mockSession(page, "STUDENT");
  await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
  await mockJson(
    page,
    "**/v1/checkout/payment-options",
    200,
    apiSuccess({
      bankSlip: options.bankSlip ?? true,
      onlinePayment: options.online ? { provider: "PAYHERE", mode: "SANDBOX" } : null,
    })
  );
  await mockJson(
    page,
    "**/v1/students/me/billing-address",
    200,
    apiSuccess(
      options.address
        ? { addressLine: "No. 1, Galle Road", city: "Colombo" }
        : { addressLine: null, city: null }
    )
  );
  await mockJson(page, "**/api/v1/orders", 201, apiSuccess(ORDER));
  await mockJson(
    page,
    `**/api/v1/orders/${ORDER.id}/payments`,
    201,
    apiSuccess({
      paymentId: "payment-ph-1",
      orderId: ORDER.id,
      status: "PENDING",
      gatewayReference: "PH-abc",
      redirectTarget: null,
      formAction: options.formAction ?? "https://sandbox.payhere.lk/pay/checkout",
      formFields: SIGNED_FIELDS,
    })
  );
}

test.describe("student checkout — PayHere", () => {
  test("posts exactly the backend-signed fields to PayHere's own checkout URL", async ({ page }) => {
    await mockCheckout(page, { online: true, address: true });
    let postedBody = "";
    await page.route("https://sandbox.payhere.lk/pay/checkout", async (route) => {
      postedBody = route.request().postData() ?? "";
      await route.fulfill({ status: 200, contentType: "text/html", body: "<h1>PayHere sandbox</h1>" });
    });

    await page.goto(`/student/checkout/${COURSE.id}`);
    await page.getByRole("button", { name: "Pay online (PayHere)" }).click();

    await expect(page).toHaveURL("https://sandbox.payhere.lk/pay/checkout");
    const posted = new URLSearchParams(postedBody);
    for (const [name, value] of Object.entries(SIGNED_FIELDS)) {
      expect(posted.get(name)).toBe(value);
    }
  });

  test("offers only bank transfer when the institute has no online payment account", async ({ page }) => {
    await mockCheckout(page, { online: false, address: true });

    await page.goto(`/student/checkout/${COURSE.id}`);

    await expect(page.getByText("Online payment isn't available at your institute yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Pay online (PayHere)" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Pay by bank transfer" })).toBeEnabled();
  });

  test("hides bank transfer when the institute has switched it off (SET-PAY-01)", async ({ page }) => {
    await mockCheckout(page, { online: true, address: true, bankSlip: false });

    await page.goto(`/student/checkout/${COURSE.id}`);

    await expect(page.getByRole("button", { name: "Pay online (PayHere)" })).toBeEnabled();
    await expect(page.getByRole("button", { name: "Pay by bank transfer" })).toHaveCount(0);
  });

  test("asks for a billing address before allowing online payment", async ({ page }) => {
    await mockCheckout(page, { online: true, address: false });

    await page.goto(`/student/checkout/${COURSE.id}`);

    await expect(page.getByRole("link", { name: "Add your address in My Profile" })).toHaveAttribute(
      "href",
      "/student/profile"
    );
    await expect(page.getByRole("button", { name: "Pay online (PayHere)" })).toBeDisabled();
  });

  test("never submits to a form action that is not PayHere's", async ({ page }) => {
    await mockCheckout(page, { online: true, address: true, formAction: "https://evil.example/collect" });
    let evilHit = false;
    await page.route("https://evil.example/**", async (route) => {
      evilHit = true;
      await route.abort();
    });

    await page.goto(`/student/checkout/${COURSE.id}`);
    await page.getByRole("button", { name: "Pay online (PayHere)" }).click();

    await expect(page.getByText("We couldn't open the secure payment page.", { exact: false })).toBeVisible();
    expect(evilHit).toBe(false);
  });

  test("the cancelled page changes nothing and links back to checkout", async ({ page }) => {
    await mockSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/orders/${ORDER.id}`, 200, apiSuccess(ORDER));

    await page.goto(`/student/payments/cancelled/${ORDER.id}`);

    await expect(page.getByRole("heading", { name: "Payment cancelled" })).toBeVisible();
    await expect(page.getByText("No money was taken for this attempt.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to checkout" })).toHaveAttribute(
      "href",
      `/student/checkout/${COURSE.id}`
    );
  });
});

test.describe("student profile — billing address", () => {
  test("saves the student's own address", async ({ page }) => {
    await mockSession(page, "STUDENT");
    await mockJson(
      page,
      "**/v1/students/me",
      200,
      apiSuccess({ id: "student-1", name: "Saman Perera", email: "saman@example.test", roleCode: "STUDENT", status: "ACTIVE" })
    );
    let savedBody: unknown = null;
    await page.route("**/v1/students/me/billing-address", async (route) => {
      if (route.request().method() === "PUT") {
        savedBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess({ addressLine: "12 Temple Road", city: "Kandy" })),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ addressLine: null, city: null })),
      });
    });

    await page.goto("/student/profile");
    await page.getByLabel("Address", { exact: true }).fill("12 Temple Road");
    await page.getByLabel("City", { exact: true }).fill("Kandy");
    await page.getByRole("button", { name: "Save address" }).click();

    await expect(page.getByText("Billing address saved.")).toBeVisible();
    expect(savedBody).toEqual({ addressLine: "12 Temple Road", city: "Kandy" });
  });
});

test.describe("tenant admin — online payments settings", () => {
  test("connects PayHere and never renders the secret back", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    const secret = "super-secret-merchant-value";
    let savedBody: Record<string, unknown> | null = null;
    let accounts: unknown[] = [];
    await page.route("**/v1/payment-gateway-accounts", async (route) => {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(accounts)) });
    });
    await page.route("**/v1/payment-gateway-accounts/payhere", async (route) => {
      savedBody = route.request().postDataJSON();
      const saved = {
        id: "acct-1",
        provider: "PAYHERE",
        mode: "SANDBOX",
        merchantIdMasked: "***1149",
        status: "ACTIVE",
        secretConfigured: true,
        disabledBy: null,
        disabledReason: null,
        disabledAt: null,
        updatedAt: new Date().toISOString(),
        checkoutReady: true,
      };
      accounts = [saved];
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(saved)) });
    });

    await page.goto("/tenant-admin/settings/payments");
    await expect(page.getByText("Online payments are not set up")).toBeVisible();
    await page.getByLabel("Merchant ID").fill("1211149");
    await page.getByLabel("Merchant secret").fill(secret);
    await page.getByRole("button", { name: "Connect PayHere" }).click();

    await expect(page.getByText("PayHere account saved.", { exact: false })).toBeVisible();
    expect(savedBody).toMatchObject({ merchantId: "1211149", merchantSecret: secret, mode: "SANDBOX" });
    await expect(page.getByText("***1149")).toBeVisible();
    await expect(page.getByLabel("Merchant secret")).toHaveValue("");
    await expect(page.locator("body")).not.toContainText(secret);
  });

  test("finance staff see status only, with no form", async ({ page }) => {
    await mockSession(page, "FINANCE_STAFF");
    await mockJson(
      page,
      "**/v1/payment-gateway-accounts",
      200,
      apiSuccess([
        {
          id: "acct-1",
          provider: "PAYHERE",
          mode: "LIVE",
          merchantIdMasked: "***1149",
          status: "ACTIVE",
          secretConfigured: true,
          disabledBy: null,
          disabledReason: null,
          disabledAt: null,
          updatedAt: new Date().toISOString(),
          checkoutReady: true,
        },
      ])
    );

    await page.goto("/tenant-admin/settings/payments");

    await expect(page.getByLabel("PayHere account status")).toBeVisible();
    await expect(page.getByLabel("Merchant ID")).toHaveCount(0);
    await expect(page.getByText("Only a Tenant Admin can change these settings.")).toBeVisible();
  });

  test("a platform switch-off is explained and blocks editing", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/payment-gateway-accounts",
      200,
      apiSuccess([
        {
          id: "acct-1",
          provider: "PAYHERE",
          mode: "LIVE",
          merchantIdMasked: "***1149",
          status: "DISABLED",
          secretConfigured: true,
          disabledBy: "PLATFORM",
          disabledReason: "Fraud review",
          disabledAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          checkoutReady: false,
        },
      ])
    );

    await page.goto("/tenant-admin/settings/payments");

    await expect(page.getByText("Online payments were switched off by the platform", { exact: false })).toBeVisible();
    await expect(page.getByLabel("Merchant ID")).toHaveCount(0);
  });

  test("warns when the account is saved but online checkout isn't ready yet", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/payment-gateway-accounts",
      200,
      apiSuccess([
        {
          id: "acct-1",
          provider: "PAYHERE",
          mode: "SANDBOX",
          merchantIdMasked: "***3649",
          status: "ACTIVE",
          secretConfigured: true,
          disabledBy: null,
          disabledReason: null,
          disabledAt: null,
          updatedAt: new Date().toISOString(),
          checkoutReady: false,
        },
      ])
    );

    await page.goto("/tenant-admin/settings/payments");

    await expect(page.getByText("students can't pay online yet", { exact: false })).toBeVisible();
  });

  test("a 403 renders the permission-denied state", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/payment-gateway-accounts", 403, apiError("FORBIDDEN", "Not allowed"));

    await page.goto("/tenant-admin/settings/payments");

    // SET-PAY-01: online payments are the "Payment gateway — PayHere" section of Settings → Payments.
    await expect(page.getByRole("heading", { name: "Payment gateway — PayHere" })).toBeVisible();
    await expect(page.getByTestId("payment-gateway-settings").getByRole("alert")).toBeVisible();
    await expect(page.getByLabel("Merchant ID")).toHaveCount(0);
  });
});

test.describe("platform admin — tenant online payments", () => {
  const TENANT = {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Advance Online Class",
    subdomain: "demo",
    status: "ACTIVE",
    requestedPlan: "STARTER",
    contactName: null,
    contactEmail: null,
    contactPhone: null,
    createdAt: new Date().toISOString(),
  };

  test("switching off names the tenant and requires a reason", async ({ page }) => {
    const token = fakeJwt({ role: "PLATFORM_ADMIN" });
    await mockJson(page, "**/v1/platform-admin/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    await mockJson(page, `**/v1/platform-admin/tenants/${TENANT.id}`, 200, apiSuccess(TENANT));
    await mockJson(
      page,
      `**/v1/platform-admin/tenants/${TENANT.id}/payment-gateway`,
      200,
      apiSuccess([
        {
          id: "acct-1",
          provider: "PAYHERE",
          mode: "LIVE",
          merchantIdMasked: "***1149",
          status: "ACTIVE",
          secretConfigured: true,
          disabledBy: null,
          disabledReason: null,
          disabledAt: null,
          updatedAt: new Date().toISOString(),
          checkoutReady: true,
        },
      ])
    );
    let disableCalls = 0;
    await page.route(`**/v1/platform-admin/tenants/${TENANT.id}/payment-gateway/disable`, async (route) => {
      disableCalls += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess({})) });
    });

    await page.goto(`/platform-admin/tenants/${TENANT.id}`);
    await page.getByRole("button", { name: "Switch off online payments" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText(`Switch off online payments for ${TENANT.name}?`)).toBeVisible();
    await dialog.getByRole("button", { name: `Switch off for ${TENANT.name}` }).click();
    await expect(dialog.getByText("A reason is required.")).toBeVisible();
    expect(disableCalls).toBe(0);

    await dialog.getByLabel("Reason (required)").fill("Fraud review");
    await dialog.getByRole("button", { name: `Switch off for ${TENANT.name}` }).click();
    await expect.poll(() => disableCalls).toBe(1);
  });
});
