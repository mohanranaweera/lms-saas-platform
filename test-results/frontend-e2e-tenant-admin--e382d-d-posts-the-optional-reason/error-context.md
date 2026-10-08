# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: frontend\e2e\tenant-admin-video-settings.spec.ts >> Tenant Admin video providers >> disconnecting asks first and posts the optional reason
- Location: frontend\e2e\tenant-admin-video-settings.spec.ts:288:7

# Error details

```
Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
Call log:
  - navigating to "/tenant-admin/settings/video-providers", waiting until "load"

```

# Test source

```ts
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
> 297 |     await page.goto("/tenant-admin/settings/video-providers");
      |                ^ Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
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
  342 |   test("with a connected channel, changing the app must be acknowledged; removing returns to the platform app", async ({
  343 |     page,
  344 |   }) => {
  345 |     await mockTenantSession(page, "TENANT_ADMIN");
  346 |     await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
  347 |     const calls = await mockGoogleApp(page, googleApp({ channelConnected: true }));
  348 | 
  349 |     await page.goto("/tenant-admin/settings/video-providers");
  350 |     const section = page.getByRole("region", { name: "Google app" });
  351 |     await section.getByLabel("Client ID").fill(OWN_CLIENT);
  352 |     await section.getByLabel("Client secret").fill(OWN_SECRET);
  353 |     const save = section.getByRole("button", { name: "Use my Google app" });
  354 |     await expect(section.getByText(/connected through a different Google app/)).toBeVisible();
  355 |     await expect(save).toBeDisabled();
  356 |     await section.getByLabel("I understand the channel must be connected again").check();
  357 |     await save.click();
  358 |     await expect(section.getByText("Your institute's Google app", { exact: true })).toBeVisible();
  359 |     expect(calls.puts).toHaveLength(1);
  360 | 
  361 |     await section.getByRole("button", { name: "Remove my Google app" }).click();
  362 |     const dialog = page.getByRole("alertdialog");
  363 |     await expect(dialog.getByText(/use the platform's Google app again/)).toBeVisible();
  364 |     await dialog.getByRole("button", { name: "Remove", exact: true }).click();
  365 |     await expect(section.getByText("Platform Google app", { exact: true })).toBeVisible();
  366 |     expect(calls.deletes).toBe(1);
  367 |   });
  368 | 
  369 |   test("invalid values are caught before saving", async ({ page }) => {
  370 |     await mockTenantSession(page, "TENANT_ADMIN");
  371 |     await mockProviders(page, [account()]);
  372 |     const calls = await mockGoogleApp(page, googleApp());
  373 | 
  374 |     await page.goto("/tenant-admin/settings/video-providers");
  375 |     const section = page.getByRole("region", { name: "Google app" });
  376 |     await section.getByLabel("Client ID").fill("not-a-client");
  377 |     await section.getByRole("button", { name: "Use my Google app" }).click();
  378 | 
  379 |     await expect(section.getByText("A Google client ID ends in .apps.googleusercontent.com.")).toBeVisible();
  380 |     await expect(section.getByText("Enter the client secret.")).toBeVisible();
  381 |     expect(calls.puts).toEqual([]);
  382 |   });
  383 | 
  384 |   test("a read-only auditor sees which app is used but no form", async ({ page }) => {
  385 |     await mockTenantSession(page, "READ_ONLY_AUDITOR");
  386 |     await mockProviders(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
  387 |     await mockGoogleApp(page, googleApp({ source: "TENANT", clientId: OWN_CLIENT, secretSaved: true }));
  388 | 
  389 |     await page.goto("/tenant-admin/settings/video-providers");
  390 |     const section = page.getByRole("region", { name: "Google app" });
  391 | 
  392 |     await expect(section.getByText(OWN_CLIENT)).toBeVisible();
  393 |     await expect(section.getByLabel("Client secret")).toHaveCount(0);
  394 |     await expect(section.getByText("Only a Tenant Admin can change the Google app.")).toBeVisible();
  395 |   });
  396 | });
  397 | 
```