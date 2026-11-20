"use client";

import type { ReactElement } from "react";
import { Tooltip as TooltipPrimitive } from "@base-ui/react/tooltip";
import { cn } from "@/lib/utils";

/**
 * Minimal hover/focus tooltip on `@base-ui/react`'s Tooltip. A tooltip is a
 * hint for sighted users only: the trigger must still carry its own
 * accessible name (e.g. `aria-label`). `children` is rendered as the trigger
 * element itself (button or link).
 */
export function Tooltip({
  label,
  children,
  side = "top",
  className,
}: {
  label: string;
  children: ReactElement;
  side?: "top" | "bottom" | "left" | "right";
  className?: string;
}) {
  return (
    <TooltipPrimitive.Root>
      <TooltipPrimitive.Trigger render={children} />
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Positioner side={side} sideOffset={6} className="z-50">
          <TooltipPrimitive.Popup
            className={cn(
              "rounded-md bg-foreground px-2 py-1 text-xs font-medium text-background shadow-md transition-opacity duration-100 data-ending-style:opacity-0 data-starting-style:opacity-0",
              className
            )}
            data-testid="tooltip"
          >
            {label}
          </TooltipPrimitive.Popup>
        </TooltipPrimitive.Positioner>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  );
}
