import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * VIDEO Phase 5c — Platform Admin → Integrations → Google (YouTube) app
 * (frontend, mock-only).
 *
 * Platform-only access, encryption of the secret, the append-only history,
 * the env-var fallback and the Verify check against Google are proven by the
 * backend's `PlatformGoogleOAuthAppIntegrationTest`. These tests prove the
 * page reflects backend state, sends exactly what was typed, never shows a
 * secret, and makes replacing the app an explicit choice.
 */

const ENV_CLIENT = "111111111111-envclient.apps.googleusercontent.com";
const NEW_CLIENT = "222222222222-newclient.apps.googleusercontent.com";
const SECRET = "GOCSPX-e2e-only-secret";
const REDIRECT = "https://app.example.test/api/v1/integrations/oauth/youtube/callback";

function view(overrides: Record<string, unknown> = {}) {
  return {
    source: "ENVIRONMENT",
    clientId: ENV_CLIENT,
    secretSaved: false,
    environmentConfigured: true,
    redirectUri: REDIRECT,
    encryptionAvailable: true,
    updatedAt: null,
    history: [],
    ...overrides,
  };
}

interface Calls {
  puts: unknown[];
  deletes: number;
  verifies: number;
}

async function mockGoogleApp(
  page: Page,
  initial: Record<string, unknown>,
  options: { putReply?: { status: number; body: unknown }; verify?: Record<string, unknown> } = {}
): Promise<Calls> {
  await mockJson(
    page,
    "**/v1/platform-admin/auth/refresh",
    200,
    apiSuccess(refreshResponseBody(fakeJwt({ role: "PLATFORM_ADMIN" })))
  );
  const calls: Calls = { puts: [], deletes: 0, verifies: 0 };
  let current = initial;
  await page.route("**/v1/platform-admin/oauth-apps/google", async (route) => {
    const method = route.request().method();
    if (method === "PUT") {
      const body = route.request().postDataJSON() as { clientId: string };
      calls.puts.push(body);
      if (options.putReply) return fulfillJson(route, options.putReply.status, options.putReply.body);
      current = view({
        source: "SAVED",
        clientId: body.clientId,
        secretSaved: true,
        updatedAt: "2026-10-05T08:00:00Z",
        history: [
          { action: "SAVED", clientIdBefore: null, clientIdAfter: "222222…ontent.com", secretChanged: true, occurredAt: "2026-10-05T08:00:00Z" },
        ],
      });
      return fulfillJson(route, 200, apiSuccess(current));
    }
    if (method === "DELETE") {
      calls.deletes += 1;
      current = view();
      return fulfillJson(route, 200, apiSuccess(current));
    }
    return fulfillJson(route, 200, apiSuccess(current));
  });
  await page.route("**/v1/platform-admin/oauth-apps/google/verify", async (route) => {
    calls.verifies += 1;
    await fulfillJson(
      route,
      200,
      apiSuccess(options.verify ?? { result: "VALID", source: "ENVIRONMENT", message: "Google accepted this client id and secret." })
    );
  });
  return calls;
}

