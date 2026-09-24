"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Accordion } from "@/components/ui/accordion";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import type { ActivityEntryResponse } from "@/lib/api/audit-log";
import { formatDateTime, shortId } from "@/lib/format";

interface ActivityQueryLike {
  status: "pending" | "error" | "success";
  data: { content: ActivityEntryResponse[]; page: number; totalPages: number } | undefined;
  error: unknown;
  isFetching: boolean;
  refetch: () => Promise<unknown>;
}

/**
 * Shared Activity tab content — first two consumers: the Wave 3 Student and
 * Teacher Detail pages' Activity tabs (`GET /v1/students/{id}/activity` /
 * `GET /v1/teachers/{id}/activity`, both backed by the same append-only
 * `AuditLogQueryService`, both returning `ActivityEntryResponse` — see
 * `lib/api/audit-log.ts`). Takes the query result and a page setter rather
 * than a studentId/teacherId directly, so it stays agnostic to which of the
 * two endpoints produced it.
 */
export function ActivityTabContent({
  query,
  page,
  onPageChange,
  emptyTitle,
  emptyDescription,
}: {
  query: ActivityQueryLike;
  page: number;
  onPageChange: (next: number) => void;
  emptyTitle: string;
  emptyDescription: string;
}) {
  const [openKeys, setOpenKeys] = useState<ReadonlySet<string>>(new Set());

  function toggleOpen(id: string, open: boolean) {
    setOpenKeys((current) => {
      const next = new Set(current);
      if (open) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading activity…"
      isEmpty={(data) => data.content.length === 0 && page === 0}
      emptyState={{ title: emptyTitle, description: emptyDescription }}
    >
      {(data) => (
        <div className="flex flex-col gap-4">
          {data.content.length === 0 ? (
            <p className="text-sm text-muted-foreground">No events on this page.</p>
          ) : (
            <ul className="flex flex-col gap-3">
              {data.content.map((entry) => {
                const fieldCount = entry.metadata ? Object.keys(entry.metadata).length : 0;
                return (
                  <li key={entry.id} className="flex flex-col gap-2 rounded-lg border border-border p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="font-medium text-foreground">{entry.action}</span>
                      <span className="text-xs text-muted-foreground">
                        {formatDateTime(entry.occurredAt)}
                      </span>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      By {entry.actorDisplayName ?? shortId(entry.actorId, "Actor")}
                    </p>
                    {entry.reason ? (
                      <p className="text-sm text-foreground">Reason: {entry.reason}</p>
                    ) : null}
                    {entry.metadata ? (
                      <Accordion
                        title={`Metadata (${fieldCount} field${fieldCount === 1 ? "" : "s"})`}
                        open={openKeys.has(entry.id)}
                        onOpenChange={(open) => toggleOpen(entry.id, open)}
                      >
                        <pre className="overflow-x-auto rounded-md bg-muted p-3 text-xs text-foreground">
                          {JSON.stringify(entry.metadata, null, 2)}
                        </pre>
                      </Accordion>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          )}
          <div className="flex items-center justify-between">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => onPageChange(Math.max(0, page - 1))}
              disabled={page === 0 || query.isFetching}
            >
              Previous
            </Button>
            <span className="text-xs text-muted-foreground">
              Page {data.page + 1} of {Math.max(data.totalPages, 1)}
            </span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => onPageChange(page + 1)}
              disabled={data.page + 1 >= data.totalPages || query.isFetching}
            >
              Next
            </Button>
          </div>
        </div>
      )}
    </QueryStateBoundary>
  );
}
