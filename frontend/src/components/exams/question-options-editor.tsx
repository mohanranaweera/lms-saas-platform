"use client";

import { ArrowDown, ArrowUp, Trash2 } from "lucide-react";
import type { FieldErrors, FieldValues, UseFormRegister } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { QuestionOptionFormValues } from "@/lib/validation/exams";

interface OptionsFormShape extends FieldValues {
  options: QuestionOptionFormValues[];
}

/**
 * The shape RHF actually attaches to `errors.options` for a Zod
 * array-level `superRefine` failure (e.g. "at least one option must be
 * correct") — RHF has no first-class type for this, so it's captured here
 * once, alongside the real per-item `FieldErrors<QuestionOptionFormValues>[]`
 * shape, rather than reading it off an `unknown` cast at each access site.
 */
interface OptionsArrayFieldError {
  message?: string;
  root?: { message?: string };
}

/**
 * Deliberately narrower than RHF's own `UseFieldArrayReturn<T, "options">`:
 * that generic type requires TypeScript to prove `"options"` is a valid
 * `ArrayPath<T>` for every possible `T extends OptionsFormShape`, which does
 * not type-check for an open generic (confirmed while wiring this up against
 * both `QuestionCreateFormValues` and `QuestionEditFormValues`). This
 * component only ever needs `fields`/`append`/`remove`/`move`, so it accepts
 * exactly that shape instead — both call sites pass their real
 * `useFieldArray({ control, name: "options" })` return value directly, which
 * is structurally compatible.
 */
interface OptionsFieldArray {
  fields: (QuestionOptionFormValues & { id: string })[];
  append: (value: QuestionOptionFormValues) => void;
  remove: (index: number) => void;
  move: (from: number, to: number) => void;
}

interface QuestionOptionsEditorProps<T extends OptionsFormShape> {
  idPrefix: string;
  fieldArray: OptionsFieldArray;
  register: UseFormRegister<T>;
  errors: FieldErrors<T>;
  disabled?: boolean;
}

/**
 * MCQ answer-option editor (screens #4/#5's shared authoring building block).
 * Full keyboard navigability per `.claude/rules/ui-ux.md` §4 and the MVP-017
 * plan §11: reordering uses explicit "Move up"/"Move down" buttons (no
 * drag-only interaction), the whole group is a `fieldset`/`legend`, the
 * "correct answer" flag is a real labeled checkbox (never an unlabeled icon
 * button), and every text input ties its validation error via
 * `aria-describedby`/`aria-invalid`.
 */
export function QuestionOptionsEditor<T extends OptionsFormShape>({
  idPrefix,
  fieldArray,
  register,
  errors,
  disabled,
}: QuestionOptionsEditorProps<T>) {
  const { fields, append, remove, move } = fieldArray;
  const optionsError = errors.options as
    | (FieldErrors<QuestionOptionFormValues>[] & OptionsArrayFieldError)
    | undefined;
  const groupErrorMessage = optionsError?.root?.message ?? optionsError?.message;

  return (
    <fieldset className="flex flex-col gap-3" disabled={disabled}>
      <legend className="text-sm font-medium text-foreground">Answer options</legend>
      {groupErrorMessage ? (
        <p role="alert" className="text-xs text-destructive">
          {groupErrorMessage}
        </p>
      ) : null}
      <ul className="flex flex-col gap-2">
        {fields.map((field, index) => {
          const textId = `${idPrefix}-option-${index}-text`;
          const textErrorId = `${idPrefix}-option-${index}-text-error`;
          const correctId = `${idPrefix}-option-${index}-correct`;
          const textError = Array.isArray(optionsError) ? optionsError[index]?.optionText : undefined;
          return (
            <li
              key={field.id}
              className="flex flex-col gap-2 rounded-lg border border-border p-3 sm:flex-row sm:items-start sm:gap-3"
            >
              <div className="flex flex-1 flex-col gap-1.5">
                <Label htmlFor={textId}>Option {index + 1} text</Label>
                <Input
                  id={textId}
                  aria-required="true"
                  aria-invalid={!!textError}
                  aria-describedby={textError ? textErrorId : undefined}
                  {...register(`options.${index}.optionText` as never)}
                />
                {textError ? (
                  <p id={textErrorId} role="alert" className="text-xs text-destructive">
                    {textError.message as string}
                  </p>
                ) : null}
                <div className="flex items-center gap-1.5">
                  <input
                    id={correctId}
                    type="checkbox"
                    className="size-4 rounded border-input"
                    {...register(`options.${index}.isCorrect` as never)}
                  />
                  <Label htmlFor={correctId} className="font-normal">
                    This option is correct
                  </Label>
                </div>
              </div>
              <div className="flex flex-row gap-1.5 sm:flex-col">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="w-full sm:w-auto"
                  disabled={disabled || index === 0}
                  onClick={() => move(index, index - 1)}
                  aria-label={`Move option ${index + 1} up`}
                >
                  <ArrowUp className="size-3.5" aria-hidden="true" />
                  Move up
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="w-full sm:w-auto"
                  disabled={disabled || index === fields.length - 1}
                  onClick={() => move(index, index + 1)}
                  aria-label={`Move option ${index + 1} down`}
                >
                  <ArrowDown className="size-3.5" aria-hidden="true" />
                  Move down
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="w-full sm:w-auto"
                  disabled={disabled || fields.length <= 1}
                  onClick={() => remove(index)}
                  aria-label={`Remove option ${index + 1}`}
                >
                  <Trash2 className="size-3.5" aria-hidden="true" />
                  Remove
                </Button>
              </div>
            </li>
          );
        })}
      </ul>
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="w-full sm:w-fit"
        disabled={disabled}
        onClick={() => append({ optionText: "", isCorrect: false })}
      >
        Add option
      </Button>
    </fieldset>
  );
}
