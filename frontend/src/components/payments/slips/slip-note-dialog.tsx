"use client";

import { useState, type ChangeEvent, type FormEvent } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, NotebookPen } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { FieldError, StaffDialog } from "@/components/payments/staff/staff-dialog";
import { isApiClientError } from "@/lib/api/error";
import { SLIP_NOTE_MAX_LENGTH, useAddSlipNote, type PaymentSlipResponse } from "@/lib/api/payment-slips";
import { slipNoteSchema, type SlipNoteFormValues } from "@/lib/validation/payment-slip";

/**
 * PAY-HUB-01 Klass "Add Note": an internal, append-only staff note on a slip
 * (never shown to students, cannot be edited or deleted, never changes the
 * slip's state). Rendered only for `canAddSlipNotes`; the backend enforces
 * `PAYMENTS_SLIPS`/`CREATE_EDIT` and the 1-1000 character limit.
 */
export function SlipNoteDialog({
  slip,
  onSaved,
}: {
  slip: PaymentSlipResponse;
  onSaved?: (message: string) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={`Add note to payment ${slip.referenceNumber}`}
        title="Add note"
        onClick={() => setOpen(true)}
        data-testid="slip-add-note"
      >
        <NotebookPen aria-hidden="true" className="text-blue-600 dark:text-blue-400" />
      </Button>
      <SlipNoteDialogShell slip={slip} open={open} onOpenChange={setOpen} onSaved={onSaved} />
    </>
  );
}

function SlipNoteDialogShell({
  slip,
  open,
  onOpenChange,
  onSaved,
}: {
  slip: PaymentSlipResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved?: (message: string) => void;
}) {
  const mutation = useAddSlipNote(slip.id);
  return (
    <StaffDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Add note"
      description={`Internal note on slip ${slip.referenceNumber}. Students never see notes, and a note can't be edited later.`}
      icon={<NotebookPen className="size-4" aria-hidden="true" />}
      busy={mutation.isPending}
    >
      <NoteForm
        slipId={slip.id}
        pending={mutation.isPending}
        onSubmit={async (body) => {
          await mutation.mutateAsync(body);
          onOpenChange(false);
          onSaved?.(`Note added to slip ${slip.referenceNumber}.`);
        }}
        onCancel={() => onOpenChange(false)}
      />
    </StaffDialog>
  );
}

function NoteForm({
  slipId,
  pending,
  onSubmit,
  onCancel,
}: {
  slipId: string;
  pending: boolean;
  onSubmit: (body: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [pageError, setPageError] = useState<string | null>(null);
  const [length, setLength] = useState(0);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SlipNoteFormValues>({
    resolver: zodResolver(slipNoteSchema),
    defaultValues: { body: "" },
  });

  const submit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await onSubmit(values.body);
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "Couldn't save the note. Please try again.");
    }
  });

  function handleFormSubmit(event: FormEvent<HTMLFormElement>) {
    if (pending) {
      event.preventDefault();
      return;
    }
    void submit(event);
  }

  const fieldId = `slip-note-${slipId}`;
  return (
    <form className="flex flex-col gap-3 p-4" onSubmit={handleFormSubmit} noValidate aria-busy={pending}>
      {pageError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{pageError}</AlertDescription>
        </Alert>
      ) : null}
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={fieldId}>Note</Label>
        <Textarea
          id={fieldId}
          rows={4}
          maxLength={SLIP_NOTE_MAX_LENGTH}
          aria-invalid={!!errors.body}
          aria-describedby={`${fieldId}-hint${errors.body ? ` ${fieldId}-error` : ""}`}
          disabled={pending}
          data-testid="slip-note-body"
          {...register("body", {
            onChange: (event: ChangeEvent<HTMLTextAreaElement>) => setLength(event.target.value.trim().length),
          })}
        />
        <p id={`${fieldId}-hint`} className="text-xs text-muted-foreground">
          {length}/{SLIP_NOTE_MAX_LENGTH}
        </p>
        <FieldError id={`${fieldId}-error`} message={errors.body?.message} />
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
        <Button type="submit" disabled={pending} aria-busy={pending} data-testid="slip-note-save">
          {pending ? "Saving…" : "Save note"}
        </Button>
      </div>
    </form>
  );
}
