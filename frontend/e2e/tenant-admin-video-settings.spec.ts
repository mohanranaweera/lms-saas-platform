import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/class-session-mocks";
import { connection } from "./fixtures/integrations-mocks";

/**
 * VIDEO Phase 3 — Tenant Admin → Video providers / Video settings
 * (frontend, mock-only).
 *
 * Who may confirm a channel, scope enforcement, the one-channel-per-tenant
 * rule, credential encryption and every cross-tenant case are proven by the
 * backend's `TenantVideoProviderIntegrationTest` and
 * `TenantVideoConfigurationIntegrationTest`. These tests prove the pages
 * reflect backend state, never show tokens, require an explicit confirmation
 * (and replacement wording) after Google returns, and send the settings the
 * admin chose.
 */

const CHANNEL = connection({
  id: "conn-yt",
  provider: "YOUTUBE",
  scope: "TENANT",
  accountLabel: "Institute Channel",
  externalAccountIdMasked: "****AbCd",
});

function account(overrides: Record<string, unknown> = {}) {
  return {
    provider: "YOUTUBE",
    available: true,
    status: "NOT_CONNECTED",
    connection: null,
    connectedByUserId: null,
    platformDisabled: false,
    platformDisabledReason: null,
    canEditVideos: true,
    ...overrides,
  };
}

const DEFAULT_SETTINGS = {
  defaultProvider: "INTERNAL",
  defaultPrivacyStatus: "UNLISTED",
  uploadsEnabled: true,
  nativePlayerRequired: false,
  watermarkEnabled: true,
  watermarkOpacity: 0.25,
  watermarkMovementIntervalSeconds: 30,
  watermarkFields: ["STUDENT_DISPLAY_ID", "TIMESTAMP"],
  captureProtectionEnabled: true,
  captureProtectionRequired: false,
  maxConcurrentSessions: 1,
  watchTimePolicy: "NONE",
  watchTimeMultiplier: null,
  watchTimeFixedMinutes: null,
  launchCodeTtlSeconds: 45,
  heartbeatIntervalSeconds: 20,
};

async function mockProviders(page: Page, accounts: unknown[]): Promise<void> {
  await mockJson(page, "**/v1/tenant-admin/video-providers", 200, apiSuccess(accounts));
}

