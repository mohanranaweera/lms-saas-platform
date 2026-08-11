import Link from "next/link";
import type { ReactNode } from "react";

interface CenteredAuthShellProps {
  /**
   * Home/brand link text above the card. Lets a caller (e.g. Platform Admin
   * login) add a distinguishing suffix without duplicating this shell.
   */
  homeLabel?: string;
  children: ReactNode;
}

/**
 * Shared centered-card chrome for chrome-less auth surfaces (tenant login,
 * register, forgot-password, Platform Admin login) — mobile-first, single
 * column, per .claude/rules/ui-ux.md §5. Extracted so the tenant-scoped
 * `(auth)` route group and Platform Admin's `platform-admin/login` route
 * group share one implementation instead of two structurally-identical
 * layouts drifting independently.
 */
export function CenteredAuthShell({
  homeLabel = "LMS Platform",
  children,
}: CenteredAuthShellProps) {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-8 bg-muted/30 px-4 py-12">
      <Link href="/" className="text-lg font-semibold text-foreground">
        {homeLabel}
      </Link>
      <div className="w-full max-w-sm">{children}</div>
    </div>
  );
}
