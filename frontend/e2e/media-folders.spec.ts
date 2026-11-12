import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/class-session-mocks";
import type { MediaFile } from "../src/lib/api/media-files";
import type { MediaFolder, MediaFolderGrant, MediaMember } from "../src/lib/api/media-folders";

/**
 * MEDIA-FOLDERS-01 (frontend): Media Library folders on the Uploads tab.
 * Mock-only UI contract tests with synthetic data. They prove URL state,
 * request shapes and error UX against a stateful in-memory mock; the backend
 * integration tests separately prove folder visibility, tenant isolation and
 * every permission check. A mocked 403/404 here is not evidence of isolation.
 */

const BIOLOGY = "11111111-1111-4111-8111-111111111111";
const CELLS = "22222222-2222-4222-8222-222222222222";
const EMPTY = "33333333-3333-4333-8333-333333333333";
const SHARED = "44444444-4444-4444-8444-444444444444";
const MISSING = "99999999-9999-4999-8999-999999999999";
const ADMIN = "aaaaaaaa-0000-4000-8000-000000000001";
const TEACHER_A = "bbbbbbbb-0000-4000-8000-000000000002";
const TEACHER_B = "bbbbbbbb-0000-4000-8000-000000000003";

type Folder = MediaFolder;
type Captured = { method: string; path: string; search: string; body: string | null };

function folder(overrides: Partial<Folder> & Pick<Folder, "id" | "name">): Folder {
  return {
    parentId: null,
    ownerUserId: ADMIN,
    ownerName: "Admin Demo",
    access: "TENANT",
    canManage: true,
    canShare: true,
    canTransfer: true,
    canUpload: true,
    childFolderCount: 0,
    fileCount: 0,
    version: 1,
    createdAt: "2026-11-01T08:00:00Z",
    updatedAt: "2026-11-01T08:00:00Z",
    ...overrides,
  };
}

function libraryFile(overrides: Partial<MediaFile> & Pick<MediaFile, "id" | "title">): MediaFile {
  return {
    kind: "DOCUMENT",
    originalFilename: `${overrides.id}.pdf`,
    contentType: "application/pdf",
    sizeBytes: 2048,
    uploadedBy: ADMIN,
    uploaderName: "Admin Demo",
    createdAt: "2026-11-01T08:00:00Z",
    attachedLessonCount: 0,
    thumbnailUrl: null,
    canEdit: true,
    canDelete: true,
    folderId: null,
    ...overrides,
  };
}

const MEMBERS: MediaMember[] = [
  { userId: TEACHER_A, name: "Demo Teacher" },
  { userId: TEACHER_B, name: "Klass Teacher" },
];

interface MockOptions {
  folders?: Folder[];
  files?: MediaFile[];
  capabilities?: string[];
  role?: string;
  renameReply?: { status: number; body: unknown };
}

