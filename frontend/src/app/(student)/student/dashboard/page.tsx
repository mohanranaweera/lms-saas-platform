import { EmptyState } from "@/components/states/empty-state";

export default function StudentDashboardPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Student Dashboard</h1>
        <p className="text-sm text-muted-foreground">
          Your enrollments, courses, and progress will appear here.
        </p>
      </div>
      <EmptyState
        title="Student dashboard coming soon"
        description="Course enrollments, progress, and upcoming exams will appear here once the course-management and enrollment modules ship."
      />
    </div>
  );
}
