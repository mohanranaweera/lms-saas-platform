"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import { useParams, usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { UserPlus, Users } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import {
  canManageAccessPolicy,
  canManageDevices,
  canManageStudents,
  canViewAccessPolicy,
  canViewCommunications,
  canViewDevices,
} from "@/lib/auth/permissions";
import { useStudentDetail, studentMgmtKeys, type DateRange, type StudentDetail } from "@/lib/api/student-management";
import type { StudentResponse } from "@/lib/api/students";
import { useTenantTimeZone } from "@/lib/api/klass-admin";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LoadingState } from "@/components/states/loading-state";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StudentStatusBadge } from "@/components/students/student-status-badge";
import { StudentFormSheet } from "../student-form-sheet";
import { useCourseNames } from "../student-display";
import { IdentitySidebar } from "./identity-sidebar";
import {
  EnrolledCoursesSection,
  ExamsSection,
  PaymentsSection,
  RemarksSection,
  SessionViewsSection,
} from "./profile-sections";
import { StudentStatusToggle } from "./student-status-toggle";
import { EnrollStudentSheet } from "./enroll-student-sheet";
import { ResetPasswordDialog } from "./reset-password-dialog";
import { ProfileFieldsSection } from "./profile-fields-section";
import { EnrollmentsTab } from "./enrollments-tab";
import { PaymentsTab } from "./payments-tab";
import { AttendanceTab } from "./attendance-tab";
import { ExamsTab } from "./exams-tab";
import { StudentActivityTab } from "./activity-tab";
import { DevicesTab } from "./devices-tab";
import { NotificationsTab } from "./notifications-tab";
import { WatchTimeTab } from "./watch-time-tab";

const VALID_TABS = [
  "enrollments",
  "payments",
  "attendance",
  "exams",
  "devices",
  "watch-time",
  "notifications",
  "activity",
  "details",
] as const;
type TabValue = (typeof VALID_TABS)[number];

function isTabValue(value: string | null): value is TabValue {
  return VALID_TABS.includes(value as TabValue);
}

/**
 * Student Profile (STU-MGMT-02 full profile, Klass "Comprehensive Report"):
 * a date-range filter (remarks, attended exams, payments, session views —
 * never enrolled courses), the Klass cards, an identity sidebar with the
 * student QR, and a "Manage" section keeping every existing admin tool
 * (enrollments revoke/extend, devices, watch time, notifications, activity,
 * account status) reachable. The `?tab=` param selects the Manage tab.
 */
function StudentProfileContent() {
  const params = useParams<{ studentId: string }>();
  const studentId = params.studentId;
  const query = useStudentDetail(studentId);

  return (
    <div className="flex flex-col gap-6">
      <Breadcrumbs items={[{ label: "Students", href: "/tenant-admin/students" }, { label: "Student Profile" }]} />
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading student…"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
        genericErrorMessage="This student's profile couldn't be loaded."
      >
        {(detail) => <StudentProfileBody detail={detail} />}
      </QueryStateBoundary>
    </div>
  );
}

function StudentProfileBody({ detail }: { detail: StudentDetail }) {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const canManage = canManageStudents(role);
  const { timeZone } = useTenantTimeZone();
  const courseName = useCourseNames();
  const queryClient = useQueryClient();

  const [fromInput, setFromInput] = useState("");
  const [toInput, setToInput] = useState("");
  const rangeInvalid = Boolean(fromInput && toInput && fromInput > toInput);
  const range: DateRange = useMemo(
    () => (rangeInvalid ? {} : { from: fromInput || undefined, to: toInput || undefined }),
    [fromInput, toInput, rangeInvalid]
  );

  const [editOpen, setEditOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 6000);
    return () => clearTimeout(timeout);
  }, [notice]);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div className="flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <Users className="size-5" aria-hidden="true" />
          </span>
          <div>
            <h1 className="text-xl font-semibold text-foreground">Student Profile</h1>
            <p className="text-sm text-muted-foreground">
              {detail.fullName}
              {detail.indexNumber ? ` · ${detail.indexNumber}` : ""}
            </p>
          </div>
        </div>
        <fieldset className="flex flex-col gap-1.5" aria-describedby="date-range-caption" data-testid="date-range-filter">
          <legend className="text-sm font-medium text-foreground">Date Range Filter</legend>
          <div className="flex flex-wrap items-end gap-2">
            <div className="flex flex-col gap-1">
              <Label htmlFor="profile-range-from" className="text-xs">
                From
              </Label>
              <Input
                id="profile-range-from"
                type="date"
                value={fromInput}
                max={toInput || undefined}
                onChange={(event) => setFromInput(event.target.value)}
                className="w-40"
              />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="profile-range-to" className="text-xs">
                To
              </Label>
              <Input
                id="profile-range-to"
                type="date"
                value={toInput}
                min={fromInput || undefined}
                onChange={(event) => setToInput(event.target.value)}
                className="w-40"
              />
            </div>
            {fromInput || toInput ? (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => {
                  setFromInput("");
                  setToInput("");
                }}
              >
                Clear dates
              </Button>
            ) : null}
          </div>
          <p id="date-range-caption" className="max-w-md text-xs text-muted-foreground">
            Filters remarks, attended exams, payments and session views. Enrolled courses always show current enrolments.
          </p>
          {rangeInvalid ? (
            <p role="alert" className="text-xs text-destructive">
              “From” must be on or before “To”.
            </p>
          ) : null}
        </fieldset>
      </div>

      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div className="order-2 flex min-w-0 flex-col gap-6 lg:order-1">
          <RemarksSection detail={detail} range={range} timeZone={timeZone} canManage={canManage} />
          <ExamsSection studentId={detail.id} range={range} timeZone={timeZone} />
          <EnrolledCoursesSection detail={detail} courseName={courseName} />
          <PaymentsSection studentId={detail.id} range={range} timeZone={timeZone} />
          <SessionViewsSection
            studentId={detail.id}
            range={range}
            timeZone={timeZone}
            canView={canViewAccessPolicy(role)}
          />
          <ManageSection
            detail={detail}
            role={role}
            onNotice={(message) => {
              setNotice(message);
              // Activate/deactivate return the legacy account shape (cached under ["students", id]);
              // apply its status to the profile right away, then refresh from the server.
              const account = queryClient.getQueryData<StudentResponse>(["students", detail.id]);
              if (account) {
                queryClient.setQueryData<StudentDetail>(studentMgmtKeys.detail(detail.id), (current) =>
                  current ? { ...current, status: account.status } : current
                );
              }
            }}
          />
        </div>
        <div className="order-1 min-w-0 lg:order-2">
          <IdentitySidebar detail={detail} timeZone={timeZone} canManage={canManage} onEdit={() => setEditOpen(true)} />
        </div>
      </div>

      {canManage ? (
        <StudentFormSheet
          open={editOpen}
          onOpenChange={setEditOpen}
          mode={{ kind: "edit", studentId: detail.id }}
          onSaved={setNotice}
        />
      ) : null}
    </div>
  );
}

