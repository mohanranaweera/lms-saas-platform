import { test, expect, type Page, type Request } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Wave 11 - communications UI. No real backend runs here: every endpoint is
 * intercepted with `page.route()`. The backend is the only authority on who
 * may send, who is in an audience and which channels exist; these specs check
 * the UI reflects its answers, sends the right requests (idempotency key,
 * audience, channels), and never shows a messaging credential back.
 */

const BROADCAST_ID = "b0000000-0000-0000-0000-000000000001";
const NOW = new Date().toISOString();

const CHANNELS_ALL = [
  { channel: "IN_APP", available: true, allowedForYou: true, reason: null },
  { channel: "EMAIL", available: true, allowedForYou: true, reason: null },
  { channel: "SMS", available: false, allowedForYou: true, reason: "NOT_CONFIGURED" },
  { channel: "WHATSAPP", available: true, allowedForYou: true, reason: null },
];

function broadcast(overrides: Record<string, unknown> = {}) {
  return {
    id: BROADCAST_ID,
    subject: "Classes resume Monday",
    body: "Hello {{name}}, classes resume on Monday.",
    smsBody: null,
    channels: ["IN_APP", "EMAIL"],
    audienceType: "ALL_STUDENTS",
    audienceCourseId: null,
    audiencePaymentStatus: null,
    recipientCount: 2,
    senderUserId: "u1",
    senderRole: "TENANT_ADMIN",
    templateId: null,
    createdAt: NOW,
    stats: [
      { channel: "IN_APP", pending: 0, sent: 2, failed: 0, skipped: 0 },
      { channel: "EMAIL", pending: 0, sent: 1, failed: 0, skipped: 1 },
    ],
    replayed: false,
    ...overrides,
  };
}

async function mockTenantSession(page: Page, role: string): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/notifications/unread-count", 200, apiSuccess({ unreadCount: 0 }));
}

async function selectOption(page: Page, labelName: string, optionName: string | RegExp): Promise<void> {
  await page.getByLabel(labelName, { exact: true }).click();
  await page.getByRole("option", { name: optionName }).click();
}

// ---------------------------------------------------------------------------
// Tenant Admin - broadcasts
// ---------------------------------------------------------------------------

