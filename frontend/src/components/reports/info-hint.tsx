"use client";

import { Popover } from "@base-ui/react/popover";
import { Info } from "lucide-react";

/**
 * Small keyboard-operable explanation popover next to a metric label (click
 * or Enter/Space to open, Escape to close). The trigger has an `aria-label`
 * naming the metric, since it is icon-only.
 */
export function InfoHint({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Popover.Root>
      <Popover.Trigger
        aria-label={`About ${label}`}
        className="inline-flex size-6 items-center justify-center rounded-full text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <Info className="size-3.5" aria-hidden="true" />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Positioner sideOffset={6} className="isolate z-50">
          <Popover.Popup className="max-w-64 rounded-md bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md ring-1 ring-foreground/10 outline-none">
            {children}
          </Popover.Popup>
        </Popover.Positioner>
      </Popover.Portal>
    </Popover.Root>
  );
}
