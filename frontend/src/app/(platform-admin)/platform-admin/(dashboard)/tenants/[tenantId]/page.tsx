"use client";

import { useParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, CreditCard, ScrollText } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ApproveTenantDialog } from "@/components/platform-admin/approve-tenant-dialog";
import { RejectTenantDialog } from "@/components/platform-admin/reject-tenant-dialog";
import { usePlatformAdminTenantDetail } from "@/lib/api/platform-admin-tenants";
import { StatusBadge } from "../status-badge";
import { formatDateTime } from "@/lib/format";

/**
 * Tenant Approval Detail (PADASH-1). `GET
 * /api/v1/platform-admin/tenants/{id}` returns a `404 NOT_FOUND` for an
 * unknown id, which `QueryStateBoundary` already renders as an `ErrorState`
 * with no special-casing needed here.
 *
 * `showTenantContext` is deliberately `false` on this screen (unlike the
 * payment/audit-log drill-downs below it in the route tree): this is a
 * same-tenant detail view — the tenant's own name already anchors the page
 * as its `PageHeader` title — whereas the drill-down screens inspect a
 * *different* domain's data (payments/audit log) while "in" a tenant's
 * context, which is what the persistent banner exists to disambiguate.
 */
export default function PlatformAdminTenantDetailPage() {
  const params = useParams<{ tenantId: string }>();
  const tenantId = params.tenantId;
  const query = usePlatformAdminTenantDetail(tenantId);

  return (
    <div className="flex flex-col gap-6">
      <Link
        href="/platform-admin/tenants"
        className="inline-flex w-fit items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground hover:underline"
      >
        <ArrowLeft className="size-3.5" aria-hidden="true" />
        Back to tenants
      </Link>

      <QueryStateBoundary
        query={query}
        loginPath="/platform-admin/login"
        permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
      >
        {(tenant) => (
          <>
            <PageHeader
              title={tenant.name}
              description={`Registered subdomain: ${tenant.subdomain}`}
              actions={
                tenant.status === "pending_approval" ? (
                  <>
                    <ApproveTenantDialog tenant={{ id: tenant.id, name: tenant.name }} />
                    <RejectTenantDialog tenant={{ id: tenant.id, name: tenant.name }} />
                  </>
                ) : undefined
              }
            />

            {/* Cross-links to this tenant's payment/audit-log drill-downs
                (MVP-020 review finding: the detail screen had no path to
                either). Each destination re-establishes its own persistent
                tenant-context banner via `showTenantContext` on arrival, so
                this row is a plain navigation link, not a duplicate banner. */}
            <div className="flex flex-wrap gap-4 text-sm">
              <Link
                href={`/platform-admin/payments/${tenant.id}`}
                className="inline-flex items-center gap-1.5 font-medium text-foreground hover:underline"
              >
                <CreditCard className="size-3.5" aria-hidden="true" />
                View payments
              </Link>
              <Link
                href={`/platform-admin/audit-log/${tenant.id}`}
                className="inline-flex items-center gap-1.5 font-medium text-foreground hover:underline"
              >
                <ScrollText className="size-3.5" aria-hidden="true" />
                View audit log
              </Link>
            </div>

            <div className="rounded-lg border border-border">
              <dl className="divide-y divide-border">
                <div className="grid grid-cols-1 gap-1 px-4 py-3 sm:grid-cols-3 sm:gap-4">
                  <dt className="text-sm font-medium text-foreground">Status</dt>
                  <dd className="sm:col-span-2">
                    <StatusBadge status={tenant.status} />
                  </dd>
                </div>
                <div className="grid grid-cols-1 gap-1 px-4 py-3 sm:grid-cols-3 sm:gap-4">
                  <dt className="text-sm font-medium text-foreground">Requested plan</dt>
                  <dd className="text-sm text-muted-foreground sm:col-span-2">
                    {tenant.requestedPlan}
                  </dd>
                </div>
                <div className="grid grid-cols-1 gap-1 px-4 py-3 sm:grid-cols-3 sm:gap-4">
                  <dt className="text-sm font-medium text-foreground">Contact name</dt>
                  <dd className="text-sm text-muted-foreground sm:col-span-2">
                    {tenant.contactName ?? "Not provided"}
                  </dd>
                </div>
                <div className="grid grid-cols-1 gap-1 px-4 py-3 sm:grid-cols-3 sm:gap-4">
                  <dt className="text-sm font-medium text-foreground">Contact email</dt>
                  <dd className="text-sm text-muted-foreground sm:col-span-2">
                    {tenant.contactEmail ?? "Not provided"}
                  </dd>
                </div>
                <div className="grid grid-cols-1 gap-1 px-4 py-3 sm:grid-cols-3 sm:gap-4">
                  <dt className="text-sm font-medium text-foreground">Contact phone</dt>
                  <dd className="text-sm text-muted-foreground sm:col-span-2">
                    {tenant.contactPhone ?? "Not provided"}
                  </dd>
                </div>
                <div className="grid grid-cols-1 gap-1 px-4 py-3 sm:grid-cols-3 sm:gap-4">
                  <dt className="text-sm font-medium text-foreground">Submitted</dt>
                  <dd className="text-sm text-muted-foreground sm:col-span-2">
                    {formatDateTime(tenant.createdAt)}
                  </dd>
                </div>
              </dl>
            </div>
          </>
        )}
      </QueryStateBoundary>
    </div>
  );
}
