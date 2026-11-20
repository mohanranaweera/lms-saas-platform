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
    integrationId: "int-1",
    name: "Institute YouTube",
    description: null,
    allTeachersAllowed: true,
    allowedTeachers: [],
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

const TEACHERS = [
  { userId: "user-t1", name: "Teacher One", email: "one@example.test" },
  { userId: "user-t2", name: "Teacher Two", email: "two@example.test" },
];

const REDIRECT = "https://app.example.test/api/v1/integrations/oauth/youtube/callback";
const OWN_CLIENT = "444444444444-institute.apps.googleusercontent.com";
const OWN_SECRET = "GOCSPX-e2e-institute-secret";

function googleApp(overrides: Record<string, unknown> = {}) {
  return {
    source: "PLATFORM",
    clientId: null,
    secretSaved: false,
    platformAppAvailable: true,
    redirectUri: REDIRECT,
    encryptionAvailable: true,
    updatedAt: null,
    channelConnected: false,
    ...overrides,
  };
}

async function mockProviders(page: Page, accounts: unknown[]): Promise<void> {
  await mockJson(page, "**/v1/tenant-admin/video-providers", 200, apiSuccess(accounts));
  await mockJson(page, "**/v1/tenant-admin/video-providers/youtube/teachers", 200, apiSuccess(TEACHERS));
  await mockJson(page, "**/v1/tenant-admin/video-providers/youtube/oauth-app", 200, apiSuccess(googleApp()));
}

/** The institute's Google app: GET returns `initial`; PUT / DELETE are recorded and answered like the backend. */
async function mockGoogleApp(page: Page, initial: Record<string, unknown>) {
  const calls = { puts: [] as unknown[], deletes: 0 };
  let current = initial;
  await page.route("**/v1/tenant-admin/video-providers/youtube/oauth-app", async (route) => {
    const method = route.request().method();
    if (method === "PUT") {
      const body = route.request().postDataJSON() as { clientId: string };
      calls.puts.push(body);
      current = { ...current, source: "TENANT", clientId: body.clientId, secretSaved: true, updatedAt: "2026-10-05T09:00:00Z" };
    } else if (method === "DELETE") {
      calls.deletes += 1;
      current = { ...current, source: "PLATFORM", clientId: null, secretSaved: false, updatedAt: null };
    }
    await fulfillJson(route, 200, apiSuccess(current));
  });
  return calls;
}

/** The integration cards: GET returns the current list; POST / PUT / DELETE are recorded and applied. */
async function mockIntegrations(page: Page, initial: Record<string, unknown>[]) {
  const calls = { posts: [] as unknown[], puts: [] as unknown[], deletes: [] as string[] };
  let cards = initial;
  await page.route("**/v1/tenant-admin/video-providers", async (route) => {
    await fulfillJson(route, 200, apiSuccess(cards));
  });
  await page.route("**/v1/tenant-admin/video-providers/youtube/integrations", async (route) => {
    const body = route.request().postDataJSON() as { name: string; description: string | null; allowedTeacherIds: string[] };
    calls.posts.push(body);
    const allowed = TEACHERS.filter((t) => body.allowedTeacherIds.includes(t.userId));
    const card = account({
      integrationId: `int-${cards.length + 1}`,
      name: body.name,
      description: body.description,
      allTeachersAllowed: allowed.length === 0,
      allowedTeachers: allowed,
    });
    cards = [...cards, card];
    await fulfillJson(route, 201, apiSuccess(card));
  });
  await page.route("**/v1/tenant-admin/video-providers/youtube/integrations/*", async (route) => {
    const id = new URL(route.request().url()).pathname.split("/").pop() ?? "";
    if (route.request().method() === "DELETE") {
      calls.deletes.push(id);
      cards = cards.filter((card) => card.integrationId !== id);
      return fulfillJson(route, 200, apiSuccess(null));
    }
    const body = route.request().postDataJSON() as { name: string; description: string | null; allowedTeacherIds: string[] };
    calls.puts.push({ id, ...body });
    const allowed = TEACHERS.filter((t) => body.allowedTeacherIds.includes(t.userId));
    cards = cards.map((card) =>
      card.integrationId === id
        ? { ...card, name: body.name, description: body.description, allTeachersAllowed: allowed.length === 0, allowedTeachers: allowed }
        : card
    );
    return fulfillJson(route, 200, apiSuccess(cards.find((card) => card.integrationId === id)));
  });
  return calls;
}

