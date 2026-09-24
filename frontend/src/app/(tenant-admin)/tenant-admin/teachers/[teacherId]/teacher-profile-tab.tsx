"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth/auth-context";
import type { Teacher } from "@/lib/api/teachers";
import { TeacherStatusBadge } from "../status-badge";
import { TeacherDecisionDialog } from "../teacher-decision-dialog";
import { TeacherSuspendDialog } from "../teacher-suspend-dialog";

/**
 * Profile tab (Wave 3 tabbed rebuild) — the pre-Wave-3 detail page's own
 * card content, unchanged, plus the new Suspend/Reactivate actions
 * (PAR-04-04). Already-decided teachers show status read-only with no
 * reopen affordance, matching the backend's one-directional approval state
 * machine — `SUSPENDED`/`APPROVED` toggle is the one exception (a real,
 * bidirectional pair, per `ApprovalStatus`'s own backend doc comment).
 */
export function TeacherProfileTab({ teacher }: { teacher: Teacher }) {
  const { session } = useAuth();
  const canDecide = session?.role === "TENANT_ADMIN";

  // Distinguishable success feedback for Suspend/Reactivate — this app has
  // no toast library (`components/ui/live-region.tsx`'s doc comment), so
  // this mirrors `students/page.tsx#createdNotice`'s established brief,
  // auto-clearing, `role="status" aria-live="polite"` page-level notice
  // pattern rather than inventing a new one.
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
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
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
