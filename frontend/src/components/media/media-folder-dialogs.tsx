"use client";

import { useId, useMemo, useState } from "react";
import { z } from "zod";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { RefreshCw, Search, Trash2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { isApiClientError } from "@/lib/api/error";
import {
  GRANT_LEVEL_LABELS,
  MEDIA_FOLDER_NAME_MAX,
  MEDIA_FOLDER_NAME_TAKEN_MESSAGE,
  isMediaFolderNameTaken,
  isMediaFolderStale,
  mediaFolderErrorMessage,
  mediaFolderNameSchema,
  serverFieldError,
  useCreateMediaFolder,
  useDeleteMediaFolder,
  useInvalidateMediaLibrary,
  useMediaFolderEligibleMembers,
  useMediaFolderGrants,
  useRenameMediaFolder,
  useRevokeMediaFolderGrant,
  useShareMediaFolder,
  useTransferMediaFolder,
  type FormerOwnerAccess,
  type MediaFolder,
  type MediaFolderGrantLevel,
  type MediaFolderNameFormValues,
} from "@/lib/api/media-folders";
import { MediaDialog } from "./media-dialog";

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50 dark:bg-input/30";

const MAX_SHARE_BATCH = 50;

/** 409 `MEDIA_FOLDER_STALE` / other failures, with a Refresh action for the stale case. */
export function FolderMutationError({
  error,
  fallback,
  onRefresh,
  testId,
}: {
  error: unknown;
  fallback: string;
  onRefresh: () => void;
  testId?: string;
}) {
  const stale = isMediaFolderStale(error);
  return (
    <Alert variant="destructive" role="alert" data-testid={testId}>
      <AlertDescription className="flex flex-col gap-2">
        <span>{mediaFolderErrorMessage(error, fallback)}</span>
        {stale ? (
          <Button type="button" size="sm" variant="outline" className="self-start" onClick={onRefresh}>
            <RefreshCw aria-hidden="true" />
            Refresh
          </Button>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}

function ownerLabel(folder: Pick<MediaFolder, "ownerName">): string {
  return folder.ownerName?.trim() || "The current owner";
}

/**
 * New folder / Rename folder. Trimmed, non-blank, at most 100 characters with
 * a live "n / 100" counter; a sibling with the same name (409
 * `MEDIA_FOLDER_NAME_TAKEN`) is reported on the field and the typed name kept.
 * Mount it only while open so each opening starts fresh.
 */
export function MediaFolderNameDialog({
  mode,
  parentId = null,
  folder,
  open,
  onOpenChange,
}: {
  mode: "create" | "rename";
  /** Create only: where the folder goes (`null` = Uploads root). */
  parentId?: string | null;
  /** Rename only. */
  folder?: MediaFolder;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const formId = useId();
  const ids = { name: useId(), counter: useId(), error: useId() };
  const create = useCreateMediaFolder();
  const rename = useRenameMediaFolder();
  const invalidate = useInvalidateMediaLibrary();
  const mutation = mode === "create" ? create : rename;
  const [serverError, setServerError] = useState<unknown>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<MediaFolderNameFormValues>({
    resolver: zodResolver(mediaFolderNameSchema),
    defaultValues: { name: mode === "rename" ? (folder?.name ?? "") : "" },
  });
  const value = useWatch({ control, name: "name" }) ?? "";

  function handleOpenChange(next: boolean) {
    if (!next && mutation.isPending) return;
    onOpenChange(next);
  }

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    const name = values.name.trim();
    try {
      if (mode === "create") {
        await create.mutateAsync({ name, parentId });
      } else if (folder) {
        if (name === folder.name) {
          onOpenChange(false);
          return;
        }
        await rename.mutateAsync({ id: folder.id, name, expectedVersion: folder.version });
      }
      onOpenChange(false);
    } catch (error) {
      if (isMediaFolderNameTaken(error)) {
        setError("name", { type: "server", message: MEDIA_FOLDER_NAME_TAKEN_MESSAGE }, { shouldFocus: true });
        return;
      }
      const nameError = serverFieldError(error, "name");
      if (nameError) {
        setError("name", { type: "server", message: nameError }, { shouldFocus: true });
        return;
      }
      setServerError(error);
    }
  });

  const title = mode === "create" ? "New folder" : `Rename “${folder?.name ?? ""}”`;
  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title={title}
      testId="media-folder-name-dialog"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button
            type="submit"
            form={formId}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
            data-testid="media-folder-name-submit"
          >
            {mutation.isPending ? "Saving…" : mode === "create" ? "Create" : "Save"}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} noValidate aria-busy={mutation.isPending} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.name}>Folder name</Label>
          <Input
            id={ids.name}
            maxLength={MEDIA_FOLDER_NAME_MAX}
            required
            autoComplete="off"
            // readOnly (not disabled) while saving, so focus can return to the field on a server error.
            readOnly={mutation.isPending}
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={`${ids.counter}${errors.name ? ` ${ids.error}` : ""}`}
            data-testid="media-folder-name-input"
            {...register("name")}
          />
          <div className="flex items-start justify-between gap-3">
            {errors.name?.message ? (
              <p id={ids.error} role="alert" className="text-xs text-destructive" data-testid="media-folder-name-error">
                {errors.name.message}
              </p>
            ) : (
              <span />
            )}
            <span id={ids.counter} className="shrink-0 text-xs text-muted-foreground tabular-nums" data-testid="media-folder-name-counter">
              {value.length} / {MEDIA_FOLDER_NAME_MAX}
            </span>
          </div>
        </div>
        {serverError ? (
          <FolderMutationError
            error={serverError}
            fallback={mode === "create" ? "Couldn't create the folder. Please try again." : "Couldn't rename the folder. Please try again."}
            onRefresh={() => {
              invalidate();
              onOpenChange(false);
            }}
            testId="media-folder-name-server-error"
          />
        ) : null}
      </form>
    </MediaDialog>
  );
}

