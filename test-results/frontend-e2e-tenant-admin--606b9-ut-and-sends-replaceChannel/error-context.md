# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: frontend\e2e\tenant-admin-video-settings.spec.ts >> Tenant Admin video providers >> replacing a different channel is spelled out and sends replaceChannel
- Location: frontend\e2e\tenant-admin-video-settings.spec.ts:221:7

# Error details

```
Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
Call log:
  - navigating to "/tenant-admin/settings/video-providers?pending=pending-2&provider=youtube", waiting until "load"

```

# Test source

```ts
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
  159 |     await page.goto("/tenant-admin/settings/video-providers");
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
> 241 |     await page.goto("/tenant-admin/settings/video-providers?pending=pending-2&provider=youtube");
      |                ^ Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
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
  260 |       409,
  261 |       apiError("VIDEO_PROVIDER_CHANNEL_IN_USE", "This YouTube channel is already connected to another institute.")
  262 |     );
  263 | 
  264 |     await page.goto("/tenant-admin/settings/video-providers?pending=pending-3&provider=youtube");
  265 |     await page.getByRole("button", { name: "Connect this channel" }).click();
  266 | 
  267 |     await expect(page.getByText("This YouTube channel is already connected to another institute.")).toBeVisible();
  268 |   });
  269 | 
  270 |   test("an expired request and a missing upload permission explain what to do", async ({ page }) => {
  271 |     await mockTenantSession(page, "TENANT_ADMIN");
  272 |     await mockProviders(page, [account()]);
  273 |     await mockJson(
  274 |       page,
  275 |       "**/v1/tenant-admin/video-providers/youtube/pending/stale",
  276 |       404,
  277 |       apiError("NOT_FOUND", "This connection request has expired or was already used. Connect again.")
  278 |     );
  279 | 
  280 |     await page.goto("/tenant-admin/settings/video-providers?pending=stale&provider=youtube");
  281 |     await expect(page.getByText(/This connection request has expired or was already used/)).toBeVisible();
  282 |     await expect(page.getByRole("button", { name: "Connect this channel" })).toHaveCount(0);
  283 | 
  284 |     await page.goto("/tenant-admin/settings/video-providers?error=missing_scopes&provider=youtube");
  285 |     await expect(page.getByText(/upload permission wasn't granted/)).toBeVisible();
  286 |   });
  287 | 
  288 |   test("disconnecting asks first and posts the optional reason", async ({ page }) => {
  289 |     await mockTenantSession(page, "TENANT_ADMIN");
  290 |     await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
  291 |     const bodies: unknown[] = [];
  292 |     await page.route("**/v1/tenant-admin/video-providers/conn-yt/disconnect", async (route) => {
  293 |       bodies.push(route.request().postDataJSON());
  294 |       await fulfillJson(route, 200, apiSuccess({ ...CHANNEL, status: "DISCONNECTED" }));
  295 |     });
  296 | 
  297 |     await page.goto("/tenant-admin/settings/video-providers");
  298 |     await page.getByRole("button", { name: "Disconnect", exact: true }).click();
  299 |     const dialog = page.getByRole("alertdialog");
  300 |     await expect(dialog.getByText("Disconnect your YouTube channel?")).toBeVisible();
  301 |     await dialog.getByLabel("Reason (optional)").fill("Moving to a new channel");
  302 |     await dialog.getByRole("button", { name: "Disconnect YouTube" }).click();
  303 | 
  304 |     await expect.poll(() => bodies).toEqual([{ reason: "Moving to a new channel" }]);
  305 |   });
  306 | 
  307 |   test("a read-only auditor sees the status without any controls", async ({ page }) => {
  308 |     await mockTenantSession(page, "READ_ONLY_AUDITOR");
  309 |     await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
  310 | 
  311 |     await page.goto("/tenant-admin/settings/video-providers");
  312 | 
  313 |     await expect(page.getByText("Institute Channel")).toBeVisible();
  314 |     await expect(page.getByRole("button", { name: /Connect|Reconnect|Disconnect|Test/ })).toHaveCount(0);
  315 |     await expect(page.getByText("Only a Tenant Admin can change video providers.")).toBeVisible();
  316 |   });
  317 | });
  318 | 
  319 | test.describe("Institute Google app (Phase 5c)", () => {
  320 |   test("by default the platform app is used; adding the institute's own sends exactly what was typed", async ({ page }) => {
  321 |     await mockTenantSession(page, "TENANT_ADMIN");
  322 |     await mockProviders(page, [account()]);
  323 |     const calls = await mockGoogleApp(page, googleApp());
  324 | 
  325 |     await page.goto("/tenant-admin/settings/video-providers");
  326 | 
  327 |     const section = page.getByRole("region", { name: "Google app" });
  328 |     await expect(section.getByText("Platform Google app", { exact: true })).toBeVisible();
  329 |     await expect(section.getByText(REDIRECT)).toBeVisible();
  330 |     await section.getByLabel("Client ID").fill(OWN_CLIENT);
  331 |     await section.getByLabel("Client secret").fill(OWN_SECRET);
  332 |     await section.getByRole("button", { name: "Use my Google app" }).click();
  333 | 
  334 |     await expect(section.getByText("Google app saved. Use Verify to check it with Google.")).toBeVisible();
  335 |     expect(calls.puts).toEqual([{ clientId: OWN_CLIENT, clientSecret: OWN_SECRET }]);
  336 |     await expect(section.getByText("Your institute's Google app", { exact: true })).toBeVisible();
  337 |     await expect(section.getByText("Saved (hidden)")).toBeVisible();
  338 |     await expect(section.getByLabel("Client secret")).toHaveValue("");
  339 |     await expect(page.locator("body")).not.toContainText(OWN_SECRET);
  340 |   });
  341 | 
```