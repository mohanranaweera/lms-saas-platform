"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  ArrowLeft,
  Ban,
  HandHelping,
  MoreHorizontal,
  RefreshCw,
  Repeat,
  Search,
  ShieldCheck,
  UserPlus,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { nativeSelectClass } from "@/components/courses/admin/form-field";
import { TableSkeleton } from "@/components/courses/admin/reorderable-table";
import { HubPagination } from "@/components/payments/hub/hub-pagination";
import { AssignStudentsDialog } from "@/components/tutes/assign-students-dialog";
import { ReasonDialog } from "@/components/tutes/reason-dialog";
import { TUTE_PAGE_SIZES } from "@/components/tutes/tute-list-page";
import type { TuteRoutes } from "@/components/tutes/tute-routes";
import { useCourse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import {
  useCancelFulfillment,
  useFulfillmentStatus,
  useSyncTutePayments,
  useTute,
  useTuteFulfillments,
  type FulfillmentView,
  type SyncResultView,
  type TuteView,
} from "@/lib/api/tutes";
import {
  METHOD_LABELS,
  TUTE_PAYMENT_TYPE_LABELS,
  displayIndex,
  formatTutePrice,
  fulfillmentStatusLabel,
} from "@/lib/tutes/tute-form";
import { TUTE_CAP, useTuteCapabilities } from "@/lib/tutes/use-tute-capabilities";
import { cn } from "@/lib/utils";

const STATUS_FILTERS = [
  { value: "", label: "All statuses" },
  { value: "PENDING", label: "Pending" },
  { value: "DISPATCHED", label: "Dispatched / handed over" },
  { value: "CANCELLED", label: "Cancelled" },
];

function syncSummary(result: SyncResultView): string {
  return `Sync complete: ${result.added} added, ${result.alreadyAssigned} already assigned, ${result.skippedOutOfStock} skipped (out of stock), ${result.held} on hold (payment reversed).`;
}

function statusError(error: unknown): string {
  if (isApiClientError(error) && error.code === "TUTE_INVALID_STATE") {
    return `${error.message} The list has been refreshed.`;
  }
  return mutationErrorMessage(error);
}

/**
 * Tute detail (spec §3/§4): stock summary, Sync payments, the assigned
 * students with their delivery status, and per-row actions by capability.
 * Assigning, unrolling or changing status never charges anyone or changes
 * course access; the server re-validates every state change.
 */
export function TuteDetailPage({ courseId, tuteId, routes }: { courseId: string; tuteId: string; routes: TuteRoutes }) {
  const { can } = useTuteCapabilities();
  const courseQuery = useCourse(courseId);
  const tuteQuery = useTute(courseId, tuteId);
  const sync = useSyncTutePayments(courseId, tuteId);
  const [syncResult, setSyncResult] = useState<SyncResultView | null>(null);
  const [assignOpen, setAssignOpen] = useState(false);
  const [assignKey, setAssignKey] = useState(0);
  const toast = useAdminToast();
  const courseName = courseQuery.data?.name ?? "Course";

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <Link
          href={routes.tutes(courseId)}
          className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to tutes
        </Link>
        <Breadcrumbs
          items={[
            { label: "Courses", href: routes.courses },
            { label: courseName, href: routes.tutes(courseId) },
            { label: tuteQuery.data?.name ?? "Tute" },
          ]}
        />
      </div>

      <QueryStateBoundary
        query={tuteQuery}
        loadingLabel="Loading tute…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: routes.dashboard }}
      >
        {(tute) => {
          const archived = tute.status === "ARCHIVED";
          return (
            <div className="flex min-w-0 flex-col gap-5">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="flex min-w-0 flex-col gap-1">
                  <h1 className="flex flex-wrap items-center gap-2 text-xl font-semibold break-words text-foreground">
                    {tute.name}
                    {archived ? <Badge variant="outline">Archived</Badge> : null}
                  </h1>
                  <p className="text-sm text-muted-foreground">
                    {TUTE_PAYMENT_TYPE_LABELS[tute.paymentType]}
                    {tute.paymentType === "PAID" ? ` · ${formatTutePrice(tute.price, tute.currency)}` : ""}
                    {tute.paidLessonTitle ? ` · Sold through ${tute.paidLessonTitle}` : ""}
                  </p>
                  {tute.description ? <p className="max-w-prose text-sm break-words text-foreground">{tute.description}</p> : null}
                </div>
                <div className="flex flex-wrap gap-2">
                  {can(TUTE_CAP.UPDATE) && !archived ? (
                    <Button
                      type="button"
                      variant="outline"
                      disabled={sync.isPending}
                      aria-busy={sync.isPending}
                      onClick={() =>
                        sync.mutate(undefined, {
                          onSuccess: (result) => setSyncResult(result),
                          onError: (error) => toast.show({ kind: "error", text: `Sync failed: ${mutationErrorMessage(error)}` }),
                        })
                      }
                      data-testid="tute-sync-payments"
                    >
                      <RefreshCw className={cn(sync.isPending && "animate-spin")} aria-hidden="true" />
                      {sync.isPending ? "Syncing…" : "Sync payments"}
                    </Button>
                  ) : null}
                  {can(TUTE_CAP.ENROLL) && !archived ? (
                    <Button
                      type="button"
                      onClick={() => {
                        setAssignKey((key) => key + 1);
                        setAssignOpen(true);
                      }}
                      data-testid="tute-assign-students"
                    >
                      <UserPlus aria-hidden="true" />
                      Assign students
                    </Button>
                  ) : null}
                </div>
              </div>

              <StockSummary tute={tute} />

              <div role="status" aria-live="polite" data-testid="tute-sync-result">
                {syncResult ? (
                  <p className="rounded-lg border border-border bg-muted/30 px-3 py-2 text-sm">{syncSummary(syncResult)}</p>
                ) : null}
              </div>

              <AssignedStudents courseId={courseId} tute={tute} routes={routes} onToast={toast.show} />

              <AssignStudentsDialog
                key={assignKey}
                courseId={courseId}
                tuteId={tute.id}
                tuteName={tute.name}
                open={assignOpen}
                onOpenChange={setAssignOpen}
                onAssigned={(result) => {
                  setAssignOpen(false);
                  toast.show({
                    kind: "success",
                    text: `${result.added} assigned${result.alreadyAssigned ? `, ${result.alreadyAssigned} already had this tute` : ""}. ${result.available} copies left.`,
                  });
                }}
              />
            </div>
          );
        }}
      </QueryStateBoundary>
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}

function StockSummary({ tute }: { tute: TuteView }) {
  const items = [
    { label: "Quantity", value: tute.quantity, hint: "Total copies held" },
    { label: "Reserved", value: tute.reserved, hint: "Pending delivery" },
    { label: "Dispatched", value: tute.dispatched, hint: "Sent or handed over" },
    { label: "Available", value: tute.available, hint: "Free to assign" },
  ];
  return (
    <dl className="grid grid-cols-2 gap-3 md:grid-cols-4" data-testid="tute-stock-summary">
      {items.map((item) => (
        <div
          key={item.label}
          className={cn(
            "rounded-xl border border-border bg-card p-3 shadow-sm",
            item.label === "Available" && item.value <= 0 && "border-warning/50"
          )}
        >
          <dt className="text-xs text-muted-foreground">{item.label}</dt>
          <dd className="flex items-center gap-1 text-xl font-semibold tabular-nums" data-testid={`tute-stock-${item.label.toLowerCase()}`}>
            {item.label === "Available" && item.value <= 0 ? <AlertTriangle className="size-4 text-warning" aria-hidden="true" /> : null}
            {item.value.toLocaleString("en-US")}
          </dd>
          <dd className="text-xs text-muted-foreground">
            {item.label === "Available" && item.value <= 0 ? "Out of stock" : item.hint}
          </dd>
        </div>
      ))}
    </dl>
  );
}

type PendingAction =
  | { kind: "cancel"; row: FulfillmentView }
  | { kind: "release"; row: FulfillmentView };

function AssignedStudents({
  courseId,
  tute,
  routes,
  onToast,
}: {
  courseId: string;
  tute: TuteView;
  routes: TuteRoutes;
  onToast: (message: { kind: "success" | "error"; text: string }) => void;
}) {
  const { can } = useTuteCapabilities();
  const [status, setStatus] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<number>(10);
  const [action, setAction] = useState<PendingAction | null>(null);
  const [actionKey, setActionKey] = useState(0);
  const [announcement, setAnnouncement] = useState("");
  const query = useTuteFulfillments(courseId, tute.id, { status, search, page, size });
  const cancel = useCancelFulfillment(courseId, tute.id);
  const statusChange = useFulfillmentStatus(courseId, tute.id);

  useEffect(() => {
    const handle = setTimeout(() => {
      setSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(handle);
  }, [searchInput]);

  const done = (text: string) => {
    setAnnouncement(text);
    onToast({ kind: "success", text });
  };
  const failed = (error: unknown) => {
    if (isApiClientError(error) && error.code === "TUTE_INVALID_STATE") void query.refetch();
    onToast({ kind: "error", text: statusError(error) });
  };

  const markHandedOver = (row: FulfillmentView) =>
    statusChange.mutate(
      { fulfillmentId: row.id, body: { action: "MARK_HANDED_OVER" } },
      { onSuccess: () => done(`${row.studentName}'s copy marked as handed over.`), onError: failed }
    );
  const changeMethod = (row: FulfillmentView) => {
    const method = row.method === "POSTAL" ? "COLLECTION" : "POSTAL";
    statusChange.mutate(
      { fulfillmentId: row.id, body: { action: "CHANGE_METHOD", method } },
      { onSuccess: () => done(`${row.studentName}'s delivery method changed to ${METHOD_LABELS[method]}.`), onError: failed }
    );
  };

  const canUnroll = can(TUTE_CAP.UNROLL);
  const canStatus = can(TUTE_CAP.UPDATE_STATUS);

  return (
    <section aria-labelledby="assigned-students-heading" className="flex min-w-0 flex-col gap-3 rounded-xl border border-border bg-card p-3 shadow-sm sm:p-4">
      <h2 id="assigned-students-heading" className="text-base font-semibold">
        Assigned students
      </h2>
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="relative w-full sm:w-72">
          <label htmlFor="fulfillments-search" className="sr-only">
            Search assigned students
          </label>
          <Input
            id="fulfillments-search"
            type="search"
            placeholder="Search by name or index"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            className="pr-8"
            data-testid="fulfillments-search"
          />
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor="fulfillments-status">Status</Label>
          <select
            id="fulfillments-status"
            className={cn(nativeSelectClass, "w-56")}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value);
              setPage(0);
            }}
            data-testid="fulfillments-status"
          >
            {STATUS_FILTERS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>
      <LiveRegion message={announcement} />

      {query.status === "pending" ? (
        <TableSkeleton label="Loading assigned students…" />
      ) : (
        <QueryStateBoundary query={query} loadingLabel="Loading assigned students…" permissionDenied={{ dashboardHref: routes.dashboard }}>
          {(data) =>
            data.content.length === 0 ? (
              <div className="rounded-lg border border-dashed border-border px-4 py-8 text-center text-sm" data-testid="fulfillments-empty">
                <p className="font-medium">{search || status ? "No assigned students match these filters" : "No students have this tute yet"}</p>
                <p className="text-muted-foreground">
                  {search || status
                    ? "Change the status or search, or clear them."
                    : "Use Assign students, or Sync payments to add students whose confirmed payments include this tute."}
                </p>
              </div>
            ) : (
              <div className="flex min-w-0 flex-col gap-3">
                <div className="overflow-x-auto rounded-lg border border-border">
                  <table className="w-full min-w-[760px] text-sm" data-testid="fulfillments-table">
                    <caption className="sr-only">Students assigned to {tute.name}</caption>
                    <thead className="bg-muted/50 text-left">
                      <tr>
                        <th scope="col" className="sticky left-0 bg-muted px-3 py-2 font-medium">Index Number</th>
                        <th scope="col" className="px-3 py-2 font-medium">Student Name</th>
                        <th scope="col" className="px-3 py-2 font-medium">Status</th>
                        <th scope="col" className="px-3 py-2 font-medium">Method</th>
                        <th scope="col" className="px-3 py-2 font-medium">Source</th>
                        <th scope="col" className="px-3 py-2 font-medium">Notes</th>
                        <th scope="col" className="px-3 py-2 text-right font-medium">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.content.map((row) => {
                        const pending = row.status === "PENDING";
                        const actions = {
                          cancel: canUnroll && pending,
                          handedOver: canStatus && pending && row.method === "COLLECTION",
                          method: canStatus && pending,
                          release: canStatus && pending && row.held,
                        };
                        const anyAction = Object.values(actions).some(Boolean);
                        return (
                          <tr key={row.id} className="border-t border-border align-top" data-testid="fulfillment-row">
                            <td className="sticky left-0 bg-card px-3 py-2 tabular-nums">
                              {routes.studentProfile && row.studentProfileId ? (
                                <Link href={routes.studentProfile(row.studentProfileId)} className="text-primary underline underline-offset-2">
                                  {displayIndex(row.indexNumber)}
                                </Link>
                              ) : (
                                displayIndex(row.indexNumber)
                              )}
                            </td>
                            <td className="px-3 py-2 font-medium">{row.studentName}</td>
                            <td className="px-3 py-2">
                              <FulfillmentStatusBadge row={row} />
                            </td>
                            <td className="px-3 py-2">{METHOD_LABELS[row.method] ?? row.method}</td>
                            <td className="px-3 py-2">{row.source === "PAYMENT" ? "Payment" : "Manual"}</td>
                            <td className="px-3 py-2">
                              <div className="flex flex-wrap gap-1">
                                {row.held || row.paymentReversedAt ? (
                                  <Badge variant="destructive" data-testid="fulfillment-hold">
                                    <AlertTriangle aria-hidden="true" />
                                    {row.status === "DISPATCHED"
                                      ? "Payment reversed after dispatch"
                                      : row.held
                                        ? "Payment reversed — on hold"
                                        : row.holdReleasedAt
                                          ? "Payment reversed — hold released"
                                          : "Payment reversed"}
                                  </Badge>
                                ) : null}
                                {row.addressMissing && row.method === "POSTAL" && pending ? (
                                  <Badge variant="warning" data-testid="fulfillment-no-address">
                                    <AlertTriangle aria-hidden="true" />
                                    No address
                                  </Badge>
                                ) : null}
                                {row.status === "CANCELLED" && row.cancelReason ? (
                                  <span className="text-xs text-muted-foreground">Reason: {row.cancelReason}</span>
                                ) : null}
                              </div>
                            </td>
                            <td className="px-3 py-2 text-right">
                              {anyAction ? (
                                <DropdownMenu>
                                  <DropdownMenuTrigger
                                    render={<Button type="button" variant="ghost" size="icon-sm" />}
                                    aria-label={`Actions for ${row.studentName}`}
                                    data-testid="fulfillment-actions"
                                  >
                                    <MoreHorizontal aria-hidden="true" />
                                  </DropdownMenuTrigger>
                                  <DropdownMenuContent data-testid="fulfillment-actions-content">
                                    {actions.handedOver ? (
                                      <DropdownMenuItem onClick={() => markHandedOver(row)} data-testid="fulfillment-action-handed-over">
                                        <HandHelping aria-hidden="true" />
                                        Mark handed over
                                      </DropdownMenuItem>
                                    ) : null}
                                    {actions.method ? (
                                      <DropdownMenuItem onClick={() => changeMethod(row)} data-testid="fulfillment-action-method">
                                        <Repeat aria-hidden="true" />
                                        {row.method === "POSTAL" ? "Change to Collection" : "Change to Postal"}
                                      </DropdownMenuItem>
                                    ) : null}
                                    {actions.release ? (
                                      <DropdownMenuItem
                                        onClick={() => {
                                          statusChange.reset();
                                          setActionKey((key) => key + 1);
                                          setAction({ kind: "release", row });
                                        }}
                                        data-testid="fulfillment-action-release"
                                      >
                                        <ShieldCheck aria-hidden="true" />
                                        Release hold
                                      </DropdownMenuItem>
                                    ) : null}
                                    {actions.cancel ? (
                                      <DropdownMenuItem
                                        variant="destructive"
                                        onClick={() => {
                                          cancel.reset();
                                          setActionKey((key) => key + 1);
                                          setAction({ kind: "cancel", row });
                                        }}
                                        data-testid="fulfillment-action-cancel"
                                      >
                                        <Ban aria-hidden="true" />
                                        Cancel (unroll)
                                      </DropdownMenuItem>
                                    ) : null}
                                  </DropdownMenuContent>
                                </DropdownMenu>
                              ) : (
                                <span className="sr-only">No actions</span>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                <HubPagination
                  idPrefix="fulfillments"
                  page={data.page}
                  size={size}
                  totalElements={data.totalElements}
                  sizes={TUTE_PAGE_SIZES}
                  onPageChange={setPage}
                  onSizeChange={(next) => {
                    setSize(next);
                    setPage(0);
                  }}
                />
              </div>
            )
          }
        </QueryStateBoundary>
      )}

      <ReasonDialog
        key={`cancel-${actionKey}`}
        open={action?.kind === "cancel"}
        onOpenChange={(next) => {
          if (!next) setAction(null);
        }}
        title={action ? `Cancel ${action.row.studentName}'s copy?` : "Cancel copy?"}
        description="The pending copy is cancelled and returned to stock. This doesn't change the student's enrollment, payments or course access."
        confirmLabel="Cancel copy"
        destructive
        testId="unroll-dialog"
        pending={cancel.isPending}
        error={cancel.isError ? statusError(cancel.error) : null}
        onSubmit={(reason) => {
          if (action?.kind !== "cancel") return;
          const row = action.row;
          cancel.mutate(
            { fulfillmentId: row.id, reason },
            {
              onSuccess: () => {
                setAction(null);
                done(`${row.studentName}'s copy cancelled.`);
              },
              onError: (error) => {
                if (isApiClientError(error) && error.code === "TUTE_INVALID_STATE") void query.refetch();
              },
            }
          );
        }}
      />
      <ReasonDialog
        key={`release-${actionKey}`}
        open={action?.kind === "release"}
        onOpenChange={(next) => {
          if (!next) setAction(null);
        }}
        title={action ? `Release hold for ${action.row.studentName}?` : "Release hold?"}
        description="This copy's payment was refunded or charged back. Releasing the hold puts it back in the dispatch queue. It doesn't change any payment."
        confirmLabel="Release hold"
        testId="release-hold-dialog"
        pending={statusChange.isPending}
        error={statusChange.isError ? statusError(statusChange.error) : null}
        onSubmit={(reason) => {
          if (action?.kind !== "release") return;
          const row = action.row;
          statusChange.mutate(
            { fulfillmentId: row.id, body: { action: "RELEASE_HOLD", reason } },
            {
              onSuccess: () => {
                setAction(null);
                done(`Hold released for ${row.studentName}.`);
              },
              onError: (error) => {
                if (isApiClientError(error) && error.code === "TUTE_INVALID_STATE") void query.refetch();
              },
            }
          );
        }}
      />
    </section>
  );
}

function FulfillmentStatusBadge({ row }: { row: FulfillmentView }) {
  const label = fulfillmentStatusLabel(row.status, row.method);
  return (
    <Badge
      variant={row.status === "PENDING" ? "secondary" : row.status === "CANCELLED" ? "outline" : "default"}
      className={cn(row.status === "DISPATCHED" && "bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300")}
      data-testid="fulfillment-status"
      data-status={row.status}
    >
      {label}
    </Badge>
  );
}