test.describe("tenant admin - broadcasts", () => {
  test("the Communication nav group links to broadcasts and an empty list explains itself", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/communications/broadcasts?*", 200, apiPageSuccess([]));
    await page.goto("/tenant-admin/communications");

    await expect(page.getByRole("heading", { name: "Broadcasts" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Message Templates" })).toBeVisible();
    await expect(page.getByText("No broadcasts yet")).toBeVisible();
    await expect(page.getByRole("link", { name: "New broadcast" })).toBeVisible();
  });

  test("a read-only auditor sees broadcasts but no way to send one", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockJson(page, "**/v1/communications/broadcasts?*", 200, apiPageSuccess([broadcast()]));
    await page.goto("/tenant-admin/communications");

    await expect(page.getByRole("link", { name: "Classes resume Monday" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "New broadcast" })).toHaveCount(0);
  });

  test("compose: preview the audience, then send once with an idempotency key", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/communications/channels", 200, apiSuccess(CHANNELS_ALL));
    await mockJson(page, "**/v1/communications/templates", 200, apiSuccess([]));
    await mockJson(page, "**/v1/courses?*", 200, apiPageSuccess([]));
    await mockJson(
      page,
      "**/v1/communications/audience-preview",
      200,
      apiSuccess({
        recipientCount: 2,
        reachByChannel: { IN_APP: 2, EMAIL: 2, SMS: 1, WHATSAPP: 1 },
        sampleNames: ["Amara Perera", "Bimal Silva"],
      })
    );
    const sent: Request[] = [];
    await page.route("**/v1/communications/broadcasts", async (route) => {
      sent.push(route.request());
      await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(apiSuccess(broadcast())) });
    });
    await mockJson(page, `**/v1/communications/broadcasts/${BROADCAST_ID}`, 200, apiSuccess(broadcast()));
    await mockJson(
      page,
      `**/v1/communications/broadcasts/${BROADCAST_ID}/deliveries?*`,
      200,
      apiPageSuccess([
        {
          id: "d1",
          recipientUserId: "s1",
          recipientName: "Amara Perera",
          channel: "EMAIL",
          status: "SENT",
          attemptCount: 1,
          destination: "a•••@example.test",
          errorCode: null,
          nextAttemptAt: null,
          completedAt: NOW,
        },
        {
          id: "d2",
          recipientUserId: "s2",
          recipientName: "Bimal Silva",
          channel: "EMAIL",
          status: "SKIPPED",
          attemptCount: 1,
          destination: null,
          errorCode: "NO_CONSENT",
          nextAttemptAt: null,
          completedAt: NOW,
        },
      ])
    );

    await page.goto("/tenant-admin/communications/new");
    await expect(page.getByRole("button", { name: "Send" })).toBeDisabled();
    await expect(page.getByText("SMS — not set up for your institute")).toBeVisible();
    await expect(page.getByLabel(/WhatsApp/)).toBeDisabled();

    await page.getByRole("button", { name: "Preview recipients" }).click();
    await expect(page.getByRole("status").filter({ hasText: "2 recipients" })).toBeVisible();

    await page.getByLabel("Subject").fill("Classes resume Monday");
    await page.getByLabel("Message", { exact: true }).fill("Hello {{name}}, classes resume on Monday.");
    await page.getByLabel(/^Email$/).check();
    await page.getByRole("button", { name: "Send to 2 recipients" }).click();

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/communications/${BROADCAST_ID}$`));
    expect(sent).toHaveLength(1);
    const body = sent[0].postDataJSON();
    expect(body.clientRequestId).toMatch(/^[0-9a-f-]{36}$/);
    expect(body.audience).toEqual({ type: "ALL_STUDENTS", courseId: null, paymentStatus: null, studentIds: null });
    expect(body.channels.sort()).toEqual(["EMAIL", "IN_APP"]);

    await expect(page.getByRole("caption").filter({ hasText: "Delivery log" })).toBeAttached();
    await expect(page.getByText("a•••@example.test").first()).toBeVisible();
    await expect(page.getByText("The recipient has turned this channel off").first()).toBeVisible();
  });

  test("a backend refusal on send is shown, not swallowed", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/communications/channels", 200, apiSuccess(CHANNELS_ALL));
    await mockJson(page, "**/v1/communications/templates", 200, apiSuccess([]));
    await mockJson(page, "**/v1/courses?*", 200, apiPageSuccess([]));
    await mockJson(
      page,
      "**/v1/communications/audience-preview",
      200,
      apiSuccess({ recipientCount: 1, reachByChannel: { IN_APP: 1, EMAIL: 1, SMS: 0, WHATSAPP: 0 }, sampleNames: ["A"] })
    );
    await mockJson(
      page,
      "**/v1/communications/broadcasts",
      400,
      apiError("VALIDATION_ERROR", "Invalid message text", [
        { field: "body", message: "uses unknown variable {{password}} - allowed: {{name}}, {{instituteName}}" },
      ])
    );
    await page.goto("/tenant-admin/communications/new");
    await page.getByRole("button", { name: "Preview recipients" }).click();
    await page.getByLabel("Subject").fill("Hi");
    await page.getByLabel("Message", { exact: true }).fill("Your password is {{password}}");
    await page.getByRole("button", { name: "Send to 1 recipient" }).click();
    await expect(page.getByText("uses unknown variable {{password}}")).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Tenant Admin - messaging settings (secrets)
// ---------------------------------------------------------------------------

test.describe("tenant admin - messaging settings", () => {
  test("saving SMS credentials sends them once and only the masked hint is ever shown", async ({ page }) => {
    const secret = "sk_live_SUPERSECRET_9876";
    let configured = false;
    await mockTenantSession(page, "TENANT_ADMIN");
    const account = (state: "NOT_CONFIGURED" | "ACTIVE") => ({
      channel: "SMS",
      configured: state === "ACTIVE",
      id: state === "ACTIVE" ? "acc-1" : null,
      provider: state === "ACTIVE" ? "SANDBOX" : null,
      senderId: state === "ACTIVE" ? "ACADEMY" : null,
      credentialHint: state === "ACTIVE" ? "••••9876" : null,
      status: state,
      disabledAt: null,
      updatedAt: state === "ACTIVE" ? NOW : null,
      operational: state === "ACTIVE",
    });
    const whatsapp = { ...account("NOT_CONFIGURED"), channel: "WHATSAPP" };
    await page.route("**/v1/tenant-admin/integrations/messaging", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({
            accounts: [account(configured ? "ACTIVE" : "NOT_CONFIGURED"), whatsapp],
            availableProviders: [{ provider: "SANDBOX", channels: ["SMS", "WHATSAPP"], credentialKeys: ["apiKey"] }],
          })
        ),
      });
    });
    let putBody: Record<string, unknown> | null = null;
    await page.route("**/v1/tenant-admin/integrations/messaging/SMS", async (route) => {
      putBody = route.request().postDataJSON();
      configured = true;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(account("ACTIVE"))) });
    });

    await page.goto("/tenant-admin/settings/messaging");
    await page.locator("#messaging-SMS-sender").fill("ACADEMY");
    await page.locator("#messaging-SMS-apiKey").fill(secret);
    await expect(page.locator("#messaging-SMS-apiKey")).toHaveAttribute("type", "password");
    await page.getByRole("button", { name: "Connect account" }).first().click();

    await expect(page.getByText("SMS account saved.")).toBeVisible();
    expect(putBody).toMatchObject({ provider: "SANDBOX", senderId: "ACADEMY", credentials: { apiKey: secret } });
    await expect(page.getByText("key ••••9876")).toBeVisible();
    await expect(page.locator("#messaging-SMS-apiKey")).toHaveValue("");
    expect(await page.content()).not.toContain(secret);
  });

  test("with no provider available on the platform the channels stay off", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/tenant-admin/integrations/messaging",
      200,
      apiSuccess({
        accounts: ["SMS", "WHATSAPP"].map((channel) => ({
          channel,
          configured: false,
          id: null,
          provider: null,
          senderId: null,
          credentialHint: null,
          status: "NOT_CONFIGURED",
          disabledAt: null,
          updatedAt: null,
          operational: false,
        })),
        availableProviders: [],
      })
    );
    await page.goto("/tenant-admin/settings/messaging");
    await expect(page.getByText("No SMS or WhatsApp provider is available on this platform yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Connect account" })).toHaveCount(0);
  });
});

// ---------------------------------------------------------------------------
// Teacher
// ---------------------------------------------------------------------------

test.describe("teacher - messages", () => {
  test("a teacher composes for their own course only and cannot pick SMS", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(
      page,
      "**/v1/communications/channels",
      200,
      apiSuccess([
        { channel: "IN_APP", available: true, allowedForYou: true, reason: null },
        { channel: "EMAIL", available: true, allowedForYou: true, reason: null },
        { channel: "SMS", available: true, allowedForYou: false, reason: null },
        { channel: "WHATSAPP", available: true, allowedForYou: false, reason: null },
      ])
    );
    await mockJson(page, "**/v1/communications/templates", 200, apiSuccess([]));
    await mockJson(page, "**/v1/courses?*", 200, apiPageSuccess([{ id: "c1", name: "Combined Maths 2026" }]));
    let previewBody: Record<string, unknown> | null = null;
    await page.route("**/v1/communications/audience-preview", async (route) => {
      previewBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiSuccess({ recipientCount: 5, reachByChannel: { IN_APP: 5, EMAIL: 5, SMS: 0, WHATSAPP: 0 }, sampleNames: [] })
        ),
      });
    });

    await page.goto("/teacher/communications/new");
    await expect(page.getByLabel("Audience", { exact: true })).toHaveCount(0);
    await expect(page.getByText("SMS — not available to teachers")).toBeVisible();

    await page.getByRole("button", { name: "Preview recipients" }).click();
    await expect(page.getByText("Choose a course.")).toBeVisible();

    await selectOption(page, "Course", "Combined Maths 2026");
    await page.getByRole("button", { name: "Preview recipients" }).click();
    await expect(page.getByRole("button", { name: "Send to 5 recipients" })).toBeVisible();
    expect(previewBody).toMatchObject({ type: "COURSE", courseId: "c1" });
  });
});

// ---------------------------------------------------------------------------
// Recipients - Notification Center
// ---------------------------------------------------------------------------

test.describe("student - notification center", () => {
  test("mark all as read and opt in to WhatsApp", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    let readAll = false;
    await page.route("**/v1/notifications?*", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiPageSuccess([
            { id: "n1", title: "Classes resume Monday", body: "Hello Amara", readAt: readAll ? NOW : null, createdAt: NOW },
          ])
        ),
      });
    });
    await page.route("**/v1/notifications/read-all", async (route) => {
      readAll = true;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess({ updated: 1 })) });
    });
    const preferences = (whatsapp: boolean) => [
      { channel: "EMAIL", optedIn: true, explicitOptInRequired: false },
      { channel: "SMS", optedIn: true, explicitOptInRequired: false },
      { channel: "WHATSAPP", optedIn: whatsapp, explicitOptInRequired: true },
    ];
    let preferenceBody: Record<string, unknown> | null = null;
    await page.route("**/v1/notifications/preferences", async (route) => {
      if (route.request().method() === "PUT") {
        preferenceBody = route.request().postDataJSON();
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(preferences(true))) });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(preferences(false))) });
    });

    await page.goto("/student/notifications");
    await expect(page.getByText("Classes resume Monday")).toBeVisible();
    await page.getByRole("button", { name: "Mark all as read" }).click();
    await expect(page.getByRole("button", { name: "Mark all as read" })).toHaveCount(0);

    await expect(page.getByText("(off until you turn it on)")).toBeVisible();
    // Controlled by the server's answer: the box flips only once the PUT succeeds.
    await page.getByLabel(/WhatsApp announcements/).click();
    await expect(page.getByLabel(/WhatsApp announcements/)).toBeChecked();
    await expect(page.getByText("Preferences saved.")).toBeVisible();
    expect(preferenceBody).toEqual({ WHATSAPP: true });
  });
});
