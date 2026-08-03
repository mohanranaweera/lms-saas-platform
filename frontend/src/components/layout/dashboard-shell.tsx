"use client";

import { cloneElement, isValidElement, useState, type ReactNode } from "react";
import { Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";

interface DashboardShellProps {
  /**
   * Persistent, visible label naming the portal/role scope of everything rendered
   * inside (see .claude/rules/ui-ux.md §1 — scope must never be implied only by the
   * URL). Role dashboards will later pair this with the authenticated tenant name
   * once tenant context exists.
   */
  portalLabel: string;
  nav: ReactNode;
  children: ReactNode;
}

/**
 * Shared shell for every role portal (student/teacher/tenant-admin/platform-admin).
 *
 * Responsive nav pattern: a persistent sidebar at `md` and above, collapsing to a
 * hamburger-triggered drawer (Sheet) below `md`, per .claude/rules/ui-ux.md §5.
 */
export function DashboardShell({ portalLabel, nav, children }: DashboardShellProps) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  // Only the mobile drawer instance needs to close itself on navigation — the desktop
  // sidebar has no open/close state to manage, so it renders `nav` as-is.
  const mobileNav = isValidElement<{ onNavigate?: () => void }>(nav)
    ? cloneElement(nav, { onNavigate: () => setMobileNavOpen(false) })
    : nav;

  return (
    <div className="flex min-h-svh flex-col md:flex-row">
      <aside className="hidden w-64 shrink-0 flex-col gap-6 border-r border-sidebar-border bg-sidebar p-4 text-sidebar-foreground md:flex">
        <span className="px-2 text-sm font-semibold">{portalLabel}</span>
        <nav aria-label="Primary">{nav}</nav>
      </aside>

      <div className="flex flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-border px-4 py-3 md:px-6">
          <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
            <SheetTrigger
              render={
                <Button
                  variant="ghost"
                  size="icon"
                  className="md:hidden"
                  aria-label="Open navigation menu"
                />
              }
            >
              <Menu aria-hidden="true" />
            </SheetTrigger>
            <SheetContent side="left">
              <SheetHeader>
                <SheetTitle>{portalLabel}</SheetTitle>
              </SheetHeader>
              <nav aria-label="Primary" className="px-4 pb-4">
                {mobileNav}
              </nav>
            </SheetContent>
          </Sheet>
          <span className="text-sm font-semibold text-foreground md:hidden">
            {portalLabel}
          </span>
        </header>

        <main className="flex-1 px-4 py-6 md:px-6">{children}</main>
      </div>
    </div>
  );
}
