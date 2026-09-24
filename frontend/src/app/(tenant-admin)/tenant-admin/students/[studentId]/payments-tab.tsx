"use client";

import Link from "next/link";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useStudentLedger } from "@/lib/api/ledger";
import type { LedgerHistoryEntryResponse } from "@/lib/api/ledger";
import { useAuth } from "@/lib/auth/auth-context";
import { canViewAccessExpiryQueue } from "@/lib/auth/permissions";
import { formatDateTime, formatMoney, shortId } from "@/lib/format";

const columns: DataTableColumn<LedgerHistoryEntryResponse>[] = [
  { key: "entryType", header: "Type", cell: (row) => row.entryType },
  { key: "amount", header: "Amount", cell: (row) => formatMoney(row.amount) },
  { key: "order", header: "Order", cell: (row) => shortId(row.orderId, "Order"), hideOnCard: true },
  { key: "createdAt", header: "Date", cell: (row) => formatDateTime(row.createdAt) },
];

/**
 * Payments tab (Wave 3) — ledger-derived, per `.claude/rules/payments.md`
 * §2 ("Payment history ... must be derived from ledger entries + slip state,
 * not from the order or upload record"). Surfaces a link into the existing
 * Reactivation Approvals queue rather than building a new "extend access"
 * control, per the wave-03 plan §5.
 */
export function PaymentsTab({ studentId }: { studentId: string }) {
  const { session } = useAuth();
  const query = useStudentLedger(studentId);
  const canViewReactivationQueue = canViewAccessExpiryQueue(session?.role ?? null);

  return (
    <div className="flex flex-col gap-4">
      {canViewReactivationQueue ? (
        <p className="text-sm text-muted-foreground">
          Need to extend this student&apos;s access to an expired enrollment?{" "}
          <Link
            href="/tenant-admin/access-expiry/reactivation-approvals"
            className="font-medium text-foreground hover:underline"
          >
            Go to Reactivation Approvals
          </Link>
          .
        </p>
      ) : null}
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading payment history…"
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: "No payments yet",
          description: "This student has no ledger-confirmed payments on record yet.",
        }}
      >
        {(entries) => (
          <DataTable
            columns={columns}
            rows={entries}
            rowKey={(row) => row.id}
            caption="Payment history"
            cardHeading={(row) => row.entryType}
            cardHeadingAdornment={(row) => (
              <span className="text-xs text-muted-foreground">{formatMoney(row.amount)}</span>
            )}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}