/**
 * Delete an empty folder after typing its name. A folder that still holds
 * folders or files (including ones the caller can't see) is refused by the
 * server with 409 `MEDIA_FOLDER_NOT_EMPTY`.
 */
export function DeleteMediaFolderDialog({
  folder,
  open,
  onOpenChange,
  onDeleted,
}: {
  folder: MediaFolder;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDeleted?: () => void;
}) {
  const formId = useId();
  const ids = { confirm: useId(), hint: useId() };
  const remove = useDeleteMediaFolder();
  const invalidate = useInvalidateMediaLibrary();
  const schema = useMemo(
    () => z.object({ confirm: z.string().refine((value) => value === folder.name, "Type the folder name exactly.") }),
    [folder.name]
  );
  const { register, control, handleSubmit } = useForm<{ confirm: string }>({
    resolver: zodResolver(schema),
    defaultValues: { confirm: "" },
  });
  const matches = useWatch({ control, name: "confirm" }) === folder.name;

  function handleOpenChange(next: boolean) {
    if (!next && remove.isPending) return;
    onOpenChange(next);
  }

  const handleDelete = handleSubmit(async () => {
    try {
      await remove.mutateAsync({ id: folder.id, expectedVersion: folder.version });
      onOpenChange(false);
      onDeleted?.();
    } catch {
      // Rendered below; the dialog stays open.
    }
  });

  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title={`Delete “${folder.name}”?`}
      description="Only an empty folder can be deleted. This can't be undone."
      testId="media-folder-delete-dialog"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={remove.isPending}>
            Cancel
          </Button>
          <Button
            type="submit"
            form={formId}
            variant="destructive"
            disabled={!matches || remove.isPending}
            aria-busy={remove.isPending}
            data-testid="media-folder-delete-confirm"
          >
            <Trash2 aria-hidden="true" />
            {remove.isPending ? "Deleting…" : "Delete folder"}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={handleDelete} noValidate className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.confirm}>
            Type <span className="font-semibold break-all">{folder.name}</span> to confirm
          </Label>
          <Input
            id={ids.confirm}
            autoComplete="off"
            disabled={remove.isPending}
            aria-describedby={ids.hint}
            data-testid="media-folder-delete-input"
            {...register("confirm")}
          />
          <p id={ids.hint} className="text-xs text-muted-foreground">
            The name must match exactly, including capital letters.
          </p>
        </div>
        {remove.isError ? (
          <FolderMutationError
            error={remove.error}
            fallback="Couldn't delete the folder. Please try again."
            onRefresh={() => {
              invalidate();
              onOpenChange(false);
            }}
            testId="media-folder-delete-error"
          />
        ) : null}
      </form>
    </MediaDialog>
  );
}

/**
 * Share a folder with teachers: a searchable checkbox list of eligible
 * members with one access level for the batch, then the current grants with a
 * per-grant level and Remove. Grants never give management rights or
 * student access; the server validates every member (400 `userIds`).
 */
