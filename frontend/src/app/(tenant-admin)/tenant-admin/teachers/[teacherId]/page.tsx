"use client";

import { Suspense, useState } from "react";
import { useParams, usePathname, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { CheckCircle2, SearchX } from "lucide-react";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LoadingState } from "@/components/states/loading-state";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { isApiClientError } from "@/lib/api/error";
import { useTeacher, type Teacher } from "@/lib/api/teachers";
import { TeacherStatusBadge } from "../status-badge";
import { TeacherProfileTab } from "./teacher-profile-tab";
import { AssignedCoursesTab } from "./assigned-courses-tab";
import { TeacherRosterTab } from "./teacher-roster-tab";
import { TeacherAttendanceTab } from "./teacher-attendance-tab";
import { TeacherExamsTab } from "./teacher-exams-tab";
import { TeacherActivityTab } from "./teacher-activity-tab";

const VALID_TABS = ["profile", "courses", "roster", "attendance", "exams", "activity"] as const;
type TabValue = (typeof VALID_TABS)[number];

function isTabValue(value: string | null): value is TabValue {
  return VALID_TABS.includes(value as TabValue);
}

/**
 * Teacher Detail (Wave 3 tabbed rebuild, PAR-04-03/PAR-04-04) — Profile /
 * Assigned Courses / Roster / Attendance / Exams / Activity. Financial
 * summary and Sessions tabs are explicitly NOT added this wave (no backend
 * data exists yet — Wave 4/7), per the wave-03 plan §5 and
 * `docs/parity/KLASS-PARITY-MASTER-INSTRUCTION.md` §34's "no placeholder-only
 * pages" rule.
 *
 * `GET /v1/teachers/{id}` returns a uniform 404 for both "doesn't exist" and
 * "belongs to another tenant" — this page special-cases that before falling
 * through to `QueryStateBoundary`'s default handling, unchanged from the
 * pre-Wave-3 page.
 */
function TeacherDetailPageContent() {
  const params = useParams<{ teacherId: string }>();
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const teacherId = params.teacherId;
  const query = useTeacher(teacherId);

  const [showCreatedNotice, setShowCreatedNotice] = useState(
    searchParams.get("created") === "true"
  );

  const tabParam = searchParams.get("tab");
  const activeTab: TabValue = isTabValue(tabParam) ? tabParam : "profile";

  function handleTabChange(next: string) {
    const search = new URLSearchParams(searchParams);
    if (next === "profile") {
      search.delete("tab");
    } else {
      search.set("tab", next);
    }
    search.delete("created");
    const qs = search.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }

  if (query.status === "error" && isApiClientError(query.error) && query.error.status === 404) {
    return (
      <div className="flex flex-col gap-6">
        <EmptyState
          icon={<SearchX className="size-8" aria-hidden="true" />}
          title="Teacher not found"
          description="This teacher account doesn't exist, or you don't have access to it."
          action={{
            label: "Back to teachers",
            onClick: () => router.push("/tenant-admin/teachers"),
          }}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link
          href="/tenant-admin/teachers"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to teachers
        </Link>
        <h1 className="text-xl font-semibold text-foreground">Teacher</h1>
      </div>

      {showCreatedNotice ? (
        <Alert>
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription className="flex items-center justify-between gap-3">
            <span>Teacher account created — pending your approval.</span>
            <Button type="button" variant="ghost" size="sm" onClick={() => setShowCreatedNotice(false)}>
              Dismiss
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading teacher…"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(teacher) => (
          <TeacherDetailBody teacher={teacher} activeTab={activeTab} onTabChange={handleTabChange} />
        )}
      </QueryStateBoundary>
    </div>
  );
}

function TeacherDetailBody({
  teacher,
  activeTab,
  onTabChange,
}: {
  teacher: Teacher;
  activeTab: TabValue;
  onTabChange: (value: string) => void;
}) {
  const items: TabItem[] = [
    { value: "profile", label: "Profile", content: <TeacherProfileTab teacher={teacher} /> },
    { value: "courses", label: "Assigned Courses", content: <AssignedCoursesTab teacherId={teacher.id} /> },
    { value: "roster", label: "Roster", content: <TeacherRosterTab teacherId={teacher.id} /> },
    { value: "attendance", label: "Attendance", content: <TeacherAttendanceTab teacherId={teacher.id} /> },
    { value: "exams", label: "Exams", content: <TeacherExamsTab teacherId={teacher.id} /> },
    { value: "activity", label: "Activity", content: <TeacherActivityTab teacherId={teacher.id} /> },
  ];

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3">
        <div>
          <h2 className="font-heading text-base font-medium text-foreground">{teacher.name}</h2>
          <p className="text-sm text-muted-foreground">{teacher.email}</p>
        </div>
        <TeacherStatusBadge status={teacher.approvalStatus} />
      </CardHeader>
      <CardContent>
        <Tabs items={items} value={activeTab} onValueChange={onTabChange} aria-label="Teacher detail sections" />
      </CardContent>
    </Card>
  );
}

export default function TenantAdminTeacherDetailPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading teacher…" />}>
      <TeacherDetailPageContent />
    </Suspense>
  );
}
