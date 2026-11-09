"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { AlertCircle, AlertTriangle, ArrowDown, ArrowUp, ArrowUpDown, CheckCheck, Printer, Search, Truck } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { LiveRegion } from "@/components/ui/live-region";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { nativeSelectClass } from "@/components/courses/admin/form-field";
import { TableSkeleton } from "@/components/courses/admin/reorderable-table";
import { HubPagination } from "@/components/payments/hub/hub-pagination";
import { TUTE_PAGE_SIZES } from "@/components/tutes/tute-list-page";
import type { TuteRoutes } from "@/components/tutes/tute-routes";
import { isApiClientError } from "@/lib/api/error";
import { saveBlobAs } from "@/lib/api/student-management";
import {
  useConfirmDispatch,
  useDispatchFilterOptions,
  useDispatchQueue,
  usePrintDispatchLabels,
  type DispatchFilters,
  type DispatchQueueView,
  type DispatchRowView,
  type DispatchSort,
  type SortDirection,
} from "@/lib/api/tutes";
import {
  EMPTY_SELECTION,
  buildLabelsRequest,
  confirmDispatchIds,
  explicitCount,
  hasExplicitSelection,
  isRowSelected,
  labelTargetCount,
  missingAddressTargetCount,
  pageSelectionState,
  printButtonLabel,
  selectAllFiltered,
  togglePage,
  toggleRow,
  type DispatchSelection,
} from "@/lib/tutes/dispatch-selection";
import { displayIndex } from "@/lib/tutes/tute-form";
import { TUTE_CAP, useTuteCapabilities } from "@/lib/tutes/use-tute-capabilities";
import { cn } from "@/lib/utils";

export const LABELS_FILENAME = "tute-dispatch-labels.pdf";
export const STALE_MESSAGE = "The list changed — refreshed, please review your selection.";

const SORT_LABELS: Record<DispatchSort, string> = {
  indexNumber: "Index Number",
  studentName: "Student Name",
  tuteCount: "Tutes",
  city: "City",
};

function plural(count: number, word: string, pluralWord = `${word}s`): string {
  return `${count} ${count === 1 ? word : pluralWord}`;
}

/**
 * Tute Dispatch (spec §7): the queue of pending postal copies grouped by
 * recipient, with filters, sorting, a persistent selection, label printing
 * (PDF) and a separate "Confirm dispatch". Printing never marks anything
 * dispatched. Every action is re-validated by the server; a stale selection
 * (409 `TUTE_SELECTION_STALE`) refreshes the list and clears the selection.
 */