export function ShareMediaFolderDialog({
  folder,
  open,
  onOpenChange,
}: {
  folder: MediaFolder;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const ids = { search: useId(), level: useId(), explain: useId(), pickerError: useId() };
  const members = useMediaFolderEligibleMembers(folder.id, open);
  const grants = useMediaFolderGrants(folder.id, open);
  const share = useShareMediaFolder(folder.id);
  const revoke = useRevokeMediaFolderGrant(folder.id);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [level, setLevel] = useState<MediaFolderGrantLevel>("VIEW");
  const [pickerError, setPickerError] = useState<string | null>(null);
  const [grantError, setGrantError] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const busy = share.isPending || revoke.isPending;

  const granted = useMemo(() => new Set((grants.data ?? []).map((grant) => grant.userId)), [grants.data]);
  const available = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return (members.data ?? []).filter(
      (member) => !granted.has(member.userId) && (member.name ?? "").toLowerCase().includes(needle)
    );
  }, [members.data, granted, search]);

  function toggle(userId: string, checked: boolean) {
    setPickerError(null);
    setSelected((current) => (checked ? [...current, userId] : current.filter((id) => id !== userId)));
  }

  async function handleShare() {
    setPickerError(null);
    setStatus("");
    if (selected.length === 0) {
      setPickerError("Choose at least one teacher.");
      return;
    }
    if (selected.length > MAX_SHARE_BATCH) {
      setPickerError(`Share with at most ${MAX_SHARE_BATCH} teachers at a time.`);
      return;
    }
    try {
      await share.mutateAsync({ userIds: selected, accessLevel: level });
      setStatus(`Shared with ${selected.length} ${selected.length === 1 ? "teacher" : "teachers"}.`);
      setSelected([]);
    } catch (error) {
      setPickerError(
        serverFieldError(error, "userIds") ?? mediaFolderErrorMessage(error, "Couldn't share the folder. Please try again.")
      );
    }
  }

  async function changeLevel(userId: string, accessLevel: MediaFolderGrantLevel) {
    setGrantError(null);
    setStatus("");
    try {
      await share.mutateAsync({ userIds: [userId], accessLevel });
      setStatus("Access updated.");
    } catch (error) {
      setGrantError(mediaFolderErrorMessage(error, "Couldn't change the access. Please try again."));
    }
  }

  async function handleRevoke(userId: string, name: string) {
    setGrantError(null);
    setStatus("");
    try {
      await revoke.mutateAsync(userId);
      setStatus(`Removed ${name}.`);
    } catch (error) {
      setGrantError(mediaFolderErrorMessage(error, "Couldn't remove the access. Please try again."));
    }
  }

  return (
    <MediaDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && busy) return;
        onOpenChange(next);
      }}
      title={`Share “${folder.name}”`}
      testId="media-folder-share-dialog"
      className="sm:max-w-lg"
      description={
        <span id={ids.explain}>
          <strong className="font-medium text-foreground">View</strong> lets a teacher see and attach the files in
          this folder. <strong className="font-medium text-foreground">Can upload</strong> also lets them add files
          here. Sharing never lets them rename, move, delete or share the folder, change other people&apos;s files,
          or give students access. Subfolders are shared separately.
        </span>
      }
      footer={
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
          Done
        </Button>
      }
    >
      <div className="flex flex-col gap-5">
        <fieldset className="flex min-w-0 flex-col gap-3" aria-describedby={pickerError ? ids.pickerError : undefined}>
          <legend className="mb-1 text-sm font-medium">Share with teachers</legend>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={ids.search}>Search teachers</Label>
            <div className="relative">
              <Search aria-hidden="true" className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground" />
              <Input
                id={ids.search}
                type="search"
                className="pl-8"
                value={search}
                autoComplete="off"
                data-testid="media-folder-share-search"
                onChange={(event) => setSearch(event.target.value)}
              />
            </div>
          </div>
          {members.status === "pending" ? (
            <LoadingState label="Loading teachers…" />
          ) : members.status === "error" ? (
            <ErrorState
              message={isApiClientError(members.error) ? members.error.message : "Couldn't load teachers."}
              onRetry={() => void members.refetch()}
            />
          ) : available.length === 0 ? (
            <p className="rounded-md border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground">
              {search.trim()
                ? "No teachers match this search."
                : (members.data?.length ?? 0) === 0
                  ? "No other approved, active teachers to share with."
                  : "Every eligible teacher already has access."}
            </p>
          ) : (
            <ul
              aria-label="Eligible teachers"
              className="flex max-h-56 flex-col overflow-y-auto rounded-md border border-border"
              data-testid="media-folder-share-members"
            >
              {available.map((member) => {
                const checkboxId = `${ids.search}-${member.userId}`;
                const name = member.name ?? "Unnamed teacher";
                return (
                  <li key={member.userId} className="border-b border-border last:border-b-0">
                    <label htmlFor={checkboxId} className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-muted/50">
                      <input
                        id={checkboxId}
                        type="checkbox"
                        className="size-4 accent-primary"
                        checked={selected.includes(member.userId)}
                        disabled={busy}
                        onChange={(event) => toggle(member.userId, event.target.checked)}
                      />
                      <span className="min-w-0 break-words">{name}</span>
                    </label>
                  </li>
                );
              })}
            </ul>
          )}
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="flex flex-col gap-1.5 sm:w-44">
              <Label htmlFor={ids.level}>Access</Label>
              <select
                id={ids.level}
                className={selectClass}
                value={level}
                disabled={busy}
                data-testid="media-folder-share-level"
                onChange={(event) => setLevel(event.target.value as MediaFolderGrantLevel)}
              >
                <option value="VIEW">{GRANT_LEVEL_LABELS.VIEW}</option>
                <option value="UPLOAD">{GRANT_LEVEL_LABELS.UPLOAD}</option>
              </select>
            </div>
            <Button
              type="button"
              disabled={busy || selected.length === 0}
              aria-busy={share.isPending}
              onClick={() => void handleShare()}
              data-testid="media-folder-share-submit"
            >
              {share.isPending ? "Sharing…" : selected.length > 0 ? `Share (${selected.length})` : "Share"}
            </Button>
          </div>
          {pickerError ? (
            <p id={ids.pickerError} role="alert" className="text-xs text-destructive" data-testid="media-folder-share-error">
              {pickerError}
            </p>
          ) : null}
        </fieldset>

        <section aria-label="People with access" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">People with access</h3>
          <p className="text-xs text-muted-foreground">
            Owner: <span className="font-medium text-foreground">{ownerLabel(folder)}</span>
          </p>
          {grants.status === "pending" ? (
            <LoadingState label="Loading access…" />
          ) : grants.status === "error" ? (
            <ErrorState
              message={isApiClientError(grants.error) ? grants.error.message : "Couldn't load who has access."}
              onRetry={() => void grants.refetch()}
            />
          ) : grants.data.length === 0 ? (
            <p className="text-sm text-muted-foreground">Not shared with anyone yet.</p>
          ) : (
            <ul className="flex flex-col divide-y divide-border rounded-md border border-border" data-testid="media-folder-grants">
              {grants.data.map((grant) => {
                const name = grant.name ?? "Unnamed teacher";
                return (
                  <li
                    key={grant.userId}
                    className="flex flex-col gap-2 px-3 py-2 sm:flex-row sm:items-center sm:justify-between"
                    data-testid="media-folder-grant"
                    data-user-id={grant.userId}
                  >
                    <span className="min-w-0 break-words">{name}</span>
                    <div className="flex items-center gap-2">
                      <select
                        aria-label={`Access for ${name}`}
                        className={`${selectClass} sm:w-36`}
                        value={grant.accessLevel}
                        disabled={busy}
                        onChange={(event) => void changeLevel(grant.userId, event.target.value as MediaFolderGrantLevel)}
                      >
                        <option value="VIEW">{GRANT_LEVEL_LABELS.VIEW}</option>
                        <option value="UPLOAD">{GRANT_LEVEL_LABELS.UPLOAD}</option>
                      </select>
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        className="text-destructive hover:text-destructive"
                        disabled={busy}
                        onClick={() => void handleRevoke(grant.userId, name)}
                      >
                        Remove<span className="sr-only"> {name}</span>
                      </Button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          {grantError ? (
            <p role="alert" className="text-xs text-destructive" data-testid="media-folder-grant-error">
              {grantError}
            </p>
          ) : null}
        </section>
        <p role="status" aria-live="polite" className="sr-only" data-testid="media-folder-share-status">
          {status}
        </p>
      </div>
    </MediaDialog>
  );
}

const transferSchema = z.object({
  newOwnerId: z.string().min(1, "Choose the new owner."),
  formerOwnerAccess: z.enum(["NONE", "VIEW", "UPLOAD"]),
});
type TransferFormValues = z.infer<typeof transferSchema>;

const RETENTION_OPTIONS: { value: FormerOwnerAccess; label: string }[] = [
  { value: "NONE", label: "No access" },
  { value: "VIEW", label: "View" },
  { value: "UPLOAD", label: "Can upload" },
];

/**
 * Transfer a folder (and the subfolders the same owner owns) to another
 * teacher. Files keep their uploader. The former owner keeps nothing by
 * default; View / Can upload leaves them a grant on each transferred folder.
 */
export function TransferMediaFolderDialog({
  folder,
  open,
  onOpenChange,
}: {
  folder: MediaFolder;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const formId = useId();
  const ids = { owner: useId(), ownerError: useId(), retention: useId(), retentionHint: useId() };
  const members = useMediaFolderEligibleMembers(folder.id, open);
  const transfer = useTransferMediaFolder();
  const invalidate = useInvalidateMediaLibrary();
  const [serverError, setServerError] = useState<unknown>(null);
  const owner = ownerLabel(folder);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<TransferFormValues>({
    resolver: zodResolver(transferSchema),
    defaultValues: { newOwnerId: "", formerOwnerAccess: "NONE" },
  });
  const ownerError = errors.newOwnerId?.message ?? null;

  function handleOpenChange(next: boolean) {
    if (!next && transfer.isPending) return;
    onOpenChange(next);
  }

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await transfer.mutateAsync({
        id: folder.id,
        newOwnerId: values.newOwnerId,
        formerOwnerAccess: values.formerOwnerAccess as FormerOwnerAccess,
        expectedVersion: folder.version,
      });
      onOpenChange(false);
    } catch (error) {
      const fieldError = serverFieldError(error, "newOwnerId");
      if (fieldError) {
        setError("newOwnerId", { type: "server", message: fieldError }, { shouldFocus: true });
        return;
      }
      setServerError(error);
    }
  });

  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title={`Transfer “${folder.name}”`}
      testId="media-folder-transfer-dialog"
      description={
        <>
          Owned by <strong className="font-medium text-foreground">{owner}</strong>. Subfolders they own move with
          it; files stay with whoever uploaded them.
        </>
      }
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={transfer.isPending}>
            Cancel
          </Button>
          <Button
            type="submit"
            form={formId}
            disabled={transfer.isPending || members.status !== "success" || members.data.length === 0}
            aria-busy={transfer.isPending}
            data-testid="media-folder-transfer-submit"
          >
            {transfer.isPending ? "Transferring…" : "Transfer"}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} noValidate className="flex flex-col gap-5">
        {members.status === "pending" ? (
          <LoadingState label="Loading teachers…" />
        ) : members.status === "error" ? (
          <ErrorState
            message={isApiClientError(members.error) ? members.error.message : "Couldn't load teachers."}
            onRetry={() => void members.refetch()}
          />
        ) : members.data.length === 0 ? (
          <p className="rounded-md border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground" data-testid="media-folder-transfer-none">
            No other approved, active teachers can receive this folder.
          </p>
        ) : (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={ids.owner}>
              New owner <span aria-hidden="true">*</span>
            </Label>
            <select
              id={ids.owner}
              className={selectClass}
              required
              disabled={transfer.isPending}
              aria-invalid={ownerError ? true : undefined}
              aria-describedby={ownerError ? ids.ownerError : undefined}
              data-testid="media-folder-transfer-owner"
              {...register("newOwnerId")}
            >
              <option value="">Choose a teacher</option>
              {members.data.map((member) => (
                <option key={member.userId} value={member.userId}>
                  {member.name ?? "Unnamed teacher"}
                </option>
              ))}
            </select>
            {ownerError ? (
              <p id={ids.ownerError} role="alert" className="text-xs text-destructive" data-testid="media-folder-transfer-owner-error">
                {ownerError}
              </p>
            ) : null}
          </div>
        )}
        <fieldset className="flex min-w-0 flex-col gap-2" aria-describedby={ids.retentionHint}>
          <legend className="mb-1 text-sm font-medium break-words">{owner} keeps</legend>
          <div className="flex flex-wrap gap-x-5 gap-y-2">
            {RETENTION_OPTIONS.map((option) => {
              const radioId = `${ids.retention}-${option.value}`;
              return (
                <label key={option.value} htmlFor={radioId} className="flex cursor-pointer items-center gap-2">
                  <input
                    id={radioId}
                    type="radio"
                    className="size-4 accent-primary"
                    value={option.value}
                    disabled={transfer.isPending}
                    {...register("formerOwnerAccess")}
                  />
                  {option.label}
                </label>
              );
            })}
          </div>
          <p id={ids.retentionHint} className="text-xs text-muted-foreground">
            Any role permissions or other folder grants {owner} has still apply. No access means the transfer leaves
            them no grant on these folders.
          </p>
        </fieldset>
        {serverError ? (
          <FolderMutationError
            error={serverError}
            fallback="Couldn't transfer the folder. Please try again."
            onRefresh={() => {
              invalidate();
              onOpenChange(false);
            }}
            testId="media-folder-transfer-error"
          />
        ) : null}
      </form>
    </MediaDialog>
  );
}