test.describe("Tenant Admin video providers", () => {
  test("a connected channel shows its details and never a token", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN", "admin-1");
    await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL, connectedByUserId: "admin-1" })]);

    await page.goto("/tenant-admin/settings/video-providers");

    const card = page.getByRole("region", { name: "YouTube" });
    await expect(card.getByText("Connected", { exact: true })).toBeVisible();
    await expect(card.getByText("Institute Channel")).toBeVisible();
    await expect(card.getByText("****AbCd")).toBeVisible();
    await expect(card.getByText("You", { exact: true })).toBeVisible();
    await expect(card.getByRole("button", { name: "Reconnect YouTube" })).toBeVisible();
    await expect(page.locator("body")).not.toContainText("google-at-");
  });

  test("connecting sends the admin to Google's consent page", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account()]);
    await mockJson(
      page,
      "**/v1/tenant-admin/video-providers/youtube/connect",
      200,
      apiSuccess({ authorizationUrl: "https://accounts.google.test/o/oauth2/v2/auth?state=abc" })
    );
    await page.route("https://accounts.google.test/**", (route) =>
      route.fulfill({ status: 200, contentType: "text/html", body: "<h1>Google consent</h1>" })
    );

    await page.goto("/tenant-admin/settings/video-providers");
    await page.getByRole("button", { name: "Connect YouTube" }).click();

    await expect(page).toHaveURL(/accounts\.google\.test/);
  });

  test("after Google returns, the channel is shown and saved only on an explicit confirm", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account()]);
    await mockJson(
      page,
      "**/v1/tenant-admin/video-providers/youtube/pending/pending-1",
      200,
      apiSuccess({ channelId: "UC-new", channelTitle: "New Channel", replacesCurrentChannel: false, currentChannelTitle: null })
    );
    const confirmBodies: unknown[] = [];
    await page.route("**/v1/tenant-admin/video-providers/youtube/pending/pending-1/confirm", async (route) => {
      confirmBodies.push(route.request().postDataJSON());
      await fulfillJson(
        route,
        200,
        apiSuccess(account({ status: "CONNECTED", connection: { ...CHANNEL, accountLabel: "New Channel" } }))
      );
    });

    await page.goto("/tenant-admin/settings/video-providers?pending=pending-1&provider=youtube");

    const panel = page.getByRole("region", { name: "Connect this YouTube channel?" });
    await expect(panel.getByText("UC-new")).toBeVisible();
    await expect(page).toHaveURL(/\/tenant-admin\/settings\/video-providers$/);
    expect(confirmBodies).toHaveLength(0);

    await panel.getByRole("button", { name: "Connect this channel" }).click();

    await expect(page.getByText("New Channel is now connected.")).toBeVisible();
    expect(confirmBodies).toEqual([{ replaceChannel: false }]);
  });

  test("replacing a different channel is spelled out and sends replaceChannel", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
    await mockJson(
      page,
      "**/v1/tenant-admin/video-providers/youtube/pending/pending-2",
      200,
      apiSuccess({
        channelId: "UC-other",
        channelTitle: "Other Channel",
        replacesCurrentChannel: true,
        currentChannelTitle: "Institute Channel",
      })
    );
    const confirmBodies: unknown[] = [];
    await page.route("**/v1/tenant-admin/video-providers/youtube/pending/pending-2/confirm", async (route) => {
      confirmBodies.push(route.request().postDataJSON());
      await fulfillJson(route, 200, apiSuccess(account({ status: "CONNECTED", connection: CHANNEL })));
    });

    await page.goto("/tenant-admin/settings/video-providers?pending=pending-2&provider=youtube");

    await expect(page.getByText(/This replaces your current channel, Institute Channel/)).toBeVisible();
    await page.getByRole("button", { name: "Replace with Other Channel" }).click();
    await expect.poll(() => confirmBodies).toEqual([{ replaceChannel: true }]);
  });

  test("a channel already used by another institute shows the backend's reason", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account()]);
    await mockJson(
      page,
      "**/v1/tenant-admin/video-providers/youtube/pending/pending-3",
      200,
      apiSuccess({ channelId: "UC-x", channelTitle: "X", replacesCurrentChannel: false, currentChannelTitle: null })
    );
    await mockJson(
      page,
      "**/v1/tenant-admin/video-providers/youtube/pending/pending-3/confirm",
      409,
      apiError("VIDEO_PROVIDER_CHANNEL_IN_USE", "This YouTube channel is already connected to another institute.")
    );

    await page.goto("/tenant-admin/settings/video-providers?pending=pending-3&provider=youtube");
    await page.getByRole("button", { name: "Connect this channel" }).click();

    await expect(page.getByText("This YouTube channel is already connected to another institute.")).toBeVisible();
  });

  test("an expired request and a missing upload permission explain what to do", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account()]);
    await mockJson(
      page,
      "**/v1/tenant-admin/video-providers/youtube/pending/stale",
      404,
      apiError("NOT_FOUND", "This connection request has expired or was already used. Connect again.")
    );

    await page.goto("/tenant-admin/settings/video-providers?pending=stale&provider=youtube");
    await expect(page.getByText(/This connection request has expired or was already used/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Connect this channel" })).toHaveCount(0);

    await page.goto("/tenant-admin/settings/video-providers?error=missing_scopes&provider=youtube");
    await expect(page.getByText(/upload permission wasn't granted/)).toBeVisible();
  });

  test("disconnecting asks first and posts the optional reason", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
    const bodies: unknown[] = [];
    await page.route("**/v1/tenant-admin/video-providers/conn-yt/disconnect", async (route) => {
      bodies.push(route.request().postDataJSON());
      await fulfillJson(route, 200, apiSuccess({ ...CHANNEL, status: "DISCONNECTED" }));
    });

    await page.goto("/tenant-admin/settings/video-providers");
    await page.getByRole("button", { name: "Disconnect", exact: true }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("Disconnect your YouTube channel?")).toBeVisible();
    await dialog.getByLabel("Reason (optional)").fill("Moving to a new channel");
    await dialog.getByRole("button", { name: "Disconnect YouTube" }).click();

    await expect.poll(() => bodies).toEqual([{ reason: "Moving to a new channel" }]);
  });

  test("a read-only auditor sees the status without any controls", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);

    await page.goto("/tenant-admin/settings/video-providers");

    await expect(page.getByText("Institute Channel")).toBeVisible();
    await expect(page.getByRole("button", { name: /Connect|Reconnect|Disconnect|Test/ })).toHaveCount(0);
    await expect(page.getByText("Only a Tenant Admin can change video providers.")).toBeVisible();
  });
});

