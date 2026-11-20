import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";
import { mediaFileKeys } from "@/lib/api/media-files";

/**
 * MEDIA-FOLDERS-01 - Media Library folders on the Uploads tab
 * (`/api/v1/media-folders`, backend `MediaFolderController`).
 *
 * The tenant, the caller's relation to a folder and every `can*` flag are
 * decided on the server. The flags here only decide which buttons are shown:
 * the backend re-checks every create / rename / move / delete / share /
 * transfer, and a folder the caller can't see is a 404 (never a 403).
 */

/** The caller's relation to a folder: owner, tenant-wide authority, or a grant. */
export type MediaFolderAccess = "OWNER" | "TENANT" | "UPLOAD" | "VIEW";
export type MediaFolderGrantLevel = "VIEW" | "UPLOAD";
export type FormerOwnerAccess = "NONE" | "VIEW" | "UPLOAD";

export interface MediaFolder {
  id: string;
  name: string;
  /** `null` at root and whenever the caller can't see the parent. */
  parentId: string | null;
  ownerUserId: string;
  ownerName: string | null;
  access: MediaFolderAccess | null;
  canManage: boolean;
  canShare: boolean;
  canTransfer: boolean;
  canUpload: boolean;
  /** Child folders the caller can see. */
  childFolderCount: number;
  fileCount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface MediaFolderCrumb {
  id: string;
  name: string;
}

export interface MediaFolderDetail extends MediaFolder {
  /**
   * Viewable ancestors directly above the folder, root-most first, excluding
   * the folder itself. It stops at the first ancestor the caller can't see, so
   * it may not reach the root - never fill in the missing part.
   */
  breadcrumb: MediaFolderCrumb[];
}

export interface MediaFolderGrant {
  userId: string;
  name: string | null;
  accessLevel: MediaFolderGrantLevel;
  grantedAt: string;
}

export interface MediaMember {
  userId: string;
  name: string | null;
}

export const MEDIA_FOLDER_NAME_MAX = 100;

/** UX check only - the server strips and validates the name authoritatively (400 field `name`). */
export const mediaFolderNameSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "A folder needs a name")
    .max(MEDIA_FOLDER_NAME_MAX, `Use at most ${MEDIA_FOLDER_NAME_MAX} characters.`)
    .refine((value) => !/[\u0000-\u001f\u007f]/.test(value), "Remove control characters from the name."),
});
export type MediaFolderNameFormValues = z.infer<typeof mediaFolderNameSchema>;

export const mediaFolderKeys = {
  all: ["media-folders"] as const,
  lists: () => [...mediaFolderKeys.all, "list"] as const,
  list: (parentId: string | null) => [...mediaFolderKeys.lists(), parentId ?? "root"] as const,
  detail: (id: string) => [...mediaFolderKeys.all, "detail", id] as const,
  grants: (id: string) => [...mediaFolderKeys.all, "grants", id] as const,
  eligible: (id: string) => [...mediaFolderKeys.all, "eligible", id] as const,
};

const BASE = "/v1/media-folders";
const folderPath = (id: string) => `${BASE}/${encodeURIComponent(id)}`;

/** A 4xx (404 hidden folder, 400 malformed id) won't change on retry. */
const retryServerErrorsOnce = (failureCount: number, error: unknown) =>
  failureCount < 1 && !(isApiClientError(error) && error.status < 500);

function useTenantQueryEnabled(enabled: boolean) {
  const { session } = useAuth();
  return {
    enabled: enabled && session?.kind === "tenant" && Boolean(session.userId),
    scope: [session?.userId, session?.role, session?.kind] as const,
  };
}

/** Folders directly under `parentId` (`null` = the root the caller can see). Never filtered by search. */
export function useMediaFolders(parentId: string | null, enabled = true) {
  const { authorizedFetch } = useAuth();
  const gate = useTenantQueryEnabled(enabled);
  return useQuery({
    queryKey: [...mediaFolderKeys.list(parentId), ...gate.scope],
    queryFn: () =>
      authorizedFetch<MediaFolder[]>(
        "tenant",
        parentId ? `${BASE}?parentId=${encodeURIComponent(parentId)}` : BASE
      ),
    enabled: gate.enabled,
    retry: retryServerErrorsOnce,
  });
}

