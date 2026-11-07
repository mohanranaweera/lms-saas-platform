"use client";

import { useState } from "react";
import Link from "next/link";
import {
  BookOpen,
  Cake,
  Clock,
  Hash,
  Mail,
  MessageSquarePlus,
  MonitorSmartphone,
  Pencil,
  Phone,
  School,
} from "lucide-react";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Button, buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";
import { StudentAvatar } from "@/components/students/student-avatar";
import { useAuth } from "@/lib/auth/auth-context";
import { canViewDevices } from "@/lib/auth/permissions";
import { useStudentDevices } from "@/lib/api/devices";
import { useTenantTimeZone } from "@/lib/api/klass-admin";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";
import { useStudentDetail, type StudentDetail } from "@/lib/api/student-management";
import { formatZonedDateTime } from "@/lib/datetime";
import { AddRemarkDialog } from "./student-action-dialogs";
import { InfoItem, useCourseNames, useStudentPhotoUrl } from "./student-display";

/**
 * Student details drawer (Klass "view" row action). Every query is keyed by
 * the selected student id (with an AbortSignal), and content renders only
 * when the loaded detail's id equals the selected id — while switching
 * rapidly between rows the drawer shows a skeleton, never the previous
 * student's data.
 */
export function StudentDetailsDrawer({
  studentId,
  onClose,
  canManage,
  onEdit,
  onNotice,
}: {
  studentId: string | null;
  onClose: () => void;
  canManage: boolean;
  onEdit: (id: string) => void;
  onNotice: (message: string) => void;
}) {
  return (
    <Sheet open={studentId !== null} onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <SheetContent
        side="right"
        className="w-full data-[side=right]:w-full data-[side=right]:sm:max-w-full data-[side=right]:md:max-w-md"
        data-testid="student-details-drawer"
      >
        <SheetHeader>
          <SheetTitle>Student Details</SheetTitle>
          <SheetDescription className="sr-only">Summary of the selected student.</SheetDescription>
        </SheetHeader>
        {studentId ? (
          <DrawerBody key={studentId} studentId={studentId} canManage={canManage} onEdit={onEdit} onNotice={onNotice} />
        ) : null}
      </SheetContent>
    </Sheet>
  );
}

function DrawerSkeleton() {
  return (
    <div className="flex flex-col gap-4 px-4" aria-busy="true" data-testid="student-drawer-loading">
      <span className="sr-only" role="status">
        Loading student…
      </span>
      <div className="flex items-center gap-3">
        <Skeleton className="size-20 rounded-full" />
        <div className="flex flex-1 flex-col gap-2">
          <Skeleton className="h-5 w-3/4" />
          <Skeleton className="h-4 w-1/3" />
        </div>
      </div>
      {Array.from({ length: 6 }).map((_, index) => (
        <Skeleton key={index} className="h-9 w-full" />
      ))}
    </div>
  );
}

function DrawerBody({
  studentId,
  canManage,
  onEdit,
  onNotice,
}: {
  studentId: string;
  canManage: boolean;
  onEdit: (id: string) => void;
  onNotice: (message: string) => void;
}) {
  const query = useStudentDetail(studentId);
  const detail = query.data && query.data.id === studentId ? query.data : undefined;

  if (query.status === "error") {
    if (isApiClientError(query.error) && query.error.status === 403) {
      return (
        <div className="px-4">
          <PermissionDeniedState error={query.error as ApiClientError} />
        </div>
      );
    }
    return (
      <div className="px-4">
        <ErrorState
          message={
            isApiClientError(query.error) && query.error.status === 404
              ? "This student no longer exists in this institute."
              : isApiClientError(query.error)
                ? query.error.message
                : "The student couldn't be loaded."
          }
          onRetry={() => void query.refetch()}
        />
      </div>
    );
  }

  if (!detail) return <DrawerSkeleton />;
  return <DrawerContent detail={detail} canManage={canManage} onEdit={onEdit} onNotice={onNotice} />;
}

