"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import {
  AUDIENCE_LABELS,
  CHANNEL_LABELS,
  useBroadcast,
  useBroadcastDeliveries,
  type BroadcastView,
  type DeliveryChannel,
  type DeliveryStatus,
  type DeliveryView,
} from "@/lib/api/communications";
import { formatDateTime } from "@/lib/format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { DeliveryStatusBadge, deliveryReason } from "./delivery-status";

const ALL = "ALL";
const STATUSES: DeliveryStatus[] = ["PENDING", "SENT", "FAILED", "SKIPPED"];

/**
 * One broadcast: what was sent, to whom, and the per-recipient delivery log
 * (Wave 11, PAR-12-04 / PAR-21-03). Destinations are masked by the backend;
 * the counts refresh on their own while anything is still queued.
 */
export function BroadcastDetail({ broadcastId, backHref }: { broadcastId: string; backHref: string }) {
  const query = useBroadcast(broadcastId);
  return (
    <div className="flex flex-col gap-6">
      <Link href={backHref} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:underline">
        <ArrowLeft className="size-4" aria-hidden="true" />
        Back to broadcasts
      </Link>
      <QueryStateBoundary query={query} loadingLabel="Loading broadcast…" loginPath="/login">
        {(broadcast) => (
          <>
            <Summary broadcast={broadcast} />
            <DeliveryLog broadcast={broadcast} />
          </>
        )}
      </QueryStateBoundary>
    </div>
  );
}

function Summary({ broadcast }: { broadcast: BroadcastView }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{broadcast.subject}</CardTitle>
        <CardDescription>
          {AUDIENCE_LABELS[broadcast.audienceType]}
          {broadcast.audiencePaymentStatus ? ` (${broadcast.audiencePaymentStatus.toLowerCase()})` : ""} ·{" "}
          {broadcast.recipientCount} {broadcast.recipientCount === 1 ? "recipient" : "recipients"} · sent{" "}
          {formatDateTime(broadcast.createdAt)}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="whitespace-pre-wrap text-sm text-foreground">{broadcast.body}</p>
        {broadcast.smsBody ? (
          <div>
            <p className="text-xs font-medium text-muted-foreground">SMS / WhatsApp text</p>
            <p className="whitespace-pre-wrap text-sm text-foreground">{broadcast.smsBody}</p>
          </div>
        ) : null}
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {broadcast.stats.map((stats) => (
            <div key={stats.channel} className="rounded-lg border border-border p-3">
              <dt className="text-sm font-medium text-foreground">{CHANNEL_LABELS[stats.channel]}</dt>
              <dd className="mt-1 text-sm text-muted-foreground">
                {stats.sent} sent
                {stats.pending > 0 ? ` · ${stats.pending} queued` : ""}
                {stats.failed > 0 ? ` · ${stats.failed} failed` : ""}
                {stats.skipped > 0 ? ` · ${stats.skipped} skipped` : ""}
              </dd>
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  );
}

function DeliveryLog({ broadcast }: { broadcast: BroadcastView }) {
  const [status, setStatus] = useState<DeliveryStatus | "">("");
  const [channel, setChannel] = useState<DeliveryChannel | "">("");
  const [page, setPage] = useState(0);
  const query = useBroadcastDeliveries(broadcast.id, { status, channel, page });

  const columns: DataTableColumn<DeliveryView>[] = [
    { key: "recipient", header: "Recipient", cell: (row) => row.recipientName ?? "Unknown", hideOnCard: true },
    { key: "channel", header: "Channel", cell: (row) => CHANNEL_LABELS[row.channel] },
    { key: "status", header: "Status", cell: (row) => <DeliveryStatusBadge status={row.status} /> },
    { key: "destination", header: "Sent to", cell: (row) => row.destination ?? "—" },
    {
      key: "detail",
      header: "Detail",
      cell: (row) => {
        const reason = deliveryReason(row.errorCode);
        if (row.status === "PENDING" && row.nextAttemptAt) {
          return `Retrying ${formatDateTime(row.nextAttemptAt)} (attempt ${row.attemptCount} of 3 failed)`;
        }
        return reason ?? (row.completedAt ? formatDateTime(row.completedAt) : "—");
      },
    },
  ];

  return (
    <Card>
      <CardHeader>
        <CardTitle>Delivery log</CardTitle>
        <CardDescription>
          Failed sends are retried automatically up to 3 times. Skipped messages were never sent to a provider.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-3 sm:flex-row">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="delivery-status">Status</Label>
            <Select
              value={status || ALL}
              onValueChange={(value) => {
                setStatus(value === ALL || !value ? "" : (value as DeliveryStatus));
                setPage(0);
              }}
            >
              <SelectTrigger id="delivery-status" className="w-44">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>All statuses</SelectItem>
                {STATUSES.map((value) => (
                  <SelectItem key={value} value={value}>
                    {value.charAt(0) + value.slice(1).toLowerCase()}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="delivery-channel">Channel</Label>
            <Select
              value={channel || ALL}
              onValueChange={(value) => {
                setChannel(value === ALL || !value ? "" : (value as DeliveryChannel));
                setPage(0);
              }}
            >
              <SelectTrigger id="delivery-channel" className="w-44">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>All channels</SelectItem>
                {broadcast.channels.map((value) => (
                  <SelectItem key={value} value={value}>
                    {CHANNEL_LABELS[value]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading deliveries…"
          loginPath="/login"
          isEmpty={(data) => data.content.length === 0}
          emptyState={{ title: "No deliveries match", description: "Try a different status or channel." }}
        >
          {(data) => (
            <div className="flex flex-col gap-3">
              <DataTable
                columns={columns}
                rows={data.content}
                rowKey={(row) => row.id}
                caption="Delivery log"
                cardHeading={(row) => row.recipientName ?? "Unknown"}
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
      </CardContent>
    </Card>
  );
}
