"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Plus, Search, UserRound } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { ReorderableTable, moveItem, type ReorderableColumn } from "@/components/courses/admin/reorderable-table";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useAuth } from "@/lib/auth/auth-context";
import { useTeachers, useReorderTeachers, type Teacher, type TeacherApprovalStatus } from "@/lib/api/teachers";
import { TEACHER_APPROVAL_STATUS_LABELS } from "./status-badge";
import { TeacherDecisionDialog } from "./teacher-decision-dialog";
import { TeacherSuspendDialog } from "./teacher-suspend-dialog";
import { TeacherEditSheet } from "./teacher-edit-sheet";

/**
 * Teacher List (Tenant Admin) — MVP-007 TCH-1.
 *
 * Fetches the full, unfiltered `/v1/teachers` list once via `useTeachers()`
 * and filters client-side by approval status + name/email search, mirroring
 * `(platform-admin)/platform-admin/tenants/page.tsx`'s filter-chain pattern.
 * This is correct here (not the `.claude/rules/ui-ux.md` §1 "never filter
 * client-side" rule, which targets a Teacher's own restricted course
 * visibility) — a Tenant Admin/Course Coordinator/Student Support/Read-only
 * Auditor is already fully authorized to view every status under the same
 * `VIEW` grant used to fetch this list.
 */

const STATUS_FILTER_OPTIONS: Array<{ value: "all" | TeacherApprovalStatus; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "PENDING", label: TEACHER_APPROVAL_STATUS_LABELS.PENDING },
  { value: "APPROVED", label: TEACHER_APPROVAL_STATUS_LABELS.APPROVED },
  { value: "REJECTED", label: TEACHER_APPROVAL_STATUS_LABELS.REJECTED },
  { value: "SUSPENDED", label: TEACHER_APPROVAL_STATUS_LABELS.SUSPENDED },
];

