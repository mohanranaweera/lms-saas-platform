"use client";

import type { ReactNode } from "react";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

/** IDs a field's control should reference via `id` / `aria-describedby`. */
export function fieldIds(prefix: string, name: string) {
  const base = `${prefix}-${name}`;
  return { inputId: base, helperId: `${base}-helper`, errorId: `${base}-error` };
}

export function describedBy(ids: { helperId: string; errorId: string }, hasHelper: boolean, hasError: boolean) {
  return [hasHelper ? ids.helperId : null, hasError ? ids.errorId : null].filter(Boolean).join(" ") || undefined;
}

/**
 * Label + control + helper + inline error, with the error rendered as
 * `role="alert"` text the control references through `aria-describedby`.
 */
export function FormField({
  ids,
  label,
  required,
  helper,
  error,
  children,
  className,
  labelAdornment,
}: {
  ids: { inputId: string; helperId: string; errorId: string };
  label: string;
  required?: boolean;
  helper?: ReactNode;
  error?: string | null;
  children: ReactNode;
  className?: string;
  labelAdornment?: ReactNode;
}) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <div className="flex items-center gap-1.5">
        <Label htmlFor={ids.inputId} className={cn(error && "text-destructive")}>
          {label}
          {required ? (
            <span aria-hidden="true" className="text-destructive">
              {" "}*
            </span>
          ) : null}
          {required ? <span className="sr-only"> (required)</span> : null}
        </Label>
        {labelAdornment}
      </div>
      {children}
      {helper ? (
        <p id={ids.helperId} className="text-xs text-muted-foreground">
          {helper}
        </p>
      ) : null}
      {error ? (
        <p id={ids.errorId} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** Native `<select>` styled like `Input`; native for reliable keyboard, screen-reader and test behaviour. */
export const nativeSelectClass =
  "h-8 w-full min-w-0 rounded-lg border border-input bg-transparent px-2 text-sm outline-none transition-colors focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:bg-input/30";
