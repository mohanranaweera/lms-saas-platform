import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

interface LoadingStateProps {
  /** Text announced to assistive tech and shown next to the spinner. */
  label?: string;
  className?: string;
}

/**
 * Accessible loading indicator. `aria-busy` + `aria-live="polite"` ensure screen
 * readers announce that an operation is in progress without interrupting the user.
 */
export function LoadingState({ label = "Loading…", className }: LoadingStateProps) {
  return (
    <div
      role="status"
      aria-busy="true"
      aria-live="polite"
      className={cn(
        "flex flex-col items-center justify-center gap-3 py-12 text-muted-foreground",
        className
      )}
    >
      <Loader2 className="size-6 animate-spin" aria-hidden="true" />
      <span className="text-sm">{label}</span>
    </div>
  );
}
