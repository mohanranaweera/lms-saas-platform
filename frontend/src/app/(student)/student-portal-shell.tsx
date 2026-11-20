"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { BrandedDashboardShell } from "@/components/branding/portal-branding";
import { StudentNav } from "@/components/layout/nav/student-nav";
import { StudentHeaderActions } from "@/components/layout/student-header-actions";
import { GlobalPayButton } from "@/components/payments/composer/payment-composer-provider";
import { useNamespaces, useT } from "@/i18n/i18n-provider";

/** Student Portal chrome (I18N-01): loads the `student` namespace and localizes the portal label and header links. */
export function StudentPortalShell({ children }: { children: ReactNode }) {
  useNamespaces(["student", "courses", "exams", "attendance", "payments", "notifications", "calendar", "devices", "player"]);
  const t = useT();
  return (
    <BrandedDashboardShell
      portalLabel={t("student.portal")}
      nav={<StudentNav />}
      headerNav={
        <div className="flex min-w-0 flex-wrap items-center gap-1">
          <Link href="/student/courses" className="rounded-lg bg-primary/10 px-3 py-1.5 text-sm font-semibold text-primary hover:bg-primary/20">
            {t("student.header.myCourses")}
          </Link>
          <Link href="/student/courses?view=available" className="rounded-lg px-3 py-1.5 text-sm font-medium text-foreground hover:bg-muted">
            {t("student.header.explore")}
          </Link>
        </div>
      }
      headerActions={<StudentHeaderActions><GlobalPayButton /></StudentHeaderActions>}
    >
      {children}
    </BrandedDashboardShell>
  );
}
