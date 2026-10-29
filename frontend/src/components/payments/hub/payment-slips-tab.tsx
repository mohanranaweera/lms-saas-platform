"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Info, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { SlipFlagBadge, SlipStatusBadge } from "@/components/payments/status-badges";
import { ApproveSlipDialog } from "@/components/payments/slips/approve-slip-dialog";
import { RejectSlipDialog } from "@/components/payments/slips/reject-slip-dialog";
import { SlipNoteDialog } from "@/components/payments/slips/slip-note-dialog";
import { SlipOrderBreakdown } from "@/components/payments/slips/slip-order-breakdown";
import { SlipViewerDialog } from "@/components/payments/slips/slip-viewer-dialog";
import { formatDateTime, formatMoney } from "@/lib/format";
import { useAuth } from "@/lib/auth/auth-context";
import { canAddSlipNotes, canReviewSlips } from "@/lib/auth/permissions";
import {
  useSlipNotes,
  useSlipReviewQueue,
  type PaymentSlipResponse,
  type PaymentSlipStatus,
} from "@/lib/api/payment-slips";
import { HubPagination } from "./hub-pagination";

type SlipView = "pending" | "APPROVED" | "REJECTED";

const VIEWS: Array<{ value: SlipView; label: string }> = [
  { value: "pending", label: "Pending" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" },
];

const SEARCH_DEBOUNCE_MS = 300;

/**
 * PAY-HUB-01 "Payment Slips" tab (Klass "Pending" grid). Loads the pending
 * queue (`SUBMITTED` + `UNDER_REVIEW`) by default; Approved / Rejected switch
 * the backend `status` filter. One server-side search covers student name,
 * email, mobile, slip reference and order number.
 *
 * Approve / Decline / Add note render per role and slip state as UX only;
 * every endpoint re-checks permission and state, and the duplicate-slip gates
 * (override reason) live in the backend and the approve dialog.
 */
export function PaymentSlipsTab() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const canReview = canReviewSlips(role);
  const canNote = canAddSlipNotes(role);
  const [view, setView] = useState<SlipView>("pending");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [viewerSlip, setViewerSlip] = useState<PaymentSlipResponse | null>(null);
  const [announcement, setAnnouncement] = useState("");

  useEffect(() => {
    const next = searchInput.trim();
    // Only a real change of the term searches again (and goes back to page 1).
    if (next === search) return;
    const handle = window.setTimeout(() => {
      setSearch(next);
      setPage(0);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(handle);
  }, [searchInput, search]);

  const query = useSlipReviewQueue({
    status: view === "pending" ? undefined : (view as PaymentSlipStatus),
    search: search || undefined,
    page,
    size,
  });

  const columns: DataTableColumn<PaymentSlipResponse>[] = [
    {
      key: "student",
      header: "Student",
      cell: (row) => <StudentCell slip={row} />,
      hideOnCard: true,
    },
    { key: "contact", header: "Contact", cell: (row) => row.studentMobile ?? row.studentEmail ?? "—" },
    { key: "order", header: "Order", cell: (row) => row.displayOrderNumber ?? "—", className: "whitespace-nowrap" },
    {
      key: "price",
      header: `Price`,
      cell: (row) => formatMoney(row.orderAmount + Number(row.orderDiscount ?? 0), row.orderCurrency),
      className: "text-right whitespace-nowrap",
    },
    {
      key: "discount",
      header: "Discount",
      cell: (row) =>
        Number(row.orderDiscount ?? 0) > 0 ? formatMoney(Number(row.orderDiscount), row.orderCurrency) : "—",
      className: "text-right whitespace-nowrap",
    },
    {
      key: "toPay",
      header: "To pay",
      cell: (row) => <span className="font-semibold">{formatMoney(row.orderAmount, row.orderCurrency)}</span>,
      className: "text-right whitespace-nowrap",
    },
    {
      key: "slip",
      header: "Payment slip",
      cell: (row) => (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={`View payment slip ${row.referenceNumber}`}
          title="View payment slip"
          onClick={() => setViewerSlip(row)}
          data-testid="slip-view"
        >
          <Info aria-hidden="true" className="text-blue-600 dark:text-blue-400" />
        </Button>
      ),
      hideOnCard: true,
    },
    {
      key: "flags",
      header: "Flags",
      cell: (row) =>
        row.flags.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {row.flags.map((flag) => (
              <SlipFlagBadge key={flag.id} flagType={flag.flagType} />
            ))}
          </div>
        ) : (
          "—"
        ),
    },
    {
      key: "actions",
      header: "Actions",
      cell: (row) => <RowActions slip={row} canReview={canReview} canNote={canNote} onAnnounce={setAnnouncement} />,
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <LiveRegion message={announcement} />
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="flex flex-col gap-1.5 sm:w-80">
          <Label htmlFor="slips-search">Search</Label>
          <div className="relative">
            <Search className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground" aria-hidden="true" />
            <Input
              id="slips-search"
              type="search"
              value={searchInput}
              maxLength={100}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder="Student, mobile, email, reference or order no."
              className="pl-8"
              data-testid="slips-search"
            />
          </div>
        </div>
        <div role="group" aria-label="Slip status" className="inline-flex rounded-lg border border-border p-0.5">
          {VIEWS.map((option) => (
            <Button
              key={option.value}
              type="button"
              size="sm"
              variant={view === option.value ? "secondary" : "ghost"}
              aria-pressed={view === option.value}
              onClick={() => {
                setView(option.value);
                setPage(0);
              }}
            >
              {option.label}
            </Button>
          ))}
        </div>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading payment slips…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0 && view === "pending" && !search && page === 0}
        emptyState={{
          title: "No slips waiting for approval",
          description: "When a student uploads a bank transfer slip, it appears here for review.",
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            {data.content.length === 0 ? (
              <EmptyState
                title={search ? "No slips match your search" : "No slips here"}
                description={
                  search
                    ? "Try a different name, mobile, reference or order number."
                    : "There are no slips with this status on this page."
                }
                action={
                  search
                    ? {
                        label: "Clear search",
                        onClick: () => {
                          setSearchInput("");
                          setSearch("");
                        },
                      }
                    : undefined
                }
              />
            ) : (
              <DataTable
                columns={columns}
                rows={data.content}
                rowKey={(row) => row.id}
                caption="Payment slips"
                cardHeading={(row) => <StudentCell slip={row} />}
                cardHeadingAdornment={(row) => <SlipStatusBadge status={row.status} />}
                cardFooter={(row) => (
                  <div className="flex items-center gap-1">
                    <Button type="button" variant="outline" size="sm" onClick={() => setViewerSlip(row)}>
                      <Info aria-hidden="true" />
                      View slip
                    </Button>
                    <RowActions slip={row} canReview={canReview} canNote={canNote} onAnnounce={setAnnouncement} />
                  </div>
                )}
                renderExpandedRow={(row) => <SlipExpandedDetails slip={row} />}
              />
            )}
            <HubPagination
              idPrefix="slips"
              page={data.page}
              size={size}
              totalElements={data.totalElements}
              onPageChange={setPage}
              onSizeChange={(next) => {
                setSize(next);
                setPage(0);
              }}
            />
          </div>
        )}
      </QueryStateBoundary>

      {viewerSlip ? (
        <SlipViewerDialog
          slip={viewerSlip}
          open={!!viewerSlip}
          onOpenChange={(open) => (open ? undefined : setViewerSlip(null))}
          canReview={canReview}
        />
      ) : null}
    </div>
  );
}

function StudentCell({ slip }: { slip: PaymentSlipResponse }) {
  const name = slip.studentName ?? slip.studentEmail ?? "Student";
  return slip.studentProfileId ? (
    <Link
      href={`/tenant-admin/students/${encodeURIComponent(slip.studentProfileId)}`}
      className="font-medium text-blue-700 underline-offset-2 hover:underline dark:text-blue-300"
    >
      {name}
    </Link>
  ) : (
    <span className="font-medium">{name}</span>
  );
}

function RowActions({
  slip,
  canReview,
  canNote,
  onAnnounce,
}: {
  slip: PaymentSlipResponse;
  canReview: boolean;
  canNote: boolean;
  onAnnounce: (message: string) => void;
}) {
  const reviewable = canReview && slip.status === "UNDER_REVIEW";
  const checking = canReview && slip.status === "SUBMITTED";
  return (
    <div className="flex items-center gap-0.5">
      {reviewable ? (
        <>
          <ApproveSlipDialog slip={slip} variant="icon" onDone={() => onAnnounce(`Slip ${slip.referenceNumber} approved.`)} />
          <RejectSlipDialog slip={slip} variant="icon" onDone={() => onAnnounce(`Slip ${slip.referenceNumber} declined.`)} />
        </>
      ) : checking ? (
        <span className="px-1 text-xs text-muted-foreground" title="Duplicate check in progress">
          Checking…
        </span>
      ) : null}
      {canNote ? <SlipNoteDialog slip={slip} onSaved={onAnnounce} /> : null}
    </div>
  );
}

function SlipExpandedDetails({ slip }: { slip: PaymentSlipResponse }) {
  const notes = useSlipNotes(slip.id);
  return (
    <div className="grid gap-4 p-2 lg:grid-cols-2">
      <div className="flex flex-col gap-3">
        <SlipOrderBreakdown slip={slip} />
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Reference</dt>
          <dd>{slip.referenceNumber}</dd>
          <dt className="text-muted-foreground">Submitted</dt>
          <dd>{formatDateTime(slip.submittedAt)}</dd>
          {slip.reviewedAt ? (
            <>
              <dt className="text-muted-foreground">Reviewed</dt>
              <dd>
                {formatDateTime(slip.reviewedAt)}
                {slip.reviewerEmail ? ` by ${slip.reviewerEmail}` : ""}
              </dd>
            </>
          ) : null}
        </dl>
        <Link
          href={`/tenant-admin/payments/slip-review/${encodeURIComponent(slip.id)}`}
          className="text-sm font-medium text-foreground underline"
        >
          Open full slip page
        </Link>
      </div>
      <section aria-label="Notes" className="flex flex-col gap-2">
        <h4 className="text-sm font-medium text-foreground">Notes</h4>
        {notes.isPending ? (
          <p className="text-sm text-muted-foreground">Loading notes…</p>
        ) : notes.isError ? (
          <p className="text-sm text-destructive">Notes could not be loaded.</p>
        ) : notes.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No notes yet.</p>
        ) : (
          <ul className="flex flex-col gap-2" data-testid="slip-notes">
            {notes.data.map((note) => (
              <li key={note.id} className="rounded-lg border border-border p-2 text-sm">
                <p className="whitespace-pre-wrap text-foreground">{note.body}</p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {note.authorEmail ?? "Staff"} · {formatDateTime(note.createdAt)}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
