import { test, expect } from "@playwright/test";
import { apiError, apiSuccess } from "./fixtures/auth-mocks";
import { makeMaterial, setupStudentMaterialsMocks, studentMaterialsUrl } from "./fixtures/materials-mocks";

/**
 * Student "Lesson/Material View"
 * (`app/(student)/student/courses/[courseId]/modules/[moduleId]/lessons/[lessonId]/materials/page.tsx`).
 *
 * `GET .../materials` is already filtered server-side to `VISIBLE`-only
 * materials for a Student caller — this suite proves the frontend renders
 * exactly what the mocked API returns (never re-filtering or inferring
 * visibility client-side) and that the anti-enumeration 404 branch never
 * leaks the backend's error message text.
 */

test.describe("student materials view — visible list", () => {
  test("renders only the materials the mocked GET returns for this lesson, nothing else", async ({
    page,
  }) => {
    const materials = [
      makeMaterial({ id: "material-1", title: "Cell structure diagram", sequence: 1 }),
      makeMaterial({ id: "material-2", title: "Homework worksheet", sequence: 2 }),
    ];
    await setupStudentMaterialsMocks(page, { materialsBody: apiSuccess(materials) });
    await page.goto(studentMaterialsUrl());

    const rows = page.locator("ol > li");
    await expect(rows).toHaveCount(2);
    await expect(page.getByText("Cell structure diagram")).toBeVisible();
    await expect(page.getByText("Homework worksheet")).toBeVisible();
    await expect(page.getByText("Some other tenant's material")).toHaveCount(0);
  });

  test("shows the contextual empty state when the mocked GET returns an empty list", async ({ page }) => {
    await setupStudentMaterialsMocks(page, { materialsBody: apiSuccess([]) });
    await page.goto(studentMaterialsUrl());

    await expect(page.getByText("No materials yet")).toBeVisible();
    await expect(
      page.getByText("Your teacher hasn't added any materials to this lesson yet.", { exact: false })
    ).toBeVisible();
  });
});

test.describe("student materials view — permission denied anti-enumeration", () => {
  /**
   * Regression coverage for the finding that this page's 403 branch used to
   * render `PermissionDeniedState` (which surfaces the backend's raw
   * `error.message`) — contradicting the page's own anti-enumeration design.
   * A 403 here must render the exact same fixed, generic copy as the 404
   * branch, and must never leak the backend's message text into the DOM.
   */
  test("a 403 on the materials fetch renders the same fixed generic copy as a 404, never the backend's message", async ({
    page,
  }) => {
    const distinctiveBackendMessage = "xyz-should-never-render-in-ui";
    await setupStudentMaterialsMocks(page, {
      materialsStatus: 403,
      materialsBody: apiError("FORBIDDEN", distinctiveBackendMessage),
    });
    await page.goto(studentMaterialsUrl());

    await expect(page.getByText("This material isn't available")).toBeVisible();
    await expect(
      page.getByText("It may have been removed, or you may not have access to it.")
    ).toBeVisible();
    await expect(page.getByText(distinctiveBackendMessage)).toHaveCount(0);
    // Not the old `PermissionDeniedState` surface.
    await expect(page.getByText("You don't have permission to view this.")).toHaveCount(0);
  });
});

test.describe("student materials view — anti-enumeration 404", () => {
  test("a 404 renders the calm fixed-copy empty state and never interpolates the backend's error message", async ({
    page,
  }) => {
    const distinctiveBackendMessage = "xyz-should-never-render";
    await setupStudentMaterialsMocks(page, {
      materialsStatus: 404,
      materialsBody: apiError("NOT_FOUND", distinctiveBackendMessage),
    });
    await page.goto(studentMaterialsUrl());

    await expect(page.getByText("This material isn't available")).toBeVisible();
    await expect(
      page.getByText("It may have been removed, or you may not have access to it.")
    ).toBeVisible();
    await expect(page.getByText(distinctiveBackendMessage)).toHaveCount(0);
  });
});

