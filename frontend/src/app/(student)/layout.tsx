import Link from "next/link";
import { DashboardShell } from "@/components/layout/dashboard-shell";
import { StudentNav } from "@/components/layout/nav/student-nav";
import { StudentHeaderActions } from "@/components/layout/student-header-actions";
import { RouteGuard } from "@/components/auth/route-guard";
import {
  GlobalPayButton,
  PaymentComposerProvider,
} from "@/components/payments/composer/payment-composer-provider";

export default function StudentLayout({ children }: { children: React.ReactNode }) {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      {/* KLASS-T5b: one PAY composer for the whole student portal (header PAY + every "Pay now"). */}
      <PaymentComposerProvider>
        <DashboardShell
          portalLabel="Student Portal"
          nav={<StudentNav />}
          headerNav={<div className="flex items-center gap-1"><Link href="/student/courses" className="rounded-lg bg-primary/10 px-3 py-1.5 text-sm font-semibold text-primary hover:bg-primary/20">My Courses</Link><Link href="/student/courses?view=available" className="rounded-lg px-3 py-1.5 text-sm font-medium text-foreground hover:bg-muted">Explore</Link></div>}
          headerActions={<StudentHeaderActions><GlobalPayButton /></StudentHeaderActions>}
        >
          {children}
        </DashboardShell>
      </PaymentComposerProvider>
    </RouteGuard>
  );
}
