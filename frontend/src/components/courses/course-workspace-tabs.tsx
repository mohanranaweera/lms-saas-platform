"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

export interface CourseWorkspaceTab {
  /** Path segment appended to the workspace's base path (`""` for the Overview/index route itself). */
  segment: string;
  label: string;
}

/**
 * The full Wave 2 course workspace tab set, shared by every role's workspace
 * (currently only the Tenant Admin course detail route,
 * `tenant-admin/courses/[courseId]`). `schedule`/`sessions`/`recordings`/
 * `analytics` are deliberate structural placeholders this wave — see
 * `CourseWorkspacePlaceholder` — never fake data/controls.
 */
export const COURSE_WORKSPACE_TABS: CourseWorkspaceTab[] = [
  { segment: "", label: "Overview" },
  { segment: "billing", label: "Fees & Billing" },
  { segment: "settings", label: "Settings" },
  { segment: "access", label: "Access" },
  { segment: "schedule", label: "Schedule" },
  { segment: "sessions", label: "Sessions" },
  { segment: "recordings", label: "Recordings" },
  { segment: "analytics", label: "Analytics" },
];

/**
 * Course workspace sub-navigation — each "tab" is a real route (not
 * client-managed panel state), so this renders as a `nav` landmark of links
 * with `aria-current="page"` on the active one, rather than a `role="tablist"`
 * ARIA-tabs pattern (which implies panels swap without navigation — not the
 * case here: every tab is independently linkable/bookmarkable, has its own
 * `QueryStateBoundary`, and survives a full page reload).
 *
 * Horizontally scrollable on narrow viewports rather than wrapping/collapsing
 * into a drawer — this project's admin-surface responsive convention
 * (`.claude/rules/ui-ux.md` §5) reserves the full mobile-nav-drawer pattern
 * for the app shell's primary navigation, not a page-local sub-nav.
 */
export function CourseWorkspaceTabs({
  basePath,
  tabs,
}: {
  basePath: string;
  tabs: CourseWorkspaceTab[];
}) {
  const pathname = usePathname();

  return (
    <nav aria-label="Course sections" className="-mx-1 overflow-x-auto">
      <ul className="flex min-w-max flex-row gap-1 border-b border-border px-1">
        {tabs.map((tab) => {
          const href = tab.segment ? `${basePath}/${tab.segment}` : basePath;
          const isActive = pathname === href;
          return (
            <li key={tab.segment || "overview"}>
              <Link
                href={href}
                aria-current={isActive ? "page" : undefined}
                className={cn(
                  "inline-flex items-center whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
                  isActive
                    ? "border-foreground text-foreground"
                    : "border-transparent text-muted-foreground hover:text-foreground"
                )}
              >
                {tab.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
