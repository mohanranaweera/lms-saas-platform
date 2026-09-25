"use client";

import { Suspense } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { LoadingState } from "@/components/states/loading-state";
import { Tabs, type TabItem } from "@/components/ui/tabs";
import { PaymentDashboardTab } from "./dashboard-tab";
import { OutstandingPaymentsTab } from "./outstanding-tab";
import { CourseSummaryTab } from "./course-summary-tab";

const VALID_TABS = ["dashboard", "outstanding", "course-summary"] as const;
type TabValue = (typeof VALID_TABS)[number];

function isTabValue(value: string | null): value is TabValue {
  return VALID_TABS.includes(value as TabValue);
}

/**
 * Tenant Admin Payment Dashboard (PAY-3, extended Wave 6 §4/§5) — now three
 * tabs: the original ledger Dashboard (with new status/method filters),
 * Outstanding (new), and Course Payment Summary (new). This nav entry
 * (`tenant-admin-nav.tsx`) still points at this one route
 * (`/tenant-admin/payments/dashboard`) — no new route was added for the two
 * new views, since none of them has independent deep-linkable identity
 * beyond "which tab", mirroring `students/[studentId]/page.tsx`'s
 * URL-`?tab=`-as-source-of-truth convention.
 *
 * Each tab fetches its own query independently and lazily (only the active
 * tab's content is mounted — see `components/ui/tabs.tsx`), so switching
 * tabs never fires all three endpoints' requests eagerly.
 */
function TenantAdminPaymentDashboardPageContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const tabParam = searchParams.get("tab");
  const activeTab: TabValue = isTabValue(tabParam) ? tabParam : "dashboard";

  function handleTabChange(next: string) {
    const search = new URLSearchParams(searchParams);
    if (next === "dashboard") {
      search.delete("tab");
    } else {
      search.set("tab", next);
    }
    const qs = search.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }

  const items: TabItem[] = [
    { value: "dashboard", label: "Dashboard", content: <PaymentDashboardTab /> },
    { value: "outstanding", label: "Outstanding", content: <OutstandingPaymentsTab /> },
    { value: "course-summary", label: "Course summary", content: <CourseSummaryTab /> },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Payment dashboard</h1>
        <p className="text-sm text-muted-foreground">
          Every confirmed payment and refund recorded in your tenant&apos;s ledger, plus
          outstanding orders and per-course payment summaries.
        </p>
      </div>

      <Tabs
        items={items}
        value={activeTab}
        onValueChange={handleTabChange}
        aria-label="Payment dashboard sections"
      />
    </div>
  );
}

export default function TenantAdminPaymentDashboardPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading payment dashboard…" />}>
      <TenantAdminPaymentDashboardPageContent />
    </Suspense>
  );
}