export function TuteDispatchPage({ routes }: { routes: TuteRoutes }) {
  const { can } = useTuteCapabilities();
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [courseId, setCourseId] = useState<string | null>(null);
  const [tuteId, setTuteId] = useState<string | null>(null);
  const [twoPlus, setTwoPlus] = useState(false);
  const [sort, setSort] = useState<DispatchSort>("indexNumber");
  const [direction, setDirection] = useState<SortDirection>("asc");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<number>(25);
  const [selection, setSelection] = useState<DispatchSelection>(EMPTY_SELECTION);
  const [announcement, setAnnouncement] = useState("");
  const [missingConfirmOpen, setMissingConfirmOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const toast = useAdminToast();

  const filters: DispatchFilters = useMemo(() => ({ search, courseId, tuteId, twoPlus }), [search, courseId, tuteId, twoPlus]);
  const query = useDispatchQueue({ ...filters, sort, direction, page, size });
  const options = useDispatchFilterOptions();
  const print = usePrintDispatchLabels();
  const confirm = useConfirmDispatch();

  useEffect(() => {
    const handle = setTimeout(() => setSearch(searchInput.trim()), 300);
    return () => clearTimeout(handle);
  }, [searchInput]);

  // Any filter / search / 2+ change clears the selection (spec §7) and returns
  // to page 1 (state adjusted during render, React's "reset state on change" pattern).
  const filtersKey = JSON.stringify(filters);
  const [previousFiltersKey, setPreviousFiltersKey] = useState(filtersKey);
  if (previousFiltersKey !== filtersKey) {
    setPreviousFiltersKey(filtersKey);
    setPage(0);
    if (selection.mode === "allFiltered" || explicitCount(selection) > 0) {
      setSelection(EMPTY_SELECTION);
      setAnnouncement("Selection cleared because the filters changed.");
    }
  }

  const courseOptions = options.data?.courses ?? [];
  const tuteOptions = (options.data?.tutes ?? []).filter((tute) => !courseId || tute.courseId === courseId);
  const filtersActive = Boolean(search || courseId || tuteId || twoPlus);
  const data: DispatchQueueView | undefined = query.data;
  const totalElements = data?.totalElements ?? 0;
  const pageRows = data?.content ?? [];
  const canPrint = can(TUTE_CAP.DISPATCH_PRINT_LABELS);
  const canConfirm = can(TUTE_CAP.UPDATE_STATUS);

  const updateSelection = (next: DispatchSelection, message: string) => {
    setSelection(next);
    setAnnouncement(message);
  };

  const clearSelection = () => updateSelection(EMPTY_SELECTION, "Selection cleared.");

  const handleStale = () => {
    toast.show({ kind: "error", text: STALE_MESSAGE });
    setSelection(EMPTY_SELECTION);
    setAnnouncement("The list changed. Selection cleared.");
    void query.refetch();
  };

  const changeSort = (column: DispatchSort) => {
    if (sort === column) setDirection((current) => (current === "asc" ? "desc" : "asc"));
    else {
      setSort(column);
      setDirection("asc");
    }
    setPage(0);
  };

  const doPrint = () => {
    if (!data) return;
    setMissingConfirmOpen(false);
    const expected = labelTargetCount(selection, data.totalElements);
    print.mutate(buildLabelsRequest(selection, filters, data.totalElements), {
      onSuccess: ({ blob, labelCount }) => {
        saveBlobAs(blob, LABELS_FILENAME);
        if (labelCount !== null && labelCount !== expected) {
          toast.show({
            kind: "error",
            text: `The PDF has ${plural(labelCount, "label")}, but ${expected} were expected. Refresh and check the list before posting.`,
          });
        } else {
          toast.show({
            kind: "success",
            text: `Downloaded ${plural(expected, "label")}. Printing doesn't mark anything as dispatched.`,
          });
        }
      },
      onError: (error) => {
        if (isApiClientError(error) && error.code === "TUTE_SELECTION_STALE") handleStale();
        else toast.show({ kind: "error", text: `Labels could not be printed: ${mutationErrorMessage(error)}` });
      },
    });
  };

  const onPrintClick = () => {
    if (!data || print.isPending) return;
    if (missingAddressTargetCount(selection, data.missingAddressCount) > 0) setMissingConfirmOpen(true);
    else doPrint();
  };

  const confirmIds = confirmDispatchIds(selection);
  const confirmCount = confirmIds?.length ?? 0;

  const doConfirm = () => {
    if (!confirmIds) return;
    setConfirmError(null);
    confirm.mutate(confirmIds, {
      onSuccess: (result) => {
        setConfirmOpen(false);
        setSelection(EMPTY_SELECTION);
        const text = `${plural(result.dispatched, "copy", "copies")} marked as dispatched${
          result.alreadyDispatched ? `; ${result.alreadyDispatched} ${result.alreadyDispatched === 1 ? "was" : "were"} already dispatched` : ""
        }.`;
        setAnnouncement(text);
        toast.show({ kind: "success", text });
        void query.refetch();
      },
      onError: (error) => {
        if (isApiClientError(error) && error.code === "TUTE_SELECTION_STALE") {
          setConfirmOpen(false);
          handleStale();
        } else if (isApiClientError(error) && error.code === "TUTE_ADDRESS_MISSING") {
          const ids = error.details?.fulfillmentIds;
          const count = Array.isArray(ids) ? ids.length : null;
          setConfirmError(
            `${count !== null ? `${plural(count, "selected copy", "selected copies")} ${count === 1 ? "has" : "have"}` : "Some selected copies have"} no delivery address. Add the address on the student's profile, or untick those students, then confirm again. Nothing was dispatched.`
          );
        } else {
          setConfirmError(mutationErrorMessage(error));
        }
      },
    });
  };

  const missingCount = data ? missingAddressTargetCount(selection, data.missingAddressCount) : 0;

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex items-center gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <Truck className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h1 className="text-xl font-semibold text-foreground">Tute Dispatch</h1>
          <p className="text-sm text-muted-foreground">Pending postal tutes, one row per recipient address.</p>
        </div>
      </div>

      <div className="flex min-w-0 flex-col gap-4 rounded-xl border border-border bg-card p-3 shadow-sm sm:p-4">
        <div className="flex flex-col gap-3 lg:flex-row lg:flex-wrap lg:items-end">
          <div className="flex flex-col gap-1 lg:w-64">
            <Label htmlFor="dispatch-search">Search</Label>
            <div className="relative">
              <Input
                id="dispatch-search"
                type="search"
                placeholder="Name, index or city"
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                className="pr-8"
                data-testid="dispatch-search"
              />
              <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            </div>
          </div>
          <div className="flex flex-col gap-1 lg:w-56">
            <Label htmlFor="dispatch-course">Course</Label>
            <select
              id="dispatch-course"
              className={nativeSelectClass}
              value={courseId ?? ""}
              disabled={options.isPending}
              onChange={(event) => {
                const next = event.target.value || null;
                setCourseId(next);
                const tutes = options.data?.tutes ?? [];
                if (!next || (tuteId && !tutes.some((tute) => tute.id === tuteId && tute.courseId === next))) setTuteId(null);
              }}
              data-testid="dispatch-course"
            >
              <option value="">All courses</option>
              {courseOptions.map((course) => (
                <option key={course.id} value={course.id}>
                  {course.name}
                </option>
              ))}
            </select>
          </div>
          <div className="flex flex-col gap-1 lg:w-56">
            <Label htmlFor="dispatch-tute">Tute</Label>
            <select
              id="dispatch-tute"
              className={nativeSelectClass}
              value={tuteId ?? ""}
              disabled={options.isPending}
              onChange={(event) => setTuteId(event.target.value || null)}
              data-testid="dispatch-tute"
            >
              <option value="">{courseId ? "All tutes of this course" : "All tutes"}</option>
              {tuteOptions.map((tute) => (
                <option key={tute.id} value={tute.id}>
                  {tute.name}
                </option>
              ))}
            </select>
          </div>
          {options.isError ? (
            <p className="text-xs text-destructive" role="alert">
              Course and tute filters could not be loaded.
            </p>
          ) : null}
          <div className="flex items-center gap-2 lg:pb-1.5">
            <Switch id="dispatch-two-plus" checked={twoPlus} onCheckedChange={setTwoPlus} data-testid="dispatch-two-plus" />
            <Label htmlFor="dispatch-two-plus" className="font-normal">
              2+ tutes only
            </Label>
          </div>
          <div className="flex flex-col gap-2 sm:flex-row lg:ml-auto">
            {canConfirm ? (
              <Button
                type="button"
                variant="outline"
                disabled={!hasExplicitSelection(selection) || confirm.isPending}
                onClick={() => {
                  setConfirmError(null);
                  confirm.reset();
                  setConfirmOpen(true);
                }}
                data-testid="dispatch-confirm"
              >
                <CheckCheck aria-hidden="true" />
                Confirm dispatch
              </Button>
            ) : null}
            {canPrint ? (
              <Button
                type="button"
                variant="outline"
                className="border-warning text-warning hover:bg-warning/10"
                disabled={!data || totalElements === 0 || print.isPending}
                aria-busy={print.isPending}
                onClick={onPrintClick}
                data-testid="dispatch-print"
              >
                <Printer aria-hidden="true" />
                {print.isPending ? "Preparing PDF…" : printButtonLabel(selection, totalElements)}
              </Button>
            ) : null}
          </div>
        </div>

        <LiveRegion message={announcement} />
        <SelectionBanner
          selection={selection}
          pageRows={pageRows}
          totalElements={totalElements}
          onSelectAll={() => updateSelection(selectAllFiltered(), `All ${totalElements} matching rows selected.`)}
          onClear={clearSelection}
        />

        {query.status === "pending" ? (
          <TableSkeleton label="Loading dispatch queue…" />
        ) : (
          <QueryStateBoundary
            query={query}
            loadingLabel="Loading dispatch queue…"
            loginPath="/login"
            permissionDenied={{ dashboardHref: routes.dashboard }}
          >
            {(queue) =>
              queue.totalElements === 0 ? (
                <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-border px-6 py-10 text-center" role="status" data-testid="dispatch-empty">
                  <p className="text-sm font-medium">{filtersActive ? "No matches for these filters" : "Nothing to send"}</p>
                  <p className="max-w-md text-sm text-muted-foreground">
                    {filtersActive
                      ? "Try another search, course or tute, or clear the filters."
                      : "Every pending postal tute has been dispatched. New ones appear here when students are assigned a tute by post."}
                  </p>
                  {filtersActive ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => {
                        setSearchInput("");
                        setSearch("");
                        setCourseId(null);
                        setTuteId(null);
                        setTwoPlus(false);
                      }}
                    >
                      Clear filters
                    </Button>
                  ) : null}
                </div>
              ) : (
                <div className={cn("flex min-w-0 flex-col gap-3", query.isFetching && "opacity-70")} aria-busy={query.isFetching || undefined}>
                  <DispatchTable
                    rows={queue.content}
                    selection={selection}
                    sort={sort}
                    direction={direction}
                    routes={routes}
                    onSort={changeSort}
                    onTogglePage={() => {
                      const next = togglePage(selection, queue.content);
                      const state = pageSelectionState(next, queue.content);
                      updateSelection(
                        next,
                        state === "all" ? `All ${queue.content.length} on this page selected.` : "Page deselected."
                      );
                    }}
                    onToggleRow={(row) => {
                      const next = toggleRow(selection, row, queue.content);
                      updateSelection(
                        next,
                        `${row.studentName} ${isRowSelected(next, row) ? "selected" : "deselected"}. ${explicitCount(next)} selected.`
                      );
                    }}
                  />
                  {queue.missingAddressCount > 0 ? (
                    <p className="flex items-center gap-1 text-xs text-warning" data-testid="dispatch-missing-summary">
                      <AlertTriangle className="size-3.5" aria-hidden="true" />
                      {plural(queue.missingAddressCount, "recipient")} matching these filters {queue.missingAddressCount === 1 ? "has" : "have"} no address.
                    </p>
                  ) : null}
                  <HubPagination
                    idPrefix="dispatch"
                    page={queue.page}
                    size={size}
                    totalElements={queue.totalElements}
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
      </div>

      <AlertDialog open={missingConfirmOpen} onOpenChange={(next) => setMissingConfirmOpen(next)}>
        <AlertDialogContent data-testid="dispatch-missing-address-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>Some labels have no address</AlertDialogTitle>
            <AlertDialogDescription>
              {`${plural(missingCount, "label")} ${missingCount === 1 ? "has" : "have"} no address and will print “No address provided”. Print anyway?`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
            <Button type="button" onClick={doPrint} data-testid="dispatch-missing-address-print">
              Print anyway
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog
        open={confirmOpen}
        onOpenChange={(next) => {
          if (!next && confirm.isPending) return;
          setConfirmOpen(next);
        }}
      >
        <AlertDialogContent data-testid="dispatch-confirm-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>Confirm dispatch?</AlertDialogTitle>
            <AlertDialogDescription>
              {`Printing does not mark anything dispatched. Confirm that these ${confirmCount} tute ${confirmCount === 1 ? "copy has" : "copies have"} been sent.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          {confirmError ? (
            <Alert variant="destructive" data-testid="dispatch-confirm-error">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{confirmError}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
            <Button type="button" onClick={doConfirm} disabled={confirm.isPending} aria-busy={confirm.isPending} data-testid="dispatch-confirm-submit">
              {confirm.isPending ? "Confirming…" : "Confirm dispatch"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}

function SelectionBanner({
  selection,
  pageRows,
  totalElements,
  onSelectAll,
  onClear,
}: {
  selection: DispatchSelection;
  pageRows: readonly DispatchRowView[];
  totalElements: number;
  onSelectAll: () => void;
  onClear: () => void;
}) {
  const count = explicitCount(selection);
  let content: React.ReactNode = null;
  if (selection.mode === "allFiltered") {
    content = (
      <>
        <span>{`All ${totalElements} matching selected.`}</span>
        <Button type="button" variant="link" size="sm" className="h-auto p-0" onClick={onClear} data-testid="dispatch-clear-selection">
          Clear selection
        </Button>
      </>
    );
  } else if (pageSelectionState(selection, pageRows) === "all" && totalElements > count) {
    content = (
      <>
        <span>{`All ${pageRows.length} on this page selected.`}</span>
        <Button type="button" variant="link" size="sm" className="h-auto p-0" onClick={onSelectAll} data-testid="dispatch-select-all-matching">
          {`Select all ${totalElements} matching`}
        </Button>
      </>
    );
  } else if (count > 0) {
    content = (
      <>
        <span>{`${count} selected.`}</span>
        <Button type="button" variant="link" size="sm" className="h-auto p-0" onClick={onClear} data-testid="dispatch-clear-selection">
          Clear selection
        </Button>
      </>
    );
  }
  if (!content) return null;
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-lg bg-primary/5 px-3 py-2 text-sm" data-testid="dispatch-selection-banner">
      {content}
    </div>
  );
}

function SortHeader({
  column,
  sort,
  direction,
  onSort,
  className,
}: {
  column: DispatchSort;
  sort: DispatchSort;
  direction: SortDirection;
  onSort: (column: DispatchSort) => void;
  className?: string;
}) {
  const active = sort === column;
  const Icon = !active ? ArrowUpDown : direction === "asc" ? ArrowUp : ArrowDown;
  return (
    <th
      scope="col"
      aria-sort={active ? (direction === "asc" ? "ascending" : "descending") : "none"}
      className={cn("px-3 py-2 font-medium", className)}
    >
      <button
        type="button"
        onClick={() => onSort(column)}
        className="inline-flex items-center gap-1 rounded outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
        data-testid={`dispatch-sort-${column}`}
      >
        {SORT_LABELS[column]}
        <Icon className={cn("size-3.5", !active && "text-muted-foreground")} aria-hidden="true" />
      </button>
    </th>
  );
}

function HeaderCheckbox({ state, onToggle, disabled }: { state: "all" | "some" | "none"; onToggle: () => void; disabled: boolean }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === "some";
  }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className="size-4 accent-primary"
      checked={state === "all"}
      onChange={onToggle}
      disabled={disabled}
      aria-label="Select all on this page"
      data-testid="dispatch-select-page"
    />
  );
}

function DispatchTable({
  rows,
  selection,
  sort,
  direction,
  routes,
  onSort,
  onTogglePage,
  onToggleRow,
}: {
  rows: DispatchRowView[];
  selection: DispatchSelection;
  sort: DispatchSort;
  direction: SortDirection;
  routes: TuteRoutes;
  onSort: (column: DispatchSort) => void;
  onTogglePage: () => void;
  onToggleRow: (row: DispatchRowView) => void;
}) {
  return (
    <div className="overflow-x-auto rounded-lg border border-border" data-testid="dispatch-table-scroll">
      <table className="w-full min-w-[820px] text-sm" data-testid="dispatch-table">
        <caption className="sr-only">Tutes to send, one row per recipient. Sortable columns have buttons in their headers.</caption>
        <thead className="bg-muted/50 text-left">
          <tr>
            <th scope="col" className="sticky left-0 z-10 w-10 bg-muted px-3 py-2">
              <HeaderCheckbox state={pageSelectionState(selection, rows)} onToggle={onTogglePage} disabled={rows.length === 0} />
            </th>
            <SortHeader column="indexNumber" sort={sort} direction={direction} onSort={onSort} />
            <SortHeader column="studentName" sort={sort} direction={direction} onSort={onSort} />
            <SortHeader column="tuteCount" sort={sort} direction={direction} onSort={onSort} />
            <th scope="col" className="px-3 py-2 font-medium">To Send</th>
            <SortHeader column="city" sort={sort} direction={direction} onSort={onSort} />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const selected = isRowSelected(selection, row);
            const index = displayIndex(row.indexNumber);
            return (
              <tr
                key={row.groupKey}
                className={cn("border-t border-border align-top", selected && "bg-primary/5")}
                data-testid="dispatch-row"
                data-selected={selected}
              >
                <td className="sticky left-0 z-10 bg-card px-3 py-2">
                  <input
                    type="checkbox"
                    className="size-4 accent-primary"
                    checked={selected}
                    onChange={() => onToggleRow(row)}
                    aria-label={`Select ${row.studentName} (${index})`}
                    data-testid="dispatch-row-checkbox"
                  />
                </td>
                <td className="px-3 py-2 tabular-nums">
                  {routes.studentProfile && row.studentProfileId ? (
                    <Link href={routes.studentProfile(row.studentProfileId)} className="text-primary underline underline-offset-2">
                      {index}
                    </Link>
                  ) : (
                    index
                  )}
                </td>
                <td className="px-3 py-2 font-medium">{row.studentName}</td>
                <td className="px-3 py-2">
                  <span
                    className="inline-flex min-w-6 items-center justify-center rounded-full bg-muted px-2 py-0.5 text-xs font-medium tabular-nums"
                    aria-label={`${row.tuteCount} ${row.tuteCount === 1 ? "tute" : "tutes"}`}
                  >
                    {row.tuteCount}
                  </span>
                </td>
                <td className="px-3 py-2">
                  <ul className="flex flex-wrap gap-1" aria-label={`Tutes to send to ${row.studentName}`}>
                    {row.items.map((item) => (
                      <li key={item.fulfillmentId} className="rounded-md bg-muted px-2 py-0.5 text-xs break-words text-foreground" data-testid="dispatch-chip">
                        {item.label}
                      </li>
                    ))}
                  </ul>
                </td>
                <td className="px-3 py-2">
                  <div className="flex flex-wrap items-center gap-1">
                    <span>{row.city?.trim() ? row.city : "—"}</span>
                    {row.addressMissing ? (
                      <span className="inline-flex items-center gap-1 text-xs font-medium text-warning" data-testid="dispatch-no-address">
                        <AlertTriangle className="size-3.5" aria-hidden="true" />
                        No address
                      </span>
                    ) : null}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
