"use client";

import { useCallback, useMemo, useState } from "react";
import { Check, Lock, Minus, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip } from "@/components/ui/tooltip";
import { LiveRegion } from "@/components/ui/live-region";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { MultiSelect } from "@/components/ui/multi-select";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";
import {
  usePermissionMatrix,
  useSavePermissionMatrix,
  type PermissionChange,
  type PermissionMatrix,
  type PermissionMatrixGroup,
  type PermissionMatrixRow,
} from "@/lib/api/staff";
import { cn } from "@/lib/utils";

/** Local, unsaved matrix edits: `${roleCode}|${capabilityKey}` → desired `granted`. */
export type PermissionEdits = ReadonlyMap<string, boolean>;

const editKey = (roleCode: string, capabilityKey: string) => `${roleCode}|${capabilityKey}`;

function isEditable(state: string | undefined): boolean {
  return state === "GRANTED" || state === "NOT_GRANTED";
}

/**
 * The edits that still mean something against `matrix`: the cell exists, is
 * editable, and the desired value differs from the server's. A reload after
 * another administrator's save therefore silently drops edits that became
 * no-ops or uneditable, and re-applies the rest on top of the new version.
 */
export function pendingChanges(matrix: PermissionMatrix | undefined, edits: PermissionEdits): PermissionChange[] {
  if (!matrix) return [];
  const changes: PermissionChange[] = [];
  for (const group of matrix.groups) {
    for (const row of group.rows) {
      for (const role of matrix.roles) {
        const key = editKey(role.code, row.key);
        if (!edits.has(key)) continue;
        const cell = row.cells[role.code];
        if (!cell || !isEditable(cell.state)) continue;
        const desired = edits.get(key) as boolean;
        if (desired !== (cell.state === "GRANTED")) {
          changes.push({ roleCode: role.code, capabilityKey: row.key, granted: desired });
        }
      }
    }
  }
  return changes;
}

/** Desktop columns shown until the viewer picks their own (STAFF-MGMT-03). */
export const DEFAULT_SHOWN_ROLES: readonly string[] = ["OPERATOR", "MANAGER", "TEACHER", "FINANCE_STAFF"];

const rolesShownStorageKey = (userId: string | null) => `lms.staff.permissions.rolesShown:${userId ?? "anonymous"}`;

function readRolesShown(userId: string | null): string[] | null {
  try {
    if (typeof window === "undefined") return null;
    const raw = window.localStorage.getItem(rolesShownStorageKey(userId));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.every((entry) => typeof entry === "string") ? parsed : null;
  } catch {
    return null;
  }
}

function writeRolesShown(userId: string | null, roles: string[]) {
  try {
    window.localStorage.setItem(rolesShownStorageKey(userId), JSON.stringify(roles));
  } catch {
    // Storage unavailable (private mode / quota): the choice just is not remembered.
  }
}

/**
 * The viewer's "Roles shown" choice (desktop matrix columns), remembered per
 * viewer in localStorage. A display preference only - never sent to the server.
 */
function useRolesShown() {
  const { session } = useAuth();
  const userId = session?.userId ?? null;
  const [state, setState] = useState<{ userId: string | null; roles: string[] | null }>(() => ({
    userId,
    roles: readRolesShown(userId),
  }));
  // Re-read when the viewer changes (adjust-state-on-prop-change pattern).
  if (state.userId !== userId) {
    setState({ userId, roles: readRolesShown(userId) });
  }
  const setRoles = useCallback(
    (roles: string[]) => {
      setState({ userId, roles });
      writeRolesShown(userId, roles);
    },
    [userId]
  );
  return { stored: state.roles, setRoles };
}

/** Page-level edit state, so switching tabs never loses unsaved edits. */
export function usePermissionEditsState() {
  const [edits, setEdits] = useState<Map<string, boolean>>(() => new Map());
  return { edits, setEdits };
}

type SaveFeedback =
  | { kind: "stale" }
  | { kind: "error"; message: string }
  | null;