/** Stateful folders + files mock: mutations change what the next refetch returns. */
async function mockFolderLibrary(page: Page, options: MockOptions = {}) {
  const role = options.role ?? "TENANT_ADMIN";
  const folders: Folder[] = options.folders ?? [
    folder({ id: BIOLOGY, name: "Biology" }),
    folder({ id: CELLS, name: "Cells", parentId: BIOLOGY }),
    folder({ id: EMPTY, name: "Empty" }),
  ];
  const files: MediaFile[] = options.files ?? [
    libraryFile({ id: "file-unfiled", title: "Loose notes" }),
    libraryFile({ id: "file-bio", title: "Biology notes", folderId: BIOLOGY, uploadedBy: TEACHER_A, uploaderName: "Demo Teacher" }),
  ];
  const grants = new Map<string, MediaFolderGrant[]>();
  const calls: Captured[] = [];

  await mockTenantSession(page, role, role === "TEACHER" ? TEACHER_A : ADMIN);
  await mockJson(
    page,
    "**/v1/me/permissions",
    200,
    apiSuccess({
      role,
      capabilities: options.capabilities ?? ["MEDIA_VIEW", "MEDIA_ADD", "MEDIA_DELETE", "MEDIA_MANAGE_FOLDERS", "MEDIA_SHARE_FOLDERS"],
      permissionVersion: 1,
    })
  );
  await page.route(/\/v1\/media(\/|\?|$)/, async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/channels")) {
      return fulfillJson(route, 200, apiSuccess({ integrations: [], managedHost: null, hasArchived: false }));
    }
    return fulfillJson(route, 200, apiPageSuccess([], { page: 0, size: 24, totalElements: 0, totalPages: 0 }));
  });

  const withCounts = (f: Folder): Folder => ({
    ...f,
    childFolderCount: folders.filter((child) => child.parentId === f.id).length,
    fileCount: files.filter((file) => file.folderId === f.id).length,
  });

  await page.route(/\/v1\/media-folders(\/|\?|$)/, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(url.pathname.indexOf("/v1/media-folders") + "/v1/media-folders".length);
    const method = request.method();
    const body = request.postData();
    calls.push({ method, path: `/media-folders${path}`, search: url.search, body });
    const json = () => (body ? (JSON.parse(body) as Record<string, unknown>) : {});

    if (path === "" && method === "GET") {
      const parentId = url.searchParams.get("parentId");
      return fulfillJson(route, 200, apiSuccess(folders.filter((f) => f.parentId === parentId).map(withCounts)));
    }
    if (path === "" && method === "POST") {
      const { name, parentId } = json() as { name: string; parentId?: string };
      const siblings = folders.filter((f) => f.parentId === (parentId ?? null));
      if (siblings.some((f) => f.name.toLowerCase() === name.toLowerCase())) {
        return fulfillJson(route, 409, apiError("MEDIA_FOLDER_NAME_TAKEN", "A folder with this name already exists"));
      }
      const created = folder({ id: `55555555-5555-4555-8555-${String(folders.length).padStart(12, "0")}`, name, parentId: parentId ?? null, access: "OWNER" });
      folders.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    const [, id, action, extra] = path.split("/");
    const target = folders.find((f) => f.id === id);
    if (!target) return fulfillJson(route, 404, apiError("NOT_FOUND", "Folder not found"));

    if (!action && method === "GET") {
      const breadcrumb: { id: string; name: string }[] = [];
      let parent = folders.find((f) => f.id === target.parentId);
      while (parent) {
        breadcrumb.unshift({ id: parent.id, name: parent.name });
        parent = folders.find((f) => f.id === parent!.parentId);
      }
      return fulfillJson(route, 200, apiSuccess({ ...withCounts(target), breadcrumb }));
    }
    if (!action && method === "PATCH") {
      if (options.renameReply) return fulfillJson(route, options.renameReply.status, options.renameReply.body);
      target.name = (json() as { name: string }).name;
      target.version += 1;
      return fulfillJson(route, 200, apiSuccess(withCounts(target)));
    }
    if (!action && method === "DELETE") {
      if (folders.some((f) => f.parentId === id) || files.some((file) => file.folderId === id)) {
        return fulfillJson(route, 409, apiError("MEDIA_FOLDER_NOT_EMPTY", "Folder is not empty"));
      }
      folders.splice(folders.indexOf(target), 1);
      return fulfillJson(route, 200, apiSuccess(null));
    }
    if (action === "move" && method === "POST") {
      target.parentId = (json() as { parentId: string | null }).parentId;
      return fulfillJson(route, 200, apiSuccess(withCounts(target)));
    }
    if (action === "eligible-members") return fulfillJson(route, 200, apiSuccess(MEMBERS));
    if (action === "grants") {
      const list = grants.get(id) ?? [];
      if (method === "POST") {
        const { userIds, accessLevel } = json() as { userIds: string[]; accessLevel: "VIEW" | "UPLOAD" };
        for (const userId of userIds) {
          const existing = list.find((g) => g.userId === userId);
          if (existing) existing.accessLevel = accessLevel;
          else list.push({ userId, name: MEMBERS.find((m) => m.userId === userId)?.name ?? null, accessLevel, grantedAt: "2026-11-12T08:00:00Z" });
        }
      }
      if (method === "DELETE" && extra) list.splice(list.findIndex((g) => g.userId === extra), 1);
      grants.set(id, list);
      return fulfillJson(route, 200, apiSuccess(list));
    }
    if (action === "transfer" && method === "POST") {
      const { newOwnerId } = json() as { newOwnerId: string };
      target.ownerUserId = newOwnerId;
      target.ownerName = MEMBERS.find((m) => m.userId === newOwnerId)?.name ?? null;
      target.version += 1;
      return fulfillJson(route, 200, apiSuccess(withCounts(target)));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked"));
  });

  await page.route(/\/v1\/media-files(\/|\?|$)/, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.slice(url.pathname.indexOf("/v1/media-files") + "/v1/media-files".length);
    const method = request.method();
    const body = request.postData();
    calls.push({ method, path: `/media-files${path}`, search: url.search, body });
    const folderId = url.searchParams.get("folderId");
    const unfiled = url.searchParams.get("unfiled") === "true";
    const inScope = (file: MediaFile) => (folderId ? file.folderId === folderId : unfiled ? !file.folderId : true);

    if (method === "GET" && path === "/summary") {
      return fulfillJson(route, 200, apiSuccess({ documents: files.length, images: 0, other: 0, total: files.length }));
    }
    if (method === "GET" && path === "/uploaders") {
      const seen = new Map<string, string | null>();
      files.filter(inScope).forEach((file) => seen.set(file.uploadedBy, file.uploaderName));
      return fulfillJson(route, 200, apiSuccess([...seen].map(([userId, name]) => ({ userId, name }))));
    }
    if (method === "GET" && path === "") {
      const q = (url.searchParams.get("q") ?? "").toLowerCase();
      const uploadedBy = url.searchParams.get("uploadedBy");
      const list = files.filter((file) => inScope(file) && (!uploadedBy || file.uploadedBy === uploadedBy) && file.title.toLowerCase().includes(q));
      return fulfillJson(route, 200, apiPageSuccess(list, { page: 0, size: 24, totalElements: list.length, totalPages: list.length ? 1 : 0 }));
    }
    if (method === "POST" && path === "") {
      const created = libraryFile({ id: "file-uploaded", title: "Uploaded notes", folderId: /name="folderId"\r\n\r\n([^\r]+)/.exec(body ?? "")?.[1] ?? null });
      files.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    const moveMatch = /^\/([^/]+)\/move$/.exec(path);
    if (moveMatch && method === "POST") {
      const file = files.find((f) => f.id === moveMatch[1]);
      if (!file) return fulfillJson(route, 404, apiError("NOT_FOUND", "Media not found"));
      file.folderId = (JSON.parse(body ?? "{}") as { folderId: string | null }).folderId;
      return fulfillJson(route, 200, apiSuccess(file));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not mocked"));
  });

  return { folders, files, calls };
}

const folderCard = (page: Page, id: string) => page.locator(`[data-testid="media-folder-card"][data-media-folder-id="${id}"]`);
const fileCard = (page: Page, id: string) => page.locator(`[data-testid="media-file-card"][data-media-file-id="${id}"]`);

async function openLibrary(page: Page, path = "/tenant-admin/media") {
  await page.goto(path);
  await expect(page.getByTestId("media-uploads-panel")).toBeVisible();
}

async function folderAction(page: Page, id: string, name: string, item: string) {
  await folderCard(page, id).getByRole("button", { name: `Folder actions for ${name}` }).click();
  await page.getByRole("menuitem", { name: item }).click();
}

test.describe("Media Library folders", () => {
  test("root shows Folders above Unfiled; folders open by click and keyboard; URL survives reload and Back", async ({ page }) => {
    await mockFolderLibrary(page);
    await openLibrary(page);

    const sections = page.locator('[data-testid="media-folders-section"], [data-testid="media-files-section"], [data-testid="media-uploads-videos"]');
    await expect(sections).toHaveCount(3);
    await expect(sections.nth(0)).toHaveAttribute("data-testid", "media-folders-section");
    await expect(sections.nth(1).getByRole("heading", { name: "Unfiled" })).toBeVisible();
    await expect(sections.nth(2)).toHaveAttribute("data-testid", "media-uploads-videos");
    await expect(page.getByTestId("media-folder-card")).toHaveCount(2);
    await expect(fileCard(page, "file-unfiled")).toBeVisible();
    await expect(fileCard(page, "file-bio")).toHaveCount(0);
    await expect(folderCard(page, BIOLOGY)).toContainText("1 folder · 1 file");

    await folderCard(page, BIOLOGY).getByRole("button", { name: "Open folder Biology" }).click();
    await expect(page).toHaveURL(new RegExp(`\\?folder=${BIOLOGY}$`));
    await expect(page.getByTestId("media-folder-heading")).toHaveText("Biology");
    await expect(page.getByTestId("media-folder-heading")).toBeFocused();
    await expect(page.getByRole("navigation", { name: "Breadcrumb" })).toContainText("Uploads");
    await expect(page.getByTestId("media-folder-count")).toContainText("1 folder · 1 file");
    await expect(fileCard(page, "file-bio")).toBeVisible();
    await expect(fileCard(page, "file-unfiled")).toHaveCount(0);
    await expect(page.getByTestId("media-uploads-videos")).toHaveCount(0);
    await expect(page.getByTestId("media-files-type").locator("option")).toHaveText(["All types", "Documents", "Images"]);

    await page.reload();
    await expect(page.getByTestId("media-folder-heading")).toHaveText("Biology");
    await expect(fileCard(page, "file-bio")).toBeVisible();

    // Keyboard: Space opens the child folder; breadcrumbs lead back up.
    await folderCard(page, CELLS).getByTestId("media-folder-open").focus();
    await page.keyboard.press("Space");
    await expect(page).toHaveURL(new RegExp(`\\?folder=${CELLS}$`));
    await expect(page.getByRole("navigation", { name: "Breadcrumb" }).getByRole("listitem")).toHaveText(["Uploads", "Biology", "Cells"]);
    await expect(page.getByTestId("media-folder-empty")).toContainText("This folder is empty");
    await expect(page.getByTestId("media-folder-empty").getByRole("button", { name: "New media" })).toBeVisible();
    await expect(page.getByTestId("media-folder-empty").getByRole("button", { name: "New folder" })).toBeVisible();

    await page.goBack();
    await expect(page.getByTestId("media-folder-heading")).toHaveText("Biology");
    await page.goBack();
    await expect(page).not.toHaveURL(/folder=/);
    await expect(page.getByTestId("media-folders-section")).toBeVisible();
    await expect(page.getByTestId("media-uploads-videos")).toBeVisible();

    await folderCard(page, BIOLOGY).getByTestId("media-folder-open").focus();
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("media-folder-heading")).toHaveText("Biology");
    await page.getByRole("navigation", { name: "Breadcrumb" }).getByRole("link", { name: "Uploads" }).click();
    await expect(page).not.toHaveURL(/folder=/);
  });

  test("an unknown or hidden folder shows a not-found state with a way back", async ({ page }) => {
    await mockFolderLibrary(page);
    await page.goto(`/tenant-admin/media?folder=${MISSING}`);
    await expect(page.getByTestId("media-folder-not-found")).toContainText("Folder not found");
    await page.getByRole("link", { name: "Back to Uploads" }).click();
    await expect(page).not.toHaveURL(/folder=/);
    await expect(page.getByTestId("media-folders-section")).toBeVisible();
  });

  test("New folder validates the name, keeps the input on a taken name, and creates in the current folder", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page);
    await openLibrary(page);
    await page.getByTestId("media-folders-new").click();
    const dialog = page.getByTestId("media-folder-name-dialog");
    await expect(dialog.getByTestId("media-folder-name-counter")).toHaveText("0 / 100");
    await dialog.getByTestId("media-folder-name-submit").click();
    await expect(dialog.getByTestId("media-folder-name-error")).toHaveText("A folder needs a name");
    await dialog.getByLabel("Folder name").fill("   ");
    await dialog.getByTestId("media-folder-name-submit").click();
    await expect(dialog.getByTestId("media-folder-name-error")).toHaveText("A folder needs a name");

    await dialog.getByLabel("Folder name").fill("biology");
    await expect(dialog.getByTestId("media-folder-name-counter")).toHaveText("7 / 100");
    await dialog.getByTestId("media-folder-name-submit").click();
    await expect(dialog.getByTestId("media-folder-name-error")).toContainText("already exists here");
    await expect(dialog.getByLabel("Folder name")).toHaveValue("biology");
    await expect(dialog.getByLabel("Folder name")).toBeFocused();

    await dialog.getByLabel("Folder name").fill("  Physics  ");
    await dialog.getByTestId("media-folder-name-submit").click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByTestId("media-folder-card").filter({ hasText: "Physics" })).toBeVisible();
    const creates = calls.filter((c) => c.method === "POST" && c.path === "/media-folders");
    expect(JSON.parse(creates[creates.length - 1].body ?? "{}")).toEqual({ name: "Physics" });

    // Inside a folder the new folder is created there.
    await folderCard(page, BIOLOGY).getByTestId("media-folder-open").click();
    await page.getByTestId("media-folders-new").click();
    await page.getByLabel("Folder name").fill("Genetics");
    await page.getByTestId("media-folder-name-submit").click();
    await expect(page.getByTestId("media-folder-card").filter({ hasText: "Genetics" })).toBeVisible();
    const nested = calls.filter((c) => c.method === "POST" && c.path === "/media-folders").pop();
    expect(JSON.parse(nested?.body ?? "{}")).toEqual({ name: "Genetics", parentId: BIOLOGY });
    expect(nested?.body).not.toMatch(/tenant/i);
  });

  test("rename reports a stale folder with a Refresh action", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page, {
      renameReply: { status: 409, body: apiError("MEDIA_FOLDER_STALE", "Folder changed") },
    });
    await openLibrary(page);
    await folderAction(page, BIOLOGY, "Biology", "Rename");
    await page.getByLabel("Folder name").fill("Biology 2026");
    await page.getByTestId("media-folder-name-submit").click();
    await expect(page.getByTestId("media-folder-name-server-error")).toContainText("This folder changed since you opened it");
    const patch = calls.find((c) => c.method === "PATCH");
    expect(JSON.parse(patch?.body ?? "{}")).toEqual({ name: "Biology 2026", expectedVersion: 1 });
    await page.getByTestId("media-folder-name-server-error").getByRole("button", { name: "Refresh" }).click();
    await expect(page.getByTestId("media-folder-name-dialog")).toHaveCount(0);
  });

  test("a file moves into a folder and back to Unfiled, with the audience change stated first", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page);
    await openLibrary(page);
    await fileCard(page, "file-unfiled").getByTestId("media-file-move").click();
    const dialog = page.getByTestId("media-file-move-dialog");
    const unfiledOption = dialog.locator('[data-testid="media-folder-picker-option"][data-folder-id="root"]');
    await expect(unfiledOption.getByRole("radio")).toBeDisabled();
    await expect(unfiledOption).toContainText("current location");
    await expect(dialog.getByTestId("media-file-move-submit")).toBeDisabled();

    // Browse into Biology and back via the picker breadcrumb.
    await dialog.getByRole("button", { name: "Show folders in Biology" }).click();
    await expect(dialog.getByRole("navigation", { name: "Destination folder path" })).toContainText("Biology");
    await expect(dialog.getByRole("radio", { name: "Cells" })).toBeEnabled();
    await dialog.getByRole("navigation", { name: "Destination folder path" }).getByRole("button", { name: "Uploads" }).click();

    await dialog.getByRole("radio", { name: "Biology" }).check();
    await expect(dialog.getByTestId("media-file-move-audience")).toContainText("Only people with access to Biology will see it");
    await expect(dialog.getByTestId("media-file-move-audience")).toContainText("Course and session attachments that use this file keep working.");
    await dialog.getByTestId("media-file-move-submit").click();
    await expect(dialog).toHaveCount(0);
    await expect(fileCard(page, "file-unfiled")).toHaveCount(0);
    expect(JSON.parse(calls.find((c) => c.path === "/media-files/file-unfiled/move")?.body ?? "{}")).toEqual({ folderId: BIOLOGY });

    await folderCard(page, BIOLOGY).getByTestId("media-folder-open").click();
    await expect(fileCard(page, "file-unfiled")).toBeVisible();
    await fileCard(page, "file-unfiled").getByTestId("media-file-move").click();
    await expect(dialog.getByRole("radio", { name: /Biology/ })).toBeDisabled();
    await dialog.getByRole("radio", { name: "Unfiled" }).check();
    await expect(dialog.getByTestId("media-file-move-audience")).toContainText("visible to everyone who can view the Media Library");
    await dialog.getByTestId("media-file-move-submit").click();
    await expect(fileCard(page, "file-unfiled")).toHaveCount(0);
    const moves = calls.filter((c) => c.path === "/media-files/file-unfiled/move");
    expect(JSON.parse(moves[1].body ?? "{}")).toEqual({ folderId: null });
  });

  test("moving a folder disables itself and its current location", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page);
    await openLibrary(page);
    await folderAction(page, EMPTY, "Empty", "Move…");
    const dialog = page.getByTestId("media-folder-move-dialog");
    await expect(dialog.getByRole("radio", { name: /Uploads \(root\)/ })).toBeDisabled();
    await expect(dialog.getByRole("radio", { name: /^Empty/ })).toBeDisabled();
    await expect(dialog.getByRole("button", { name: "Show folders in Empty" })).toHaveCount(0);
    await dialog.getByRole("radio", { name: "Biology" }).check();
    await dialog.getByTestId("media-folder-move-submit").click();
    await expect(dialog).toHaveCount(0);
    await expect(folderCard(page, EMPTY)).toHaveCount(0);
    expect(JSON.parse(calls.find((c) => c.path === `/media-folders/${EMPTY}/move`)?.body ?? "{}")).toEqual({ parentId: BIOLOGY, expectedVersion: 1 });
  });

  test("Share adds teachers with a level, changes a level and removes a grant", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page);
    await openLibrary(page);
    await folderAction(page, BIOLOGY, "Biology", "Share…");
    const dialog = page.getByTestId("media-folder-share-dialog");
    await expect(dialog).toContainText("Sharing never lets them rename, move, delete or share the folder");
    await expect(dialog.getByText("Not shared with anyone yet.")).toBeVisible();
    await expect(dialog.getByTestId("media-folder-share-submit")).toBeDisabled();

    await dialog.getByLabel("Search teachers").fill("klass");
    await expect(dialog.getByRole("checkbox")).toHaveCount(1);
    await dialog.getByLabel("Search teachers").fill("");
    await dialog.getByRole("checkbox", { name: "Demo Teacher" }).check();
    await expect(dialog.getByLabel("Access", { exact: true })).toHaveValue("VIEW");
    await dialog.getByLabel("Access", { exact: true }).selectOption("UPLOAD");
    await dialog.getByTestId("media-folder-share-submit").click();
    const grant = dialog.locator(`[data-testid="media-folder-grant"][data-user-id="${TEACHER_A}"]`);
    await expect(grant).toContainText("Demo Teacher");
    await expect(grant.getByLabel("Access for Demo Teacher")).toHaveValue("UPLOAD");
    // An already-shared teacher leaves the picker.
    await expect(dialog.getByRole("checkbox")).toHaveCount(1);
    expect(JSON.parse(calls.find((c) => c.method === "POST" && c.path === `/media-folders/${BIOLOGY}/grants`)?.body ?? "{}")).toEqual({
      userIds: [TEACHER_A],
      accessLevel: "UPLOAD",
    });

    await grant.getByLabel("Access for Demo Teacher").selectOption("VIEW");
    await expect(grant.getByLabel("Access for Demo Teacher")).toHaveValue("VIEW");
    await expect.poll(() => calls.filter((c) => c.method === "POST" && c.path === `/media-folders/${BIOLOGY}/grants`).length).toBe(2);
    expect(JSON.parse(calls.filter((c) => c.method === "POST" && c.path === `/media-folders/${BIOLOGY}/grants`).pop()?.body ?? "{}")).toEqual({
      userIds: [TEACHER_A],
      accessLevel: "VIEW",
    });

    await grant.getByRole("button", { name: "Remove Demo Teacher" }).click();
    await expect(dialog.getByText("Not shared with anyone yet.")).toBeVisible();
    expect(calls.some((c) => c.method === "DELETE" && c.path === `/media-folders/${BIOLOGY}/grants/${TEACHER_A}`)).toBe(true);
    await dialog.getByRole("button", { name: "Done" }).click();
    await expect(dialog).toHaveCount(0);
  });

  test("Transfer ownership requires a new owner and sends the retention choice", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page);
    await openLibrary(page);
    await folderAction(page, BIOLOGY, "Biology", "Transfer ownership…");
    const dialog = page.getByTestId("media-folder-transfer-dialog");
    await expect(dialog).toContainText("Owned by Admin Demo. Subfolders they own move with it; files stay with whoever uploaded them.");
    await expect(dialog.getByRole("group", { name: "Admin Demo keeps" })).toBeVisible();
    await expect(dialog.getByRole("radio", { name: "No access" })).toBeChecked();
    await expect(dialog).toContainText("No access means the transfer leaves them no grant");

    await dialog.getByTestId("media-folder-transfer-submit").click();
    await expect(dialog.getByTestId("media-folder-transfer-owner-error")).toHaveText("Choose the new owner.");
    await dialog.getByLabel("New owner").selectOption(TEACHER_B);
    await dialog.getByRole("radio", { name: "View" }).check();
    await dialog.getByTestId("media-folder-transfer-submit").click();
    await expect(dialog).toHaveCount(0);
    expect(JSON.parse(calls.find((c) => c.path === `/media-folders/${BIOLOGY}/transfer`)?.body ?? "{}")).toEqual({
      newOwnerId: TEACHER_B,
      formerOwnerAccess: "VIEW",
      expectedVersion: 1,
    });
  });

  test("Delete needs the typed name, explains a non-empty folder and removes an empty one", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page);
    await openLibrary(page);
    await folderAction(page, BIOLOGY, "Biology", "Delete");
    const dialog = page.getByTestId("media-folder-delete-dialog");
    await expect(dialog.getByTestId("media-folder-delete-confirm")).toBeDisabled();
    await dialog.getByLabel("Type Biology to confirm").fill("biology");
    await expect(dialog.getByTestId("media-folder-delete-confirm")).toBeDisabled();
    await dialog.getByLabel("Type Biology to confirm").fill("Biology");
    await dialog.getByTestId("media-folder-delete-confirm").click();
    await expect(dialog.getByTestId("media-folder-delete-error")).toContainText("Move or delete its folders and files first.");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();

    await folderAction(page, EMPTY, "Empty", "Delete");
    await page.getByLabel("Type Empty to confirm").fill("Empty");
    await page.getByTestId("media-folder-delete-confirm").click();
    await expect(page.getByTestId("media-folder-delete-dialog")).toHaveCount(0);
    await expect(folderCard(page, EMPTY)).toHaveCount(0);
    const deleted = calls.find((c) => c.method === "DELETE" && c.path === `/media-folders/${EMPTY}`);
    expect(deleted?.search).toBe("?expectedVersion=1");
  });

  test("Uploaded by filters the current scope and Clear filters resets it", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page, {
      files: [
        libraryFile({ id: "file-a", title: "Admin notes" }),
        libraryFile({ id: "file-b", title: "Teacher notes", uploadedBy: TEACHER_A, uploaderName: "Demo Teacher" }),
      ],
    });
    await openLibrary(page);
    await expect(page.getByTestId("media-files-count")).toHaveText("2 files");
    await page.getByLabel("Uploaded by").selectOption(TEACHER_A);
    await expect(page.getByTestId("media-files-count")).toHaveText("1 file");
    await expect(fileCard(page, "file-b")).toBeVisible();
    expect(calls.some((c) => c.path === "/media-files" && c.search.includes(`uploadedBy=${TEACHER_A}`) && c.search.includes("unfiled=true"))).toBe(true);
    expect(calls.filter((c) => c.path === "/media-files").every((c) => !(c.search.includes("folderId") && c.search.includes("unfiled")))).toBe(true);
    await page.getByTestId("media-files-search").fill("missing");
    await expect(page.getByText("No files match", { exact: true })).toBeVisible();
    await page.getByTestId("media-files-section").getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByLabel("Uploaded by")).toHaveValue("");
    await expect(page.getByTestId("media-files-count")).toHaveText("2 files");
    // Folders are not filtered by search.
    await expect(page.getByTestId("media-folder-card")).toHaveCount(2);
  });

  test("New media inside a folder uploads into it", async ({ page }) => {
    const { calls } = await mockFolderLibrary(page);
    await openLibrary(page, `/tenant-admin/media?folder=${BIOLOGY}`);
    await page.getByTestId("media-files-new").click();
    await expect(page.getByTestId("media-file-upload-dialog")).toContainText("Documents and images go into Biology.");
    await page.getByTestId("media-file-upload-input").setInputFiles({ name: "cells.txt", mimeType: "text/plain", buffer: Buffer.from("Synthetic notes") });
    await page.getByTestId("media-file-upload-submit").click();
    await expect(fileCard(page, "file-uploaded")).toBeVisible();
    const upload = calls.find((c) => c.method === "POST" && c.path === "/media-files");
    expect(upload?.body).toContain('name="folderId"');
    expect(upload?.body).toContain(BIOLOGY);
  });

  test("controls follow server flags: no New folder without the capability, no kebab without rights, view-only folder hides New media", async ({ page }) => {
    await mockFolderLibrary(page, {
      role: "TEACHER",
      capabilities: ["MEDIA_VIEW", "MEDIA_ADD"],
      folders: [
        folder({ id: SHARED, name: "Shared with me", access: "VIEW", canManage: false, canShare: false, canTransfer: false, canUpload: false }),
      ],
      files: [libraryFile({ id: "file-shared", title: "Shared notes", folderId: SHARED, canEdit: false, canDelete: false })],
    });
    await openLibrary(page, "/teacher/media");
    await expect(folderCard(page, SHARED)).toContainText("View only");
    await expect(page.getByTestId("media-folders-new")).toHaveCount(0);
    await expect(page.getByTestId("media-files-new")).toBeVisible();
    await expect(folderCard(page, SHARED).getByTestId("media-folder-actions")).toHaveCount(0);

    await folderCard(page, SHARED).getByTestId("media-folder-open").click();
    await expect(page.getByTestId("media-folder-heading")).toHaveText("Shared with me");
    await expect(page.getByTestId("media-files-new")).toHaveCount(0);
    await expect(page.getByTestId("media-folders-new")).toHaveCount(0);
    await expect(fileCard(page, "file-shared").getByTestId("media-file-move")).toHaveCount(0);
  });

  test("no horizontal overflow at 375px, at the root and inside a folder", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await mockFolderLibrary(page, {
      folders: [
        folder({ id: BIOLOGY, name: "A folder with a deliberately long name that must wrap on a phone screen" }),
        folder({ id: CELLS, name: "Cells", parentId: BIOLOGY }),
      ],
    });
    await openLibrary(page);
    await expect(page.getByTestId("media-folder-card")).toHaveCount(1);
    const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(await overflow()).toBeLessThanOrEqual(0);
    await page.getByTestId("media-folder-open").click();
    await folderCard(page, CELLS).getByTestId("media-folder-open").click();
    await expect(page.getByTestId("media-folder-heading")).toHaveText("Cells");
    expect(await overflow()).toBeLessThanOrEqual(0);
  });
});
