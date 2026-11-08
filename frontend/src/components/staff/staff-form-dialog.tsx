"use client";

import type { ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * STAFF-MGMT-02 form modal (the Klass "Add New User" dialog): header with
 * icon, title and close X, scrollable body, footer actions; full-screen below
 * `sm`. Built on `@base-ui/react`'s Dialog (focus trap, Escape, `aria-modal`,
 * focus returned to the trigger on close) - same shape as
 * `components/students/student-dialog.tsx`, kept per-domain by convention.
 */
export function StaffFormDialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  testId,
  icon,
  className,
  finalFocus,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: ReactNode;
  children: ReactNode;
  footer: ReactNode;
  testId?: string;
  icon?: ReactNode;
  className?: string;
  /** Where focus goes on close (defaults to Base UI's: the previously focused element). */
  finalFocus?: () => HTMLElement | null;
}) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => onOpenChange(next)}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/30 transition-opacity duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0" />
        <DialogPrimitive.Popup
          data-testid={testId}
          finalFocus={finalFocus}
          className={cn(
            "fixed inset-0 z-50 flex flex-col bg-popover text-sm text-popover-foreground shadow-lg outline-none transition duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0 sm:inset-auto sm:top-1/2 sm:left-1/2 sm:max-h-[calc(100dvh-2rem)] sm:w-[calc(100%-2rem)] sm:max-w-2xl sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-xl sm:border sm:border-border",
            className
          )}
        >
          <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
            <DialogPrimitive.Title className="flex items-center gap-2 text-base font-semibold text-foreground">
              {icon}
              {title}
            </DialogPrimitive.Title>
            <DialogPrimitive.Close
              render={<Button type="button" variant="ghost" size="icon-sm" aria-label="Close" />}
            >
              <XIcon aria-hidden="true" />
            </DialogPrimitive.Close>
          </div>
          {description ? (
            <DialogPrimitive.Description className="px-4 pt-3 text-sm text-muted-foreground">
              {description}
            </DialogPrimitive.Description>
          ) : null}
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">{children}</div>
          <div className="flex flex-col-reverse gap-2 border-t border-border px-4 py-3 sm:flex-row sm:justify-end">
            {footer}
          </div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
