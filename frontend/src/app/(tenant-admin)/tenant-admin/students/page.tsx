"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Plus } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageStudents } from "@/lib/auth/permissions";
import { useStudents, type StudentResponse } from "@/lib/api/students";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { StudentStatusBadge } from "@/components/students/student-status-badge";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { CreateStudentSheet } from "./create-student-sheet";

/**
 * Student List (MVP-006, Tenant Admin). Fetches the full tenant-scoped list
 * from `GET /v1/students` (no server-side pagination/search/filter exists —
 * see `StudentController`) and applies search/status filtering client-side,
 * same pattern as the platform-admin tenants scaffold
 * (`app/(platform-admin)/platform-admin/tenants/page.tsx`), which this page
 * also borrows the canonical responsive table/card pattern from (now
 * extracted into `components/ui/data-table.tsx`).
 */

type StatusFilter = "all" | "ACTIVE" | "SUSPENDED";

const STATUS_FILTER_OPTIONS: Array<{ value: StatusFilter; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "ACTIVE", label: "Active" },
  { value: "SUSPENDED", label: "Suspended" },
];

export default function StudentListPage() {
  const { session } = useAuth();
  const studentsQuery = useStudents();

  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("all");
  const [sheetOpen, setSheetOpen] = useState(false);
  const [createdNotice, setCreatedNotice] = useState<string | null>(null);

  const canManage = canManageStudents(session?.role ?? null);

  const clearFilters = () => {
    setSearch("");
    setStatusFilter("all");
  };

  // A newly-created student could otherwise be invisible right after the
  // sheet closes if an active search/status filter happens to exclude it
  // (e.g. status filter set to "Suspended") — reset filters so the new row
  // is guaranteed visible, and announce success since the sheet closing
  // alone gives no positive confirmation.
  useEffect(() => {
    if (!createdNotice) return;
    const timeout = setTimeout(() => setCreatedNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [createdNotice]);

  const handleCreated = (student: StudentResponse) => {
    clearFilters();
    setCreatedNotice(`${student.name} was added.`);
  };

  const columns: DataTableColumn<StudentResponse>[] = [
    { key: "name", header: "Name", cell: (student) => student.name, hideOnCard: true },
    { key: "email", header: "Email", cell: (student) => student.email },
    {
      key: "status",
      header: "Status",
      cell: (student) => <StudentStatusBadge status={student.status} />,
      hideOnCard: true,
    },
    {
      key: "actions",
      header: "Actions",
      cell: (student) => (
        <Link
          href={`/tenant-admin/students/${student.id}`}
          aria-label={`View ${student.name}`}
          className="font-medium text-foreground hover:underline"
        >
          View
        </Link>
      ),
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Students</h1>
          <p className="text-sm text-muted-foreground">
            Manage this institute&apos;s student accounts.
          </p>
        </div>
        {canManage ? (
          <Button
            type="button"
            aria-haspopup="dialog"
            aria-expanded={sheetOpen}
            onClick={() => setSheetOpen(true)}
          >
            <Plus aria-hidden="true" />
            Add student
          </Button>
        ) : null}
      </div>

      <div role="status" aria-live="polite">
        {createdNotice ? <p className="text-sm text-muted-foreground">{createdNotice}</p> : null}
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor="student-search">Search</Label>
          <Input
            id="student-search"
            type="search"
            placeholder="Search by name or email"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-1.5 sm:w-56">
          <Label htmlFor="student-status-filter">Status</Label>
          <select
            id="student-status-filter"
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value as StatusFilter)}
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

      <QueryStateBoundary
        query={studentsQuery}
        loadingLabel="Loading students…"
        isEmpty={(students) => students.length === 0}
        emptyState={{
          title: "No students yet",
          description: canManage
            ? "This institute doesn't have any student accounts yet. Add your first student to get started."
            : "This institute doesn't have any student accounts yet.",
          action: canManage ? { label: "Add student", onClick: () => setSheetOpen(true) } : undefined,
        }}
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(students) => {
          const query = search.trim().toLowerCase();
          const filtered = students.filter((student) => {
            const matchesQuery =
              query.length === 0 ||
              student.name.toLowerCase().includes(query) ||
              student.email.toLowerCase().includes(query);
            const matchesStatus = statusFilter === "all" || student.status === statusFilter;
            return matchesQuery && matchesStatus;
          });

          if (filtered.length === 0) {
            return (
              <EmptyState
                title="No students match your filters"
                description="No students match your current search and status filter. Try a different search term or clear the status filter."
                action={{ label: "Clear filters", onClick: clearFilters }}
              />
            );
          }

          return (
            <>
              <DataTable
                columns={columns}
                rows={filtered}
                rowKey={(student) => student.id}
                caption="Students"
                cardHeading={(student) => student.name}
                cardHeadingAdornment={(student) => <StudentStatusBadge status={student.status} />}
                cardFooter={(student) => (
                  <Link
                    href={`/tenant-admin/students/${student.id}`}
                    aria-label={`View ${student.name}`}
                    className="text-sm font-medium text-foreground hover:underline"
                  >
                    View
                  </Link>
                )}
              />
              <p role="status" aria-live="polite" className="text-xs text-muted-foreground">
                Showing {filtered.length} of {students.length} students.
              </p>
            </>
          );
        }}
      </QueryStateBoundary>

      {canManage ? (
        <CreateStudentSheet open={sheetOpen} onOpenChange={setSheetOpen} onCreated={handleCreated} />
      ) : null}
    </div>
  );
}