export function useMediaFolder(id: string | null) {
  const { authorizedFetch } = useAuth();
  const gate = useTenantQueryEnabled(Boolean(id));
  return useQuery({
    queryKey: [...mediaFolderKeys.detail(id ?? ""), ...gate.scope],
    queryFn: () => authorizedFetch<MediaFolderDetail>("tenant", folderPath(id!)),
    enabled: gate.enabled,
    retry: retryServerErrorsOnce,
  });
}

/** Grants are only readable by callers who may share the folder (`canShare`). */
export function useMediaFolderGrants(id: string, enabled = true) {
  const { authorizedFetch } = useAuth();
  const gate = useTenantQueryEnabled(enabled);
  return useQuery({
    queryKey: [...mediaFolderKeys.grants(id), ...gate.scope],
    queryFn: () => authorizedFetch<MediaFolderGrant[]>("tenant", `${folderPath(id)}/grants`),
    enabled: gate.enabled,
    retry: retryServerErrorsOnce,
  });
}

/** Approved active teachers who may receive a grant or ownership (the current owner excluded). */
export function useMediaFolderEligibleMembers(id: string, enabled = true) {
  const { authorizedFetch } = useAuth();
  const gate = useTenantQueryEnabled(enabled);
  return useQuery({
    queryKey: [...mediaFolderKeys.eligible(id), ...gate.scope],
    queryFn: () => authorizedFetch<MediaMember[]>("tenant", `${folderPath(id)}/eligible-members`),
    enabled: gate.enabled,
    retry: retryServerErrorsOnce,
  });
}

/** Folder changes alter folder lists, breadcrumbs, counts and which files are visible. */
export function useInvalidateMediaLibrary() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: mediaFolderKeys.all });
    void queryClient.invalidateQueries({ queryKey: mediaFileKeys.all });
  };
}

/** Refresh after a 404/409 too: the folder may have changed or gone since it was listed. */
function invalidateOnConflict(invalidate: () => void) {
  return (error: unknown) => {
    if (isApiClientError(error) && (error.status === 404 || error.status === 409)) invalidate();
  };
}

export function useCreateMediaFolder() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaLibrary();
  return useMutation({
    mutationFn: ({ name, parentId }: { name: string; parentId: string | null }) =>
      authorizedFetch<MediaFolder>("tenant", BASE, {
        method: "POST",
        body: JSON.stringify(parentId ? { name, parentId } : { name }),
      }),
    onSuccess: invalidate,
  });
}

export function useRenameMediaFolder() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaLibrary();
  return useMutation({
    mutationFn: ({ id, name, expectedVersion }: { id: string; name: string; expectedVersion?: number }) =>
      authorizedFetch<MediaFolder>("tenant", folderPath(id), {
        method: "PATCH",
        body: JSON.stringify({ name, expectedVersion }),
      }),
    onSuccess: invalidate,
    onError: invalidateOnConflict(invalidate),
  });
}

export function useMoveMediaFolder() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaLibrary();
  return useMutation({
    mutationFn: ({ id, parentId, expectedVersion }: { id: string; parentId: string | null; expectedVersion?: number }) =>
      authorizedFetch<MediaFolder>("tenant", `${folderPath(id)}/move`, {
        method: "POST",
        body: JSON.stringify({ parentId, expectedVersion }),
      }),
    onSuccess: invalidate,
    onError: invalidateOnConflict(invalidate),
  });
}

export function useDeleteMediaFolder() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaLibrary();
  return useMutation({
    mutationFn: ({ id, expectedVersion }: { id: string; expectedVersion?: number }) =>
      authorizedFetch<null>(
        "tenant",
        expectedVersion === undefined
          ? folderPath(id)
          : `${folderPath(id)}?expectedVersion=${encodeURIComponent(String(expectedVersion))}`,
        { method: "DELETE" }
      ),
    onSuccess: invalidate,
    onError: invalidateOnConflict(invalidate),
  });
}

