# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: frontend\e2e\tenant-admin-video-settings.spec.ts >> Tenant Admin video providers >> a connected channel shows its details and never a token
- Location: frontend\e2e\tenant-admin-video-settings.spec.ts:155:7

# Error details

```
Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
Call log:
  - navigating to "/tenant-admin/settings/video-providers", waiting until "load"

```

# Test source

```ts
  59  |   watchTimeMultiplier: null,
  60  |   watchTimeFixedMinutes: null,
  61  |   launchCodeTtlSeconds: 45,
  62  |   heartbeatIntervalSeconds: 20,
  63  | };
  64  | 
  65  | const TEACHERS = [
  66  |   { userId: "user-t1", name: "Teacher One", email: "one@example.test" },
  67  |   { userId: "user-t2", name: "Teacher Two", email: "two@example.test" },
  68  | ];
  69  | 
  70  | const REDIRECT = "https://app.example.test/api/v1/integrations/oauth/youtube/callback";
  71  | const OWN_CLIENT = "444444444444-institute.apps.googleusercontent.com";
  72  | const OWN_SECRET = "GOCSPX-e2e-institute-secret";
  73  | 
  74  | function googleApp(overrides: Record<string, unknown> = {}) {
  75  |   return {
  76  |     source: "PLATFORM",
  77  |     clientId: null,
  78  |     secretSaved: false,
  79  |     platformAppAvailable: true,
  80  |     redirectUri: REDIRECT,
  81  |     encryptionAvailable: true,
  82  |     updatedAt: null,
  83  |     channelConnected: false,
  84  |     ...overrides,
  85  |   };
  86  | }
  87  | 
  88  | async function mockProviders(page: Page, accounts: unknown[]): Promise<void> {
  89  |   await mockJson(page, "**/v1/tenant-admin/video-providers", 200, apiSuccess(accounts));
  90  |   await mockJson(page, "**/v1/tenant-admin/video-providers/youtube/teachers", 200, apiSuccess(TEACHERS));
  91  |   await mockJson(page, "**/v1/tenant-admin/video-providers/youtube/oauth-app", 200, apiSuccess(googleApp()));
  92  | }
  93  | 
  94  | /** The institute's Google app: GET returns `initial`; PUT / DELETE are recorded and answered like the backend. */
  95  | async function mockGoogleApp(page: Page, initial: Record<string, unknown>) {
  96  |   const calls = { puts: [] as unknown[], deletes: 0 };
  97  |   let current = initial;
  98  |   await page.route("**/v1/tenant-admin/video-providers/youtube/oauth-app", async (route) => {
  99  |     const method = route.request().method();
  100 |     if (method === "PUT") {
  101 |       const body = route.request().postDataJSON() as { clientId: string };
  102 |       calls.puts.push(body);
  103 |       current = { ...current, source: "TENANT", clientId: body.clientId, secretSaved: true, updatedAt: "2026-10-05T09:00:00Z" };
  104 |     } else if (method === "DELETE") {
  105 |       calls.deletes += 1;
  106 |       current = { ...current, source: "PLATFORM", clientId: null, secretSaved: false, updatedAt: null };
  107 |     }
  108 |     await fulfillJson(route, 200, apiSuccess(current));
  109 |   });
  110 |   return calls;
  111 | }
  112 | 
  113 | /** The integration cards: GET returns the current list; POST / PUT / DELETE are recorded and applied. */
  114 | async function mockIntegrations(page: Page, initial: Record<string, unknown>[]) {
  115 |   const calls = { posts: [] as unknown[], puts: [] as unknown[], deletes: [] as string[] };
  116 |   let cards = initial;
  117 |   await page.route("**/v1/tenant-admin/video-providers", async (route) => {
  118 |     await fulfillJson(route, 200, apiSuccess(cards));
  119 |   });
  120 |   await page.route("**/v1/tenant-admin/video-providers/youtube/integrations", async (route) => {
  121 |     const body = route.request().postDataJSON() as { name: string; description: string | null; allowedTeacherIds: string[] };
  122 |     calls.posts.push(body);
  123 |     const allowed = TEACHERS.filter((t) => body.allowedTeacherIds.includes(t.userId));
  124 |     const card = account({
  125 |       integrationId: `int-${cards.length + 1}`,
  126 |       name: body.name,
  127 |       description: body.description,
  128 |       allTeachersAllowed: allowed.length === 0,
  129 |       allowedTeachers: allowed,
  130 |     });
  131 |     cards = [...cards, card];
  132 |     await fulfillJson(route, 201, apiSuccess(card));
  133 |   });
  134 |   await page.route("**/v1/tenant-admin/video-providers/youtube/integrations/*", async (route) => {
  135 |     const id = new URL(route.request().url()).pathname.split("/").pop() ?? "";
  136 |     if (route.request().method() === "DELETE") {
  137 |       calls.deletes.push(id);
  138 |       cards = cards.filter((card) => card.integrationId !== id);
  139 |       return fulfillJson(route, 200, apiSuccess(null));
  140 |     }
  141 |     const body = route.request().postDataJSON() as { name: string; description: string | null; allowedTeacherIds: string[] };
  142 |     calls.puts.push({ id, ...body });
  143 |     const allowed = TEACHERS.filter((t) => body.allowedTeacherIds.includes(t.userId));
  144 |     cards = cards.map((card) =>
  145 |       card.integrationId === id
  146 |         ? { ...card, name: body.name, description: body.description, allTeachersAllowed: allowed.length === 0, allowedTeachers: allowed }
  147 |         : card
  148 |     );
  149 |     return fulfillJson(route, 200, apiSuccess(cards.find((card) => card.integrationId === id)));
  150 |   });
  151 |   return calls;
  152 | }
  153 | 
  154 | test.describe("Tenant Admin video providers", () => {
  155 |   test("a connected channel shows its details and never a token", async ({ page }) => {
  156 |     await mockTenantSession(page, "TENANT_ADMIN", "admin-1");
  157 |     await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL, connectedByUserId: "admin-1" })]);
  158 | 
> 159 |     await page.goto("/tenant-admin/settings/video-providers");
      |                ^ Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
  160 | 
  161 |     const card = page.getByRole("region", { name: "YouTube" });
  162 |     await expect(card.getByText("Connected", { exact: true })).toBeVisible();
  163 |     await expect(card.getByText("Institute Channel")).toBeVisible();
  164 |     await expect(card.getByText("****AbCd")).toBeVisible();
  165 |     await expect(card.getByText("You", { exact: true })).toBeVisible();
  166 |     await expect(card.getByRole("button", { name: "Reconnect YouTube" })).toBeVisible();
  167 |     await expect(page.locator("body")).not.toContainText("google-at-");
  168 |   });
  169 | 
  170 |   test("connecting sends the admin to Google's consent page", async ({ page }) => {
  171 |     await mockTenantSession(page, "TENANT_ADMIN");
  172 |     await mockProviders(page, [account()]);
  173 |     await mockJson(
  174 |       page,
  175 |       "**/v1/tenant-admin/video-providers/youtube/integrations/int-1/connect",
  176 |       200,
  177 |       apiSuccess({ authorizationUrl: "https://accounts.google.test/o/oauth2/v2/auth?state=abc" })
  178 |     );
  179 |     await page.route("https://accounts.google.test/**", (route) =>
  180 |       route.fulfill({ status: 200, contentType: "text/html", body: "<h1>Google consent</h1>" })
  181 |     );
  182 | 
  183 |     await page.goto("/tenant-admin/settings/video-providers");
  184 |     await page.getByRole("button", { name: "Connect YouTube" }).click();
  185 | 
  186 |     await expect(page).toHaveURL(/accounts\.google\.test/);
  187 |   });
  188 | 
  189 |   test("after Google returns, the channel is shown and saved only on an explicit confirm", async ({ page }) => {
  190 |     await mockTenantSession(page, "TENANT_ADMIN");
  191 |     await mockProviders(page, [account()]);
  192 |     await mockJson(
  193 |       page,
  194 |       "**/v1/tenant-admin/video-providers/youtube/pending/pending-1",
  195 |       200,
  196 |       apiSuccess({ integrationId: "int-1", integrationName: "Institute YouTube", channelId: "UC-new", channelTitle: "New Channel", replacesCurrentChannel: false, currentChannelTitle: null })
  197 |     );
  198 |     const confirmBodies: unknown[] = [];
  199 |     await page.route("**/v1/tenant-admin/video-providers/youtube/pending/pending-1/confirm", async (route) => {
  200 |       confirmBodies.push(route.request().postDataJSON());
  201 |       await fulfillJson(
  202 |         route,
  203 |         200,
  204 |         apiSuccess(account({ status: "CONNECTED", connection: { ...CHANNEL, accountLabel: "New Channel" } }))
  205 |       );
  206 |     });
  207 | 
  208 |     await page.goto("/tenant-admin/settings/video-providers?pending=pending-1&provider=youtube");
  209 | 
  210 |     const panel = page.getByRole("region", { name: /Connect this YouTube channel/ });
  211 |     await expect(panel.getByText("UC-new")).toBeVisible();
  212 |     await expect(page).toHaveURL(/\/tenant-admin\/settings\/video-providers$/);
  213 |     expect(confirmBodies).toHaveLength(0);
  214 | 
  215 |     await panel.getByRole("button", { name: "Connect this channel" }).click();
  216 | 
  217 |     await expect(page.getByText("New Channel is now connected.")).toBeVisible();
  218 |     expect(confirmBodies).toEqual([{ replaceChannel: false }]);
  219 |   });
  220 | 
  221 |   test("replacing a different channel is spelled out and sends replaceChannel", async ({ page }) => {
  222 |     await mockTenantSession(page, "TENANT_ADMIN");
  223 |     await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
  224 |     await mockJson(
  225 |       page,
  226 |       "**/v1/tenant-admin/video-providers/youtube/pending/pending-2",
  227 |       200,
  228 |       apiSuccess({
  229 |         channelId: "UC-other",
  230 |         channelTitle: "Other Channel",
  231 |         replacesCurrentChannel: true,
  232 |         currentChannelTitle: "Institute Channel",
  233 |       })
  234 |     );
  235 |     const confirmBodies: unknown[] = [];
  236 |     await page.route("**/v1/tenant-admin/video-providers/youtube/pending/pending-2/confirm", async (route) => {
  237 |       confirmBodies.push(route.request().postDataJSON());
  238 |       await fulfillJson(route, 200, apiSuccess(account({ status: "CONNECTED", connection: CHANNEL })));
  239 |     });
  240 | 
  241 |     await page.goto("/tenant-admin/settings/video-providers?pending=pending-2&provider=youtube");
  242 | 
  243 |     await expect(page.getByText(/This replaces your current channel, Institute Channel/)).toBeVisible();
  244 |     await page.getByRole("button", { name: "Replace with Other Channel" }).click();
  245 |     await expect.poll(() => confirmBodies).toEqual([{ replaceChannel: true }]);
  246 |   });
  247 | 
  248 |   test("a channel already used by another institute shows the backend's reason", async ({ page }) => {
  249 |     await mockTenantSession(page, "TENANT_ADMIN");
  250 |     await mockProviders(page, [account()]);
  251 |     await mockJson(
  252 |       page,
  253 |       "**/v1/tenant-admin/video-providers/youtube/pending/pending-3",
  254 |       200,
  255 |       apiSuccess({ channelId: "UC-x", channelTitle: "X", replacesCurrentChannel: false, currentChannelTitle: null })
  256 |     );
  257 |     await mockJson(
  258 |       page,
  259 |       "**/v1/tenant-admin/video-providers/youtube/pending/pending-3/confirm",
```