import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * Plain native `<textarea>` styled to match `input.tsx`'s conventions
 * exactly (same border/focus/invalid treatment) — first consumer: the Wave 3
 * Student "Enroll"/"Revoke enrollment" mandatory-`reason` fields
 * (`app/(tenant-admin)/tenant-admin/students/[studentId]/**`). No `@base-ui/react`
 * primitive wrapped here (unlike `input.tsx`) since Base UI has no dedicated
 * textarea primitive — a native element already carries every accessibility
 * property this component needs (label association via `htmlFor`/`id`,
 * `aria-invalid`/`aria-describedby` passthrough).
 */
function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "min-h-20 w-full min-w-0 resize-y rounded-lg border border-input bg-transparent px-2.5 py-2 text-base transition-colors outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-input/50 disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 md:text-sm dark:bg-input/30 dark:disabled:bg-input/80 dark:aria-invalid:border-destructive/50 dark:aria-invalid:ring-destructive/40",
        className
      )}
      {...props}
    />
  );
}

export { Textarea };
