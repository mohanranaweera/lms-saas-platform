import { CheckCircle2, Clock, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Per-question review chip for the Student Results & Review screen (#3).
 * Never color alone (`.claude/rules/ui-ux.md` §4). There is no per-question
 * max-points field anywhere in this backend's contract (see
 * `lib/api/exams.ts`), so this deliberately does not claim a precise
 * "correct"/"incorrect" verdict beyond the simple, documented MCQ
 * auto-marking heuristic: `autoScore` is set exclusively for MCQ answers and
 * (per `McqAutoMarkingServiceTest`'s own description — "unanswered MCQ items
 * score zero") is a binary zero/non-zero outcome at this MVP, so `> 0` is
 * treated as "correct". `manualScore` set (structured, once marked) shows the
 * awarded score directly rather than a binary verdict, since structured
 * marking is not binary. Neither score present yet means the answer is still
 * queued for manual marking.
 */
export function AnswerResultChip({
  autoScore,
  manualScore,
}: {
  autoScore: number | string | null;
  manualScore: number | string | null;
}) {
  if (autoScore !== null && autoScore !== undefined) {
    const numeric = Number(autoScore);
    const isCorrect = numeric > 0;
    return (
      <span
        className={cn(
          "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
          isCorrect ? "text-foreground" : "text-destructive"
        )}
      >
        {isCorrect ? (
          <CheckCircle2 className="size-3.5" aria-hidden="true" />
        ) : (
          <XCircle className="size-3.5" aria-hidden="true" />
        )}
        {isCorrect ? `Correct (${numeric} pts)` : "Incorrect (0 pts)"}
      </span>
    );
  }

  if (manualScore !== null && manualScore !== undefined) {
    return (
      <span className="inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-foreground">
        <CheckCircle2 className="size-3.5" aria-hidden="true" />
        {`Marked (${Number(manualScore)} pts)`}
      </span>
    );
  }

  return (
    <span className="inline-flex w-fit items-center gap-1.5 rounded-md border border-dashed border-border bg-muted/40 px-2 py-0.5 text-xs font-medium text-muted-foreground">
      <Clock className="size-3.5" aria-hidden="true" />
      Pending review
    </span>
  );
}
