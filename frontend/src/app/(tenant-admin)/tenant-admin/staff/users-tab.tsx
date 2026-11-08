"use client";

import { useEffect, useMemo, useState } from "react";
import {
  ChevronFirst,
  ChevronLast,
  ChevronLeft,
  ChevronRight,
  KeyRound,
  Mail,
  MoreVertical,
  Pencil,
  Search,
  UserCheck,
  UserCog,
  UserX,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { LiveRegion } from "@/components/ui/live-region";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StaffStatusBadge } from "@/components/staff/staff-status-badge";
import { SignInMobileCard } from "@/components/auth/sign-in-mobile-card";
import { isApiClientError } from "@/lib/api/error";
import { useTenantTimeZone } from "@/lib/api/klass-admin";
import { formatZonedDateTime } from "@/lib/datetime";
import { useStaffLifecycle, useStaffList, type StaffResponse } from "@/lib/api/staff";
import {
  AddUserDialog,
  ChangeRoleDialog,
  DeactivateStaffDialog,
  EditStaffDialog,
  staffDisplayName,
} from "./staff-dialogs";

const PAGE_SIZES = [10, 25, 50] as const;
const DEBOUNCE_MS = 300;

type RowDialog =
  | { kind: "edit"; staff: StaffResponse }
  | { kind: "role"; staff: StaffResponse }
  | { kind: "deactivate"; staff: StaffResponse }
  | { kind: "mobile"; staff: StaffResponse }
  | null;

function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timeout);
  }, [value, delay]);
  return debounced;
}

/**
 * Staff → Users (Klass parity). Server-side search (debounced) and
 * pagination; row actions render only when the server's per-row `actions`
 * flags AND my capabilities allow them, so no action is ever shown that the
 * server would refuse. Every endpoint still re-checks permission.
 */
