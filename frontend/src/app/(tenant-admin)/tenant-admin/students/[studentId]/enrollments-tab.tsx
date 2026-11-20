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
import { ExtendAccessDialog } from "./extend-access-dialog";

function accessEndText(row: EnrollmentHistoryEntryResponse): string {
  const end = row.effectiveExpiresAt ?? row.accessExpiresAt;
  if (!end) {
    return "No expiry";
  }
  const grace =
    row.accessEndsAt && row.accessEndsAt !== end ? ` (grace until ${formatDateTime(row.accessEndsAt)})` : "";
  return `${formatDateTime(end)}${grace}`;
}

/** Wave 10: extend a current, time-limited enrollment (active or expired) - never a revoked/superseded one. */
function canExtendRow(row: EnrollmentHistoryEntryResponse): boolean {
  return row.current && row.accessExpiresAt !== null;
}

function RowActions({
  row,
  studentId,
  canManage,
  canExtend,
  onRevoked,
  onNotice,
}: {
  row: EnrollmentHistoryEntryResponse;
  studentId: string;
  canManage: boolean;
  canExtend: boolean;
  onRevoked: (courseId: string) => void;
  onNotice: (message: string) => void;
}) {
  const showExtend = canExtend && canExtendRow(row);
  const showRevoke = canManage && row.current;
  if (!showExtend && !showRevoke) {
    return null;
  }
  return (
    <div className="flex flex-wrap gap-2">
      {showExtend ? <ExtendAccessDialog studentId={studentId} enrollment={row} onSuccess={onNotice} /> : null}
      {showRevoke ? (
        <RevokeEnrollmentDialog
          studentId={studentId}
          enrollmentId={row.enrollmentId}
          courseId={row.courseId}
          onSuccess={() => onRevoked(row.courseId)}
        />
      ) : null}
    </div>
  );
}

const columns = (
  studentId: string,
  canManage: boolean,
  canExtend: boolean,
  onRevoked: (courseId: string) => void,
  onNotice: (message: string) => void
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
        <span className="flex flex-wrap gap-1">
          <Badge variant="default">
            <CheckCircle2 className="size-3.5" aria-hidden="true" />
            Current
          </Badge>
          {!row.accessActive ? <Badge variant="warning">Access ended</Badge> : null}
          {row.extended ? <Badge variant="secondary">Extended</Badge> : null}
        </span>
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
    header: "Access ends",
    cell: (row) => accessEndText(row),
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
    cell: (row) => (
      <RowActions
        row={row}
        studentId={studentId}
        canManage={canManage}
        canExtend={canExtend}
        onRevoked={onRevoked}
        onNotice={onNotice}
      />
    ),
    hideOnCard: true,
  },
];

export function EnrollmentsTab({
  studentId,
  canManage,
  canExtend,
}: {
  studentId: string;
  canManage: boolean;
  /** Wave 10 - `ACCESS_EXPIRY`/`CREATE_EDIT` (Tenant Admin). UX only. */
  canExtend: boolean;
}) {
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
            columns={columns(studentId, canManage, canExtend, handleRevoked, setNotice)}
            rows={enrollments}
            rowKey={(row) => row.enrollmentId}
            caption="Enrollment history"
            cardHeading={(row) => shortId(row.courseId)}
            cardHeadingAdornment={(row) =>
              row.current ? <Badge variant="default">Current</Badge> : <Badge variant="outline">Superseded</Badge>
            }
            cardFooter={(row) => (
              <RowActions
                row={row}
                studentId={studentId}
                canManage={canManage}
                canExtend={canExtend}
                onRevoked={handleRevoked}
                onNotice={setNotice}
              />
            )}
          />
        </div>
      )}
    </QueryStateBoundary>
  );
}
