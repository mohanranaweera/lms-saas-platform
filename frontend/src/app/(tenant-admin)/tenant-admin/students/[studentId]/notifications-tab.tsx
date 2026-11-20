"use client";

import { useState } from "react";
import Link from "next/link";
import { CHANNEL_LABELS, useStudentDeliveryHistory, type StudentDeliveryView } from "@/lib/api/communications";
import { formatDateTime } from "@/lib/format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { DeliveryStatusBadge, deliveryReason } from "@/components/communications/delivery-status";

/**
 * Student detail - Notifications (Wave 11, PAR-03-04): every broadcast
 * delivery to this student, per channel, newest first. `COMMUNICATIONS`/`VIEW`
 * (Tenant Admin, Student Support, Auditor); another tenant's student is a 404.
 */
export function NotificationsTab({ studentId }: { studentId: string }) {
  const [page, setPage] = useState(0);
  const query = useStudentDeliveryHistory(studentId, page);
  const columns: DataTableColumn<StudentDeliveryView>[] = [
    {
      key: "subject",
      header: "Message",
      cell: (row) => (
        <Link href={`/tenant-admin/communications/${row.broadcastId}`} className="underline-offset-4 hover:underline">
          {row.subject ?? "Broadcast"}
        </Link>
      ),
      hideOnCard: true,
    },
    { key: "channel", header: "Channel", cell: (row) => CHANNEL_LABELS[row.channel] },
    { key: "status", header: "Status", cell: (row) => <DeliveryStatusBadge status={row.status} /> },
    { key: "detail", header: "Detail", cell: (row) => deliveryReason(row.errorCode) ?? "—" },
    { key: "sent", header: "Sent", cell: (row) => formatDateTime(row.createdAt) },
  ];
  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading notification history…"
      loginPath="/login"
      isEmpty={(data) => data.content.length === 0 && page === 0}
      emptyState={{
        title: "No messages yet",
        description: "Broadcasts sent to this student will be listed here with their delivery status.",
      }}
    >
      {(data) => (
        <div className="flex flex-col gap-3">
          <DataTable
            columns={columns}
            rows={data.content}
            rowKey={(row) => row.id}
            caption="Notification history"
            cardHeading={(row) => row.subject ?? "Broadcast"}
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