export function PermissionsTab({
  canEdit,
  capabilitiesLoaded,
  edits,
  setEdits,
}: {
  canEdit: boolean;
  /** False while `GET /v1/me/permissions` is in flight: no read-only notice yet, no editing either. */
  capabilitiesLoaded: boolean;
  edits: Map<string, boolean>;
  setEdits: (next: Map<string, boolean>) => void;
}) {
  const matrixQuery = usePermissionMatrix();
  const save = useSavePermissionMatrix();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [feedback, setFeedback] = useState<SaveFeedback>(null);
  const [announcement, setAnnouncement] = useState("");
  const [mobileRole, setMobileRole] = useState<string>("");
  const rolesShown = useRolesShown();

  const matrix = matrixQuery.data;
  const changes = useMemo(() => pendingChanges(matrix, edits), [matrix, edits]);
  const dirty = canEdit && changes.length > 0;

  // Leaving with unsaved edits (reload/close and in-app links) is guarded by the page (page.tsx).

  const effective = useCallback(
    (roleCode: string, row: PermissionMatrixRow): boolean => {
      const key = editKey(roleCode, row.key);
      if (edits.has(key)) return edits.get(key) as boolean;
      return row.cells[roleCode]?.state === "GRANTED";
    },
    [edits]
  );

  const setMany = (updates: Array<{ roleCode: string; row: PermissionMatrixRow; granted: boolean }>) => {
    const next = new Map(edits);
    for (const { roleCode, row, granted } of updates) {
      const key = editKey(roleCode, row.key);
      const server = row.cells[roleCode]?.state === "GRANTED";
      if (granted === server) next.delete(key);
      else next.set(key, granted);
    }
    setEdits(next);
    const count = pendingChanges(matrix, next).length;
    setAnnouncement(count === 0 ? "No unsaved changes." : `${count} unsaved ${count === 1 ? "change" : "changes"}.`);
  };

  const discard = () => {
    setEdits(new Map());
    setFeedback(null);
    setAnnouncement("Unsaved permission changes discarded.");
  };

  const reload = async () => {
    setFeedback(null);
    await matrixQuery.refetch();
    setAnnouncement("Permissions reloaded. Your unsaved changes were kept where they still apply.");
  };

  const submit = async () => {
    if (!matrix || save.isPending) return;
    setFeedback(null);
    try {
      await save.mutateAsync({ expectedVersion: matrix.version, changes });
      setEdits(new Map());
      setConfirmOpen(false);
      setAnnouncement(`Permissions saved (${changes.length} ${changes.length === 1 ? "change" : "changes"}).`);
    } catch (error) {
      setConfirmOpen(false);
      if (isApiClientError(error) && error.status === 409 && error.code === "PERMISSIONS_STALE") {
        setFeedback({ kind: "stale" });
      } else if (isApiClientError(error) && error.status === 403) {
        setFeedback({ kind: "error", message: "You don't have permission to change role permissions." });
      } else {
        setFeedback({
          kind: "error",
          message: isApiClientError(error) ? error.message : "Permissions couldn't be saved. Please try again.",
        });
      }
    }
  };

  // Desktop columns: the viewer's stored choice (server order), else the
  // defaults; never empty. Mobile always offers every role in its selector.
  const visibleRoles = useMemo(() => {
    if (!matrix) return [] as string[];
    const codes = matrix.roles.map((role) => role.code);
    const pick = (wanted: readonly string[] | null) => (wanted ? codes.filter((code) => wanted.includes(code)) : []);
    const stored = pick(rolesShown.stored);
    if (stored.length > 0) return stored;
    const defaults = pick(DEFAULT_SHOWN_ROLES);
    return defaults.length > 0 ? defaults : codes;
  }, [matrix, rolesShown.stored]);

  const changeRolesShown = (next: string[]) => {
    if (next.length === 0) {
      setAnnouncement("At least one role must be shown.");
      return;
    }
    rolesShown.setRoles(next);
    setAnnouncement(`${next.length} of ${matrix?.roles.length ?? next.length} roles shown.`);
  };

  const summary = useMemo(() => {
    if (!matrix) return [];
    return matrix.roles
      .map((role) => ({
        role,
        granted: changes.filter((change) => change.roleCode === role.code && change.granted).length,
        revoked: changes.filter((change) => change.roleCode === role.code && !change.granted).length,
      }))
      .filter((entry) => entry.granted + entry.revoked > 0);
  }, [matrix, changes]);

  return (
    <section
      aria-labelledby="manage-permissions-heading"
      className="flex flex-col rounded-xl border border-border bg-card"
    >
      <LiveRegion message={announcement} />
      <div className="flex flex-col gap-3 border-b border-border p-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 id="manage-permissions-heading" className="text-lg font-medium text-foreground">
            Manage Permissions
          </h2>
          {capabilitiesLoaded && !canEdit ? (
            <p className="text-sm text-muted-foreground">
              Read-only: only a Tenant Admin can change role permissions.
            </p>
          ) : null}
        </div>
        {canEdit ? (
          <div className="flex flex-wrap items-center gap-2">
            {dirty ? (
              <span className="text-sm text-muted-foreground" data-testid="unsaved-count">
                {changes.length} unsaved {changes.length === 1 ? "change" : "changes"}
              </span>
            ) : null}
            {dirty ? (
              <Button type="button" variant="ghost" size="sm" onClick={discard} disabled={save.isPending}>
                Discard
              </Button>
            ) : null}
            <Button
              type="button"
              variant="outline"
              className="uppercase"
              disabled={!dirty || save.isPending || !matrix}
              onClick={() => setConfirmOpen(true)}
              aria-haspopup="dialog"
            >
              {save.isPending ? "Saving…" : "Save changes"}
            </Button>
          </div>
        ) : null}
      </div>

      {feedback?.kind === "stale" ? (
        <Alert variant="destructive" className="m-4 mb-0" data-testid="permissions-stale">
          <AlertTitle>Another administrator changed permissions — reload to see their changes</AlertTitle>
          <AlertDescription>
            Your unsaved changes are kept and re-applied where they still apply.{" "}
            <Button type="button" variant="outline" size="sm" className="mt-2" onClick={() => void reload()}>
              <RotateCcw aria-hidden="true" />
              Reload
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      {feedback?.kind === "error" ? (
        <Alert variant="destructive" className="m-4 mb-0">
          <AlertDescription>{feedback.message} Your unsaved changes are kept.</AlertDescription>
        </Alert>
      ) : null}

      {matrixQuery.status === "pending" ? (
        <div role="status" aria-label="Loading permissions…" className="flex flex-col gap-2 p-4" data-testid="permissions-skeleton">
          <span className="sr-only">Loading permissions…</span>
          {Array.from({ length: 8 }, (_, index) => (
            <Skeleton key={index} className="h-9 w-full" />
          ))}
        </div>
      ) : (
        <div className="p-4">
          <QueryStateBoundary
            query={matrixQuery}
            loginPath="/login"
            permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
            isEmpty={(data) => data.roles.length === 0 || data.groups.length === 0}
            emptyState={{
              title: "No configurable permissions",
              description: "There are no role permissions to configure for this institute yet.",
            }}
          >
            {(data) => {
              const selectedRole = data.roles.some((role) => role.code === mobileRole)
                ? mobileRole
                : (data.roles[0]?.code ?? "");
              return (
                <div className="flex flex-col gap-3">
                  <div className="hidden flex-col gap-1.5 md:flex md:max-w-md" data-testid="roles-shown-control">
                    <Label htmlFor="permissions-roles-shown">Roles shown</Label>
                    <MultiSelect
                      id="permissions-roles-shown"
                      testId="roles-shown"
                      options={data.roles.map((role) => ({ value: role.code, label: role.displayName }))}
                      value={visibleRoles}
                      onChange={changeRolesShown}
                      placeholder="Choose roles to compare"
                      searchPlaceholder="Filter roles…"
                      hideSelectedInTrigger
                      aria-describedby="permissions-roles-shown-help"
                    />
                    <p id="permissions-roles-shown-help" className="text-xs text-muted-foreground">
                      {visibleRoles.length} of {data.roles.length} roles shown. Unsaved changes to hidden roles are kept
                      and included when you save.
                    </p>
                  </div>
                  <div className="flex items-center gap-2 md:hidden">
                    <Label htmlFor="permissions-role-select">Role column</Label>
                    <select
                      id="permissions-role-select"
                      value={selectedRole}
                      onChange={(event) => setMobileRole(event.target.value)}
                      className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
                    >
                      {data.roles.map((role) => (
                        <option key={role.code} value={role.code}>
                          {role.displayName}
                        </option>
                      ))}
                    </select>
                  </div>
                  <MatrixTable
                    matrix={data}
                    canEdit={canEdit && !save.isPending}
                    readOnly={!canEdit}
                    mobileRole={selectedRole}
                    visibleRoles={visibleRoles}
                    effective={effective}
                    onSet={setMany}
                  />
                </div>
              );
            }}
          </QueryStateBoundary>
        </div>
      )}

      <AlertDialog open={confirmOpen} onOpenChange={(open) => (!open && save.isPending ? undefined : setConfirmOpen(open))}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Save permission changes?</AlertDialogTitle>
            {/* The per-role summary is part of the description so it is read when the dialog opens. */}
            <AlertDialogDescription render={<div />} className="flex flex-col gap-2">
              <span>These changes apply to everyone with the role, from their next request.</span>
              <ul className="flex flex-col gap-1 text-sm text-foreground" data-testid="save-summary">
                {summary.map(({ role, granted, revoked }) => (
                  <li key={role.code}>
                    <span className="font-medium">{role.displayName}</span>: {granted} granted, {revoked} revoked
                    {visibleRoles.includes(role.code) ? null : (
                      <span className="text-muted-foreground"> (column not shown)</span>
                    )}
                  </li>
                ))}
              </ul>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <Button type="button" variant="outline" disabled={save.isPending} onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            <Button type="button" disabled={save.isPending} onClick={() => void submit()}>
              {save.isPending ? "Saving…" : "Save"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}

function MatrixTable({
  matrix,
  canEdit,
  readOnly,
  mobileRole,
  visibleRoles,
  effective,
  onSet,
}: {
  matrix: PermissionMatrix;
  canEdit: boolean;
  readOnly: boolean;
  mobileRole: string;
  /** Desktop (md+) columns from "Roles shown". */
  visibleRoles: readonly string[];
  effective: (roleCode: string, row: PermissionMatrixRow) => boolean;
  onSet: (updates: Array<{ roleCode: string; row: PermissionMatrixRow; granted: boolean }>) => void;
}) {
  // Below md: only the selected role. md and up: the "Roles shown" columns.
  const roleCellClass = (roleCode: string) => {
    const mobile = roleCode === mobileRole;
    const desktop = visibleRoles.includes(roleCode);
    if (mobile && desktop) return "";
    if (mobile) return "md:hidden";
    if (desktop) return "hidden md:table-cell";
    return "hidden";
  };

  return (
    <div
      className="relative max-h-[70vh] w-0 min-w-full overflow-auto rounded-lg border border-border"
      data-testid="permission-matrix-scroll"
      tabIndex={0}
      role="region"
      aria-label="Permission matrix"
    >
      <table className="w-max min-w-full border-separate border-spacing-0 text-left text-sm">
        <caption className="sr-only">Role permissions: rows are permissions, columns are roles</caption>
        <thead>
          <tr>
            <th
              scope="col"
              className="sticky top-0 left-0 z-30 w-56 min-w-48 border-b border-border bg-card px-4 py-3 align-bottom font-medium"
            >
              Permission
            </th>
            {matrix.roles.map((role) => (
              <th
                key={role.code}
                scope="col"
                className={cn(
                  "sticky top-0 z-20 w-28 min-w-24 max-w-32 border-b border-border bg-card px-2 py-3 text-center align-bottom text-xs leading-tight font-medium break-words whitespace-normal uppercase",
                  roleCellClass(role.code)
                )}
              >
                {role.displayName}
              </th>
            ))}
          </tr>
        </thead>
        {matrix.groups.map((group) => (
          <GroupBody
            key={group.key}
            group={group}
            matrix={matrix}
            canEdit={canEdit}
            readOnly={readOnly}
            roleCellClass={roleCellClass}
            effective={effective}
            onSet={onSet}
          />
        ))}
      </table>
    </div>
  );
}

function GroupBody({
  group,
  matrix,
  canEdit,
  readOnly,
  roleCellClass,
  effective,
  onSet,
}: {
  group: PermissionMatrixGroup;
  matrix: PermissionMatrix;
  canEdit: boolean;
  readOnly: boolean;
  roleCellClass: (roleCode: string) => string;
  effective: (roleCode: string, row: PermissionMatrixRow) => boolean;
  onSet: (updates: Array<{ roleCode: string; row: PermissionMatrixRow; granted: boolean }>) => void;
}) {
  return (
    <tbody>
      <tr className="bg-muted/60">
        <th
          scope="colgroup"
          colSpan={1}
          className="sticky left-0 z-10 border-b border-border bg-muted px-4 py-2 font-semibold"
        >
          {group.label}
        </th>
        {matrix.roles.map((role) => {
          const editableRows = group.rows.filter((row) => isEditable(row.cells[role.code]?.state));
          if (readOnly || editableRows.length === 0) {
            return <td key={role.code} className={cn("border-b border-border px-2 py-2", roleCellClass(role.code))} />;
          }
          const grantedCount = editableRows.filter((row) => effective(role.code, row)).length;
          const all = grantedCount === editableRows.length;
          const state: "true" | "false" | "mixed" = all ? "true" : grantedCount === 0 ? "false" : "mixed";
          return (
            <td key={role.code} className={cn("border-b border-border px-2 py-2 text-center", roleCellClass(role.code))}>
              <button
                type="button"
                role="checkbox"
                aria-checked={state}
                aria-label={`All ${group.label} — ${role.displayName}`}
                disabled={!canEdit}
                onClick={() =>
                  onSet(editableRows.map((row) => ({ roleCode: role.code, row, granted: !all })))
                }
                data-testid={`select-all-${group.key}-${role.code}`}
                className="inline-flex min-h-6 min-w-6 items-center gap-1.5 rounded-sm px-1.5 py-1 text-xs underline underline-offset-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50"
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "inline-flex size-3.5 items-center justify-center rounded-[3px] border border-input",
                    state !== "false" && "border-primary bg-primary text-primary-foreground"
                  )}
                >
                  {state === "true" ? <Check className="size-3" /> : state === "mixed" ? <Minus className="size-3" /> : null}
                </span>
                {all ? "Deselect all" : "Select all"}
              </button>
            </td>
          );
        })}
      </tr>
      {group.rows.map((row) => (
        <tr key={row.key} className="hover:bg-muted/30">
          <th scope="row" className="sticky left-0 z-10 border-b border-border bg-card px-4 py-2 font-normal">
            {row.label}
          </th>
          {matrix.roles.map((role) => (
            <td key={role.code} className={cn("border-b border-border px-2 py-2 text-center", roleCellClass(role.code))}>
              <MatrixCell
                row={row}
                roleCode={role.code}
                roleName={role.displayName}
                checked={effective(role.code, row)}
                canEdit={canEdit}
                readOnly={readOnly}
                onToggle={(granted) => onSet([{ roleCode: role.code, row, granted }])}
              />
            </td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}

function MatrixCell({
  row,
  roleCode,
  roleName,
  checked,
  canEdit,
  readOnly,
  onToggle,
}: {
  row: PermissionMatrixRow;
  roleCode: string;
  roleName: string;
  checked: boolean;
  canEdit: boolean;
  readOnly: boolean;
  onToggle: (granted: boolean) => void;
}) {
  const cell = row.cells[roleCode];
  const label = `${row.label} — ${roleName}`;
  if (cell && isEditable(cell.state)) {
    return (
      <input
        type="checkbox"
        className="size-4 cursor-pointer accent-primary disabled:cursor-not-allowed"
        aria-label={label}
        checked={checked}
        disabled={!canEdit || readOnly}
        onChange={(event) => onToggle(event.target.checked)}
        data-testid={`cell-${row.key}-${roleCode}`}
      />
    );
  }
  const locked = cell?.state === "LOCKED";
  const reason = cell?.reason || row.reason || (locked ? "System-managed" : "Not available");
  return (
    <Tooltip label={reason}>
      {/* A focusable info control (never a checkbox) so keyboard users can reach the reason tooltip. */}
      <button
        type="button"
        className="inline-flex min-h-6 min-w-6 items-center justify-center rounded-sm text-muted-foreground outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        data-testid={`cell-${row.key}-${roleCode}`}
        data-state={locked ? "locked" : "unavailable"}
      >
        {locked ? <Lock className="size-4" aria-hidden="true" /> : <Minus className="size-4" aria-hidden="true" />}
        <span className="sr-only">
          {label}: {locked ? "locked" : "not available"} — {reason}
        </span>
      </button>
    </Tooltip>
  );
}
