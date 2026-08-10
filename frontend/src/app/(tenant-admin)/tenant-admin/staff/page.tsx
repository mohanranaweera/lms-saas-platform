"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/auth-context";
import { useStaffList, type StaffAccount } from "@/lib/api/staff";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Button } from "@/components/ui/button";
import { RoleBadge } from "./role-badge";
import { StaffStatusBadge } from "./status-badge";

/**
 * Staff List (MVP-005) — real, tenant-scoped data via `GET /v1/staff`
 * (`useStaffList`). Only ever a true zero-data empty state in this pass:
 * there is no filter/search UI here since the backend has no filtering
 * support to back one, per the module brief.
 */
export default function StaffListPage() {
  const { session } = useAuth();
  const router = useRouter();
  const query = useStaffList();

  // UX convenience only, per `.claude/rules/ui-ux.md` §1 (Staff sub-roles)
  // and `.claude/rules/security.md` — the real enforcement is the 403
  // `POST /v1/staff` returns regardless of what this button shows/hides.
  const canAddStaff = session?.role === "TENANT_ADMIN";

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Staff</h1>
          <p className="text-sm text-muted-foreground">
            Staff accounts for your institute — finance, course coordination, student support,
            and other administrative roles.
          </p>
        </div>
        {canAddStaff ? (
          <Button render={<Link href="/tenant-admin/staff/new" />}>Add staff</Button>
        ) : null}
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading staff…"
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: "No staff accounts yet",
          description:
            "Add your first staff account to start delegating finance, course, or support tasks to your team.",
          action: canAddStaff
            ? { label: "Add staff", onClick: () => router.push("/tenant-admin/staff/new") }
            : undefined,
        }}
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(staff) => <StaffListContent staff={staff} />}
      </QueryStateBoundary>
    </div>
  );
}

function StaffListContent({ staff }: { staff: StaffAccount[] }) {
  return (
    <>
      {/* Desktop/tablet: table. Below md: card list (.claude/rules/ui-ux.md §5). */}
      <div className="hidden overflow-x-auto rounded-lg border border-border md:block">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-border bg-muted/40">
            <tr>
              <th scope="col" className="px-4 py-2 font-medium text-foreground">
                Name
              </th>
              <th scope="col" className="px-4 py-2 font-medium text-foreground">
                Email
              </th>
              <th scope="col" className="px-4 py-2 font-medium text-foreground">
                Role
              </th>
              <th scope="col" className="px-4 py-2 font-medium text-foreground">
                Status
              </th>
            </tr>
          </thead>
          <tbody>
            {staff.map((member) => (
              <tr key={member.id} className="border-b border-border last:border-0">
                <td className="px-4 py-2.5 font-medium text-foreground">
                  <Link href={`/tenant-admin/staff/${member.id}`} className="hover:underline">
                    {member.name}
                  </Link>
                </td>
                <td className="px-4 py-2.5 text-muted-foreground">{member.email}</td>
                <td className="px-4 py-2.5">
                  <RoleBadge roleCode={member.roleCode} />
                </td>
                <td className="px-4 py-2.5">
                  <StaffStatusBadge status={member.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul className="flex flex-col gap-3 md:hidden">
        {staff.map((member) => (
          <li key={member.id} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <Link
              href={`/tenant-admin/staff/${member.id}`}
              className="font-medium text-foreground hover:underline"
            >
              {member.name}
            </Link>
            <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-xs text-muted-foreground">
              <dt className="font-medium text-foreground">Email</dt>
              <dd>{member.email}</dd>
              <dt className="font-medium text-foreground">Role</dt>
              <dd>
                <RoleBadge roleCode={member.roleCode} />
              </dd>
              <dt className="font-medium text-foreground">Status</dt>
              <dd>
                <StaffStatusBadge status={member.status} />
              </dd>
            </dl>
          </li>
        ))}
      </ul>
    </>
  );
}
