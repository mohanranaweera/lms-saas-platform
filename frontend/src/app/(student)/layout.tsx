import { DashboardShell } from "@/components/layout/dashboard-shell";
import { StudentNav } from "@/components/layout/nav/student-nav";
import { LogoutControl } from "@/components/auth/logout-control";
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
          headerActions={
            <div className="flex items-center gap-2">
              <GlobalPayButton />
              <LogoutControl kind="tenant" portalLabel="Student Portal" />
            </div>
          }
        >
          {children}
        </DashboardShell>
      </PaymentComposerProvider>
    </RouteGuard>
  );
}
