"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Store } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StorefrontStatusBadge, storefrontStatus } from "@/components/storefront/storefront-status-badge";
import { useTeacherStorefronts, type TeacherStorefrontRow } from "@/lib/api/teacher-storefronts";
import type { PageResponse } from "@/lib/api/courses";
import { cn } from "@/lib/utils";

/**
 * STORE-01 / ADR-037: Tenant Admin -> Teachers -> Storefronts. Lists every
 * teacher of this institute with their storefront status. Visible to holders
 * of `TEACHER_STOREFRONT_MANAGE`; the endpoint re-checks it (403 renders the
 * permission-denied state).
 */
export default function TeacherStorefrontsPage() {
  const router = useRouter();
  const [page, setPage] = useState(0);
  const query = useTeacherStorefronts(page);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <div className="flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <Store aria-hidden="true" />
          </span>
          <h1 className="text-xl font-semibold text-foreground">Teacher storefronts</h1>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">
          Give a teacher a personal homepage that sells only the courses you choose. Storefronts are off until you
          turn them on.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading teacher storefronts…"
        isEmpty={(data: PageResponse<TeacherStorefrontRow>) => data.content.length === 0 && data.page === 0}
        emptyState={{
          title: "No teachers yet",
          description: "A storefront belongs to a teacher. Add a teacher first, then set up their storefront here.",
          action: { label: "Add teacher", onClick: () => router.push("/tenant-admin/teachers/new") },
        }}
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
        genericErrorMessage="We couldn't load teacher storefronts."
      >
        {(data) => <StorefrontTable data={data} onPage={setPage} />}
      </QueryStateBoundary>
    </div>
  );
}

function addressOf(row: TeacherStorefrontRow): string | null {
  return row.storefront?.publishedUrl ?? row.storefront?.previewUrl ?? null;
}

function productSummary(row: TeacherStorefrontRow): string {
  const products = row.storefront?.products ?? [];
  if (products.length === 0) return "No courses listed";
  const published = products.filter((product) => product.published).length;
  return `${published} of ${products.length} published`;
}

function StorefrontTable({ data, onPage }: { data: PageResponse<TeacherStorefrontRow>; onPage: (page: number) => void }) {
  const manageLink = (row: TeacherStorefrontRow, full = false) => (
    <Link
      href={`/tenant-admin/teachers/storefronts/${encodeURIComponent(row.teacherId)}`}
      className={cn(buttonVariants({ variant: "outline", size: "sm" }), full && "w-full justify-center")}
    >
      {row.storefront?.id ? "Manage" : "Set up"}
      <span className="sr-only"> storefront for {row.teacherName}</span>
    </Link>
  );

  const columns: DataTableColumn<TeacherStorefrontRow>[] = [
    { key: "teacher", header: "Teacher", cell: (row) => <span className="font-medium break-words">{row.teacherName}</span>, hideOnCard: true },
    { key: "status", header: "Status", cell: (row) => <StorefrontStatusBadge status={storefrontStatus(row.storefront)} />, hideOnCard: true },
    {
      key: "address",
      header: "Address",
      cell: (row) => {
        const address = addressOf(row);
        return address ? <span className="break-all text-muted-foreground">{address}</span> : <span className="text-muted-foreground">—</span>;
      },
    },
    { key: "courses", header: "Courses", cell: (row) => productSummary(row) },
    { key: "actions", header: "Actions", cell: (row) => manageLink(row), hideOnCard: true },
  ];

  return (
    <div className="flex flex-col gap-4">
      <DataTable
        columns={columns}
        rows={data.content}
        rowKey={(row) => row.teacherId}
        caption="Teacher storefronts"
        cardHeading={(row) => row.teacherName}
        cardHeadingAdornment={(row) => <StorefrontStatusBadge status={storefrontStatus(row.storefront)} />}
        cardFooter={(row) => manageLink(row, true)}
      />
      {data.totalPages > 1 ? (
        <nav aria-label="Teacher storefronts pagination" className="flex items-center justify-between gap-3">
          <Button type="button" variant="outline" size="sm" disabled={data.page <= 0} onClick={() => onPage(Math.max(data.page - 1, 0))}>
            Previous
          </Button>
          <span className="text-sm text-muted-foreground" aria-live="polite">
            Page {data.page + 1} of {data.totalPages}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={data.page + 1 >= data.totalPages}
            onClick={() => onPage(data.page + 1)}
          >
            Next
          </Button>
        </nav>
      ) : null}
    </div>
  );
}
