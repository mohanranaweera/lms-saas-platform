"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

export interface NavItem {
  label: string;
  /** Omit when the destination page doesn't exist yet — renders as a "Soon" item, not a dead link. */
  href?: string;
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
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
