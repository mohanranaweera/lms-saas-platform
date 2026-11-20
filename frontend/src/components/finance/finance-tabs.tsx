"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { ArrowLeftRight, BarChart3, CalendarClock, Landmark, Tag, Wallet } from "lucide-react";
import { useFinanceGates } from "@/components/finance/book/shared";
import { cn } from "@/lib/utils";

/**
 * FIN-01 route-based Finance tab bar (Klass parity): six deep-linkable links
 * styled as tabs, `aria-current="page"` on the active one, horizontally
 * scrollable on narrow screens. A tab the viewer lacks the read capability
 * for is hidden (UX only - each tab's endpoints re-check server-side).
 */
const TABS = [
  { href: "/tenant-admin/finance", label: "Student Income", icon: Wallet, gate: "incomeView" },
  { href: "/tenant-admin/finance/budget", label: "Budget Dashboard", icon: BarChart3, gate: "budgetView" },
  { href: "/tenant-admin/finance/accounts", label: "Accounts", icon: Landmark, gate: "bookView" },
  { href: "/tenant-admin/finance/transactions", label: "Transactions", icon: ArrowLeftRight, gate: "bookView" },
  { href: "/tenant-admin/finance/scheduled", label: "Scheduled", icon: CalendarClock, gate: "schedulesView" },
  { href: "/tenant-admin/finance/categories", label: "Categories", icon: Tag, gate: "categoriesView" },
] as const;

export function FinanceTabs() {
  const pathname = usePathname();
  const gates = useFinanceGates();
  const visible = TABS.filter((tab) => gates[tab.gate] || pathname === tab.href);
  return (
    <nav aria-label="Finance sections" className="-mx-1 overflow-x-auto border-b border-border">
      <ul className="flex min-w-max gap-1 px-1">
        {visible.map((tab) => {
          const active = pathname === tab.href;
          const Icon = tab.icon;
          return (
            <li key={tab.href}>
              <Link
                href={tab.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium whitespace-nowrap outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
                  active
                    ? "border-primary text-foreground"
                    : "border-transparent text-muted-foreground hover:text-foreground"
                )}
              >
                <Icon className="size-4" aria-hidden="true" />
                {tab.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/** Shared page frame for the six Finance tabs: H1 "Finance", the tab bar, then the tab's own heading/actions. */
export function FinanceHubFrame({
  title,
  description,
  actions,
  children,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <h1 className="text-xl font-semibold text-foreground">Finance</h1>
      <FinanceTabs />
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h2 className="text-lg font-semibold text-foreground">{title}</h2>
          {description ? <div className="text-sm text-muted-foreground">{description}</div> : null}
        </div>
        {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
      {children}
    </div>
  );
}
