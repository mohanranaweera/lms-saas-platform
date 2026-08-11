"use client";

import { useMemo, useState } from "react";
import { Info } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { EmptyState } from "@/components/states/empty-state";
import { MOCK_TENANTS } from "./mock-tenants";
import { StatusBadge, TENANT_STATUS_LABELS, type TenantStatus } from "./status-badge";

/**
 * Tenant List — SCAFFOLD ONLY, not wired to live data.
 *
 * `GET /api/v1/platform-admin/tenants` does not exist on the backend yet — the
 * whole Platform Admin approval/status workflow is designed-but-unbuilt, blocked
 * on an auth module that doesn't exist (module plan §20/§21 item 13). This page
 * intentionally has no React Query, no fetch, no approve/reject/suspend/cancel
 * action, and no fabricated loading/error/permission-denied state — those would
 * misrepresent conditions that aren't real yet. It exists only to demonstrate the
 * table layout, status treatment, and responsive/empty-state behavior against
 * local sample data.
 */

const STATUS_FILTER_OPTIONS: Array<{ value: "all" | TenantStatus; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "pending_approval", label: TENANT_STATUS_LABELS.pending_approval },
  { value: "trial", label: TENANT_STATUS_LABELS.trial },
  { value: "active", label: TENANT_STATUS_LABELS.active },
  { value: "suspended", label: TENANT_STATUS_LABELS.suspended },
  { value: "cancelled", label: TENANT_STATUS_LABELS.cancelled },
  { value: "rejected", label: TENANT_STATUS_LABELS.rejected },
];

function formatSubmittedDate(iso: string): string {
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  }).format(new Date(iso));
}

export default function PlatformAdminTenantsPage() {
  const [statusFilter, setStatusFilter] = useState<"all" | TenantStatus>("all");
  const [search, setSearch] = useState("");

  const filteredByStatus = useMemo(
    () =>
      statusFilter === "all"
        ? MOCK_TENANTS
        : MOCK_TENANTS.filter((tenant) => tenant.status === statusFilter),
    [statusFilter]
  );

  const filtered = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return filteredByStatus;
    return filteredByStatus.filter(
      (tenant) =>
        tenant.name.toLowerCase().includes(query) ||
        tenant.subdomain.toLowerCase().includes(query)
    );
  }, [filteredByStatus, search]);

  const clearFilters = () => {
    setStatusFilter("all");
    setSearch("");
  };

  // Two distinct empty states, per .claude/rules/ui-ux.md §3 — a true zero-data
  // state (no sample tenant has ever reached the selected status) is a different
  // condition, with different copy, from "your filter/search combination excludes
  // every row that does exist."
  const emptyVariant: "zero-status" | "no-match" | null =
    filtered.length > 0 ? null : filteredByStatus.length === 0 ? "zero-status" : "no-match";

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Tenants</h1>
        <p className="text-sm text-muted-foreground">
          Cross-tenant registration list — every row names the tenant, per
          .claude/rules/ui-ux.md §1.
        </p>
      </div>

      <div
        role="note"
        className="flex items-start gap-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-900/60 dark:bg-amber-950 dark:text-amber-200"
      >
        <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <p>
          Showing sample data. Live tenant data and approval actions will appear once
          platform admin authentication ships.
        </p>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor="tenant-search">Search</Label>
          <Input
            id="tenant-search"
            type="search"
            placeholder="Search by tenant name or subdomain"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1.5 sm:w-56">
          <Label htmlFor="tenant-status-filter">Status</Label>
          <select
            id="tenant-status-filter"
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value as "all" | TenantStatus)}
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          >
            {STATUS_FILTER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {emptyVariant === "zero-status" ? (
        <EmptyState
          title="No tenants with this status"
          description={`There are no tenant registrations currently in "${TENANT_STATUS_LABELS[statusFilter as TenantStatus]}" status in this sample data.`}
          action={{ label: "View all tenants", onClick: clearFilters }}
        />
      ) : emptyVariant === "no-match" ? (
        <EmptyState
          title="No tenants match your filter"
          description="No tenants match your current search and status filter. Try a different search term or clear the status filter."
          action={{ label: "Clear filters", onClick: clearFilters }}
        />
      ) : (
        <>
          {/* Desktop/tablet: table. Below md: card list (.claude/rules/ui-ux.md §5). */}
          <div className="hidden overflow-x-auto rounded-lg border border-border md:block">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border bg-muted/40">
                <tr>
                  <th scope="col" className="px-4 py-2 font-medium text-foreground">
                    Tenant name
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-foreground">
                    Subdomain
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-foreground">
                    Status
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-foreground">
                    Requested plan
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-foreground">
                    Submitted
                  </th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((tenant) => (
                  <tr key={tenant.id} className="border-b border-border last:border-0">
                    <td className="px-4 py-2.5 font-medium text-foreground">{tenant.name}</td>
                    <td className="px-4 py-2.5 text-muted-foreground">{tenant.subdomain}</td>
                    <td className="px-4 py-2.5">
                      <StatusBadge status={tenant.status} />
                    </td>
                    <td className="px-4 py-2.5 text-muted-foreground">{tenant.requestedPlan}</td>
                    <td className="px-4 py-2.5 text-muted-foreground">
                      {formatSubmittedDate(tenant.submittedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <ul className="flex flex-col gap-3 md:hidden">
            {filtered.map((tenant) => (
              <li
                key={tenant.id}
                className="flex flex-col gap-2 rounded-lg border border-border p-4"
              >
                <div className="flex items-start justify-between gap-2">
                  <span className="font-medium text-foreground">{tenant.name}</span>
                  <StatusBadge status={tenant.status} />
                </div>
                <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-xs text-muted-foreground">
                  <dt className="font-medium text-foreground">Subdomain</dt>
                  <dd>{tenant.subdomain}</dd>
                  <dt className="font-medium text-foreground">Requested plan</dt>
                  <dd>{tenant.requestedPlan}</dd>
                  <dt className="font-medium text-foreground">Submitted</dt>
                  <dd>{formatSubmittedDate(tenant.submittedAt)}</dd>
                </dl>
              </li>
            ))}
          </ul>
        </>
      )}

      {filtered.length > 0 ? (
        <p className="text-xs text-muted-foreground">
          Showing {filtered.length} of {MOCK_TENANTS.length} sample tenants.
        </p>
      ) : null}
    </div>
  );
}
