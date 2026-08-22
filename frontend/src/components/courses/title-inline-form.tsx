"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";
import { titleOnlySchema, type TitleOnlyFormValues } from "@/lib/validation/course";
import { isApiClientError } from "@/lib/api/error";

interface TitleInlineFormProps {
  /** Used to derive unique element ids (`${idPrefix}-title`) — must be unique per rendered instance. */
  idPrefix: string;
  label: string;
  /** Omit (or leave `""`) for a create form; supply the current title for a rename form. */
  initialTitle?: string;
  submitLabel: string;
  /** Announced via the sr-only `aria-live` region while `onSubmit` is in flight. */
  pendingLabel: string;
  onSubmit: (title: string) => Promise<void>;
  /** Disables the whole form (e.g. while a sibling reorder/delete is in flight). */
  disabled?: boolean;
  /**
   * Create forms (`clearOnSuccess`) reset back to an empty field after a
   * successful submit, ready for the next entry, and never disable Submit
   * based on "no changes yet". Rename forms keep the just-saved value in
   * place and disable Save until the field is dirty again, so it's never
   * possible to fire a needless no-op PATCH.
   */
  clearOnSuccess?: boolean;
}

/**
 * Shared title create/rename form for modules and lessons — the only field
 * either ever accepts from a user; `sequence` is always computed
 * separately by the caller (new items) or carried over unchanged (renames),
 * never entered here. Mirrors the established form idiom (local `pageError`
 * state, `ErrorState` for mutation failures, `isApiClientError` field-error
 * mapping, `role="status" aria-live="polite"` sr-only busy announcement,
 * `aria-busy` on the form) from `course-teacher-reassign-form.tsx`.
 */
export function TitleInlineForm({
  idPrefix,
  label,
  initialTitle = "",
  submitLabel,
  pendingLabel,
  onSubmit,
  disabled,
  clearOnSuccess = false,
}: TitleInlineFormProps) {
  const [pageError, setPageError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<TitleOnlyFormValues>({
    resolver: zodResolver(titleOnlySchema),
    defaultValues: { title: initialTitle },
  });

  const submit = handleSubmit(async (values) => {
    setPageError(null);
    setIsSubmitting(true);
    try {
      const title = values.title.trim();
      await onSubmit(title);
      reset({ title: clearOnSuccess ? "" : title });
    } catch (error) {
      if (isApiClientError(error)) {
        const titleError = error.fieldErrors.find((fieldError) => fieldError.field === "title");
        if (titleError) {
          setError("title", { type: "server", message: titleError.message });
        } else {
          setPageError(error.message);
        }
      } else {
        setPageError("An unexpected error occurred. Please try again.");
      }
    } finally {
      setIsSubmitting(false);
    }
  });

  const busy = isSubmitting || !!disabled;

  return (
    <div className="flex flex-col gap-2">
      <form
        className="flex flex-col gap-2 sm:flex-row sm:items-end"
        noValidate
        aria-busy={busy}
        onSubmit={submit}
      >
        <span role="status" aria-live="polite" className="sr-only">
          {isSubmitting ? pendingLabel : ""}
        </span>
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-title`}>{label}</Label>
          <Input
            id={`${idPrefix}-title`}
            disabled={busy}
            aria-invalid={!!errors.title}
            aria-describedby={errors.title ? `${idPrefix}-title-error` : undefined}
            {...register("title")}
          />
          {errors.title ? (
            <p id={`${idPrefix}-title-error`} role="alert" className="text-xs text-destructive">
              {errors.title.message}
            </p>
          ) : null}
        </div>
        <Button
          type="submit"
          size="sm"
          disabled={busy || (!clearOnSuccess && !isDirty)}
          aria-busy={busy}
        >
          {isSubmitting ? "Saving…" : submitLabel}
        </Button>
      </form>
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}
    </div>
  );
}
