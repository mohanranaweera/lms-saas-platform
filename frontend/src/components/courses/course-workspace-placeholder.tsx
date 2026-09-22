import { Construction } from "lucide-react";

/**
 * Deliberate scope-boundary placeholder for the course workspace tabs that
 * are visible/clickable but have no functionality yet this wave (Schedule,
 * Sessions, Recordings, Analytics — Wave 2 plan, explicitly out of scope).
 * Renders NO fake data, NO fake controls, and NO partial functionality — an
 * honest "not yet available" state only, so this is never later mistaken for
 * an oversight (`.claude/rules/ui-ux.md` §3's "no generic empty-state copy"
 * rule doesn't apply here: this is a genuine, identical-by-design state
 * across every one of these tabs, not a case that needs contextual copy).
 */
export function CourseWorkspacePlaceholder({ title, description }: { title: string; description: string }) {
  return (
    <div
      role="status"
      className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center"
    >
      <Construction className="size-6 text-muted-foreground" aria-hidden="true" />
      <p className="text-sm font-medium text-foreground">{title}</p>
      <p className="max-w-md text-sm text-muted-foreground">{description}</p>
      <p className="text-xs text-muted-foreground">Coming in a later release.</p>
    </div>
  );
}