test.describe("Tenant Admin video providers", () => {
  test("Settings → Video shows providers and video settings on one tab; old routes redirect there", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN", "admin-1");
    await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL, connectedByUserId: "admin-1" })]);

    await page.goto("/tenant-admin/settings/video-configuration");
    await expect(page).toHaveURL(/\/tenant-admin\/settings\/video$/);
    await expect(page.getByRole("heading", { name: "Settings", level: 1 })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Settings sections" }).getByRole("link", { name: "Video" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { name: "Video providers", level: 2 })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Video settings", level: 2 })).toBeVisible();
    const sidebar = page.getByRole("navigation", { name: "Primary" }).first();
    await expect(sidebar.getByRole("link", { name: "Video Providers", exact: true })).toHaveCount(0);
    await expect(sidebar.getByRole("link", { name: "Video Settings", exact: true })).toHaveCount(0);
    await expect(sidebar.getByRole("link", { name: "Settings", exact: true })).toHaveAttribute("aria-current", "page");
  });

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
      "**/v1/tenant-admin/video-providers/youtube/integrations/int-1/connect",
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
      apiSuccess({ integrationId: "int-1", integrationName: "Institute YouTube", channelId: "UC-new", channelTitle: "New Channel", replacesCurrentChannel: false, currentChannelTitle: null })
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

    const panel = page.getByRole("region", { name: /Connect this YouTube channel/ });
    await expect(panel.getByText("UC-new")).toBeVisible();
    await expect(page).toHaveURL(/\/tenant-admin\/settings\/video$/);
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

test.describe("Institute Google app (Phase 5c)", () => {
  test("by default the platform app is used; adding the institute's own sends exactly what was typed", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account()]);
    const calls = await mockGoogleApp(page, googleApp());

    await page.goto("/tenant-admin/settings/video-providers");

    const section = page.getByRole("region", { name: "Google app" });
    await expect(section.getByText("Platform Google app", { exact: true })).toBeVisible();
    await expect(section.getByText(REDIRECT)).toBeVisible();
    await section.getByLabel("Client ID").fill(OWN_CLIENT);
    await section.getByLabel("Client secret").fill(OWN_SECRET);
    await section.getByRole("button", { name: "Use my Google app" }).click();

    await expect(section.getByText("Google app saved. Use Verify to check it with Google.")).toBeVisible();
    expect(calls.puts).toEqual([{ clientId: OWN_CLIENT, clientSecret: OWN_SECRET }]);
    await expect(section.getByText("Your institute's Google app", { exact: true })).toBeVisible();
    await expect(section.getByText("Saved (hidden)")).toBeVisible();
    await expect(section.getByLabel("Client secret")).toHaveValue("");
    await expect(page.locator("body")).not.toContainText(OWN_SECRET);
  });

  test("with a connected channel, changing the app must be acknowledged; removing returns to the platform app", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
    const calls = await mockGoogleApp(page, googleApp({ channelConnected: true }));

    await page.goto("/tenant-admin/settings/video-providers");
    const section = page.getByRole("region", { name: "Google app" });
    await section.getByLabel("Client ID").fill(OWN_CLIENT);
    await section.getByLabel("Client secret").fill(OWN_SECRET);
    const save = section.getByRole("button", { name: "Use my Google app" });
    await expect(section.getByText(/connected through a different Google app/)).toBeVisible();
    await expect(save).toBeDisabled();
    await section.getByLabel("I understand the channel must be connected again").check();
    await save.click();
    await expect(section.getByText("Your institute's Google app", { exact: true })).toBeVisible();
    expect(calls.puts).toHaveLength(1);

    await section.getByRole("button", { name: "Remove my Google app" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText(/use the platform's Google app again/)).toBeVisible();
    await dialog.getByRole("button", { name: "Remove", exact: true }).click();
    await expect(section.getByText("Platform Google app", { exact: true })).toBeVisible();
    expect(calls.deletes).toBe(1);
  });

  test("invalid values are caught before saving", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, [account()]);
    const calls = await mockGoogleApp(page, googleApp());

    await page.goto("/tenant-admin/settings/video-providers");
    const section = page.getByRole("region", { name: "Google app" });
    await section.getByLabel("Client ID").fill("not-a-client");
    await section.getByRole("button", { name: "Use my Google app" }).click();

    await expect(section.getByText("A Google client ID ends in .apps.googleusercontent.com.")).toBeVisible();
    await expect(section.getByText("Enter the client secret.")).toBeVisible();
    expect(calls.puts).toEqual([]);
  });

  test("a read-only auditor sees which app is used but no form", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
    await mockGoogleApp(page, googleApp({ source: "TENANT", clientId: OWN_CLIENT, secretSaved: true }));

    await page.goto("/tenant-admin/settings/video-providers");
    const section = page.getByRole("region", { name: "Google app" });

    await expect(section.getByText(OWN_CLIENT)).toBeVisible();
    await expect(section.getByLabel("Client secret")).toHaveCount(0);
    await expect(section.getByText("Only a Tenant Admin can change the Google app.")).toBeVisible();
  });
});

