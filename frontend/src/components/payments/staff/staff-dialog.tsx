"use client";

import type { ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";

/** Native `<select>` styling shared by the staff payment forms (matches `input.tsx`). */
export const NATIVE_SELECT_CLASS =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:bg-input/30";

/**
 * Modal shell for the staff payment / free card dialogs: full-screen on
 * mobile, centered card from `sm`. The body is only mounted while open, so
 * every open starts with fresh form state (and a fresh idempotency key).
 * Dismissal is blocked while `busy` so an in-flight request is never orphaned.
 */
export function StaffDialog({
  open,
  onOpenChange,
  title,
  description,
  icon,
  busy = false,
  testId,
  size = "md",
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  icon?: ReactNode;
  busy?: boolean;
  testId?: string;
  /** `"lg"` widens the centered card (e.g. the Payments hub slip viewer). */
  size?: "md" | "lg";
  children: ReactNode;
}) {
  return (
    <DialogPrimitive.Root
      open={open}
      onOpenChange={(next) => {
        if (!next && busy) return;
        onOpenChange(next);
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/40 transition-opacity duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0" />
        <DialogPrimitive.Popup
          data-testid={testId}
          className={`fixed inset-0 z-50 flex flex-col bg-popover text-sm text-popover-foreground shadow-lg outline-none transition duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0 sm:inset-auto sm:top-1/2 sm:left-1/2 sm:max-h-[calc(100dvh-2rem)] sm:w-[calc(100%-2rem)] ${size === "lg" ? "sm:max-w-3xl" : "sm:max-w-xl"} sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-xl sm:border sm:border-border`}
        >
          <div className="flex items-start justify-between gap-3 border-b border-border px-4 py-3">
            <div className="flex items-start gap-3">
              {icon ? (
                <span className="mt-0.5 inline-flex size-9 items-center justify-center rounded-lg bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-200">
                  {icon}
                </span>
              ) : null}
              <div>
                <DialogPrimitive.Title className="text-base font-semibold text-foreground">{title}</DialogPrimitive.Title>
                {description ? (
                  <DialogPrimitive.Description className="text-sm text-muted-foreground">
                    {description}
                  </DialogPrimitive.Description>
                ) : null}
              </div>
            </div>
            <DialogPrimitive.Close
              disabled={busy}
              render={<Button type="button" variant="ghost" size="icon-sm" aria-label="Close" />}
            >
              <XIcon aria-hidden="true" />
            </DialogPrimitive.Close>
          </div>
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto">{open ? children : null}</div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

/** Label + control + hint/error, with `aria-describedby` wiring left to the caller via ids. */
export function FieldError({ id, message }: { id: string; message: string | null | undefined }) {
  if (!message) return null;
  return (
    <p id={id} role="alert" className="text-xs text-destructive">
      {message}
    </p>
  );
}
