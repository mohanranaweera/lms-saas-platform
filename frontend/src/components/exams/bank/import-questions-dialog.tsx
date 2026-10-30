"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import {
  importRowErrors,
  useDownloadQuestionTemplate,
  useImportQuestions,
  type ImportRowError,
  type QuestionImport,
} from "@/lib/api/question-bank";

const ID_PREFIX = "import-questions";
const MAX_CSV_BYTES = 1024 * 1024;

const importSchema = z.object({
  files: z
    .custom<FileList | undefined>()
    .refine((files) => Boolean(files && files.length > 0), "File is required.")
    .refine((files) => !files?.[0] || files[0].name.toLowerCase().endsWith(".csv"), "Choose a .csv file.")
    .refine((files) => !files?.[0] || files[0].size <= MAX_CSV_BYTES, "The file must be 1 MB or smaller."),
});
type ImportValues = z.infer<typeof importSchema>;

/**
 * IMPORT QUESTIONS dialog (Klass CSV import). Each selected file gets one
 * Idempotency-Key, reused on every retry of that same file (the caller owns
 * the key map so it survives re-opening the dialog). A 422 rejection is
 * atomic: nothing is imported and the row errors are listed.
 * Mount with a fresh `key` per open.
 */
export function ImportQuestionsDialog({
  open,
  onOpenChange,
  courseId,
  moduleId,
  idempotencyKeyFor,
  onImported,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  courseId: string;
  moduleId: string;
  idempotencyKeyFor: (file: File) => string;
  onImported: (result: QuestionImport) => void;
}) {
  const importMutation = useImportQuestions(courseId, moduleId);
  const template = useDownloadQuestionTemplate();
  const [formError, setFormError] = useState<string | null>(null);
  const [rowErrors, setRowErrors] = useState<ImportRowError[]>([]);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ImportValues>({ resolver: zodResolver(importSchema), defaultValues: { files: undefined } });
  const pending = importMutation.isPending;

  const onSubmit = handleSubmit(async (values) => {
    const file = values.files?.[0];
    if (!file) return;
    setFormError(null);
    setRowErrors([]);
    try {
      const result = await importMutation.mutateAsync({ file, idempotencyKey: idempotencyKeyFor(file) });
      onImported(result);
    } catch (error) {
      const rows = importRowErrors(error);
      if (rows.length > 0) {
        setRowErrors(rows);
        setFormError("Nothing was imported. Fix these rows in the file and import it again.");
      } else {
        setFormError(mutationErrorMessage(error));
      }
    }
  });

  const fileIds = fieldIds(ID_PREFIX, "file");
  const fileError = errors.files?.message ? String(errors.files.message) : null;
  const fileRegister = register("files");

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && pending) return;
        onOpenChange(next);
      }}
      title="Import questions"
      testId="import-questions-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending} data-testid="import-cancel">
            Cancel
          </Button>
          <Button type="submit" form="import-questions-form" disabled={pending} aria-busy={pending} data-testid="import-submit">
            {pending ? "Importing…" : "Import"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">
          Please select the CSV file which contains the questions to import. You can{" "}
          <button
            type="button"
            className="font-medium text-primary underline underline-offset-4 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50"
            onClick={() => template.mutate()}
            disabled={template.isPending}
            data-testid="import-template-link"
          >
            download the sample template
          </button>{" "}
          to see the expected columns (question, correct answer, answer 2 … answer 10, and optional marks and
          explanation).
        </p>
        {template.isError ? (
          <p role="alert" className="text-xs text-destructive">
            {mutationErrorMessage(template.error)}
          </p>
        ) : null}

        {formError ? (
          <Alert variant="destructive" data-testid="import-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              <p>{formError}</p>
              {rowErrors.length > 0 ? (
                <ul className="mt-2 flex max-h-56 list-disc flex-col gap-1 overflow-y-auto pl-5" data-testid="import-row-errors">
                  {rowErrors.map((rowError, index) => (
                    <li key={`${rowError.row}-${rowError.column}-${index}`}>
                      {rowError.row > 0 ? `Row ${rowError.row}` : "File"}
                      {rowError.column ? `, ${rowError.column}` : ""}: {rowError.message}
                    </li>
                  ))}
                </ul>
              ) : null}
            </AlertDescription>
          </Alert>
        ) : null}

        <form id="import-questions-form" noValidate onSubmit={onSubmit}>
          <fieldset disabled={pending} className="min-w-0">
            <FormField ids={fileIds} label="CSV file" required error={fileError} helper="A .csv file up to 1 MB and 500 questions.">
              <input
                id={fileIds.inputId}
                type="file"
                accept=".csv,text/csv"
                aria-required="true"
                aria-invalid={Boolean(fileError)}
                aria-describedby={describedBy(fileIds, true, Boolean(fileError))}
                className="block w-full min-w-0 text-sm file:mr-3 file:rounded-md file:border file:border-border file:bg-muted file:px-3 file:py-1.5 file:text-sm file:font-medium"
                data-testid="import-file"
                {...fileRegister}
                onChange={(event) => {
                  setFormError(null);
                  setRowErrors([]);
                  void fileRegister.onChange(event);
                }}
              />
            </FormField>
          </fieldset>
        </form>
      </div>
    </AdminFormDialog>
  );
}
