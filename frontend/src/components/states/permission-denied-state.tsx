import Link from "next/link";
import { ShieldAlert } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ApiClientError } from "@/lib/api/error";

interface PermissionDeniedStateProps {
  /**
   * The real backend 401/403 response that triggered this state — must be an
   * actual `ApiClientError` (see src/lib/api/error.ts), never a fabricated
   * string or a reason guessed from a client-stored role/permission value or
   * the current route. Narrowing this prop from a bare `string` to the real
   * error object makes "server-verified only" compiler-enforced rather than
   * just documented: there is no way to reach this component without
   * something that actually came back from a real API call. This component
   * renders UX only — the backend remains the sole authorization enforcement
   * point regardless of what this component shows or hides.
   */
  error: ApiClientError;
  /**
   * Href for a "back to your dashboard" link. Supplied by the caller — this
   * component has no role/portal context of its own to derive one from.
   */
  dashboardHref?: string;
  className?: string;
}

export function PermissionDeniedState({
  error,
  dashboardHref,
  className,
}: PermissionDeniedStateProps) {
  return (
    <div
      role="alert"
      className={cn(
        "flex flex-col items-center gap-3 rounded-lg border border-border bg-muted/40 px-6 py-12 text-center",
        className
      )}
    >
      <ShieldAlert className="size-6 text-muted-foreground" aria-hidden="true" />
      <p className="text-sm font-medium text-foreground">
        You don&apos;t have permission to view this.
      </p>
      <p className="text-sm text-muted-foreground">{error.message}</p>
      {dashboardHref ? (
        <Link
          href={dashboardHref}
          className="text-sm font-medium text-foreground hover:underline"
        >
          Back to your dashboard
        </Link>
      ) : null}
    </div>
  );
}
