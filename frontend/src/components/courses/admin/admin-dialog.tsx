"use client";

import type { ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { PlusCircle, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Centered, focus-trapped form modal (the Klass "Add New ..." dialogs):
 * header with title and close X, scrollable body, sticky footer; full-screen
 * on narrow viewports. Built on the
 * same `@base-ui/react` Dialog primitive as `components/ui/sheet.tsx`, which
 * provides the focus trap, Escape handling and `aria-modal` semantics.
 */
export function AdminFormDialog({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  testId,
  className,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  children: ReactNode;
  footer: ReactNode;
  testId?: string;
  className?: string;
}) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => onOpenChange(next)}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/30 transition-opacity duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0" />
        <DialogPrimitive.Popup
          data-testid={testId}
          className={cn(
            // Full-screen sheet below `sm` (ui-ux.md §5); centered modal from `sm` up.
            "fixed inset-0 z-50 flex flex-col bg-popover text-sm text-popover-foreground shadow-lg outline-none transition duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0 sm:inset-auto sm:top-1/2 sm:left-1/2 sm:max-h-[calc(100dvh-2rem)] sm:w-[calc(100%-2rem)] sm:max-w-2xl sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-xl sm:border sm:border-border sm:data-ending-style:scale-95 sm:data-starting-style:scale-95",
            className
          )}
        >
          <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
            <DialogPrimitive.Title className="flex items-center gap-2 text-base font-semibold text-foreground">
              <PlusCircle className="size-5 text-primary" aria-hidden="true" />
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