/** Grant mutations answer with the folder's current grants; store them and refresh folder flags. */
function useGrantMutationSuccess(id: string) {
  const queryClient = useQueryClient();
  const { session } = useAuth();
  return (grants: MediaFolderGrant[]) => {
    queryClient.setQueryData(
      [...mediaFolderKeys.grants(id), session?.userId, session?.role, session?.kind],
      grants
    );
    void queryClient.invalidateQueries({ queryKey: mediaFolderKeys.grants(id) });
    void queryClient.invalidateQueries({ queryKey: mediaFolderKeys.lists() });
  };
}

export function useShareMediaFolder(id: string) {
  const { authorizedFetch } = useAuth();
  const onSuccess = useGrantMutationSuccess(id);
  return useMutation({
    mutationFn: ({ userIds, accessLevel }: { userIds: string[]; accessLevel: MediaFolderGrantLevel }) =>
      authorizedFetch<MediaFolderGrant[]>("tenant", `${folderPath(id)}/grants`, {
        method: "POST",
        body: JSON.stringify({ userIds, accessLevel }),
      }),
    onSuccess,
  });
}

export function useRevokeMediaFolderGrant(id: string) {
  const { authorizedFetch } = useAuth();
  const onSuccess = useGrantMutationSuccess(id);
  return useMutation({
    mutationFn: (userId: string) =>
      authorizedFetch<MediaFolderGrant[]>("tenant", `${folderPath(id)}/grants/${encodeURIComponent(userId)}`, {
        method: "DELETE",
      }),
    onSuccess,
  });
}

export function useTransferMediaFolder() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateMediaLibrary();
  return useMutation({
    mutationFn: ({
      id,
      newOwnerId,
      formerOwnerAccess,
      expectedVersion,
    }: {
      id: string;
      newOwnerId: string;
      formerOwnerAccess: FormerOwnerAccess;
      expectedVersion?: number;
    }) =>
      authorizedFetch<MediaFolder>("tenant", `${folderPath(id)}/transfer`, {
        method: "POST",
        body: JSON.stringify({ newOwnerId, formerOwnerAccess, expectedVersion }),
      }),
    onSuccess: invalidate,
    onError: invalidateOnConflict(invalidate),
  });
}

export function isMediaFolderStale(error: unknown): boolean {
  return isApiClientError(error) && error.code === "MEDIA_FOLDER_STALE";
}

export function isMediaFolderNameTaken(error: unknown): boolean {
  return isApiClientError(error) && error.code === "MEDIA_FOLDER_NAME_TAKEN";
}

export const MEDIA_FOLDER_NAME_TAKEN_MESSAGE = "A folder with this name already exists here. Choose another name.";
export const MEDIA_FOLDER_STALE_MESSAGE = "This folder changed since you opened it. Refresh and try again.";

/** Folder mutation failures mapped to clear copy (409 codes per MEDIA-FOLDERS-01 §6). */
export function mediaFolderErrorMessage(error: unknown, fallback: string): string {
  if (isApiClientError(error)) {
    switch (error.code) {
      case "MEDIA_FOLDER_NAME_TAKEN":
        return MEDIA_FOLDER_NAME_TAKEN_MESSAGE;
      case "MEDIA_FOLDER_STALE":
        return MEDIA_FOLDER_STALE_MESSAGE;
      case "MEDIA_FOLDER_INVALID_MOVE":
        return "A folder can't be moved into itself or one of its subfolders.";
      case "MEDIA_FOLDER_NOT_EMPTY":
        return "This folder isn't empty. Move or delete its folders and files first.";
    }
    if (error.status === 404) return "This folder no longer exists or you can't access it. The list has been refreshed.";
    if (error.status === 403) return "You don't have permission to do that with this folder.";
    return error.message || fallback;
  }
  return fallback;
}

/** The server's field error for `field`, when a 400 named one. */
export function serverFieldError(error: unknown, field: string): string | null {
  if (!isApiClientError(error)) return null;
  return error.fieldErrors?.find((fieldError) => fieldError.field === field)?.message ?? null;
}

export const GRANT_LEVEL_LABELS: Record<MediaFolderGrantLevel, string> = {
  VIEW: "View",
  UPLOAD: "Can upload",
};
