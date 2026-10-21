"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { RevokeFreeCardDialog } from "@/components/payments/staff/revoke-free-card-dialog";
import { NATIVE_SELECT_CLASS } from "@/components/payments/staff/staff-dialog";
import { FreeCardStatusBadge } from "@/components/payments/staff/staff-status-badges";
import { useCourses } from "@/lib/api/courses";
import {
  FREE_CARD_STATUS_OPTIONS,
  formatYearMonth,
  useFreeCards,
  type FreeCard,
  type FreeCardStatus,
} from "@/lib/api/free-cards";
import { useAuth } from "@/lib/auth/auth-context";
import { canRevokeFreeCard } from "@/lib/auth/permissions";

const PAGE_SIZE = 20;

/**
 * Free Cards (ADR-030, Klass "FREE CARDS" tab): every free card of the
 * institute with course + status filters and a Revoke action for
 * `canRevokeFreeCard` (UX only; `FREE_CARDS` VIEW/DELETE are enforced by the
 * backend). One row per card - a card is never deleted, only revoked.
 */
export default function FreeCardsPage() {
  const { session } = useAuth();
  const canRevoke = canRevokeFreeCard(session?.role ?? null);
  const [courseId, setCourseId] = useState("");
  const [status, setStatus] = useState<FreeCardStatus | "">("");
  const [page, setPage] = useState(0);
  const [announcement, setAnnouncement] = useState("");
  const courses = useCourses();
  const query = useFreeCards({ courseId: courseId || null, status: status || null, page, size: PAGE_SIZE });
  const filtered = courseId !== "" || status !== "";

  const columns: DataTableColumn<FreeCard>[] = [
    {
      key: "student",
      header: "Student",
      cell: (row) =>
        row.studentId ? (
          <Link
            href={`/tenant-admin/payments/students?studentId=${encodeURIComponent(row.studentId)}&courseId=${encodeURIComponent(row.courseId)}`}
            className="font-medium text-foreground hover:underline"
          >
            {row.studentName ?? "Student"}
          </Link>
        ) : (
          (row.studentName ?? "—")
        ),
      hideOnCard: true,
    },
    { key: "course", header: "Course", cell: (row) => row.courseTitle ?? "—" },
    { key: "months", header: "Months", cell: (row) => row.coveredMonths.map(formatYearMonth).join(", ") },
    { key: "validity", header: "Validity", cell: (row) => `${row.validFrom} – ${row.validTo}` },
    { key: "status", header: "Status", cell: (row) => <FreeCardStatusBadge status={row.status} />, hideOnCard: true },
    { key: "issuedBy", header: "Issued by", cell: (row) => row.issuedByName ?? row.issuedBy },
    {
      key: "actions",
      header: "Actions",
      cell: (row) =>
        canRevoke && row.status !== "REVOKED" ? <RevokeFreeCardDialog card={row} onRevoked={setAnnouncement} /> : "—",
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Free Cards</h1>
        <p className="text-sm text-muted-foreground">
          Free monthly access given to students. A free card is not a payment and records no revenue. Issue new cards from{" "}
          <Link href="/tenant-admin/payments/students" className="font-medium text-foreground underline">
            Student Payments
          </Link>
          .
        </p>
      </div>
      <LiveRegion message={announcement} />

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:max-w-2xl">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="free-cards-course">Course</Label>
          <select
            id="free-cards-course"
            className={NATIVE_SELECT_CLASS}
            value={courseId}
            onChange={(event) => {
              setCourseId(event.target.value);
              setPage(0);
            }}
            data-testid="free-cards-course-filter"
          >
            <option value="">All courses</option>
            {(courses.data?.content ?? []).map((course) => (
              <option key={course.id} value={course.id}>
                {course.name}
              </option>
            ))}
          </select>
          {courses.isError ? <p className="text-xs text-muted-foreground">Couldn&apos;t load the course list.</p> : null}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="free-cards-status">Status</Label>
          <select
            id="free-cards-status"
            className={NATIVE_SELECT_CLASS}
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as FreeCardStatus | "");
              setPage(0);
            }}
            data-testid="free-cards-status-filter"
          >
            <option value="">All statuses</option>
            {FREE_CARD_STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading free cards…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={
          filtered
            ? {
                title: "No free cards match your filters",
                description: "Try another course or status.",
                action: {
                  label: "Clear filters",
                  onClick: () => {
                    setCourseId("");
                    setStatus("");
                    setPage(0);
                  },
                },
              }
            : {
                title: "No free cards yet",
                description: "Free cards issued from Student Payments appear here.",
              }
        }
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            <DataTable
              columns={columns}
              rows={data.content}
              rowKey={(row) => row.id}
              caption="Free cards"
              cardHeading={(row) => row.studentName ?? "Student"}
              cardHeadingAdornment={(row) => <FreeCardStatusBadge status={row.status} />}
              cardFooter={
                canRevoke
                  ? (row) => (row.status !== "REVOKED" ? <RevokeFreeCardDialog card={row} onRevoked={setAnnouncement} /> : null)
                  : undefined
              }
            />
            <div className="flex items-center justify-between">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => Math.max(0, current - 1))}
                disabled={page === 0}
              >
                Previous
              </Button>
              <span className="text-xs text-muted-foreground">
                Page {data.page + 1} of {Math.max(data.totalPages, 1)}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => current + 1)}
                disabled={data.page + 1 >= data.totalPages}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
