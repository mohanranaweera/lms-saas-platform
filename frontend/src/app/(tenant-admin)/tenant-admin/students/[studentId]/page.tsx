"use client";

import { Suspense, useEffect, useState } from "react";
import { useParams, usePathname, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, UserPlus } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageStudents } from "@/lib/auth/permissions";
import { useStudent, type StudentResponse } from "@/lib/api/students";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LoadingState } from "@/components/states/loading-state";
import { StudentStatusBadge } from "@/components/students/student-status-badge";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { StudentStatusToggle } from "./student-status-toggle";
import { EnrollStudentSheet } from "./enroll-student-sheet";
import { ResetPasswordDialog } from "./reset-password-dialog";
import { ProfileTab } from "./profile-tab";
import { EnrollmentsTab } from "./enrollments-tab";
import { PaymentsTab } from "./payments-tab";
import { AttendanceTab } from "./attendance-tab";
import { ExamsTab } from "./exams-tab";
import { StudentActivityTab } from "./activity-tab";

const VALID_TABS = ["profile", "enrollments", "payments", "attendance", "exams", "activity"] as const;
type TabValue = (typeof VALID_TABS)[number];

function isTabValue(value: string | null): value is TabValue {
  return VALID_TABS.includes(value as TabValue);
}

/**
 * Student Detail (Wave 3 tabbed rebuild, PAR-03-04/PAR-03-05) — Profile /
 * Enrollments / Payments / Attendance / Exams / Activity, each backed by its
 * own owning domain's client (never a student-specific duplicate — see
 * `lib/api/enrollments.ts`/`lib/api/ledger.ts`/`lib/api/attendance.ts`/
 * `lib/api/exams.ts`/`lib/api/audit-log.ts`). Device reset and generic
 * notification history are explicitly out of scope this wave (Wave 10/11) —
 * no tab/placeholder for either exists here, per
 * `docs/parity/KLASS-PARITY-MASTER-INSTRUCTION.md` §34's "no placeholder-only
 * pages" rule.
 *
 * The active tab lives in the URL's `?tab=` query param (deep-linkable,
 * survives a refresh), mirroring `tenant-admin/audit-log/page.tsx`'s
 * established URL-as-source-of-truth convention.
 */
function StudentDetailPageContent() {
  const params = useParams<{ studentId: string }>();
  const studentId = params.studentId;
  const { session } = useAuth();
  const canManage = canManageStudents(session?.role ?? null);

  const studentQuery = useStudent(studentId);

  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get("tab");
  const activeTab: TabValue = isTabValue(tabParam) ? tabParam : "profile";

  const [enrollOpen, setEnrollOpen] = useState(false);

  function handleTabChange(next: string) {
    const search = new URLSearchParams(searchParams);
    if (next === "profile") {
      search.delete("tab");
    } else {
      search.set("tab", next);
    }
    const qs = search.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/students"
          className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to students
        </Link>
      </div>

      <QueryStateBoundary
        query={studentQuery}
        loadingLabel="Loading student…"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(student) => (
          <StudentDetailBody
            student={student}
            canManage={canManage}
            activeTab={activeTab}
            onTabChange={handleTabChange}
            enrollOpen={enrollOpen}
            onEnrollOpenChange={setEnrollOpen}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}

function StudentDetailBody({
  student,
  canManage,
  activeTab,
  onTabChange,
  enrollOpen,
  onEnrollOpenChange,
}: {
  student: StudentResponse;
  canManage: boolean;
  activeTab: TabValue;
  onTabChange: (value: string) => void;
  enrollOpen: boolean;
  onEnrollOpenChange: (open: boolean) => void;
}) {
  // Distinguishable success feedback for the consequential Activate/
  // Deactivate/Enroll/Revoke actions (there is no toast library in this app
  // — `components/ui/live-region.tsx`'s doc comment — so this mirrors
  // `students/page.tsx#createdNotice`'s established brief, auto-clearing,
  // `role="status" aria-live="polite"` page-level notice pattern rather than
  // inventing a new one). "Revoke" (per enrollment row) is announced from
  // `EnrollmentsTab` itself, closer to the row it affects.
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  const items: TabItem[] = [
    { value: "profile", label: "Profile", content: <ProfileTab student={student} canManage={canManage} /> },
    {
      value: "enrollments",
      label: "Enrollments",
      content: <EnrollmentsTab studentId={student.id} canManage={canManage} />,
    },
    { value: "payments", label: "Payments", content: <PaymentsTab studentId={student.id} /> },
    { value: "attendance", label: "Attendance", content: <AttendanceTab studentId={student.id} /> },
    { value: "exams", label: "Exams", content: <ExamsTab studentId={student.id} /> },
    { value: "activity", label: "Activity", content: <StudentActivityTab studentId={student.id} /> },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-semibold text-foreground">{student.name}</h1>
          <StudentStatusBadge status={student.status} />
        </div>
        {canManage ? (
          <div className="flex flex-wrap items-center gap-2">
            <StudentStatusToggle student={student} onSuccess={setNotice} />
            <Button type="button" variant="outline" onClick={() => onEnrollOpenChange(true)}>
              <UserPlus aria-hidden="true" />
              Enroll
            </Button>
            <ResetPasswordDialog student={student} />
          </div>
        ) : null}
      </div>

      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>

      <Tabs
        items={items}
        value={activeTab}
        onValueChange={onTabChange}
        aria-label="Student detail sections"
      />

      {canManage ? (
        <EnrollStudentSheet
          student={student}
          open={enrollOpen}
          onOpenChange={onEnrollOpenChange}
          onEnrolled={() => setNotice(`${student.name} was enrolled.`)}
        />
      ) : null}
    </div>
  );
}

export default function StudentDetailPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading student…" />}>
      <StudentDetailPageContent />
    </Suspense>
  );
}