test.describe("Tenant Admin video settings", () => {
  test("shows the platform defaults and saves the chosen settings", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const saved: Record<string, unknown>[] = [];
    await page.route("**/v1/tenant-admin/video-configuration", async (route) => {
      if (route.request().method() === "PUT") {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        saved.push(body);
        await fulfillJson(
          route,
          200,
          apiSuccess({ settings: body, customized: true, updatedAt: "2026-10-04T10:00:00Z", updatedBy: "admin-1" })
        );
        return;
      }
      await fulfillJson(
        route,
        200,
        apiSuccess({ settings: DEFAULT_SETTINGS, customized: false, updatedAt: null, updatedBy: null })
      );
    });

    await page.goto("/tenant-admin/settings/video-configuration");

    await expect(page.getByText("These are the platform defaults.", { exact: false })).toBeVisible();
    await expect(page.getByLabel("Videos playing at once per student")).toHaveValue("1");

    await page.getByLabel("Store new videos on").selectOption("YOUTUBE");
    await page.getByLabel("Require the AOC Secure Player app").check();
    await page.getByLabel("Watch-time limit per video").selectOption("VIDEO_DURATION_MULTIPLIER");
    await page.getByLabel("Multiplier").fill("1.5");
    await page.getByLabel("Session code").check();
    await page.getByRole("button", { name: "Save video settings" }).click();

    await expect(page.getByText("Video settings saved.")).toBeVisible();
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({
      defaultProvider: "YOUTUBE",
      nativePlayerRequired: true,
      watchTimePolicy: "VIDEO_DURATION_MULTIPLIER",
      watchTimeMultiplier: 1.5,
      watchTimeFixedMinutes: null,
    });
    expect(saved[0].watermarkFields).toEqual(["STUDENT_DISPLAY_ID", "TIMESTAMP", "SESSION_ID"]);
  });

  test("invalid values are caught before saving and server field errors are shown", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    let putCount = 0;
    await page.route("**/v1/tenant-admin/video-configuration", async (route) => {
      if (route.request().method() === "PUT") {
        putCount += 1;
        await fulfillJson(
          route,
          400,
          apiError("VALIDATION_ERROR", "Invalid video configuration", [
            { field: "heartbeatIntervalSeconds", message: "must be between 10 and 60" },
          ])
        );
        return;
      }
      await fulfillJson(
        route,
        200,
        apiSuccess({ settings: DEFAULT_SETTINGS, customized: false, updatedAt: null, updatedBy: null })
      );
    });

    await page.goto("/tenant-admin/settings/video-configuration");
    await page.getByLabel("Watch-time limit per video").selectOption("FIXED_MINUTES");
    await page.getByRole("button", { name: "Save video settings" }).click();
    await expect(page.getByText("Minutes is required.")).toBeVisible();
    expect(putCount).toBe(0);

    await page.getByLabel("Minutes per video").fill("90");
    await page.getByRole("button", { name: "Save video settings" }).click();
    await expect(page.getByText("must be between 10 and 60")).toBeVisible();
    expect(putCount).toBe(1);
  });

  test("a read-only auditor sees the settings disabled and cannot save", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockJson(
      page,
      "**/v1/tenant-admin/video-configuration",
      200,
      apiSuccess({ settings: DEFAULT_SETTINGS, customized: false, updatedAt: null, updatedBy: null })
    );

    await page.goto("/tenant-admin/settings/video-configuration");

    await expect(page.getByLabel("Store new videos on")).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save video settings" })).toHaveCount(0);
  });
});
