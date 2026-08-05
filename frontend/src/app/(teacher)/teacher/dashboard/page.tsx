import { EmptyState } from "@/components/states/empty-state";

export default function TeacherDashboardPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Teacher Dashboard</h1>
        <p className="text-sm text-muted-foreground">
          Your assigned courses, rosters, and materials will appear here.
        </p>
      </div>
      <EmptyState
        title="Teacher dashboard coming soon"
        description="Your assigned courses and student rosters will appear here once the course-management module ships. If you expect to see courses now, contact your tenant admin."
      />
    </div>
  );
}
