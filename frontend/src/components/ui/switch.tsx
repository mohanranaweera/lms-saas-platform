"use client";

import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Minimal on/off switch: a native `<button role="switch" aria-checked>`, so it
 * is focusable, toggles with Space/Enter, and is labelled by a
 * `<Label htmlFor={id}>` (or `aria-label`). State is shown by thumb position
 * and the accessible checked state, not by colour alone.
 */
export function Switch({
  checked,
  onCheckedChange,
  className,
  disabled,
  ...props
}: Omit<React.ComponentProps<"button">, "onChange" | "role" | "type"> & {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      data-state={checked ? "checked" : "unchecked"}
      onClick={() => onCheckedChange(!checked)}
      className={cn(
        "inline-flex h-5 w-9 shrink-0 items-center rounded-full border border-transparent p-0.5 outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50",
        checked ? "bg-primary" : "bg-input dark:bg-input/80",
        className
      )}
      {...props}
    >
      <span
        aria-hidden="true"
        className={cn(
          "block size-4 rounded-full bg-background shadow-sm ring-1 ring-foreground/10 transition-transform",
          checked ? "translate-x-4" : "translate-x-0"
        )}
      />
    </button>
  );
}
