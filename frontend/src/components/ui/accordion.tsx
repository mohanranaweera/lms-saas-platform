"use client";

import { useId, useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Shared "Accordion" primitive (`docs/ui-ux/component-library-spec.md`
 * §2.8): header (title + chevron that rotates on expand) toggling a
 * collapsible content region. First consumer:
 * `app/(tenant-admin)/tenant-admin/audit-log/page.tsx`'s metadata detail
 * (collapsed to a short summary, expanding to a `<pre>`-formatted block).
 *
 * Accessibility contract (non-negotiable per the spec and
 * `.claude/rules/ui-ux.md` §4): the header is a real `<button>` (keyboard-
 * operable by construction) carrying `aria-expanded`, and the content
 * region's `id` is referenced via the header's `aria-controls`. The content
 * region is toggled with the native `hidden` attribute rather than an
 * animated height/opacity transition — this removes it from the
 * accessibility tree and tab order outright while collapsed (no risk of a
 * screen-reader/keyboard user reaching hidden content), at the cost of the
 * content itself appearing instantly rather than animating in. Only the
 * chevron rotates, gated by `motion-reduce:transition-none` per
 * `prefers-reduced-motion` (the spec's "03 Foundations" motion guidance) —
 * there is no other motion in this component for reduced-motion users to
 * suppress.
 */
export interface AccordionProps {
  title: ReactNode;
  children: ReactNode;
  defaultOpen?: boolean;
  className?: string;
  /**
   * Controlled open/closed state. When supplied (together with
   * `onOpenChange`), this component no longer tracks its own `open` state
   * internally — the caller owns it. First consumer:
   * `app/(tenant-admin)/tenant-admin/audit-log/page.tsx`'s metadata panel,
   * whose "open" state is lifted into a shared per-row map so the desktop
   * `<tr>` and mobile `<li>` renders of the same logical row (only one of
   * which is ever visible at a time, per breakpoint) share one value instead
   * of drifting independently across a viewport resize.
   *
   * Omit both `open` and `onOpenChange` for the original uncontrolled
   * behavior (internal state, seeded from `defaultOpen`) — every existing
   * caller that doesn't pass these props keeps working unchanged.
   */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}

export function Accordion({
  title,
  children,
  defaultOpen = false,
  className,
  open: controlledOpen,
  onOpenChange,
}: AccordionProps) {
  const isControlled = controlledOpen !== undefined;
  const [internalOpen, setInternalOpen] = useState(defaultOpen);
  const open = isControlled ? controlledOpen : internalOpen;
  const contentId = useId();

  function toggle() {
    const next = !open;
    if (!isControlled) {
      setInternalOpen(next);
    }
    onOpenChange?.(next);
  }

  return (
    <div className={cn("rounded-md border border-border", className)}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={contentId}
        onClick={toggle}
        className="flex w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-sm font-medium text-foreground outline-none hover:bg-muted/40 focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <span>{title}</span>
        <ChevronDown
          aria-hidden="true"
          className={cn(
            "size-4 shrink-0 transition-transform duration-150 motion-reduce:transition-none",
            open && "rotate-180"
          )}
        />
      </button>
      <div id={contentId} hidden={!open} className="px-3 pb-3 pt-1">
        {children}
      </div>
    </div>
  );
}