test.describe("YouTube integrations (Phase 5d)", () => {
  test("adding an integration sends exactly the name, description and chosen teachers", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, []);
    const calls = await mockIntegrations(page, []);

    await page.goto("/tenant-admin/settings/video-providers");
    await expect(page.getByText("No YouTube integrations yet.")).toBeVisible();
    await page.getByRole("button", { name: "Add integration" }).click();

    const sheet = page.getByRole("dialog", { name: "Add YouTube integration" });
    await sheet.getByLabel("Name").fill("  AOC YT ");
    await sheet.getByLabel("Description (optional)").fill("Institute Youtube Channel");
    await sheet.getByLabel("Selected teachers only").check();
    await sheet.getByLabel(/Teacher Two/).check();
    await sheet.getByRole("button", { name: "Create integration" }).click();

    await expect(sheet).toBeHidden();
    expect(calls.posts).toEqual([
      { name: "AOC YT", description: "Institute Youtube Channel", allowedTeacherIds: ["user-t2"] },
    ]);
    const card = page.getByRole("region", { name: "AOC YT" });
    await expect(card.getByText("YouTube · 1 teacher")).toBeVisible();
    await expect(card.getByText("Institute Youtube Channel")).toBeVisible();
    await expect(card.getByRole("button", { name: "Connect YouTube" })).toBeVisible();
    await expect(page.getByText("0 of 1 YouTube integration connected")).toBeVisible();
  });

  test("a name is required and selected teachers need at least one", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, []);
    const calls = await mockIntegrations(page, []);

    await page.goto("/tenant-admin/settings/video-providers");
    await page.getByRole("button", { name: "Add integration" }).click();
    const sheet = page.getByRole("dialog", { name: "Add YouTube integration" });
    await sheet.getByLabel("Selected teachers only").check();
    await sheet.getByRole("button", { name: "Create integration" }).click();

    await expect(sheet.getByText("Enter a name.")).toBeVisible();
    await expect(sheet.getByText("Choose at least one teacher, or allow all teachers.")).toBeVisible();
    expect(calls.posts).toEqual([]);
  });

  test("editing a card sends the changes; deleting explains the videos are archived", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, []);
    const calls = await mockIntegrations(page, [
      account({ allTeachersAllowed: false, allowedTeachers: TEACHERS, status: "CONNECTED", connection: CHANNEL }),
      account({ integrationId: "int-2", name: "Science" }),
    ]);

    await page.goto("/tenant-admin/settings/video-providers");
    await expect(page.getByText("1 of 2 YouTube integrations connected")).toBeVisible();
    const card = page.getByRole("region", { name: "Institute YouTube" });
    await expect(card.getByText("YouTube · 2 teachers")).toBeVisible();

    await card.getByRole("button", { name: "Edit Institute YouTube" }).click();
    const sheet = page.getByRole("dialog", { name: "Edit YouTube integration" });
    await expect(sheet.getByLabel("Name")).toHaveValue("Institute YouTube");
    await sheet.getByLabel("All teachers").check();
    await sheet.getByRole("button", { name: "Save changes" }).click();
    await expect(sheet).toBeHidden();
    expect(calls.puts).toEqual([{ id: "int-1", name: "Institute YouTube", description: null, allowedTeacherIds: [] }]);
    await expect(card.getByText("YouTube · All teachers")).toBeVisible();

    await page.getByRole("region", { name: "Science" }).getByRole("button", { name: "Delete Science" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText(/the Media Library lists them under Archived/)).toBeVisible();
    await dialog.getByRole("button", { name: "Delete integration" }).click();
    await expect(page.getByRole("region", { name: "Science" })).toHaveCount(0);
    expect(calls.deletes).toEqual(["int-2"]);
  });

  test("the tenth integration is the last one", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockProviders(page, []);
    await mockIntegrations(
      page,
      Array.from({ length: 10 }, (_, i) => account({ integrationId: `int-${i + 1}`, name: `Channel ${i + 1}` }))
    );

    await page.goto("/tenant-admin/settings/video-providers");

    await expect(page.getByRole("button", { name: "Add integration" })).toBeDisabled();
    await expect(page.getByText("Up to 10 YouTube integrations per institute.")).toBeVisible();
  });

  test("a read-only auditor sees the cards without add, edit or delete", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockProviders(page, []);
    await mockIntegrations(page, [account({ status: "CONNECTED", connection: CHANNEL })]);

    await page.goto("/tenant-admin/settings/video-providers");

    await expect(page.getByRole("region", { name: "Institute YouTube" })).toBeVisible();
    await expect(page.getByRole("button", { name: /Add integration|Edit|Delete/ })).toHaveCount(0);
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
    await page.getByLabel("Student full name").check();
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
    expect(saved[0].watermarkFields).toEqual(["STUDENT_DISPLAY_ID", "TIMESTAMP", "STUDENT_FULL_NAME", "SESSION_ID"]);
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
