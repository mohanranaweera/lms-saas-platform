import Link from "next/link";
import { EmptyState } from "@/components/states/empty-state";

/**
 * Static placeholder — no summary/KPI data-fetching exists yet for this
 * screen (that's a future module, not part of MVP-020). Updated by MVP-020
 * to stop claiming Tenants/Payments/Audit Log are still unshipped: those
 * three are live (linked below and in the sidebar nav) as of this module —
 * only the dashboard's own summary/KPI rollup remains unbuilt. `EmptyState`'s
 * `action` prop takes an `onClick`, not an `href`, and only supports one
 * action — plain `Link`s below it are used instead for the three real
 * destinations, rather than making this a client component just to wrap one
 * navigation in a handler.
 */
export default function PlatformAdminDashboardPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">
          Platform Admin Dashboard
        </h1>
        <p className="text-sm text-muted-foreground">
          Cross-tenant oversight — a platform-wide summary will appear here. In the
          meantime, use Tenants, Payments, or the Audit Log below or in the sidebar.
        </p>
      </div>
      <EmptyState
        title="No dashboard summary yet"
        description="Platform-wide KPIs and reports aren't built yet."
      />
      <div className="flex flex-wrap justify-center gap-4 text-sm">
        <Link href="/platform-admin/tenants" className="font-medium text-foreground hover:underline">
          Go to tenants
        </Link>
        <Link href="/platform-admin/payments" className="font-medium text-foreground hover:underline">
          Go to payments
        </Link>
        <Link href="/platform-admin/audit-log" className="font-medium text-foreground hover:underline">
          Go to audit log
        </Link>
      </div>
    </div>
  );
}
