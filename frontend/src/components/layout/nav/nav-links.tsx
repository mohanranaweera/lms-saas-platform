"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

export interface NavItem {
  label: string;
  /** Omit when the destination page doesn't exist yet — renders as a "Soon" item, not a dead link. */
  href?: string;
  /** Optional trailing indicator (e.g. `<NotificationsNavBadge />`) rendered next to the label. Only meaningful when `href` is set. */
  badge?: ReactNode;
}

/**
 * Shared rendering for the small per-role placeholder nav lists
 * (`student-nav.tsx`, `teacher-nav.tsx`, `tenant-admin-nav.tsx`,
 * `platform-admin-nav.tsx`). Keeps active-state and accessibility behavior
 * consistent instead of re-implemented per role.
 */
export function NavLinks({
  items,
  onNavigate,
}: {
  items: NavItem[];
  /** Invoked when a real (non-placeholder) link is activated — e.g. to close a mobile drawer. */
  onNavigate?: () => void;
}) {
  const pathname = usePathname();

  return (
    <ul className="flex flex-col gap-1">
      {items.map((item) => {
        if (!item.href) {
          return (
            <li key={item.label}>
              <span
                aria-disabled="true"
                className="flex items-center justify-between rounded-md px-2.5 py-1.5 text-sm text-muted-foreground/60"
              >
                {item.label}
                <span className="text-xs">Soon</span>
              </span>
            </li>
          );
        }

        const isActive = pathname === item.href;

        return (
          <li key={item.label}>
            <Link
              href={item.href}
              aria-current={isActive ? "page" : undefined}
              onClick={onNavigate}
              className={cn(
                "flex items-center rounded-md px-2.5 py-1.5 text-sm text-foreground transition-colors hover:bg-muted",
                isActive && "bg-muted font-medium"
              )}
            >
              {item.label}
              {item.badge}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

/**
 * Minimal grouped-section wrapper around `NavLinks` (Wave 1 — Tenant Admin
 * IA restructure, `docs/parity/KLASS-PARITY-MASTER-INSTRUCTION.md` §6):
 * a visually-hidden-to-nobody group heading followed by that group's
 * `NavLinks`. Renders nothing at all — not even the heading — when `items`
 * is empty, so a group with zero currently-visible entries for a given role
 * (e.g. a staff sub-role with no Institute Configuration access) doesn't
 * leave a dangling empty heading in the sidebar. Deliberately not a heavier
 * nav framework (accordion/collapsible sections, icons, nesting) — none of
 * the current per-role nav shells need that yet.
 */
export function NavGroup({
  label,
  items,
  onNavigate,
}: {
  label: string;
  items: NavItem[];
  onNavigate?: () => void;
}) {
  if (items.length === 0) {
    return null;
  }

  return (
    <div className="flex flex-col gap-1">
      <span className="px-2.5 py-1 text-xs font-semibold tracking-wide text-muted-foreground/70 uppercase">
        {label}
      </span>
      <NavLinks items={items} onNavigate={onNavigate} />
    </div>
  );
}
