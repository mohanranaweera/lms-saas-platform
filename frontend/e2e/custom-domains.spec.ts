import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * TEN-DOM-01 / ADR-021 — tenant custom domains (frontend).
 *
 * Mock-only: DNS verification, host resolution, tenant isolation and the
 * Platform Admin switch are proven by the backend's
 * `CustomDomainIntegrationTest`. These tests prove the pages show the DNS
 * records to create, reflect every backend answer (never deciding anything
 * themselves), hide actions from read-only roles, and that API calls go to
 * `/api` on the site's own address.
 */

type Status = "PENDING_VERIFICATION" | "ACTIVE" | "DNS_BROKEN" | "SUSPENDED";

interface MockDomain {
  id: string;
  hostname: string;
  status: Status;
  primary: boolean;
  dnsRecords: { type: string; name: string; value: string | null }[];
  verifiedAt: string | null;
  lastCheckedAt: string | null;
  lastError: string | null;
  suspendedReason: string | null;
  createdAt: string;
}

const CODE = "0123456789abcdef0123456789abcdef";
const PATH = "**/v1/tenant-config/custom-domains";

function domain(hostname: string, overrides: Partial<MockDomain> = {}): MockDomain {
  return {
    id: `dom-${hostname}`,
    hostname,
    status: "PENDING_VERIFICATION",
    primary: false,
    dnsRecords: [
      { type: "CNAME", name: hostname, value: "tenants.lms.test" },
      { type: "TXT", name: `_lms-verify.${hostname}`, value: CODE },
    ],
    verifiedAt: null,
    lastCheckedAt: null,
    lastError: null,
    suspendedReason: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

async function mockSession(page: Page, role: string): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
}

/** A small in-memory backend for the Tenant Admin endpoints. */
async function mockTenantBackend(
  page: Page,
  initial: MockDomain[],
  options: { enabled?: boolean; available?: boolean } = {}
) {
  const state = { domains: [...initial], calls: [] as string[] };
  const overview = () => ({
    enabled: options.enabled ?? true,
    available: options.available ?? true,
    maxDomains: 3,
    domains: state.domains,
  });

  await page.route(PATH, async (route: Route) => {
    const request = route.request();
    if (request.method() === "GET") {
      await fulfillJson(route, 200, apiSuccess(overview()));
      return;
    }
    const body = request.postDataJSON() as { hostname: string };
    state.calls.push(`POST ${body.hostname}`);
    if (body.hostname === "taken.example.org") {
      await fulfillJson(route, 409, apiError("CUSTOM_DOMAIN_TAKEN", "This domain is already in use on the platform."));
      return;
    }
    const created = domain(body.hostname);
    state.domains.push(created);
    await fulfillJson(route, 201, apiSuccess(created));
  });

  await page.route(`${PATH}/*/*`, async (route: Route) => {
    const [, id, action] = new URL(route.request().url()).pathname.match(/custom-domains\/([^/]+)\/([^/]+)$/) ?? [];
    state.calls.push(`${action} ${id}`);
    const target = state.domains.find((item) => item.id === id);
    if (!target) {
      await fulfillJson(route, 404, apiError("NOT_FOUND", "Custom domain not found"));
      return;
    }
    if (action === "verify") {
      target.status = "ACTIVE";
      target.verifiedAt = new Date().toISOString();
      target.lastCheckedAt = target.verifiedAt;
    }
    if (action === "primary") {
      state.domains.forEach((item) => (item.primary = item.id === id));
    }
    await fulfillJson(route, 200, apiSuccess(target));
  });

  await page.route(`${PATH}/*`, async (route: Route) => {
    if (route.request().method() !== "DELETE") {
      await route.fallback();
      return;
    }
    const id = new URL(route.request().url()).pathname.split("/").pop() ?? "";
    state.calls.push(`DELETE ${id}`);
    state.domains = state.domains.filter((item) => item.id !== id);
    await fulfillJson(route, 200, apiSuccess(null));
  });

  return state;
}

test.describe("tenant admin — custom domain", () => {
  test("shows the DNS records to create and verifies the domain", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    const state = await mockTenantBackend(page, [domain("learn.abc.lk")]);

    await page.goto("/tenant-admin/settings/domains");

    const card = page.getByRole("article", { name: "learn.abc.lk" });
    await expect(card.getByText("Waiting for DNS")).toBeVisible();
    await expect(card.getByText("tenants.lms.test")).toBeVisible();
    await expect(card.getByText("_lms-verify.learn.abc.lk")).toBeVisible();
    await expect(card.getByText(CODE)).toBeVisible();
    await expect(card.getByRole("button", { name: "Copy TXT value" })).toBeVisible();

    await card.getByRole("button", { name: "Verify" }).click();

    await expect(card.getByText("learn.abc.lk is verified and serving your site.")).toBeVisible();
    await expect(card.getByText("Active", { exact: true })).toBeVisible();
    expect(state.calls).toContain("verify dom-learn.abc.lk");
  });

  test("checks the domain format before calling the backend and shows backend conflicts", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    const state = await mockTenantBackend(page, []);

    await page.goto("/tenant-admin/settings/domains");
    await expect(page.getByText("No custom domain yet")).toBeVisible();

    const input = page.getByLabel("Domain", { exact: true });
    await input.fill("https://learn.abc.lk/");
    await page.getByRole("button", { name: "Add domain" }).click();
    await expect(page.getByText(/no https:\/\/ or path/)).toBeVisible();
    expect(state.calls).toHaveLength(0);

    await input.fill("taken.example.org");
    await page.getByRole("button", { name: "Add domain" }).click();
    await expect(page.getByText("This domain is already in use on the platform.")).toBeVisible();

    await input.fill("  Learn.ABC.lk. ");
    await page.getByRole("button", { name: "Add domain" }).click();
    await expect(page.getByRole("article", { name: "learn.abc.lk" })).toBeVisible();
    expect(state.calls).toContain("POST learn.abc.lk");
  });

  test("makes an active domain primary and removes a domain after confirmation", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    const state = await mockTenantBackend(page, [
      domain("learn.abc.lk", { status: "ACTIVE", verifiedAt: new Date().toISOString() }),
      domain("old.abc.lk"),
    ]);

    await page.goto("/tenant-admin/settings/domains");

    const active = page.getByRole("article", { name: "learn.abc.lk" });
    await active.getByRole("button", { name: "Make primary" }).click();
    await expect(active.getByText("Primary", { exact: true })).toBeVisible();
    expect(state.calls).toContain("primary dom-learn.abc.lk");

    await page.getByRole("article", { name: "old.abc.lk" }).getByRole("button", { name: "Remove" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("Remove old.abc.lk?")).toBeVisible();
    await dialog.getByRole("button", { name: "Remove domain" }).click();

    await expect(page.getByRole("article", { name: "old.abc.lk" })).toHaveCount(0);
    expect(state.calls).toContain("DELETE dom-old.abc.lk");
  });

  test("explains when the platform has not switched custom domains on", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    await mockTenantBackend(page, [], { enabled: false });

    await page.goto("/tenant-admin/settings/domains");

    await expect(page.getByText(/aren.t enabled for your institute/)).toBeVisible();
    await expect(page.getByLabel("Domain", { exact: true })).toHaveCount(0);
  });

  test("a suspended domain shows the platform's reason and offers no actions", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    await mockTenantBackend(page, [
      domain("learn.abc.lk", { status: "SUSPENDED", suspendedReason: "Phishing report" }),
    ]);

    await page.goto("/tenant-admin/settings/domains");

    const card = page.getByRole("article", { name: "learn.abc.lk" });
    await expect(card.getByText("Suspended", { exact: true })).toBeVisible();
    await expect(card.getByText(/Phishing report/)).toBeVisible();
    await expect(card.getByRole("button")).toHaveCount(0);
  });

  test("a Read-only Auditor sees the domains without any actions", async ({ page }) => {
    await mockSession(page, "READ_ONLY_AUDITOR");
    await mockTenantBackend(page, [domain("learn.abc.lk")]);

    await page.goto("/tenant-admin/settings/domains");

    await expect(page.getByRole("article", { name: "learn.abc.lk" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Verify" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Remove" })).toHaveCount(0);
    await expect(page.getByLabel("Domain", { exact: true })).toHaveCount(0);
    await expect(page.getByText("Only a Tenant Admin can change custom domains.")).toBeVisible();
  });

  test("a 403 renders the permission-denied state", async ({ page }) => {
    await mockSession(page, "TEACHER");
    await mockJson(page, PATH, 403, apiError("FORBIDDEN", "Not allowed"));

    await page.goto("/tenant-admin/settings/domains");

    await expect(page.getByRole("heading", { name: "Custom domain" })).toBeVisible();
    await expect(page.getByLabel("Domain", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("article")).toHaveCount(0);
  });

  test("API calls go to /api on the site's own address", async ({ page }) => {
    await mockSession(page, "TENANT_ADMIN");
    await mockTenantBackend(page, []);
    const apiOrigins = new Set<string>();
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.pathname.includes("/v1/tenant-config/custom-domains")) {
        apiOrigins.add(`${url.origin}${url.pathname.split("/v1/")[0]}`);
      }
    });

    await page.goto("/tenant-admin/settings/domains");
    await expect(page.getByText("No custom domain yet")).toBeVisible();

    const pageOrigin = new URL(page.url()).origin;
    expect([...apiOrigins]).toEqual([`${pageOrigin}/api`]);
  });
});

