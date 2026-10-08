# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: frontend\e2e\tenant-admin-video-settings.spec.ts >> Tenant Admin video settings >> a read-only auditor sees the settings disabled and cannot save
- Location: frontend\e2e\tenant-admin-video-settings.spec.ts:579:7

# Error details

```
Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
Call log:
  - navigating to "/tenant-admin/settings/video-configuration", waiting until "load"

```

# Test source

```ts
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
  532 | 
  533 |     await expect(page.getByText("Video settings saved.")).toBeVisible();
  534 |     expect(saved).toHaveLength(1);
  535 |     expect(saved[0]).toMatchObject({
  536 |       defaultProvider: "YOUTUBE",
  537 |       nativePlayerRequired: true,
  538 |       watchTimePolicy: "VIDEO_DURATION_MULTIPLIER",
  539 |       watchTimeMultiplier: 1.5,
  540 |       watchTimeFixedMinutes: null,
  541 |     });
  542 |     expect(saved[0].watermarkFields).toEqual(["STUDENT_DISPLAY_ID", "STUDENT_FULL_NAME", "TIMESTAMP", "SESSION_ID"]);
  543 |   });
  544 | 
  545 |   test("invalid values are caught before saving and server field errors are shown", async ({ page }) => {
  546 |     await mockTenantSession(page, "TENANT_ADMIN");
  547 |     let putCount = 0;
  548 |     await page.route("**/v1/tenant-admin/video-configuration", async (route) => {
  549 |       if (route.request().method() === "PUT") {
  550 |         putCount += 1;
  551 |         await fulfillJson(
  552 |           route,
  553 |           400,
  554 |           apiError("VALIDATION_ERROR", "Invalid video configuration", [
  555 |             { field: "heartbeatIntervalSeconds", message: "must be between 10 and 60" },
  556 |           ])
  557 |         );
  558 |         return;
  559 |       }
  560 |       await fulfillJson(
  561 |         route,
  562 |         200,
  563 |         apiSuccess({ settings: DEFAULT_SETTINGS, customized: false, updatedAt: null, updatedBy: null })
  564 |       );
  565 |     });
  566 | 
  567 |     await page.goto("/tenant-admin/settings/video-configuration");
  568 |     await page.getByLabel("Watch-time limit per video").selectOption("FIXED_MINUTES");
  569 |     await page.getByRole("button", { name: "Save video settings" }).click();
  570 |     await expect(page.getByText("Minutes is required.")).toBeVisible();
  571 |     expect(putCount).toBe(0);
  572 | 
  573 |     await page.getByLabel("Minutes per video").fill("90");
  574 |     await page.getByRole("button", { name: "Save video settings" }).click();
  575 |     await expect(page.getByText("must be between 10 and 60")).toBeVisible();
  576 |     expect(putCount).toBe(1);
  577 |   });
  578 | 
  579 |   test("a read-only auditor sees the settings disabled and cannot save", async ({ page }) => {
  580 |     await mockTenantSession(page, "READ_ONLY_AUDITOR");
  581 |     await mockJson(
  582 |       page,
  583 |       "**/v1/tenant-admin/video-configuration",
  584 |       200,
  585 |       apiSuccess({ settings: DEFAULT_SETTINGS, customized: false, updatedAt: null, updatedBy: null })
  586 |     );
  587 | 
> 588 |     await page.goto("/tenant-admin/settings/video-configuration");
      |                ^ Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
  589 | 
  590 |     await expect(page.getByLabel("Store new videos on")).toBeDisabled();
  591 |     await expect(page.getByRole("button", { name: "Save video settings" })).toHaveCount(0);
  592 |   });
  593 | });
  594 | 
```