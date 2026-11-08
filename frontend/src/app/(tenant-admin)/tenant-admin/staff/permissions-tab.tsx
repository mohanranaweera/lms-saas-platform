"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
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
  edits,
  setEdits,
}: {
  canEdit: boolean;
  edits: Map<string, boolean>;
  setEdits: (next: Map<string, boolean>) => void;
}) {
  const matrixQuery = usePermissionMatrix();
  const save = useSavePermissionMatrix();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [feedback, setFeedback] = useState<SaveFeedback>(null);
  const [announcement, setAnnouncement] = useState("");
  const [mobileRole, setMobileRole] = useState<string>("");

  const matrix = matrixQuery.data;
  const changes = useMemo(() => pendingChanges(matrix, edits), [matrix, edits]);
  const dirty = canEdit && changes.length > 0;

  // Warn before leaving the page with unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

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
    setAnnouncement("");
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
          {!canEdit ? (
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
            <AlertDialogDescription>
              These changes apply to everyone with the role, from their next request.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <ul className="flex flex-col gap-1 text-sm" data-testid="save-summary">
            {summary.map(({ role, granted, revoked }) => (
              <li key={role.code}>
                <span className="font-medium">{role.displayName}</span>: {granted} granted, {revoked} revoked
              </li>
            ))}
          </ul>
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
  effective,
  onSet,
}: {
  matrix: PermissionMatrix;
  canEdit: boolean;
  readOnly: boolean;
  mobileRole: string;
  effective: (roleCode: string, row: PermissionMatrixRow) => boolean;
  onSet: (updates: Array<{ roleCode: string; row: PermissionMatrixRow; granted: boolean }>) => void;
}) {
  const roleCellClass = (roleCode: string) => cn(roleCode !== mobileRole && "hidden md:table-cell");

  return (
    <div
      className="max-h-[70vh] overflow-auto rounded-lg border border-border"
      data-testid="permission-matrix-scroll"
      tabIndex={0}
      role="region"
      aria-label="Permission matrix"
    >
      <table className="w-full border-separate border-spacing-0 text-left text-sm">
        <caption className="sr-only">Role permissions: rows are permissions, columns are roles</caption>
        <thead>
          <tr>
            <th
              scope="col"
              className="sticky top-0 left-0 z-30 min-w-48 border-b border-border bg-card px-4 py-3 font-medium"
            >
              Permission
            </th>
            {matrix.roles.map((role) => (
              <th
                key={role.code}
                scope="col"
                className={cn(
                  "sticky top-0 z-20 min-w-32 border-b border-border bg-card px-4 py-3 text-center font-medium uppercase",
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
            return <td key={role.code} className={cn("border-b border-border px-4 py-2", roleCellClass(role.code))} />;
          }
          const grantedCount = editableRows.filter((row) => effective(role.code, row)).length;
          const all = grantedCount === editableRows.length;
          const state: "true" | "false" | "mixed" = all ? "true" : grantedCount === 0 ? "false" : "mixed";
          return (
            <td key={role.code} className={cn("border-b border-border px-4 py-2 text-center", roleCellClass(role.code))}>
              <button
                type="button"
                role="checkbox"
                aria-checked={state}
                aria-label={`${all ? "Deselect" : "Select"} all ${group.label} — ${role.displayName}`}
                disabled={!canEdit}
                onClick={() =>
                  onSet(editableRows.map((row) => ({ roleCode: role.code, row, granted: !all })))
                }
                data-testid={`select-all-${group.key}-${role.code}`}
                className="inline-flex items-center gap-1.5 rounded-sm text-xs underline underline-offset-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50"
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
            <td key={role.code} className={cn("border-b border-border px-4 py-2 text-center", roleCellClass(role.code))}>
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
      <span
        className="inline-flex items-center justify-center text-muted-foreground"
        data-testid={`cell-${row.key}-${roleCode}`}
        data-state={locked ? "locked" : "unavailable"}
      >
        {locked ? <Lock className="size-4" aria-hidden="true" /> : <Minus className="size-4" aria-hidden="true" />}
        <span className="sr-only">
          {label}: {locked ? "locked" : "not available"} — {reason}
        </span>
      </span>
    </Tooltip>
  );
}
