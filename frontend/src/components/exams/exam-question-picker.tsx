"use client";

import { ArrowDown, ArrowUp } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { ExamQuestionResponse } from "@/lib/api/exams";

interface ExamQuestionPickerProps {
  availableQuestions: ExamQuestionResponse[];
  selectedIds: string[];
  onChange: (ids: string[]) => void;
  disabled?: boolean;
}

/**
 * Ordered question-link picker for the Exam Scheduler (screen #5) — the exam
 * draft's `questionIds` array order IS the exam's question sequence
 * (`ExamUpdateRequest.questionIds` REPLACES the whole ordered link set on
 * save). Reordering uses explicit "Move up"/"Move down" buttons, matching
 * `QuestionOptionsEditor`'s keyboard-only pattern — no drag-and-drop.
 *
 * Role-based gating (`canAuthorExams`, MVP-017 review Fix 3) is applied by
 * the caller, not duplicated here: `ExamDraftForm` folds `!canAuthorExams(role)`
 * into the same `disabled` prop it already passes down for `!isDraft`/
 * `isSubmitting`, and additionally wraps this component in a disabled
 * `<fieldset>`, which natively disables every button rendered below
 * regardless of the explicit `disabled` prop. A caller without authoring
 * access therefore cannot reach Add/Remove/Move here even if this
 * component's own `disabled` prop were ever wired incorrectly.
 */
export function ExamQuestionPicker({
  availableQuestions,
  selectedIds,
  onChange,
  disabled,
}: ExamQuestionPickerProps) {
  const questionsById = new Map(availableQuestions.map((question) => [question.id, question]));
  const selected = selectedIds.filter((id) => questionsById.has(id));
  const unselected = availableQuestions.filter((question) => !selectedIds.includes(question.id));

  function move(index: number, direction: -1 | 1) {
    const next = [...selected];
    const target = index + direction;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    onChange(next);
  }

  function add(id: string) {
    onChange([...selected, id]);
  }

  function remove(id: string) {
    onChange(selected.filter((existingId) => existingId !== id));
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h3 className="text-sm font-medium text-foreground">Questions in this exam (in order)</h3>
        {selected.length === 0 ? (
          <p className="text-xs text-muted-foreground">
            No questions linked yet — scheduling requires at least one.
          </p>
        ) : (
          <ul className="mt-2 flex flex-col gap-2">
            {selected.map((id, index) => {
              const question = questionsById.get(id);
              if (!question) return null;
              return (
                <li
                  key={id}
                  className="flex flex-col gap-2 rounded-md border border-border p-2.5 sm:flex-row sm:items-center sm:justify-between"
                >
                  <span className="text-sm text-foreground">
                    {index + 1}. {question.body}
                  </span>
                  <div className="flex shrink-0 gap-1.5">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={disabled || index === 0}
                      onClick={() => move(index, -1)}
                      aria-label={`Move question ${index + 1} up`}
                    >
                      <ArrowUp className="size-3.5" aria-hidden="true" />
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={disabled || index === selected.length - 1}
                      onClick={() => move(index, 1)}
                      aria-label={`Move question ${index + 1} down`}
                    >
                      <ArrowDown className="size-3.5" aria-hidden="true" />
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={disabled}
                      onClick={() => remove(id)}
                    >
                      Remove
                    </Button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div>
        <h3 className="text-sm font-medium text-foreground">Available in the question bank</h3>
        {unselected.length === 0 ? (
          <p className="text-xs text-muted-foreground">
            Every question in this course&apos;s bank is already linked.
          </p>
        ) : (
          <ul className="mt-2 flex flex-col gap-2">
            {unselected.map((question) => (
              <li
                key={question.id}
                className="flex flex-col gap-2 rounded-md border border-dashed border-border p-2.5 sm:flex-row sm:items-center sm:justify-between"
              >
                <span className="text-sm text-foreground">{question.body}</span>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={disabled}
                  onClick={() => add(question.id)}
                  className="w-full sm:w-fit"
                >
                  Add to exam
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