export function UsersTab({
  canManage,
  canAssignRoles,
}: {
  canManage: boolean;
  canAssignRoles: boolean;
}) {
  const { timeZone } = useTenantTimeZone();
  const [searchInput, setSearchInput] = useState("");
  const q = useDebounced(searchInput.trim(), DEBOUNCE_MS);
  const [size, setSize] = useState<number>(10);
  const [page, setPage] = useState(0);
  const filterKey = `${q}|${size}`;
  const [lastFilterKey, setLastFilterKey] = useState(filterKey);
  if (filterKey !== lastFilterKey) {
    setLastFilterKey(filterKey);
    setPage(0);
  }

  const params = useMemo(() => ({ q, page, size }), [q, page, size]);
  const listQuery = useStaffList(params);
  const lifecycle = useStaffLifecycle();

  const [addOpen, setAddOpen] = useState(false);
  const [dialog, setDialog] = useState<RowDialog>(null);
  const [notice, setNotice] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(""), 8000);
    return () => clearTimeout(timeout);
  }, [notice]);

  // A page emptied by a deactivation/filter falls back to the last real page.
  const totalPages = listQuery.data?.totalPages ?? 0;
  if (listQuery.data && page > 0 && page >= totalPages) {
    setPage(Math.max(totalPages - 1, 0));
  }

  const runLifecycle = (staff: StaffResponse, action: "reactivate" | "resend-invite") => {
    if (lifecycle.isPending) return;
    setActionError(null);
    lifecycle.mutate(
      { id: staff.id, action },
      {
        onSuccess: () =>
          setNotice(
            action === "reactivate"
              ? `${staffDisplayName(staff)} was reactivated.`
              : `Invitation re-sent to ${staff.email}.`
          ),
        onError: (error) =>
          setActionError(
            isApiClientError(error)
              ? error.message
              : action === "reactivate"
                ? "The user couldn't be reactivated. Please try again."
                : "The invitation couldn't be re-sent. Please try again."
          ),
      }
    );
  };

  const renderActions = (staff: StaffResponse) => {
    const name = staffDisplayName(staff);
    const a = staff.actions;
    const showEdit = canManage && a.canEdit;
    const showRole = canAssignRoles && a.canChangeRole;
    const showDeactivate = canManage && a.canDeactivate;
    const showReactivate = canManage && a.canReactivate;
    const showResend = canManage && a.canResendInvite;
    const lifecycleBusy = lifecycle.isPending && lifecycle.variables?.id === staff.id;
    return (
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={`Actions for ${name}`}
              data-testid={`staff-actions-${staff.id}`}
              disabled={lifecycleBusy}
            />
          }
        >
          <MoreVertical aria-hidden="true" />
        </DropdownMenuTrigger>
        <DropdownMenuContent>
          {showEdit ? (
            <DropdownMenuItem onClick={() => setDialog({ kind: "edit", staff })}>
              <Pencil aria-hidden="true" />
              Edit details
            </DropdownMenuItem>
          ) : null}
          {showRole ? (
            <DropdownMenuItem onClick={() => setDialog({ kind: "role", staff })}>
              <UserCog aria-hidden="true" />
              Change role
            </DropdownMenuItem>
          ) : null}
          {showResend ? (
            <DropdownMenuItem onClick={() => runLifecycle(staff, "resend-invite")}>
              <Mail aria-hidden="true" />
              Resend invite
            </DropdownMenuItem>
          ) : null}
          {showReactivate ? (
            <DropdownMenuItem onClick={() => runLifecycle(staff, "reactivate")}>
              <UserCheck aria-hidden="true" />
              Reactivate
            </DropdownMenuItem>
          ) : null}
          <DropdownMenuItem onClick={() => setDialog({ kind: "mobile", staff })}>
            <KeyRound aria-hidden="true" />
            Sign-in mobile
          </DropdownMenuItem>
          {showDeactivate ? (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem variant="destructive" onClick={() => setDialog({ kind: "deactivate", staff })}>
                <UserX aria-hidden="true" />
                Deactivate
              </DropdownMenuItem>
            </>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
    );
  };

  const columns: DataTableColumn<StaffResponse>[] = [
    {
      key: "firstName",
      header: "First Name",
      hideOnCard: true,
      cell: (staff) => (
        <span className="flex flex-wrap items-center gap-2">
          {staff.firstName || "—"}
          {staff.status !== "ACTIVE" ? <StaffStatusBadge status={staff.status} /> : null}
        </span>
      ),
    },
    { key: "lastName", header: "Last Name", hideOnCard: true, cell: (staff) => staff.lastName || "—" },
    { key: "email", header: "Email", cell: (staff) => <span className="break-all">{staff.email}</span> },
    {
      key: "contact",
      header: "Contact Number",
      cell: (staff) => <span className="tabular-nums">{staff.contactNumber || "—"}</span>,
    },
    { key: "role", header: "Role", cell: (staff) => staff.roleName ?? staff.roleCode },
    {
      key: "lastLogin",
      header: "Last Login",
      cell: (staff) => (
        <span className="whitespace-nowrap tabular-nums">
          {formatZonedDateTime(staff.lastLoginAt, timeZone, "Never")}
        </span>
      ),
    },
    { key: "actions", header: "Actions", hideOnCard: true, cell: renderActions },
  ];

  const data = listQuery.data;
  const total = data?.totalElements ?? 0;
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min((page + 1) * size, total);
  const lastPage = Math.max(totalPages - 1, 0);

  return (
    <section aria-labelledby="staff-users-heading" className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4">
      <h2 id="staff-users-heading" className="sr-only">
        Users
      </h2>
      <LiveRegion message={notice} />
      {notice ? (
        <p className="text-sm text-muted-foreground" data-testid="staff-notice">
          {notice}
        </p>
      ) : null}
      {actionError ? (
        <p role="alert" className="text-sm text-destructive">
          {actionError}
        </p>
      ) : null}

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="w-full sm:max-w-sm">
          <Label htmlFor="staff-search" className="sr-only">
            Search staff by name, email or phone
          </Label>
          <div className="relative">
            <Input
              id="staff-search"
              type="search"
              placeholder="Search"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              className="pr-8"
            />
            <Search
              aria-hidden="true"
              className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground"
            />
          </div>
        </div>
        {canManage ? (
          <Button type="button" aria-haspopup="dialog" onClick={() => setAddOpen(true)} className="uppercase">
            Add User
          </Button>
        ) : null}
      </div>

      {listQuery.status === "pending" ? (
        <div role="status" aria-live="polite" aria-label="Loading staff…" className="flex flex-col gap-2" data-testid="staff-skeleton">
          <span className="sr-only">Loading staff…</span>
          {Array.from({ length: 5 }, (_, index) => (
            <Skeleton key={index} className="h-10 w-full" />
          ))}
        </div>
      ) : (
        <QueryStateBoundary
          query={listQuery}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(result) => result.content.length === 0 && result.totalElements === 0}
          emptyState={
            q
              ? {
                  title: `No staff match "${q}"`,
                  description: "Try a different name, email or phone number.",
                  action: { label: "Clear search", onClick: () => setSearchInput("") },
                }
              : {
                  title: "No staff yet",
                  description: canManage
                    ? "Add your first staff member. They'll get an email to set their password."
                    : "This institute doesn't have any staff accounts yet.",
                  action: canManage ? { label: "Add User", onClick: () => setAddOpen(true) } : undefined,
                }
          }
        >
          {(result) => (
            <div className="flex flex-col gap-3" aria-busy={listQuery.isFetching}>
              <DataTable
                columns={columns}
                rows={result.content}
                rowKey={(staff) => staff.id}
                caption="Staff users"
                cardHeading={(staff) => staffDisplayName(staff)}
                cardHeadingAdornment={(staff) =>
                  staff.status !== "ACTIVE" ? <StaffStatusBadge status={staff.status} /> : null
                }
                cardFooter={(staff) => <div className="flex justify-end">{renderActions(staff)}</div>}
              />
              <nav
                aria-label="Staff pagination"
                className="flex flex-col items-end gap-2 sm:flex-row sm:items-center sm:justify-end sm:gap-4"
              >
                <div className="flex items-center gap-2">
                  <Label htmlFor="staff-page-size" className="text-sm font-normal text-muted-foreground">
                    Items per page:
                  </Label>
                  <select
                    id="staff-page-size"
                    value={size}
                    onChange={(event) => setSize(Number(event.target.value))}
                    className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
                  >
                    {PAGE_SIZES.map((option) => (
                      <option key={option} value={option}>
                        {option}
                      </option>
                    ))}
                  </select>
                </div>
                <p className="text-sm tabular-nums text-muted-foreground" aria-live="polite" data-testid="staff-range">
                  {from}–{to} of {total}
                </p>
                <div className="flex items-center gap-1">
                  <Button type="button" variant="ghost" size="icon-sm" aria-label="First page" disabled={page === 0} onClick={() => setPage(0)}>
                    <ChevronFirst aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Previous page"
                    disabled={page === 0}
                    onClick={() => setPage((current) => Math.max(current - 1, 0))}
                  >
                    <ChevronLeft aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Next page"
                    disabled={page >= lastPage}
                    onClick={() => setPage((current) => Math.min(current + 1, lastPage))}
                  >
                    <ChevronRight aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Last page"
                    disabled={page >= lastPage}
                    onClick={() => setPage(lastPage)}
                  >
                    <ChevronLast aria-hidden="true" />
                  </Button>
                </div>
              </nav>
            </div>
          )}
        </QueryStateBoundary>
      )}

      {canManage ? (
        <AddUserDialog
          open={addOpen}
          onOpenChange={setAddOpen}
          onCreated={(staff) => setNotice(`Invitation sent to ${staff.email}.`)}
        />
      ) : null}
      {dialog?.kind === "edit" ? (
        <EditStaffDialog staff={dialog.staff} onOpenChange={(open) => !open && setDialog(null)} onSaved={setNotice} />
      ) : null}
      {dialog?.kind === "role" ? (
        <ChangeRoleDialog staff={dialog.staff} onOpenChange={(open) => !open && setDialog(null)} onSaved={setNotice} />
      ) : null}
      {dialog?.kind === "deactivate" ? (
        <DeactivateStaffDialog staff={dialog.staff} onOpenChange={(open) => !open && setDialog(null)} onDone={setNotice} />
      ) : null}
      <Sheet open={dialog?.kind === "mobile"} onOpenChange={(open) => (open ? null : setDialog(null))}>
        <SheetContent
          side="right"
          className="w-full data-[side=right]:w-full data-[side=right]:sm:max-w-full data-[side=right]:md:max-w-md"
        >
          <SheetHeader>
            <SheetTitle>
              {dialog?.kind === "mobile" ? `Sign-in mobile — ${staffDisplayName(dialog.staff)}` : "Sign-in mobile"}
            </SheetTitle>
            <SheetDescription>{dialog?.kind === "mobile" ? dialog.staff.email : null}</SheetDescription>
          </SheetHeader>
          {dialog?.kind === "mobile" ? (
            <div className="px-4 pb-4">
              <SignInMobileCard
                path={`/v1/staff/${dialog.staff.id}/login-mobile`}
                description="Lets this staff member sign in with a mobile number instead of their email."
                canEdit={canManage}
                idPrefix="staff-login-mobile"
              />
            </div>
          ) : null}
        </SheetContent>
      </Sheet>
    </section>
  );
}