test.describe("platform admin — tenant custom domains", () => {
  const TENANT = {
    id: "22222222-2222-2222-2222-222222222222",
    name: "ABC Institute",
    subdomain: "abc",
    status: "ACTIVE",
    requestedPlan: "STARTER",
    contactName: null,
    contactEmail: null,
    contactPhone: null,
    createdAt: new Date().toISOString(),
    customDomainsEnabled: false,
  };
  const BASE = `**/v1/platform-admin/tenants/${TENANT.id}`;

  async function mockPlatform(page: Page, enabled: boolean, domains: MockDomain[]) {
    await mockJson(page, "**/v1/platform-admin/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "PLATFORM_ADMIN" }))));
    await mockJson(page, BASE, 200, apiSuccess(TENANT));
    await mockJson(page, `${BASE}/payment-gateway`, 200, apiSuccess([]));
    const state = { enabled, domains, calls: [] as string[] };
    await page.route(`${BASE}/custom-domains`, async (route) => {
      await fulfillJson(route, 200, apiSuccess({ enabled: state.enabled, available: true, maxDomains: 3, domains: state.domains }));
    });
    await page.route(`${BASE}/custom-domains-enabled`, async (route) => {
      const body = route.request().postDataJSON() as { enabled: boolean };
      state.calls.push(`enabled=${body.enabled}`);
      state.enabled = body.enabled;
      await fulfillJson(route, 200, apiSuccess({ enabled: state.enabled, available: true, maxDomains: 3, domains: state.domains }));
    });
    await page.route(`${BASE}/custom-domains/*/suspend`, async (route) => {
      const body = route.request().postDataJSON() as { reason: string };
      state.calls.push(`suspend:${body.reason}`);
      state.domains = state.domains.map((item) => ({ ...item, status: "SUSPENDED" as Status, suspendedReason: body.reason }));
      await fulfillJson(route, 200, apiSuccess(state.domains[0]));
    });
    return state;
  }

  test("switches custom domains on for the named tenant", async ({ page }) => {
    const state = await mockPlatform(page, false, []);

    await page.goto(`/platform-admin/tenants/${TENANT.id}`);

    const card = page.getByRole("region", { name: "Custom domains" });
    await expect(card.getByText(/Custom domains are\s+off\s+for ABC Institute/)).toBeVisible();
    await card.getByRole("button", { name: "Switch on for ABC Institute" }).click();

    await expect(card.getByText(/Custom domains are\s+on\s+for ABC Institute/)).toBeVisible();
    expect(state.calls).toEqual(["enabled=true"]);
  });

  test("suspending a domain names the tenant and requires a reason", async ({ page }) => {
    const state = await mockPlatform(page, true, [domain("learn.abc.lk", { status: "ACTIVE" })]);

    await page.goto(`/platform-admin/tenants/${TENANT.id}`);
    const card = page.getByRole("region", { name: "Custom domains" });
    await card.getByRole("button", { name: "Suspend" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("Suspend learn.abc.lk for ABC Institute?")).toBeVisible();
    await dialog.getByRole("button", { name: "Suspend domain" }).click();
    await expect(dialog.getByText("A reason is required.")).toBeVisible();
    expect(state.calls).toHaveLength(0);

    await dialog.getByLabel("Reason (required)").fill("Phishing report");
    await dialog.getByRole("button", { name: "Suspend domain" }).click();

    await expect(card.getByText("Suspended: Phishing report")).toBeVisible();
    expect(state.calls).toEqual(["suspend:Phishing report"]);
  });
});