function DrawerContent({
  detail,
  canManage,
  onEdit,
  onNotice,
}: {
  detail: StudentDetail;
  canManage: boolean;
  onEdit: (id: string) => void;
  onNotice: (message: string) => void;
}) {
  const { session } = useAuth();
  const showDevices = canViewDevices(session?.role ?? null);
  const { timeZone } = useTenantTimeZone();
  const courseName = useCourseNames();
  const photoUrl = useStudentPhotoUrl(detail, detail.id);
  const [remarkOpen, setRemarkOpen] = useState(false);

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-4 pb-6" data-testid="student-drawer-content" data-student-id={detail.id}>
      <div className="flex items-center gap-4">
        <StudentAvatar name={detail.fullName} photoUrl={photoUrl} size="lg" />
        <div className="min-w-0">
          <p className="truncate text-lg font-semibold text-foreground" data-testid="drawer-student-name">
            {detail.fullName}
          </p>
          <p className="text-sm text-muted-foreground">
            Index Number · <span className="font-mono tabular-nums" data-testid="drawer-student-index">{detail.indexNumber ?? "—"}</span>
          </p>
          {detail.pending ? <p className="text-xs font-medium text-amber-700 dark:text-amber-400">Pending approval</p> : null}
        </div>
      </div>

      <div className="flex flex-wrap gap-2">
        {canManage ? (
          <>
            <Button type="button" size="sm" onClick={() => setRemarkOpen(true)}>
              <MessageSquarePlus aria-hidden="true" />
              Add Remark
            </Button>
            <Button type="button" size="sm" variant="outline" onClick={() => onEdit(detail.id)}>
              <Pencil aria-hidden="true" />
              Edit
            </Button>
          </>
        ) : null}
        <Link href={`/tenant-admin/students/${detail.id}`} className={buttonVariants({ size: "sm", variant: "outline" })}>
          See Full Profile
        </Link>
      </div>

      <div className="grid grid-cols-1 gap-3">
        <InfoItem icon={<Hash />} label="Index Number" value={detail.indexNumber} />
        <InfoItem icon={<Mail />} label="Email" value={<span className="break-all">{detail.email}</span>} />
        <InfoItem icon={<Phone />} label="Mobile" value={detail.mobile} />
        <InfoItem icon={<Phone />} label="WhatsApp" value={detail.whatsappNumber} />
        <InfoItem icon={<School />} label="School" value={detail.school} />
        <InfoItem icon={<Cake />} label="Birthday" value={detail.dateOfBirth} />
        <InfoItem icon={<Clock />} label="Last Login" value={formatZonedDateTime(detail.lastLoginAt, timeZone, "Never")} />
      </div>

      {showDevices ? <DrawerDevices studentId={detail.id} /> : null}

      <section aria-labelledby="drawer-courses-heading" className="flex flex-col gap-2">
        <h3 id="drawer-courses-heading" className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <BookOpen className="size-4" aria-hidden="true" /> Enrolled Courses
        </h3>
        {detail.enrolledCourseIds.length === 0 ? (
          <p className="text-sm text-muted-foreground">Not enrolled in any course yet.</p>
        ) : (
          <ul className="flex flex-col gap-1 text-sm" data-testid="drawer-courses">
            {detail.enrolledCourseIds.map((courseId) => (
              <li key={courseId} className="rounded-md bg-muted/40 px-2 py-1">
                {courseName(courseId)}
              </li>
            ))}
          </ul>
        )}
      </section>

      {canManage ? (
        <AddRemarkDialog
          student={{ id: detail.id, name: detail.fullName, indexNumber: detail.indexNumber }}
          open={remarkOpen}
          onOpenChange={setRemarkOpen}
          onAdded={onNotice}
        />
      ) : null}
    </div>
  );
}

function DrawerDevices({ studentId }: { studentId: string }) {
  const query = useStudentDevices(studentId);
  return (
    <section aria-labelledby="drawer-devices-heading" className="flex flex-col gap-2">
      <h3 id="drawer-devices-heading" className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <MonitorSmartphone className="size-4" aria-hidden="true" /> Devices
        {query.data ? (
          <span className="rounded-full bg-muted px-2 text-xs font-medium" data-testid="drawer-device-count">
            {query.data.devices.filter((device) => device.status === "ACTIVE").length}
          </span>
        ) : null}
      </h3>
      {query.status === "pending" ? (
        <Skeleton className="h-8 w-full" />
      ) : query.status === "error" ? (
        <p role="alert" className="text-sm text-destructive">
          Devices couldn&apos;t be loaded.
        </p>
      ) : query.data.devices.length === 0 ? (
        <p className="text-sm text-muted-foreground">No devices yet — one appears after the student signs in.</p>
      ) : (
        <ul className="flex flex-col gap-1 text-sm">
          {query.data.devices.map((device) => (
            <li key={device.id} className="flex items-center justify-between gap-2 rounded-md bg-muted/40 px-2 py-1">
              <span className="truncate">{device.label}</span>
              <span className="text-xs text-muted-foreground">{device.status === "ACTIVE" ? "Active" : "Removed"}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
