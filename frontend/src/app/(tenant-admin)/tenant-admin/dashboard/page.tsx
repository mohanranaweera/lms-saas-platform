import { EmptyState } from "@/components/states/empty-state";

export default function TenantAdminDashboardPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">
          Tenant Admin Dashboard
        </h1>
        <p className="text-sm text-muted-foreground">
          Your institute&apos;s staff, students, and courses will be managed here.
        </p>
      </div>
      <EmptyState
        title="Tenant admin dashboard coming soon"
        description="Staff management, student rosters, and course setup for your institute will appear here once the tenant-management module ships."
      />
    </div>
  );
}
