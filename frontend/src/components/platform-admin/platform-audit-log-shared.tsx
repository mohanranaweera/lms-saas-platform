"use client";

import Link from "next/link";
import { Skeleton } from "@/components/ui/skeleton";
import { Accordion } from "@/components/ui/accordion";
import type { DataTableColumn } from "@/components/ui/data-table";
import type { PlatformAuditLogEntryResponse } from "@/lib/api/platform-admin-audit-log";
import type { PlatformAuditLogFilterFormValues } from "@/lib/validation/platform-audit-log";
import { formatDateTime, shortId } from "@/lib/format";

/**
 * Shared table skeleton, expanded-row renderer, query-string builder, and
 * common columns for the Platform Audit Log (`(dashboard)/audit-log/page.tsx`)
 * and its per-tenant drill-down (`(dashboard)/audit-log/[tenantId]/page.tsx`).
 * Extracted here because both pages previously carried byte-identical copies
 * of each of these (MVP-020 review finding). `columns` are still assembled
 * per page rather than fully shared — the list page has an extra `tenant`
 * column the drill-down deliberately omits (every row there already belongs
 * to the one tenant named in the page's own banner) — see
 * `createPlatformAuditLogTenantColumn` below, used by the list page only.
 */

export function PlatformAuditLogTableSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-3">
      <div className="hidden overflow-hidden rounded-lg border border-border md:block">
        <div className="divide-y divide-border">
          {Array.from({ length: 6 }).map((_, index) => (
            <div key={index} className="flex items-center gap-6 px-4 py-3">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-4 w-28" />
              <Skeleton className="h-4 w-36" />
            </div>
          ))}
        </div>
      </div>
      <ul className="flex flex-col gap-3 md:hidden">
        {Array.from({ length: 3 }).map((_, index) => (
          <li key={index} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-2/3" />
          </li>
        ))}
      </ul>
    </div>
  );
}

export function renderPlatformAuditLogExpandedRow(
  row: PlatformAuditLogEntryResponse,
  metadataOpenKeys: ReadonlySet<string>,
  onMetadataOpenChange: (rowId: string, open: boolean) => void
) {
  const fieldCount = row.metadata ? Object.keys(row.metadata).length : 0;
  return (
    <div className="flex flex-col gap-3 text-sm">
      <p>
        <span className="font-medium text-foreground">Reason: </span>
        <span className="text-muted-foreground">{row.reason ?? "Not provided for this event."}</span>
      </p>
      {row.metadata ? (
        <Accordion
          title={`Metadata (${fieldCount} field${fieldCount === 1 ? "" : "s"})`}
          open={metadataOpenKeys.has(row.id)}
          onOpenChange={(open) => onMetadataOpenChange(row.id, open)}
        >
          <pre className="overflow-x-auto rounded-md bg-muted p-3 text-xs text-foreground">
            {JSON.stringify(row.metadata, null, 2)}
          </pre>
        </Accordion>
      ) : (
        <p className="text-xs text-muted-foreground">No metadata recorded for this event.</p>
      )}
    </div>
  );
}

export function buildPlatformAuditLogQueryString(
  filters: PlatformAuditLogFilterFormValues,
  page: number
): string {
  const search = new URLSearchParams();
  if (filters.from) search.set("from", filters.from);
  if (filters.to) search.set("to", filters.to);
  if (filters.action) search.set("action", filters.action);
  if (page > 0) search.set("page", String(page));
  return search.toString();
}

/**
 * Identifies who performed the action — the field both pages' `columns`
 * previously omitted entirely (MVP-020 review finding: `actorId` was
 * declared on the response type but never rendered anywhere). No
 * `actorDisplayName` exists on `PlatformAuditLogEntryResponse` (unlike the
 * tenant-scoped `AuditLogEntryResponse`), so this renders the same
 * `shortId` fallback that field's own doc comment already uses for a
 * best-effort-unresolved actor.
 */
export const platformAuditLogActorColumn: DataTableColumn<PlatformAuditLogEntryResponse> = {
  key: "actor",
  header: "Actor",
  cell: (row) => shortId(row.actorId, "Actor"),
};

export const platformAuditLogActionColumn: DataTableColumn<PlatformAuditLogEntryResponse> = {
  key: "action",
  header: "Action",
  cell: (row) => row.action,
  // Already surfaced as the mobile card's unlabeled heading via `cardHeading`.
  hideOnCard: true,
};

export const platformAuditLogTargetColumn: DataTableColumn<PlatformAuditLogEntryResponse> = {
  key: "target",
  header: "Target",
  cell: (row) => `${row.targetEntity} #${row.targetId.slice(0, 8)}`,
};

export const platformAuditLogOccurredAtColumn: DataTableColumn<PlatformAuditLogEntryResponse> = {
  key: "occurredAt",
  header: "Occurred at",
  cell: (row) => formatDateTime(row.occurredAt),
  // Already surfaced via `cardHeadingAdornment`.
  hideOnCard: true,
};

/** List-page-only — the drill-down omits this column (see file doc comment). */
export function createPlatformAuditLogTenantColumn(
  hrefPrefix: string
): DataTableColumn<PlatformAuditLogEntryResponse> {
  return {
    key: "tenant",
    header: "Tenant",
    cell: (row) => (
      <Link
        href={`${hrefPrefix}/${row.tenantId}`}
        className="font-medium text-foreground hover:underline"
      >
        {row.tenantName ?? shortId(row.tenantId, "Tenant")}
      </Link>
    ),
  };
}
