"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/auth-context";
import type { Teacher } from "@/lib/api/teachers";
import { TeacherStatusBadge } from "../status-badge";
import { TeacherDecisionDialog } from "../teacher-decision-dialog";
import { TeacherSuspendDialog } from "../teacher-suspend-dialog";
import { TeacherProfileForm } from "./teacher-profile-form";
import { SignInMobileCard } from "@/components/auth/sign-in-mobile-card";

/**
 * Profile tab (Wave 3 tabbed rebuild) — editable teacher accounts open
 * directly in the profile form. View-only roles retain the read-only profile
 * summary because the backend remains authoritative for mutation access.
 */
export function TeacherProfileTab({ teacher }: { teacher: Teacher }) {
  const { session } = useAuth();
  const router = useRouter();
  const canDecide = session?.role === "TENANT_ADMIN";
  const canEdit = session?.role === "TENANT_ADMIN" || session?.role === "COURSE_COORDINATOR";

  // Distinguishable success feedback for profile and lifecycle actions — this
  // app has no toast library, so keep the existing accessible live-region pattern.
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  return (
    <div className="flex flex-col gap-6">
      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>

      {/* ADR-031: TEACHERS VIEW/CREATE_EDIT server-side; canEdit is UX only. */}
      <SignInMobileCard
        path={`/v1/teachers/${teacher.id}/login-mobile`}
        description="Lets this teacher sign in with a mobile number instead of their email."
        canEdit={canEdit}
        idPrefix="teacher-login-mobile"
      />

      {canEdit ? (
        <TeacherProfileForm
          teacher={teacher}
          onCancel={() => router.push("/tenant-admin/teachers")}
          onSaved={() => setNotice("Teacher profile saved.")}
        />
      ) : (
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <div>
            <dt className="text-xs font-medium text-muted-foreground">First name</dt>
            <dd className="text-sm text-foreground">{teacher.firstName ?? teacher.name}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-muted-foreground">Last name</dt>
            <dd className="text-sm text-foreground">{teacher.lastName ?? "—"}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-muted-foreground">Contact number</dt>
            <dd className="text-sm text-foreground">{teacher.contactNumber ?? "—"}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-muted-foreground">Institute commission</dt>
            <dd className="text-sm text-foreground">{teacher.instituteCommissionPercent ?? 0}%</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-muted-foreground">Qualifications</dt>
            <dd className="whitespace-pre-wrap text-sm text-foreground">{teacher.qualifications ?? "—"}</dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-xs font-medium text-muted-foreground">Description</dt>
            <dd className="whitespace-pre-wrap text-sm text-foreground">{teacher.description ?? "—"}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-muted-foreground">Email</dt>
            <dd className="text-sm text-foreground">{teacher.email}</dd>
          </div>
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
                <dt className="text-xs font-medium text-muted-foreground">Decided by</dt>
                <dd className="text-sm text-foreground">{teacher.approvedBy ?? "—"}</dd>
              </div>
              <div>
                <dt className="text-xs font-medium text-muted-foreground">Decided at</dt>
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
      )}

      {canDecide && teacher.approvalStatus === "PENDING" ? (
        <div className="flex flex-wrap items-center gap-3">
          <TeacherDecisionDialog teacher={teacher} action="approve" triggerVariant="full" />
          <TeacherDecisionDialog teacher={teacher} action="reject" triggerVariant="full" />
        </div>
      ) : null}
      {canDecide && teacher.approvalStatus === "APPROVED" ? (
        <div className="flex flex-wrap items-center gap-3">
          <TeacherSuspendDialog
            teacher={teacher}
            action="suspend"
            triggerVariant="full"
            onSuccess={() => setNotice(`${teacher.name} was suspended.`)}
          />
        </div>
      ) : null}
      {canDecide && teacher.approvalStatus === "SUSPENDED" ? (
        <div className="flex flex-wrap items-center gap-3">
          <TeacherSuspendDialog
            teacher={teacher}
            action="reactivate"
            triggerVariant="full"
            onSuccess={() => setNotice(`${teacher.name} was reactivated.`)}
          />
        </div>
      ) : null}
    </div>
  );
}
