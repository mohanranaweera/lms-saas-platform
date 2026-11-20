import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

/** Scrolling body + footer for a `bare` `CalendarDialog` whose content is a form. */
export function DialogBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("min-h-0 flex-1 overflow-y-auto px-4 py-4", className)}>{children}</div>;
}

export function DialogFooter({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col-reverse gap-2 border-t border-border px-4 py-3 sm:flex-row sm:justify-end">{children}</div>
  );
}
