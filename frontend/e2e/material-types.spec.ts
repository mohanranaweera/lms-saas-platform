import { test, expect } from "@playwright/test";
import { setupTeacherMaterialsMocks, teacherModulesUrl } from "./fixtures/materials-mocks";
import { setupVideoMocks, VIDEO_ASSET_ID } from "./fixtures/video-mocks";

/**
 * Teacher Materials Manager — Wave 5 material-type selector
 * (`components/courses/material-upload-form.tsx`): creating a `LINK`,
 * `NOTE`, and `VIDEO` material, plus the per-type validation errors. Mirrors
 * `material-upload-states.spec.ts`'s established stateful-mock pattern; no
 * real backend/object-storage runs in this environment.
 */

test.describe("material type selector — external link", () => {
  test("creating a LINK material sends materialType=LINK + externalUrl, and the row renders a Link badge", async ({
    page,
  }) => {
    const state = await setupTeacherMaterialsMocks(page, []);
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Material type", { exact: true }).click();
    await page.getByRole("option", { name: "External link" }).click();

    await page.getByLabel("Title", { exact: true }).fill("Recommended video");
    await page.getByLabel("Link URL", { exact: true }).fill("https://example.com/watch?v=123");
    await page.getByRole("button", { name: "Add material" }).click();

    await expect.poll(() => state.createCalls.length).toBe(1);
    expect(state.createCalls[0]).toMatchObject({
      title: "Recommended video",
      materialType: "LINK",
      externalUrl: "https://example.com/watch?v=123",
    });

    await expect(page.getByLabel("Material title", { exact: true })).toHaveValue("Recommended video");
    await expect(page.getByText("Link", { exact: true })).toBeVisible();
  });

  test("submitting a LINK material without a URL shows a field-specific validation error", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, []);
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Material type", { exact: true }).click();
    await page.getByRole("option", { name: "External link" }).click();
    await page.getByLabel("Title", { exact: true }).fill("Missing URL");
    await page.getByRole("button", { name: "Add material" }).click();

    // Both the field-specific alert and the form's shared top-level alert
    // render the same message by this form's established design (mirrors
    // the pre-Wave-5 file-upload variant's identical dual-alert behavior) —
    // `.first()` avoids a strict-mode ambiguity, not a bug being masked.
    await expect(page.getByText("A URL is required for a link material.").first()).toBeVisible();
  });
});

test.describe("material type selector — note", () => {
  test("creating a NOTE material sends materialType=NOTE + noteContent, and the row renders the note inline", async ({
    page,
  }) => {
    const state = await setupTeacherMaterialsMocks(page, []);
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Material type", { exact: true }).click();
    await page.getByRole("option", { name: "Note" }).click();

    await page.getByLabel("Title", { exact: true }).fill("Reading tip");
    await page.getByLabel("Note content", { exact: true }).fill("Read chapter 3 before next class.");
    await page.getByRole("button", { name: "Add material" }).click();

    await expect.poll(() => state.createCalls.length).toBe(1);
    expect(state.createCalls[0]).toMatchObject({
      title: "Reading tip",
      materialType: "NOTE",
      noteContent: "Read chapter 3 before next class.",
    });

    await expect(page.getByText("Read chapter 3 before next class.")).toBeVisible();
  });

  test("submitting a NOTE material without content shows a field-specific validation error", async ({
    page,
  }) => {
    await setupTeacherMaterialsMocks(page, []);
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Material type", { exact: true }).click();
    await page.getByRole("option", { name: "Note" }).click();
    await page.getByLabel("Title", { exact: true }).fill("Empty note");
    await page.getByRole("button", { name: "Add material" }).click();

    await expect(page.getByText("Note content is required.").first()).toBeVisible();
  });
});

test.describe("material type selector — video (two-step upload-then-attach)", () => {
  test("the Add material submit stays disabled until Step 1's video upload succeeds", async ({ page }) => {
    await setupTeacherMaterialsMocks(page, []);
    await setupVideoMocks(page);
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Material type", { exact: true }).click();
    await page.getByRole("option", { name: "Video" }).click();
    await page.getByLabel("Title", { exact: true }).fill("Week 1 lecture");

    await expect(page.getByRole("button", { name: "Add material" })).toBeDisabled();
  });

  test("uploading a video file then submitting sends materialType=VIDEO + the returned videoAssetId", async ({
    page,
  }) => {
    const materialsState = await setupTeacherMaterialsMocks(page, []);
    await setupVideoMocks(page);
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Material type", { exact: true }).click();
    await page.getByRole("option", { name: "Video" }).click();
    await page.getByLabel("Title", { exact: true }).fill("Week 1 lecture");

    await page.getByLabel("Video file", { exact: true }).setInputFiles({
      name: "week1.mp4",
      mimeType: "video/mp4",
      buffer: Buffer.from("fake video bytes"),
    });
    await page.getByRole("button", { name: "Upload video" }).click();

    await expect(page.getByText(/Uploaded: lecture\.mp4/)).toBeVisible();

    const submitButton = page.getByRole("button", { name: "Add material" });
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect.poll(() => materialsState.createCalls.length).toBe(1);
    expect(materialsState.createCalls[0]).toMatchObject({
      title: "Week 1 lecture",
      materialType: "VIDEO",
      videoAssetId: VIDEO_ASSET_ID,
    });
  });

  test("expanding Playback rules and submitting also PUTs the video's playback policy before creating the material", async ({
    page,
  }) => {
    const materialsState = await setupTeacherMaterialsMocks(page, []);
    const videoState = await setupVideoMocks(page);
    await page.goto(teacherModulesUrl());

    await page.getByLabel("Material type", { exact: true }).click();
    await page.getByRole("option", { name: "Video" }).click();
    await page.getByLabel("Title", { exact: true }).fill("Week 1 lecture");

    await page.getByLabel("Video file", { exact: true }).setInputFiles({
      name: "week1.mp4",
      mimeType: "video/mp4",
      buffer: Buffer.from("fake video bytes"),
    });
    await page.getByRole("button", { name: "Upload video" }).click();
    await expect(page.getByText(/Uploaded: lecture\.mp4/)).toBeVisible();

    await page.getByRole("button", { name: "Playback rules" }).click();
    await page.getByLabel("Max views per student", { exact: true }).fill("3");
    await page.getByLabel("Allow download", { exact: true }).check();

    await page.getByRole("button", { name: "Add material" }).click();

    await expect.poll(() => materialsState.createCalls.length).toBe(1);
    expect(videoState.policyRequests).toHaveLength(1);
    expect(videoState.policyRequests[0]).toMatchObject({
      maxViewsPerStudent: 3,
      allowDownload: true,
    });
  });
});
