"use client";

import { Suspense, type ReactNode } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { DollarSign, FileClock } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { LoadingState } from "@/components/states/loading-state";
import { AllOrdersTab } from "@/components/payments/hub/all-orders-tab";
import { FreeCardsTab } from "@/components/payments/hub/free-cards-tab";
import { PaymentSlipsTab } from "@/components/payments/hub/payment-slips-tab";
import { RefundsTab } from "@/components/payments/hub/refunds-tab";
import { useAuth } from "@/lib/auth/auth-context";
import {
  canProcessRefunds,
  canViewFreeCards,
  canViewPaymentDashboard,
  canViewStudentPayments,
} from "@/lib/auth/permissions";
import { useSlipPendingCount } from "@/lib/api/payment-slips";
import { isPaymentsHubTab, type PaymentsHubTab } from "@/lib/payments/hub-routes";

/**
 * PAY-HUB-01 Payments hub (Klass "Payments"): one page for All Orders,
 * Payment Slips, Free Cards and Refunds. The active tab lives in `?tab=`
 * (deep-linkable); only the active tab is mounted, so each tab fetches its
 * own data lazily. Which tabs show is decided by role helpers - UX only; each
 * tab's endpoints re-check permission server-side.
 */
export default function PaymentsHubPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading payments…" />}>
      <PaymentsHubContent />
    </Suspense>
  );
}

const FALLBACK_TAB_CONTENT: Record<PaymentsHubTab, ReactNode> = {
  orders: <AllOrdersTab />,
  slips: <PaymentSlipsTab />,
  "free-cards": <FreeCardsTab />,
  refunds: <RefundsTab />,
};

function PaymentsHubContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { session } = useAuth();
  const role = session?.role ?? null;

  const canSeeOrdersAndSlips = canViewPaymentDashboard(role);
  const pendingCount = useSlipPendingCount({ enabled: canSeeOrdersAndSlips });
  const pending = pendingCount.data?.pending ?? null;

  const items: Array<TabItem & { value: PaymentsHubTab }> = [];
  if (canSeeOrdersAndSlips) {
    items.push({ value: "orders", label: "All Orders", content: <AllOrdersTab /> });
    items.push({
      value: "slips",
      label: "Payment Slips",
      content: <PaymentSlipsTab />,
      badge: pending ? (
        <Badge variant="secondary" className="ml-1.5" aria-label={`${pending} pending`}>
          {pending}
        </Badge>
      ) : undefined,
    });
  }
  if (canViewStudentPayments(role) || canViewFreeCards(role)) {
    items.push({ value: "free-cards", label: "Free Cards", content: <FreeCardsTab /> });
  }
  // Viewers (PAYMENTS_SLIPS/VIEW) see refundable payments read-only; the Refund
  // action itself is gated inside the tab (and enforced by the backend).
  if (canSeeOrdersAndSlips || canProcessRefunds(role)) {
    items.push({ value: "refunds", label: "Refunds", content: <RefundsTab /> });
  }

  const tabParam = searchParams.get("tab") ?? "orders";
  const activeTab =
    isPaymentsHubTab(tabParam) && items.some((item) => item.value === tabParam) ? tabParam : items[0]?.value;

  function handleTabChange(next: string) {
    // Tab-specific params (free-card view/student, etc.) never carry over.
    router.replace(next === "orders" ? pathname : `${pathname}?tab=${next}`, { scroll: false });
  }

  if (items.length === 0 || !activeTab) {
    // No tab is visible to this role (UX only). Render the requested tab's
    // content anyway, without tabs, so the real request is made and the
    // server's 403 drives the permission-denied state - never a client-side
    // guess (.claude/rules/frontend.md).
    return (
      <div className="flex flex-col gap-6">
        <h1 className="text-xl font-semibold text-foreground">Payments</h1>
        {FALLBACK_TAB_CONTENT[isPaymentsHubTab(tabParam) ? tabParam : "orders"]}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center gap-3">
        <span className="inline-flex size-10 items-center justify-center rounded-lg bg-blue-600 text-white" aria-hidden="true">
          <DollarSign className="size-5" />
        </span>
        <h1 className="text-xl font-semibold text-foreground">Payments</h1>
      </div>

      {canSeeOrdersAndSlips ? (
        <section
          aria-label="Payment slips awaiting review"
          className="flex flex-col gap-1 rounded-xl border border-border bg-blue-50/60 p-5 dark:bg-blue-950/30"
          data-testid="payments-pending-card"
        >
          <p className="text-xs font-medium text-muted-foreground">Payments</p>
          <p className="text-3xl font-semibold text-foreground">
            {pending === null ? "—" : pending} pending
          </p>
          <p className="text-sm text-muted-foreground">Payment slips awaiting review</p>
          {pending ? (
            <button
              type="button"
              onClick={() => handleTabChange("slips")}
              className="mt-2 inline-flex w-fit items-center gap-1.5 rounded-full bg-amber-100 px-3 py-1 text-xs font-medium text-amber-800 hover:bg-amber-200 dark:bg-amber-950 dark:text-amber-200"
            >
              <FileClock className="size-3.5" aria-hidden="true" />
              Review {pending} pending
            </button>
          ) : null}
        </section>
      ) : null}

      <Tabs items={items} value={activeTab} onValueChange={handleTabChange} aria-label="Payments sections" />
    </div>
  );
}
