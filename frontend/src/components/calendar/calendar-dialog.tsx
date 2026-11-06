"use client";

import type { ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * Focus-trapped modal used by the calendar module (schedule editor, class
 * details, "+N more" list). Same `@base-ui/react` Dialog primitive and layout
 * as `AdminFormDialog` (full-screen below `sm`, centered from `sm` up), but
 * with a caller-chosen title icon and an optional footer.
 */
export function CalendarDialog({
  open,
  onOpenChange,
  title,
  icon,
  description,
  children,
  footer,
  testId,
  className,
  bare = false,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  icon?: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  testId?: string;
  className?: string;
  /** Render `children` directly (the caller supplies its own scrolling body and footer, e.g. a `<form>`). */
  bare?: boolean;
}) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => onOpenChange(next)}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/30 transition-opacity duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0" />
        <DialogPrimitive.Popup
          data-testid={testId}
          className={cn(
            "fixed inset-0 z-50 flex flex-col bg-popover text-sm text-popover-foreground shadow-lg outline-none transition duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0 sm:inset-auto sm:top-1/2 sm:left-1/2 sm:max-h-[calc(100dvh-2rem)] sm:w-[calc(100%-2rem)] sm:max-w-xl sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-xl sm:border sm:border-border sm:data-ending-style:scale-95 sm:data-starting-style:scale-95",
            className
          )}
        >
          <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
            <DialogPrimitive.Title className="flex min-w-0 items-center gap-2 text-base font-semibold text-foreground">
              {icon}
              <span className="min-w-0 break-words">{title}</span>
            </DialogPrimitive.Title>
            <DialogPrimitive.Close render={<Button type="button" variant="ghost" size="icon-sm" aria-label="Close" />}>
              <XIcon aria-hidden="true" />
            </DialogPrimitive.Close>
          </div>
          {description ? (
            <DialogPrimitive.Description className="px-4 pt-3 text-sm text-foreground" render={<div />}>
              {description}
            </DialogPrimitive.Description>
          ) : null}
          {bare ? children : <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">{children}</div>}
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
