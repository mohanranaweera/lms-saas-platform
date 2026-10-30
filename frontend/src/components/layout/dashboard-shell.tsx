"use client";

import { cloneElement, isValidElement, useState, type ReactNode } from "react";
import { Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ThemeToggle } from "@/components/theme/theme-toggle";
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
  /**
   * Rendered at the end of the header, opposite the mobile nav trigger — the
   * caller composes whatever it needs here (a logout control, an account
   * menu, ...), the same "accept a slot" pattern already used for `nav`.
   * This shared shell deliberately has no knowledge of what goes in it —
   * feature-specific components belong in the caller (each role's
   * `layout.tsx`), not imported here.
   */
  headerActions?: ReactNode;
  headerNav?: ReactNode;
  /** Optional brand block (e.g. tenant logo + portal label) replacing the plain sidebar/drawer label. */
  brand?: ReactNode;
  /** Optional compact brand for the mobile header, replacing the plain label there. */
  mobileBrand?: ReactNode;
  /** Optional footer rendered below the main content. */
  footer?: ReactNode;
}

/**
 * Shared shell for every role portal (student/teacher/tenant-admin/platform-admin).
 *
 * Responsive nav pattern: a persistent sidebar at `md` and above, collapsing to a
 * hamburger-triggered drawer (Sheet) below `md`, per .claude/rules/ui-ux.md §5.
 */
export function DashboardShell({
  portalLabel,
  nav,
  children,
  headerActions,
  headerNav,
  brand,
  mobileBrand,
  footer,
}: DashboardShellProps) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  // Only the mobile drawer instance needs to close itself on navigation — the desktop
  // sidebar has no open/close state to manage, so it renders `nav` as-is.
  const mobileNav = isValidElement<{ onNavigate?: () => void }>(nav)
    ? cloneElement(nav, { onNavigate: () => setMobileNavOpen(false) })
    : nav;

  return (
    <div className="flex min-h-svh flex-col md:flex-row">
      <aside className="hidden w-64 shrink-0 flex-col gap-6 border-r border-sidebar-border bg-sidebar p-4 text-sidebar-foreground md:flex">
        {brand ? <div className="px-2">{brand}</div> : <span className="px-2 text-sm font-semibold">{portalLabel}</span>}
        <nav aria-label="Primary">{nav}</nav>
      </aside>

      <div className="flex flex-1 flex-col">
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-4 py-3 md:px-6">
          <div className="flex items-center gap-3">
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
                  <SheetTitle>{brand ?? portalLabel}</SheetTitle>
                </SheetHeader>
                <nav aria-label="Primary" className="px-4 pb-4">
                  {mobileNav}
                </nav>
              </SheetContent>
            </Sheet>
            {mobileBrand ? (
              <span className="flex min-w-0 md:hidden">{mobileBrand}</span>
            ) : (
              <span className="text-sm font-semibold text-foreground md:hidden">
                {portalLabel}
              </span>
            )}
          </div>
          <div className="flex min-w-0 flex-auto flex-wrap items-center justify-between gap-3">
            {/* Below `sm` the header nav drops to its own row so the actions never push the page sideways. */}
            {headerNav ? <div className="order-last flex w-full items-center gap-1 sm:order-none sm:w-auto">{headerNav}</div> : <span />}
            <div className="flex min-w-0 flex-wrap items-center justify-end gap-2">
              <ThemeToggle className="shrink-0" />
              {headerActions}
            </div>
          </div>
        </header>

        <main className="flex-1 px-4 py-6 md:px-6">{children}</main>
        {footer}
      </div>
    </div>
  );
}
