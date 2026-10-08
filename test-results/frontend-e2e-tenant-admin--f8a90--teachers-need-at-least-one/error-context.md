# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: frontend\e2e\tenant-admin-video-settings.spec.ts >> YouTube integrations (Phase 5d) >> a name is required and selected teachers need at least one
- Location: frontend\e2e\tenant-admin-video-settings.spec.ts:426:7

# Error details

```
Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
Call log:
  - navigating to "/tenant-admin/settings/video-providers", waiting until "load"

```

# Test source

```ts
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
  398 | test.describe("YouTube integrations (Phase 5d)", () => {
  399 |   test("adding an integration sends exactly the name, description and chosen teachers", async ({ page }) => {
  400 |     await mockTenantSession(page, "TENANT_ADMIN");
  401 |     await mockProviders(page, []);
  402 |     const calls = await mockIntegrations(page, []);
  403 | 
  404 |     await page.goto("/tenant-admin/settings/video-providers");
  405 |     await expect(page.getByText("No YouTube integrations yet.")).toBeVisible();
  406 |     await page.getByRole("button", { name: "Add integration" }).click();
  407 | 
  408 |     const sheet = page.getByRole("dialog", { name: "Add YouTube integration" });
  409 |     await sheet.getByLabel("Name").fill("  AOC YT ");
  410 |     await sheet.getByLabel("Description (optional)").fill("Institute Youtube Channel");
  411 |     await sheet.getByLabel("Selected teachers only").check();
  412 |     await sheet.getByLabel(/Teacher Two/).check();
  413 |     await sheet.getByRole("button", { name: "Create integration" }).click();
  414 | 
  415 |     await expect(sheet).toBeHidden();
  416 |     expect(calls.posts).toEqual([
  417 |       { name: "AOC YT", description: "Institute Youtube Channel", allowedTeacherIds: ["user-t2"] },
  418 |     ]);
  419 |     const card = page.getByRole("region", { name: "AOC YT" });
  420 |     await expect(card.getByText("YouTube · 1 teacher")).toBeVisible();
  421 |     await expect(card.getByText("Institute Youtube Channel")).toBeVisible();
  422 |     await expect(card.getByRole("button", { name: "Connect YouTube" })).toBeVisible();
  423 |     await expect(page.getByText("0 of 1 YouTube integration connected")).toBeVisible();
  424 |   });
  425 | 
  426 |   test("a name is required and selected teachers need at least one", async ({ page }) => {
  427 |     await mockTenantSession(page, "TENANT_ADMIN");
  428 |     await mockProviders(page, []);
  429 |     const calls = await mockIntegrations(page, []);
  430 | 
> 431 |     await page.goto("/tenant-admin/settings/video-providers");
      |                ^ Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
  432 |     await page.getByRole("button", { name: "Add integration" }).click();
  433 |     const sheet = page.getByRole("dialog", { name: "Add YouTube integration" });
  434 |     await sheet.getByLabel("Selected teachers only").check();
  435 |     await sheet.getByRole("button", { name: "Create integration" }).click();
  436 | 
  437 |     await expect(sheet.getByText("Enter a name.")).toBeVisible();
  438 |     await expect(sheet.getByText("Choose at least one teacher, or allow all teachers.")).toBeVisible();
  439 |     expect(calls.posts).toEqual([]);
  440 |   });
  441 | 
  442 |   test("editing a card sends the changes; deleting explains the videos are archived", async ({ page }) => {
  443 |     await mockTenantSession(page, "TENANT_ADMIN");
  444 |     await mockProviders(page, []);
  445 |     const calls = await mockIntegrations(page, [
  446 |       account({ allTeachersAllowed: false, allowedTeachers: TEACHERS, status: "CONNECTED", connection: CHANNEL }),
  447 |       account({ integrationId: "int-2", name: "Science" }),
  448 |     ]);
  449 | 
  450 |     await page.goto("/tenant-admin/settings/video-providers");
  451 |     await expect(page.getByText("1 of 2 YouTube integrations connected")).toBeVisible();
  452 |     const card = page.getByRole("region", { name: "Institute YouTube" });
  453 |     await expect(card.getByText("YouTube · 2 teachers")).toBeVisible();
  454 | 
  455 |     await card.getByRole("button", { name: "Edit Institute YouTube" }).click();
  456 |     const sheet = page.getByRole("dialog", { name: "Edit YouTube integration" });
  457 |     await expect(sheet.getByLabel("Name")).toHaveValue("Institute YouTube");
  458 |     await sheet.getByLabel("All teachers").check();
  459 |     await sheet.getByRole("button", { name: "Save changes" }).click();
  460 |     await expect(sheet).toBeHidden();
  461 |     expect(calls.puts).toEqual([{ id: "int-1", name: "Institute YouTube", description: null, allowedTeacherIds: [] }]);
  462 |     await expect(card.getByText("YouTube · All teachers")).toBeVisible();
  463 | 
  464 |     await page.getByRole("region", { name: "Science" }).getByRole("button", { name: "Delete Science" }).click();
  465 |     const dialog = page.getByRole("alertdialog");
  466 |     await expect(dialog.getByText(/the Media Library lists them under Archived/)).toBeVisible();
  467 |     await dialog.getByRole("button", { name: "Delete integration" }).click();
  468 |     await expect(page.getByRole("region", { name: "Science" })).toHaveCount(0);
  469 |     expect(calls.deletes).toEqual(["int-2"]);
  470 |   });
  471 | 
  472 |   test("the tenth integration is the last one", async ({ page }) => {
  473 |     await mockTenantSession(page, "TENANT_ADMIN");
  474 |     await mockProviders(page, []);
  475 |     await mockIntegrations(
  476 |       page,
  477 |       Array.from({ length: 10 }, (_, i) => account({ integrationId: `int-${i + 1}`, name: `Channel ${i + 1}` }))
  478 |     );
  479 | 
  480 |     await page.goto("/tenant-admin/settings/video-providers");
  481 | 
  482 |     await expect(page.getByRole("button", { name: "Add integration" })).toBeDisabled();
  483 |     await expect(page.getByText("Up to 10 YouTube integrations per institute.")).toBeVisible();
  484 |   });
  485 | 
  486 |   test("a read-only auditor sees the cards without add, edit or delete", async ({ page }) => {
  487 |     await mockTenantSession(page, "READ_ONLY_AUDITOR");
  488 |     await mockProviders(page, []);
  489 |     await mockIntegrations(page, [account({ status: "CONNECTED", connection: CHANNEL })]);
  490 | 
  491 |     await page.goto("/tenant-admin/settings/video-providers");
  492 | 
  493 |     await expect(page.getByRole("region", { name: "Institute YouTube" })).toBeVisible();
  494 |     await expect(page.getByRole("button", { name: /Add integration|Edit|Delete/ })).toHaveCount(0);
  495 |   });
  496 | });
  497 | 
  498 | test.describe("Tenant Admin video settings", () => {
  499 |   test("shows the platform defaults and saves the chosen settings", async ({ page }) => {
  500 |     await mockTenantSession(page, "TENANT_ADMIN");
  501 |     const saved: Record<string, unknown>[] = [];
  502 |     await page.route("**/v1/tenant-admin/video-configuration", async (route) => {
  503 |       if (route.request().method() === "PUT") {
  504 |         const body = route.request().postDataJSON() as Record<string, unknown>;
  505 |         saved.push(body);
  506 |         await fulfillJson(
  507 |           route,
  508 |           200,
  509 |           apiSuccess({ settings: body, customized: true, updatedAt: "2026-10-04T10:00:00Z", updatedBy: "admin-1" })
  510 |         );
  511 |         return;
  512 |       }
  513 |       await fulfillJson(
  514 |         route,
  515 |         200,
  516 |         apiSuccess({ settings: DEFAULT_SETTINGS, customized: false, updatedAt: null, updatedBy: null })
  517 |       );
  518 |     });
  519 | 
  520 |     await page.goto("/tenant-admin/settings/video-configuration");
  521 | 
  522 |     await expect(page.getByText("These are the platform defaults.", { exact: false })).toBeVisible();
  523 |     await expect(page.getByLabel("Videos playing at once per student")).toHaveValue("1");
  524 | 
  525 |     await page.getByLabel("Store new videos on").selectOption("YOUTUBE");
  526 |     await page.getByLabel("Require the AOC Secure Player app").check();
  527 |     await page.getByLabel("Watch-time limit per video").selectOption("VIDEO_DURATION_MULTIPLIER");
  528 |     await page.getByLabel("Multiplier").fill("1.5");
  529 |     await page.getByLabel("Student full name").check();
  530 |     await page.getByLabel("Session code").check();
  531 |     await page.getByRole("button", { name: "Save video settings" }).click();
```