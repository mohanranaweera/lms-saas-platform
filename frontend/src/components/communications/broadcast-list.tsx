"use client";

import { useState } from "react";
import Link from "next/link";
import { useBroadcasts, AUDIENCE_LABELS, CHANNEL_LABELS, type BroadcastView } from "@/lib/api/communications";
import { formatDateTime } from "@/lib/format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";

function delivered(view: BroadcastView): string {
  const sent = view.stats.reduce((sum, stats) => sum + stats.sent, 0);
  const total = view.stats.reduce((sum, stats) => sum + stats.sent + stats.pending + stats.failed + stats.skipped, 0);
  return `${sent} / ${total}`;
}

/**
 * Sent broadcasts, newest first (Wave 11). The backend scopes the list: staff
 * with `COMMUNICATIONS`/`VIEW` see every broadcast in the tenant, a teacher
 * sees only their own.
 */
export function BroadcastList({ basePath }: { basePath: string }) {
  const [page, setPage] = useState(0);
  const query = useBroadcasts(page);

  const columns: DataTableColumn<BroadcastView>[] = [
    {
      key: "subject",
      header: "Subject",
      cell: (row) => (
        <Link href={`${basePath}/${row.id}`} className="font-medium text-foreground underline-offset-4 hover:underline">
          {row.subject}
        </Link>
      ),
      hideOnCard: true,
    },
    { key: "audience", header: "Audience", cell: (row) => AUDIENCE_LABELS[row.audienceType] },
    { key: "channels", header: "Channels", cell: (row) => row.channels.map((c) => CHANNEL_LABELS[c]).join(", ") },
    { key: "recipients", header: "Recipients", cell: (row) => row.recipientCount },
    { key: "delivered", header: "Delivered", cell: (row) => delivered(row) },
    { key: "sent", header: "Sent", cell: (row) => formatDateTime(row.createdAt) },
  ];

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading broadcasts…"
      loginPath="/login"
      isEmpty={(data) => data.content.length === 0 && page === 0}
      emptyState={{
        title: "No broadcasts yet",
        description: "Messages you send to students or teachers will be listed here with their delivery status.",
      }}
    >
      {(data) => (
        <div className="flex flex-col gap-3">
          <DataTable
            columns={columns}
            rows={data.content}
            rowKey={(row) => row.id}
            caption="Sent broadcasts"
            cardHeading={(row) => (
              <Link href={`${basePath}/${row.id}`} className="font-medium underline-offset-4 hover:underline">
                {row.subject}
              </Link>
            )}
          />
          {data.totalPages > 1 ? (
            <div className="flex items-center justify-between gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <span className="text-sm text-muted-foreground">
                Page {page + 1} of {data.totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          ) : null}
        </div>
      )}
    </QueryStateBoundary>
  );
}