export default function TenantAdminTeachersPage() {
  const { session } = useAuth();
  const router = useRouter();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [statusFilter, setStatusFilter] = useState<"all" | TeacherApprovalStatus>("all");
  const [search, setSearch] = useState("");
  const query = useTeachers({ query: search, page, size: pageSize });

  // Row-level Approve/Reject actions are UX convenience only — the backend
  // independently re-enforces both the PENDING precondition (409) and the
  // literal Tenant Admin role requirement (403) on every call.
  const canDecide = session?.role === "TENANT_ADMIN";
  // "Add teacher" needs the same UX-hiding treatment: only Tenant Admin and
  // Course Coordinator hold TEACHERS/CREATE_EDIT (Student Support and
  // Read-only Auditor are VIEW-only) — the backend independently re-enforces
  // this on POST /v1/teachers regardless of what renders here.
  const canCreate = session?.role === "TENANT_ADMIN" || session?.role === "COURSE_COORDINATOR";

  const clearFilters = () => {
    setStatusFilter("all");
    setSearch("");
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-3">
            <span className="flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground"><UserRound aria-hidden="true" /></span>
            <h1 className="text-xl font-semibold text-foreground">Teachers</h1>
          </div>
          <p className="text-sm text-muted-foreground">
            Teacher accounts for your institute, and their approval status.
          </p>
        </div>
        {canCreate ? (
          <Button render={<Link href="/tenant-admin/teachers/new" />}>
            <Plus aria-hidden="true" />
            ADD TEACHER
          </Button>
        ) : null}
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading teachers…"
        isEmpty={(data: Teacher[]) => data.length === 0 && search.trim() === "" && statusFilter === "all"}
        emptyState={{
          title: "No teachers yet",
          description: canCreate
            ? "You haven't added any teacher accounts for your institute yet. Add your first teacher to get started."
            : "No teacher accounts have been added for your institute yet.",
          action: canCreate
            ? { label: "Add teacher", onClick: () => router.push("/tenant-admin/teachers/new") }
            : undefined,
        }}
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(teachers) => (
          <TeacherListResults
            teachers={teachers}
            statusFilter={statusFilter}
            setStatusFilter={setStatusFilter}
            search={search}
            setSearch={setSearch}
            clearFilters={clearFilters}
            canDecide={canDecide}
            canEdit={canCreate}
            page={page}
            pageSize={pageSize}
            setPage={setPage}
            setPageSize={setPageSize}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}

interface TeacherListResultsProps {
  teachers: Teacher[];
  statusFilter: "all" | TeacherApprovalStatus;
  setStatusFilter: (value: "all" | TeacherApprovalStatus) => void;
  search: string;
  setSearch: (value: string) => void;
  clearFilters: () => void;
  canDecide: boolean;
  canEdit: boolean;
  page: number;
  pageSize: number;
  setPage: (page: number) => void;
  setPageSize: (size: number) => void;
}

function TeacherAvatar({ teacher }: { teacher: Teacher }) {
  const [imageFailed, setImageFailed] = useState(false);

  if (teacher.profileImageUrl && !imageFailed) {
    return <img src={teacher.profileImageUrl} alt={`${teacher.name} profile`} className="size-10 rounded-full object-cover" onError={() => setImageFailed(true)} />;
  }

  return <span className="flex size-10 items-center justify-center rounded-full bg-muted"><UserRound aria-hidden="true" /></span>;
}

function TeacherListResults({
  teachers,
  statusFilter,
  setStatusFilter,
  search,
  setSearch,
  clearFilters,
  canDecide,
  canEdit,
  page,
  pageSize,
  setPage,
  setPageSize,
}: TeacherListResultsProps) {
  const [editingTeacher, setEditingTeacher] = useState<Teacher | null>(null);
  const reorder = useReorderTeachers();

  const filteredByStatus = useMemo(
    () =>
      statusFilter === "all"
        ? teachers
        : teachers.filter((teacher) => teacher.approvalStatus === statusFilter),
    [teachers, statusFilter]
  );

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return filteredByStatus;
    return filteredByStatus.filter(
      (teacher) =>
        teacher.name.toLowerCase().includes(q) ||
        teacher.email.toLowerCase().includes(q) ||
        (teacher.firstName ?? "").toLowerCase().includes(q) ||
        (teacher.lastName ?? "").toLowerCase().includes(q) ||
        (teacher.contactNumber ?? "").toLowerCase().includes(q)
    );
  }, [filteredByStatus, search]);

  // `teachers` is non-empty here (QueryStateBoundary already rendered the
  // "no teachers yet" empty state for the true zero-data case) — this is the
  // distinct "no results match your filters" state, per
  // `.claude/rules/ui-ux.md` §3.
  const noMatch = filtered.length === 0;
  // A third, distinct empty state: the approval queue (status filter =
  // PENDING, no search term) legitimately has zero rows today — "you're all
  // caught up" reads very differently from "your search/filter combination
  // matched nothing," per plan §4/§11 and acceptance criterion #7.
  const isPendingQueueEmpty = noMatch && statusFilter === "PENDING" && search.trim() === "";

  // Distinguishable success feedback for row-level Suspend/Reactivate — this
  // app has no toast library (`components/ui/live-region.tsx`'s doc
  // comment), so this mirrors `students/page.tsx#createdNotice`'s
  // established brief, auto-clearing, `role="status" aria-live="polite"`
  // page-level notice pattern rather than inventing a new one. (Approve/
  // Reject already gets its own distinguishable confirmation for free: the
  // row disappears from the PENDING-filtered view /the badge updates in
  // place, which is why only Suspend/Reactivate wires this.)
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  function renderActions(teacher: Teacher, triggerVariant: "icon" | "full") {
    if (!canDecide) return null;
    if (teacher.approvalStatus === "PENDING") {
      return triggerVariant === "icon" ? (
        <div className="flex items-center gap-2">
          <TeacherDecisionDialog teacher={teacher} action="approve" triggerVariant="icon" />
          <TeacherDecisionDialog teacher={teacher} action="reject" triggerVariant="icon" />
        </div>
      ) : (
        // Full-label, size="lg" triggers (not the icon-only pair used in
        // the desktop table) — two adjacent small icon buttons for a
        // consequential action is a mis-tap risk on a touch surface, per
        // ui-ux.md §4/§5. `flex-1` gives each button an equal, wide tap
        // target instead.
        <div className="flex items-stretch gap-3 pt-1">
          <TeacherDecisionDialog teacher={teacher} action="approve" triggerVariant="full" className="flex-1" />
          <TeacherDecisionDialog teacher={teacher} action="reject" triggerVariant="full" className="flex-1" />
        </div>
      );
    }
    if (teacher.approvalStatus === "APPROVED") {
      return (
        <TeacherSuspendDialog
          teacher={teacher}
          action="suspend"
          triggerVariant={triggerVariant}
          className={triggerVariant === "full" ? "w-full" : undefined}
          onSuccess={() => setNotice(`${teacher.name} was suspended.`)}
        />
      );
    }
    if (teacher.approvalStatus === "SUSPENDED") {
      return (
        <TeacherSuspendDialog
          teacher={teacher}
          action="reactivate"
          triggerVariant={triggerVariant}
          className={triggerVariant === "full" ? "w-full" : undefined}
          onSuccess={() => setNotice(`${teacher.name} was reactivated.`)}
        />
      );
    }
    return null;
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 rounded-lg border border-border bg-card p-3 shadow-sm sm:flex-row sm:items-center sm:justify-between">
        <div className="relative w-full sm:max-w-sm">
          <Label htmlFor="teacher-search" className="sr-only">Search teachers</Label>
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input id="teacher-search" type="search" placeholder="Search" value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} className="pl-9" />
        </div>
        <div className="flex flex-col gap-1.5 sm:w-56">
          <Label htmlFor="teacher-status-filter">Status</Label>
          <select
            id="teacher-status-filter"
            value={statusFilter}
            onChange={(event) =>
              setStatusFilter(event.target.value as "all" | TeacherApprovalStatus)
            }
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          >
            {STATUS_FILTER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>

      {noMatch ? (
        isPendingQueueEmpty ? (
          <EmptyState
            title="No teachers waiting for approval"
            description="There are no pending teacher accounts right now. New teacher accounts appear here until a Tenant Admin approves or rejects them."
            action={{ label: "View all teachers", onClick: clearFilters }}
          />
        ) : (
          <EmptyState
            title="No teachers match your filter"
            description="No teachers match your current search and status filter. Try a different search term or clear the status filter."
            action={{ label: "Clear filters", onClick: clearFilters }}
          />
        )
      ) : (
        <ReorderableTable
          columns={[
            { key: "teacher", header: "Teacher", cardLabel: "Teacher", cell: (teacher) => <div className="flex items-center gap-3"><TeacherAvatar teacher={teacher} /><div><p className="font-medium text-foreground">{teacher.name}</p><p className="text-xs text-muted-foreground">{teacher.approvalStatus}</p></div></div> },
            { key: "email", header: "Email", cell: (teacher) => teacher.email },
            { key: "contact", header: "Contact Number", cell: (teacher) => teacher.contactNumber ?? "—" },
            { key: "students", header: "Student Count", cell: (teacher) => teacher.studentCount },
            { key: "commission", header: "Commission", cell: (teacher) => `${teacher.instituteCommissionPercent ?? 0}%` },
          ] as ReorderableColumn<Teacher>[]}
          rows={filtered}
          rowKey={(teacher) => teacher.id}
          rowLabel={(teacher) => teacher.name}
          caption="Teachers"
          reorderEnabled={!search.trim() && statusFilter === "all" && filtered.length > 1}
          reorderPending={reorder.isPending}
          showMoveButtons={false}
          showPosition={false}
          onMove={(from, to) => {
            const next = moveItem(filtered, from, to);
            void reorder.mutateAsync(next.map((teacher) => teacher.id));
          }}
          actions={(teacher) => <div className="flex items-center gap-1">{canEdit ? <Button type="button" size="icon-sm" variant="ghost" aria-label={`Edit teacher ${teacher.name}`} onClick={() => setEditingTeacher(teacher)}>✎</Button> : null}{renderActions(teacher, "icon")}</div>}
        />
      )}

      {!noMatch ? (
        <div className="flex flex-wrap items-center justify-end gap-3 text-sm text-muted-foreground">
          <label htmlFor="teacher-page-size">Items per page:</label>
          <select id="teacher-page-size" value={pageSize} onChange={(event) => { setPageSize(Number(event.target.value)); setPage(0); }} className="h-9 rounded-md border border-input bg-background px-2">
            {[10, 25, 50].map((size) => <option key={size} value={size}>{size}</option>)}
          </select>
          <span>{teachers.length === 0 ? "0 of 0" : `${page * pageSize + 1}–${page * pageSize + teachers.length} of ${teachers.length}`}</span>
          <Button type="button" size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)} aria-label="Previous page">‹</Button>
          <Button type="button" size="sm" variant="outline" disabled={teachers.length < pageSize} onClick={() => setPage(page + 1)} aria-label="Next page">›</Button>
        </div>
      ) : null}

      <TeacherEditSheet
        teacher={editingTeacher}
        open={editingTeacher !== null}
        onOpenChange={(open) => {
          if (!open) setEditingTeacher(null);
        }}
        onSaved={() => {
          setEditingTeacher(null);
          setNotice("Teacher profile saved.");
        }}
      />
    </div>
  );
}
