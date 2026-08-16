import { CheckCircle2 } from "lucide-react";
import { cn } from "@/lib/utils";

export interface StepDefinition {
  id: string;
  label: string;
}

interface StepperProps {
  steps: StepDefinition[];
  currentStepIndex: number;
  className?: string;
}

/**
 * Course Builder step indicator. `aria-current="step"` on the active step per
 * `.claude/rules/ui-ux.md` §4 (keyboard/screen-reader operable multi-step
 * flow). Purely presentational — the owning form drives which step is
 * "current" and where focus moves on transition (see `course-builder`
 * components, which move focus to each step's heading via a ref keyed on
 * step index).
 */
export function Stepper({ steps, currentStepIndex, className }: StepperProps) {
  return (
    <ol aria-label="Course builder steps" className={cn("flex flex-wrap items-center gap-x-1 gap-y-2", className)}>
      {steps.map((step, index) => {
        const isCurrent = index === currentStepIndex;
        const isComplete = index < currentStepIndex;
        return (
          <li key={step.id} className="flex items-center gap-1">
            <span
              aria-current={isCurrent ? "step" : undefined}
              className={cn(
                "flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium",
                isCurrent && "border-foreground bg-foreground text-background",
                !isCurrent && isComplete && "border-border bg-muted text-foreground",
                !isCurrent && !isComplete && "border-border text-muted-foreground"
              )}
            >
              {isComplete ? (
                <CheckCircle2 className="size-3.5" aria-hidden="true" />
              ) : (
                <span aria-hidden="true">{index + 1}</span>
              )}
              {step.label}
            </span>
            {index < steps.length - 1 ? (
              <span aria-hidden="true" className="px-0.5 text-muted-foreground">
                &rsaquo;
              </span>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}
