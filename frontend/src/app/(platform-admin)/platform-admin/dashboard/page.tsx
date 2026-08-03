import { EmptyState } from "@/components/states/empty-state";

export default function PlatformAdminDashboardPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">
          Platform Admin Dashboard
        </h1>
        <p className="text-sm text-muted-foreground">
          Cross-tenant oversight — tenants, platform-wide reports, and support queues
          will appear here.
        </p>
      </div>
      <EmptyState
        title="Platform admin dashboard coming soon"
        description="Tenant onboarding, platform-wide reports, and support queues will appear here once the tenant-management module ships."
      />
    </div>
  );
}
