import { ShieldAlert } from "lucide-react";
import { cn } from "@/lib/utils";

interface PermissionDeniedStateProps {
  /**
   * Human-readable reason, if any. This must only ever be populated from a real
   * backend 401/403 response (e.g. `ApiClientError.message` from
   * `src/lib/api/error.ts`) — never fabricated from a client-stored role/permission
   * string or guessed from the current route. This component renders UX only; the
   * backend remains the sole authorization enforcement point regardless of what this
   * component shows or hides. There is no default `reason` implying real
   * enforcement — omit the prop entirely rather than inventing placeholder text.
   */
  reason?: string;
  className?: string;
}

export function PermissionDeniedState({ reason, className }: PermissionDeniedStateProps) {
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
      {reason ? <p className="text-sm text-muted-foreground">{reason}</p> : null}
    </div>
  );
}
