"use client";

import Link from "next/link";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LedgerEntryTypeBadge } from "@/components/payments/status-badges";
import { formatDateTime } from "@/lib/format";
import { useLedgerHistory } from "@/lib/api/ledger";

/**
 * Student Payment History (PAY-3). `GET /api/v1/ledger/history` — always the
 * caller's own history, no filter params exist on this endpoint, so this
 * screen has exactly one, contextual empty state ("no payments yet"); it
 * does not invent a filter UI the backend can't support.
 *
 * Card-list rendering — this is a consumer surface, not the admin data-table
 * (`components/ui/data-table.tsx` is reserved for Tenant Admin screens per
 * the module plan).
 */
export default function PaymentHistoryPage() {
  const query = useLedgerHistory();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Payment history</h1>
        <p className="text-sm text-muted-foreground">
          Every confirmed payment and refund recorded against your account.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading payment history…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(entries) => entries.length === 0}
        emptyState={{
          title: "No payments yet",
          description:
            "You haven't made any payments yet. Once you enroll in a course and complete payment, it will appear here.",
        }}
      >
        {(entries) => (
          <ul className="flex flex-col gap-3">
            {entries.map((entry) => (
              <li
                key={entry.id}
                className="flex flex-col gap-2 rounded-lg border border-border p-4"
              >
                <div className="flex items-start justify-between gap-2">
                  <LedgerEntryTypeBadge entryType={entry.entryType} />
                  <span className="text-sm font-semibold text-foreground">
                    {entry.amount.toFixed(2)}
                  </span>
                </div>
                <dl className="grid grid-cols-2 gap-x-2 gap-y-1 text-xs text-muted-foreground">
                  <dt className="font-medium text-foreground">Date</dt>
                  <dd>{formatDateTime(entry.createdAt)}</dd>
                </dl>
                <Link
                  href={`/student/payments/awaiting-confirmation/${entry.orderId}`}
                  className="text-sm font-medium text-foreground hover:underline"
                >
                  View order status
                </Link>
              </li>
            ))}
          </ul>
        )}
      </QueryStateBoundary>
    </div>
  );
}
