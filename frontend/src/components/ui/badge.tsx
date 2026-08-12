import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

/**
 * Small status-chip primitive, following `button.tsx`'s `cva` pattern.
 *
 * Color is never the only signal here — every variant is designed to be
 * paired with a text label (and typically an icon) by the caller, per
 * `.claude/rules/ui-ux.md` §4 ("Status indicators ... must not rely on color
 * alone"). This component only supplies the container styling; composing an
 * icon + label is the caller's responsibility (see
 * `components/students/student-status-badge.tsx` for the reference usage).
 */
const badgeVariants = cva(
  "inline-flex w-fit items-center gap-1.5 rounded-md border px-2 py-0.5 text-xs font-medium",
  {
    variants: {
      variant: {
        default: "border-border bg-muted text-foreground",
        destructive:
          "border-destructive/30 bg-destructive/10 text-destructive dark:border-destructive/50",
        outline: "border-border bg-transparent text-foreground",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function Badge({
  className,
  variant,
  ...props
}: React.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return (
    <span
      data-slot="badge"
      className={cn(badgeVariants({ variant, className }))}
      {...props}
    />
  )
}

export { Badge, badgeVariants }
