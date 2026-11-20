import { RouteGuard } from "@/components/auth/route-guard";
import { TenantAnalytics } from "@/components/analytics/tenant-analytics";
import { ImpersonationBanner } from "@/components/auth/impersonation-banner";
import { PaymentComposerProvider } from "@/components/payments/composer/payment-composer-provider";
import { StudentPortalShell } from "./student-portal-shell";

export default function StudentLayout({ children }: { children: React.ReactNode }) {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      {/* ADR-033: persistent banner while this tab is a read-only support session (renders nothing otherwise). */}
      <ImpersonationBanner />
      {/* SET-CFG-01: the tenant's GA4 tag, when configured (skipped in support tabs). */}
      <TenantAnalytics />
      {/* KLASS-T5b: one PAY composer for the whole student portal (header PAY + every "Pay now"). */}
      <PaymentComposerProvider>
        <StudentPortalShell>{children}</StudentPortalShell>
      </PaymentComposerProvider>
    </RouteGuard>
  );
}