function ManageSection({
  detail,
  role,
  onNotice,
}: {
  detail: StudentDetail;
  role: string | null;
  onNotice: (message: string) => void;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get("tab");
  const activeTab: TabValue = isTabValue(tabParam) ? tabParam : "enrollments";
  const [enrollOpen, setEnrollOpen] = useState(false);
  const canManage = canManageStudents(role);
  const extendAccess = canManageAccessPolicy(role);

  // Legacy account-level controls still take the old `StudentResponse` shape.
  const student: StudentResponse = {
    id: detail.id,
    name: detail.fullName,
    email: detail.email,
    roleCode: "STUDENT",
    status: detail.status,
  };

  function handleTabChange(next: string) {
    const search = new URLSearchParams(searchParams);
    if (next === "enrollments") search.delete("tab");
    else search.set("tab", next);
    const qs = search.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }

  const items: TabItem[] = [
    {
      value: "enrollments",
      label: "Enrollments",
      content: <EnrollmentsTab studentId={detail.id} canManage={canManage} canExtend={extendAccess} />,
    },
    { value: "payments", label: "Payments", content: <PaymentsTab studentId={detail.id} /> },
    { value: "attendance", label: "Attendance", content: <AttendanceTab studentId={detail.id} /> },
    { value: "exams", label: "Exams", content: <ExamsTab studentId={detail.id} /> },
    ...(canViewDevices(role)
      ? [{ value: "devices", label: "Devices", content: <DevicesTab studentId={detail.id} canManage={canManageDevices(role)} /> }]
      : []),
    ...(canViewAccessPolicy(role)
      ? [{ value: "watch-time", label: "Watch time", content: <WatchTimeTab studentId={detail.id} canGrant={extendAccess} /> }]
      : []),
    ...(canViewCommunications(role)
      ? [{ value: "notifications", label: "Notifications", content: <NotificationsTab studentId={detail.id} /> }]
      : []),
    { value: "activity", label: "Activity", content: <StudentActivityTab studentId={detail.id} /> },
    {
      value: "details",
      label: "Additional details",
      content: <ProfileFieldsSection studentId={detail.id} canManage={canManage} />,
    },
  ];

  return (
    <section
      aria-labelledby="manage-heading"
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 shadow-sm"
      data-testid="profile-manage"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h2 id="manage-heading" className="flex items-center gap-2 text-base font-semibold text-foreground">
          Manage
          <StudentStatusBadge status={detail.status} />
        </h2>
        {canManage ? (
          <div className="flex flex-wrap items-center gap-2">
            <StudentStatusToggle student={student} onSuccess={onNotice} />
            <Button type="button" variant="outline" onClick={() => setEnrollOpen(true)}>
              <UserPlus aria-hidden="true" />
              Enroll
            </Button>
            <ResetPasswordDialog student={student} />
          </div>
        ) : null}
      </div>
      <Tabs items={items} value={activeTab} onValueChange={handleTabChange} aria-label="Student detail sections" />
      {canManage ? (
        <EnrollStudentSheet
          student={student}
          open={enrollOpen}
          onOpenChange={setEnrollOpen}
          onEnrolled={() => onNotice(`${detail.fullName} was enrolled.`)}
        />
      ) : null}
    </section>
  );
}

export default function StudentProfilePage() {
  return (
    <Suspense fallback={<LoadingState label="Loading student…" />}>
      <StudentProfileContent />
    </Suspense>
  );
}
