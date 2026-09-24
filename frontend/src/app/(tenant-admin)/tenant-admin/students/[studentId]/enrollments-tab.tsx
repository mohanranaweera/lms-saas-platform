"use client";

import { useEffect, useState } from "react";
import { CheckCircle2, Circle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import {
  useStudentEnrollments,
  type EnrollmentHistoryEntryResponse,
} from "@/lib/api/enrollments";
import { formatDateTime, shortId } from "@/lib/format";
import { RevokeEnrollmentDialog } from "./revoke-enrollment-dialog";

const columns = (
  studentId: string,
  canManage: boolean,
  onRevoked: (courseId: string) => void
): DataTableColumn<EnrollmentHistoryEntryResponse>[] => [
  {
    key: "course",
    header: "Course",
    cell: (row) => shortId(row.courseId),
    hideOnCard: true,
  },
  {
    key: "current",
    header: "Status",
    cell: (row) =>
      row.current ? (
        <Badge variant="default">
          <CheckCircle2 className="size-3.5" aria-hidden="true" />
          Current
        </Badge>
      ) : row.revokedAt ? (
        <Badge variant="destructive">Revoked</Badge>
      ) : (
        <Badge variant="outline">
          <Circle className="size-3.5" aria-hidden="true" />
          Superseded
        </Badge>
      ),
    hideOnCard: true,
  },
  {
    key: "activatedAt",
    header: "Activated",
    cell: (row) => (row.activatedAt ? formatDateTime(row.activatedAt) : "—"),
  },
  {
    key: "accessExpiresAt",
    header: "Access expires",
    cell: (row) => (row.accessExpiresAt ? formatDateTime(row.accessExpiresAt) : "No expiry"),
  },
  {
    key: "revoked",
    header: "Revoked",
    cell: (row) =>
      row.revokedAt ? (
        <span>
          {formatDateTime(row.revokedAt)}
          {row.revokeReason ? ` — ${row.revokeReason}` : ""}
        </span>
      ) : (
        "—"
      ),
  },
  {
    key: "actions",
    header: "Actions",
    cell: (row) =>
      canManage && row.current ? (
        <RevokeEnrollmentDialog
          studentId={studentId}
          enrollmentId={row.enrollmentId}
          courseId={row.courseId}
          onSuccess={() => onRevoked(row.courseId)}
        />
      ) : null,
    hideOnCard: true,
  },
];

export function EnrollmentsTab({ studentId, canManage }: { studentId: string; canManage: boolean }) {
  const query = useStudentEnrollments(studentId);

  // Distinguishable success feedback for Revoke — this app has no toast
  // library (`components/ui/live-region.tsx`'s doc comment), so this mirrors
  // `students/page.tsx#createdNotice`'s established brief, auto-clearing,
  // `role="status" aria-live="polite"` page-level notice pattern rather than
  // inventing a new one.
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  function handleRevoked(courseId: string) {
    setNotice(`Enrollment in ${shortId(courseId)} was revoked.`);
  }

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading enrollment history…"
      isEmpty={(data) => data.length === 0}
      emptyState={{
        title: "No enrollments yet",
        description: canManage
          ? "This student hasn't been enrolled in any course yet. Use the Enroll action to grant access to a course."
          : "This student hasn't been enrolled in any course yet.",
      }}
    >
      {(enrollments) => (
        <div className="flex flex-col gap-4">
          <div role="status" aria-live="polite">
            {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
          </div>
          <DataTable
            columns={columns(studentId, canManage, handleRevoked)}
            rows={enrollments}
            rowKey={(row) => row.enrollmentId}
            caption="Enrollment history"
            cardHeading={(row) => shortId(row.courseId)}
            cardHeadingAdornment={(row) =>
              row.current ? <Badge variant="default">Current</Badge> : <Badge variant="outline">Superseded</Badge>
            }
            cardFooter={(row) =>
              canManage && row.current ? (
                <RevokeEnrollmentDialog
                  studentId={studentId}
                  enrollmentId={row.enrollmentId}
                  courseId={row.courseId}
                  onSuccess={() => handleRevoked(row.courseId)}
                />
              ) : null
            }
          />
        </div>
      )}
    </QueryStateBoundary>
  );
}