test.describe("student materials view — View action", () => {
  test("clicking View triggers a fresh GET download-url request only after the click, and opens the returned signed URL", async ({
    page,
  }) => {
    const material = makeMaterial({ id: "material-1", title: "Cell structure diagram", sequence: 1 });
    const signedUrl = "https://cdn.example.test/signed/material-1?token=abc123";
    const state = await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrl: { url: signedUrl, expiresAt: new Date(Date.now() + 60_000).toISOString() },
    });

    // Stub `window.open` before navigation so the real app code's
    // `window.open(result.url, "_blank", "noopener,noreferrer")` call
    // (material-row.tsx / the student page's `handleView`) is captured
    // instead of actually opening a new tab/popup.
    await page.addInitScript(() => {
      (window as unknown as { __openedUrls: unknown[] }).__openedUrls = [];
      window.open = (...args: Parameters<typeof window.open>) => {
        (window as unknown as { __openedUrls: unknown[] }).__openedUrls.push(args);
        return null;
      };
    });

    await page.goto(studentMaterialsUrl());
    await expect(page.getByText("Cell structure diagram")).toBeVisible();

    // No download-url request before the click.
    expect(state.downloadUrlCallCount).toBe(0);

    await page.getByRole("button", { name: "View" }).click();

    await expect.poll(() => state.downloadUrlCallCount).toBe(1);
    expect(state.downloadUrlRequestedIds).toEqual(["material-1"]);

    const openedUrls = await page.evaluate(
      () => (window as unknown as { __openedUrls: unknown[] }).__openedUrls
    );
    expect(openedUrls).toEqual([[signedUrl, "_blank", "noopener,noreferrer"]]);
  });
});

test.describe("student materials view — View action anti-enumeration regression", () => {
  /**
   * Regression coverage for the exact bug this suite was missing: a failed
   * `download-url` fetch (any status — 403, 404, or otherwise) must render
   * only `StudentMaterialRow.handleView`'s fixed, generic copy and must
   * never leak the backend's real error message into the DOM. `handleView`'s
   * `catch` block deliberately discards the caught error entirely (no
   * `error.message`/`error.code` read at all — see the component's own doc
   * comment), so both cases below assert identically: same fixed copy, and
   * the distinctive backend message text absent from the page.
   */
  test("a 404 on the download-url fetch renders the fixed generic copy, never the backend's message", async ({
    page,
  }) => {
    const material = makeMaterial({ id: "material-1", title: "Cell structure diagram", sequence: 1 });
    const distinctiveBackendMessage = "xyz-should-never-render-in-ui";
    await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrlStatus: 404,
      downloadUrlBody: apiError("NOT_FOUND", distinctiveBackendMessage),
    });

    await page.goto(studentMaterialsUrl());
    await expect(page.getByText("Cell structure diagram")).toBeVisible();

    await page.getByRole("button", { name: "View" }).click();

    await expect(
      page.getByText("This material isn't available. It may have been removed, or you may not have access to it.")
    ).toBeVisible();
    await expect(page.getByText(distinctiveBackendMessage)).toHaveCount(0);
  });

  test("a 403 on the download-url fetch renders the same fixed generic copy, never the backend's message", async ({
    page,
  }) => {
    const material = makeMaterial({ id: "material-1", title: "Cell structure diagram", sequence: 1 });
    const distinctiveBackendMessage = "xyz-forbidden-should-never-render-either";
    await setupStudentMaterialsMocks(page, {
      materialsBody: apiSuccess([material]),
      downloadUrlStatus: 403,
      downloadUrlBody: apiError("FORBIDDEN", distinctiveBackendMessage),
    });

    await page.goto(studentMaterialsUrl());
    await expect(page.getByText("Cell structure diagram")).toBeVisible();

    await page.getByRole("button", { name: "View" }).click();

    // Same fixed copy as the 404 case above — `handleView`'s catch doesn't
    // branch on status at all, confirmed by reading the component before
    // writing this assertion (see module doc comment above).
    await expect(
      page.getByText("This material isn't available. It may have been removed, or you may not have access to it.")
    ).toBeVisible();
    await expect(page.getByText(distinctiveBackendMessage)).toHaveCount(0);
  });
});
