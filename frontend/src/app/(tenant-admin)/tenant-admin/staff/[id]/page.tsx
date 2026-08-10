"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useStaff } from "@/lib/api/staff";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { RoleBadge } from "../role-badge";
import { StaffStatusBadge } from "../status-badge";

/**
 * Staff Detail (MVP-005) — read-only, via `GET /v1/staff/{id}`. No
 * edit/role-change control, delete/deactivate button, password-reset
 * button, or activity log: none of those have a backend endpoint yet (see
 * `StaffController`'s doc comment), so building any of them would invent
 * behavior that doesn't exist. A `404` here (own tenant's missing id, or
 * another tenant's id — indistinguishable by design) renders via
 * `QueryStateBoundary`'s generic error branch, same as any other non-401/403
 * failure.
 */
export default function StaffDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const query = useStaff(id);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/staff"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to staff
        </Link>
        <h1 className="mt-1 text-xl font-semibold text-foreground">Staff details</h1>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading staff account…"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(staff) => (
          <Card className="max-w-lg">
            <CardHeader>
              <h2
                data-slot="card-title"
                className="font-heading text-base leading-snug font-medium"
              >
                {staff.name}
              </h2>
            </CardHeader>
            <CardContent>
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3 text-sm">
                <dt className="font-medium text-foreground">Name</dt>
                <dd className="text-muted-foreground">{staff.name}</dd>

                <dt className="font-medium text-foreground">Email</dt>
                <dd className="text-muted-foreground">{staff.email}</dd>

                <dt className="font-medium text-foreground">Role</dt>
                <dd>
                  <RoleBadge roleCode={staff.roleCode} />
                </dd>

                <dt className="font-medium text-foreground">Status</dt>
                <dd>
                  <StaffStatusBadge status={staff.status} />
                </dd>
              </dl>
            </CardContent>
          </Card>
        )}
      </QueryStateBoundary>
    </div>
  );
}
