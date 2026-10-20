"use client";

import type { ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Small centered modal for the student course screens (lesson launch
 * outcomes, external-player handoff). Built on the `@base-ui/react` Dialog
 * primitive: focus trap, Escape, `aria-modal`, labelled by its title.
 */
export function StudentDialog({
  open,
  onOpenChange,
  title,
  icon,
  children,
  footer,
  testId,
  className,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  icon?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
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
            "fixed top-1/2 left-1/2 z-50 flex max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 flex-col rounded-xl border border-border bg-popover text-sm text-popover-foreground shadow-lg outline-none transition duration-150 data-ending-style:scale-95 data-ending-style:opacity-0 data-starting-style:scale-95 data-starting-style:opacity-0",
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
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">{children}</div>
          {footer ? (
            <div className="flex flex-col-reverse gap-2 border-t border-border px-4 py-3 sm:flex-row sm:justify-end">
              {footer}
            </div>
          ) : null}
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
