"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { CheckCircle2, SearchX } from "lucide-react";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { isApiClientError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth/auth-context";
import { useTeacher } from "@/lib/api/teachers";
import { TeacherStatusBadge } from "../status-badge";
import { TeacherDecisionDialog } from "../teacher-decision-dialog";

/**
 * Teacher Detail (Tenant Admin) — MVP-007 TCH-1.
 *
 * `GET /v1/teachers/{id}` returns a uniform 404 for both "doesn't exist" and
 * "belongs to another tenant" (deliberate — see contract in
 * `lib/api/teachers.ts`), so this page never tries to distinguish them; it
 * special-cases 404 with a dedicated "Teacher not found" message (a generic
 * "Try again" retry button is pointless for a permanently-missing/cross-tenant
 * resource) before falling through to `QueryStateBoundary`'s default
 * loading/error/permission-denied handling for every other case.
 */
export default function TenantAdminTeacherDetailPage() {
  const params = useParams<{ teacherId: string }>();
  const searchParams = useSearchParams();
  const router = useRouter();
  const { session } = useAuth();
  const teacherId = params.teacherId;
  const query = useTeacher(teacherId);

  const [showCreatedNotice, setShowCreatedNotice] = useState(
    searchParams.get("created") === "true"
  );

  const canDecide = session?.role === "TENANT_ADMIN";

  if (query.status === "error" && isApiClientError(query.error) && query.error.status === 404) {
    return (
      <div className="flex flex-col gap-6">
        <EmptyState
          icon={<SearchX className="size-8" aria-hidden="true" />}
          title="Teacher not found"
          description="This teacher account doesn't exist, or you don't have access to it."
          action={{
            label: "Back to teachers",
            onClick: () => router.push("/tenant-admin/teachers"),
          }}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link
          href="/tenant-admin/teachers"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to teachers
        </Link>
        <h1 className="text-xl font-semibold text-foreground">Teacher</h1>
      </div>

      {showCreatedNotice ? (
        <Alert>
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription className="flex items-center justify-between gap-3">
            <span>Teacher account created — pending your approval.</span>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => setShowCreatedNotice(false)}
            >
              Dismiss
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading teacher…"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(teacher) => (
          <Card>
            <CardHeader className="flex flex-row items-start justify-between gap-3">
              <div>
                <h2 className="font-heading text-base font-medium text-foreground">
                  {teacher.name}
                </h2>
                <p className="text-sm text-muted-foreground">{teacher.email}</p>
              </div>
              <TeacherStatusBadge status={teacher.approvalStatus} />
            </CardHeader>
            <CardContent className="flex flex-col gap-6">
              <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Account status</dt>
                  <dd className="text-sm text-foreground">{teacher.accountStatus}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Approval status</dt>
                  <dd className="text-sm text-foreground">
                    <TeacherStatusBadge status={teacher.approvalStatus} />
                  </dd>
                </div>
                {teacher.approvalStatus !== "PENDING" ? (
                  <>
                    <div>
                      <dt className="text-xs font-medium text-muted-foreground">
                        Decided by
                      </dt>
                      <dd className="text-sm text-foreground">{teacher.approvedBy ?? "—"}</dd>
                    </div>
                    <div>
                      <dt className="text-xs font-medium text-muted-foreground">
                        Decided at
                      </dt>
                      <dd className="text-sm text-foreground">
                        {teacher.approvedAt
                          ? new Intl.DateTimeFormat("en-US", {
                              dateStyle: "medium",
                              timeStyle: "short",
                            }).format(new Date(teacher.approvedAt))
                          : "—"}
                      </dd>
                    </div>
                  </>
                ) : null}
              </dl>

              {/* Already-decided teachers show status read-only, with no
                  reopen affordance — matches the backend's one-directional
                  approval state machine (no APPROVED/REJECTED -> PENDING
                  transition exists). */}
              {canDecide && teacher.approvalStatus === "PENDING" ? (
                <div className="flex items-center gap-3">
                  <TeacherDecisionDialog
                    teacher={teacher}
                    action="approve"
                    triggerVariant="full"
                  />
                  <TeacherDecisionDialog
                    teacher={teacher}
                    action="reject"
                    triggerVariant="full"
                  />
                </div>
              ) : null}
            </CardContent>
          </Card>
        )}
      </QueryStateBoundary>
    </div>
  );
}
