"use client";

import { Eraser } from "lucide-react";
import { Button } from "@/components/ui/button";
import { RichContent } from "@/components/exams/rich-content";
import { formatExamMarks } from "@/components/exams/config/exam-shared";
import type { AttemptItem } from "@/lib/api/student-exams";
import { cn } from "@/lib/utils";

export function optionLetter(index: number): string {
  return index < 26 ? String.fromCharCode(65 + index) : String(index + 1);
}

/**
 * One question of an attempt: number, marks, rich body and its options as a
 * native radio group in the persisted per-attempt order. Arrow keys move
 * between options (native radio behaviour); every change is saved by the
 * caller. Correct answers are never part of this data.
 */
export function AttemptQuestion({
  item,
  number,
  total,
  selectedOptionId,
  disabled,
  onSelect,
}: {
  item: AttemptItem;
  number: number;
  total: number;
  selectedOptionId: string | null;
  disabled: boolean;
  onSelect: (optionId: string | null) => void;
}) {
  const headingId = `attempt-q-${item.id}-heading`;
  const bodyId = `attempt-q-${item.id}-body`;
  const marks = formatExamMarks(item.marks);
  return (
    <section
      aria-labelledby={headingId}
      className="flex min-w-0 scroll-mt-40 flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5"
      data-testid="attempt-question"
      data-item-id={item.id}
      id={`attempt-q-${item.id}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} tabIndex={-1} className="text-sm font-semibold text-muted-foreground outline-none">
          Question {number} <span className="font-normal">of {total}</span>
        </h2>
        {marks ? <span className="rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground">{marks}</span> : null}
      </div>
      <div id={bodyId}>
        <RichContent html={item.bodyHtml} className="text-base text-foreground" testId="attempt-question-body" />
      </div>
      <div role="radiogroup" aria-labelledby={`${headingId} ${bodyId}`} className="flex flex-col gap-2">
        {item.options.map((option, index) => {
          const inputId = `attempt-q-${item.id}-opt-${option.id}`;
          const checked = selectedOptionId === option.id;
          return (
            <label
              key={option.id}
              htmlFor={inputId}
              data-testid="attempt-option"
              data-option-id={option.id}
              className={cn(
                "flex min-w-0 cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-ring/50",
                checked ? "border-primary bg-primary/5" : "border-border hover:bg-muted/50",
                disabled && "cursor-not-allowed opacity-70"
              )}
            >
              <input
                type="radio"
                id={inputId}
                name={`attempt-q-${item.id}`}
                value={option.id}
                checked={checked}
                disabled={disabled}
                onChange={() => onSelect(option.id)}
                className="mt-1 size-4 shrink-0 accent-primary"
              />
              <span
                aria-hidden="true"
                className={cn(
                  "flex size-6 shrink-0 items-center justify-center rounded-full border text-xs font-semibold",
                  checked ? "border-primary bg-primary text-primary-foreground" : "border-border text-muted-foreground"
                )}
              >
                {optionLetter(index)}
              </span>
              <span className="sr-only">Option {optionLetter(index)}:</span>
              <RichContent html={option.html} className="min-w-0 flex-1 text-sm text-foreground" />
            </label>
          );
        })}
      </div>
      {selectedOptionId ? (
        <div>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={disabled}
            onClick={() => onSelect(null)}
            data-testid="attempt-clear-answer"
          >
            <Eraser aria-hidden="true" />
            Clear answer
          </Button>
        </div>
      ) : null}
    </section>
  );
}
