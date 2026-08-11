"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useAuth } from "@/lib/auth/auth-context";
import { useStaff, useUpdateStaffRole, type StaffAccount } from "@/lib/api/staff";
import { STAFF_ROLE_OPTIONS, type StaffRoleCode } from "@/lib/validation/staff";
import { isApiClientError } from "@/lib/api/error";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { RoleBadge } from "../role-badge";
import { StaffStatusBadge } from "../status-badge";

/**
 * Staff Detail (MVP-005) — read-only account fields via `GET /v1/staff/{id}`,
 * plus an inline role-edit control via `PATCH /v1/staff/{id}` (see
 * `RoleEditor` below). There is still no delete/deactivate/password-reset
 * control: none of those have a backend endpoint yet (see `StaffController`'s
 * doc comment), so building any of them would invent behavior that doesn't
 * exist.
 *
 * A `404` here (own tenant's missing id) and a `403` (another tenant's id,
 * or a caller with no staff-detail permission) are, by backend design,
 * returned in the identical shape for "this id isn't yours to see" — the
 * `notFound` prop below makes `QueryStateBoundary` render both cases
 * identically (generic "Staff account not found" copy, no raw backend
 * message/code, no retry button) instead of leaking which case occurred.
 */
export default function StaffDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const query = useStaff(id);
  const { session } = useAuth();

  // UX convenience only, per `.claude/rules/ui-ux.md` §1 (Staff sub-roles)
  // and `.claude/rules/security.md` — the real enforcement is the 403
  // `PATCH /v1/staff/{id}` returns regardless of what this control shows or
  // hides. Read-only Auditor (view-only on Staff & Roles) never sees it.
  const canEditRole = session?.role === "TENANT_ADMIN";

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/staff"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to staff
        </Link>
        <h1 className="mt-1 text-xl font-semibold text-foreground">Staff details</h1>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading staff account…"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        notFound={{ title: "Staff account not found" }}
        loginPath="/login"
      >
        {(staff) => (
          <Card className="max-w-lg">
            <CardHeader>
              <h2
                data-slot="card-title"
                className="font-heading text-base leading-snug font-medium"
              >
                {staff.name}
              </h2>
            </CardHeader>
            <CardContent>
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3 text-sm">
                <dt className="font-medium text-foreground">Name</dt>
                <dd className="text-muted-foreground">{staff.name}</dd>

                <dt className="font-medium text-foreground">Email</dt>
                <dd className="text-muted-foreground">{staff.email}</dd>

                <dt className="font-medium text-foreground">Role</dt>
                <dd>
                  <RoleEditor staff={staff} canEdit={canEditRole} />
                </dd>

                <dt className="font-medium text-foreground">Status</dt>
                <dd>
                  <StaffStatusBadge status={staff.status} />
                </dd>
              </dl>
            </CardContent>
          </Card>
        )}
      </QueryStateBoundary>
    </div>
  );
}

/**
 * Read-only role display for everyone, plus an inline "Change role" edit
 * mode for a Tenant Admin (`canEdit`). Reuses the exact hand-rolled
 * fieldset/legend/radio pattern from `staff/new/page.tsx`'s role picker —
 * there is no shared `RadioGroup` primitive in this codebase yet.
 */
function RoleEditor({ staff, canEdit }: { staff: StaffAccount; canEdit: boolean }) {
  const [editing, setEditing] = useState(false);
  const [selectedRole, setSelectedRole] = useState<StaffRoleCode | "">("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useUpdateStaffRole(staff.id);

  if (!editing) {
    return (
      <div className="flex flex-wrap items-center gap-2">
        <RoleBadge roleCode={staff.roleCode} />
        {canEdit ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              setSelectedRole(
                STAFF_ROLE_OPTIONS.some((option) => option.value === staff.roleCode)
                  ? (staff.roleCode as StaffRoleCode)
                  : ""
              );
              setFieldError(null);
              setPageError(null);
              setEditing(true);
            }}
          >
            Change role
          </Button>
        ) : null}
      </div>
    );
  }

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFieldError(null);
    setPageError(null);

    if (!selectedRole) {
      setFieldError("Select a role.");
      return;
    }

    try {
      await mutation.mutateAsync(selectedRole);
      setEditing(false);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.status === 403) {
          // Same posture as `new/page.tsx`'s submit handler: a real,
          // backend-verified 403, not a client guess. Retrying an
          // unauthorized submit would just fail identically, so this stays
          // as an inline message rather than a full permission-denied panel
          // (the rest of the page — the account itself — is still visible
          // and correct).
          setPageError(error.message);
          return;
        }
        const roleFieldError = error.fieldErrors.find((fe) => fe.field === "roleCode");
        if (roleFieldError) {
          setFieldError(roleFieldError.message);
          return;
        }
        setPageError(error.message);
        return;
      }
      setPageError("An unexpected error occurred. Please try again.");
    }
  };

  return (
    <form className="flex flex-col gap-3" onSubmit={onSubmit} aria-busy={mutation.isPending} noValidate>
      <span role="status" aria-live="polite" className="sr-only">
        {mutation.isPending ? "Saving role…" : ""}
      </span>
      <fieldset
        disabled={mutation.isPending}
        className="flex flex-col gap-2"
        aria-invalid={!!fieldError}
        aria-describedby={fieldError ? "staff-role-edit-error" : undefined}
      >
        <legend className="text-sm font-medium text-foreground">Role</legend>
        <div className="flex flex-col gap-1.5">
          {STAFF_ROLE_OPTIONS.map((option) => (
            <label key={option.value} className="flex items-center gap-2 text-sm text-foreground">
              <input
                type="radio"
                name="staff-role-edit"
                value={option.value}
                checked={selectedRole === option.value}
                onChange={() => setSelectedRole(option.value)}
                className="size-4"
              />
              {option.label}
            </label>
          ))}
        </div>
        {fieldError ? (
          <p id="staff-role-edit-error" role="alert" className="text-xs text-destructive">
            {fieldError}
          </p>
        ) : null}
      </fieldset>

      {pageError ? (
        <p role="alert" className="text-xs text-destructive">
          {pageError}
        </p>
      ) : null}

      <div className="flex flex-row gap-2">
        <Button type="submit" size="sm" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save"}
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={mutation.isPending}
          onClick={() => {
            setEditing(false);
            setFieldError(null);
            setPageError(null);
          }}
        >
          Cancel
        </Button>
      </div>
    </form>
  );
}