test.describe("Platform Admin Google OAuth app", () => {
  test("shows the environment app, the redirect URI to register and verifies it with Google", async ({ page }) => {
    const calls = await mockGoogleApp(page, view());

    await page.goto("/platform-admin/integrations");

    const card = page.getByRole("region", { name: "Google (YouTube) app" });
    await expect(card.getByText("From environment variables", { exact: true })).toBeVisible();
    await expect(card.getByText(ENV_CLIENT).first()).toBeVisible();
    await expect(card.getByText(REDIRECT)).toBeVisible();
    await expect(card.getByLabel("Client ID")).toHaveValue(ENV_CLIENT);
    await expect(card.getByLabel("Client secret")).toHaveValue("");

    await card.getByRole("button", { name: "Verify with Google" }).click();
    await expect(card.getByText("Google accepted this client id and secret.")).toBeVisible();
    expect(calls.verifies).toBe(1);
  });

  test("replacing the app needs an explicit acknowledgement and sends exactly what was typed", async ({ page }) => {
    const calls = await mockGoogleApp(page, view());

    await page.goto("/platform-admin/integrations");
    const card = page.getByRole("region", { name: "Google (YouTube) app" });
    await card.getByLabel("Client ID").fill(NEW_CLIENT);
    await card.getByLabel("Client secret").fill(SECRET);

    await expect(card.getByText(/Every institute's connected YouTube channel stops working/)).toBeVisible();
    const saveButton = card.getByRole("button", { name: "Save", exact: true });
    await expect(saveButton).toBeDisabled();
    await card.getByLabel("I understand every institute must reconnect").check();
    await saveButton.click();

    await expect(card.getByText("Google app saved. Use Verify to check it with Google.")).toBeVisible();
    expect(calls.puts).toEqual([{ clientId: NEW_CLIENT, clientSecret: SECRET }]);
    await expect(card.getByText("Saved on this screen", { exact: true })).toBeVisible();
    await expect(card.getByText("Saved (hidden)")).toBeVisible();
    await expect(card.getByLabel("Client secret")).toHaveValue("");
    await expect(page.locator("body")).not.toContainText(SECRET);
    await expect(card.getByText(/Saved 222222…ontent\.com \(new secret\)/)).toBeVisible();
  });

  test("invalid values are caught before saving and server field errors are shown", async ({ page }) => {
    const calls = await mockGoogleApp(page, view({ source: "NOT_CONFIGURED", clientId: null, environmentConfigured: false }), {
      putReply: {
        status: 400,
        body: apiError("VALIDATION_ERROR", "Invalid Google OAuth app", [
          { field: "clientSecret", message: "is required (at most 255 characters, no spaces)" },
        ]),
      },
    });

    await page.goto("/platform-admin/integrations");
    const card = page.getByRole("region", { name: "Google (YouTube) app" });
    await expect(card.getByText("Not configured", { exact: true })).toBeVisible();
    await expect(card.getByRole("button", { name: "Verify with Google" })).toHaveCount(0);

    await card.getByLabel("Client ID").fill("my-client");
    await card.getByLabel("Client secret").fill("has spaces");
    await card.getByRole("button", { name: "Save", exact: true }).click();
    await expect(card.getByText("A Google client ID ends in .apps.googleusercontent.com.")).toBeVisible();
    await expect(card.getByText("The client secret can't contain spaces.")).toBeVisible();
    expect(calls.puts).toEqual([]);

    await card.getByLabel("Client ID").fill(NEW_CLIENT);
    await card.getByLabel("Client secret").fill("server-rejects-this");
    await card.getByRole("button", { name: "Save", exact: true }).click();
    await expect(card.getByText("is required (at most 255 characters, no spaces)")).toBeVisible();
  });

  test("a rejected app is announced and the saved app can be removed", async ({ page }) => {
    const calls = await mockGoogleApp(
      page,
      view({ source: "SAVED", clientId: NEW_CLIENT, secretSaved: true, updatedAt: "2026-10-05T08:00:00Z" }),
      { verify: { result: "REJECTED", source: "SAVED", message: "Google rejected this client id or secret." } }
    );

    await page.goto("/platform-admin/integrations");
    const card = page.getByRole("region", { name: "Google (YouTube) app" });
    await card.getByRole("button", { name: "Verify with Google" }).click();
    await expect(card.getByRole("alert").filter({ hasText: "Google rejected this client id or secret." })).toBeVisible();

    await card.getByRole("button", { name: "Remove saved app" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText(/environment variables will be used again/)).toBeVisible();
    await dialog.getByRole("button", { name: "Remove", exact: true }).click();

    await expect(card.getByText("From environment variables", { exact: true })).toBeVisible();
    expect(calls.deletes).toBe(1);
  });

  test("without the encryption key nothing can be saved", async ({ page }) => {
    await mockGoogleApp(page, view({ encryptionAvailable: false }));

    await page.goto("/platform-admin/integrations");
    const card = page.getByRole("region", { name: "Google (YouTube) app" });

    await expect(card.getByText(/credential encryption key/)).toBeVisible();
    await expect(card.getByLabel("Client ID")).toBeDisabled();
    await expect(card.getByRole("button", { name: "Save", exact: true })).toBeDisabled();
  });

  test("a 403 shows the permission-denied state", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/platform-admin/auth/refresh",
      200,
      apiSuccess(refreshResponseBody(fakeJwt({ role: "PLATFORM_ADMIN" })))
    );
    await mockJson(page, "**/v1/platform-admin/oauth-apps/google", 403, apiError("FORBIDDEN", "Forbidden"));

    await page.goto("/platform-admin/integrations");

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });
});
