"use client";

import { useState, type ReactNode } from "react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import { EXPENSE_METHODS, useCreateExpense, type ExpenseCategory } from "@/lib/api/finance";
import {
  AMOUNT_HELPER_TEXT,
  EXPENSE_RECEIPT_ACCEPT,
  EXPENSE_RECEIPT_MAX_BYTES,
  expenseSchema,
  type ExpenseFormValues,
} from "@/lib/validation/finance";

const FIELDS = ["categoryId", "expenseDate", "description", "amount", "method", "reference"] as const;

/**
 * Record-expense form (Wave 7, PAR-23-01/05). The receipt's type/size are
 * pre-checked here only for fast feedback — the server independently sniffs
 * magic bytes and enforces the size limit (415/413), and those errors are
 * surfaced inline. An expense cannot be edited after saving; mistakes are
 * corrected by voiding and re-recording.
 */
export function ExpenseForm({
  categories,
  onDone,
}: {
  categories: ExpenseCategory[];
  onDone: () => void;
}) {
  const mutation = useCreateExpense();
  const [pageError, setPageError] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ExpenseFormValues>({
    resolver: zodResolver(expenseSchema),
    defaultValues: { categoryId: "", expenseDate: "", description: "", amount: "", reference: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setFileError(null);
    if (file) {
      if (!["application/pdf", "image/png", "image/jpeg"].includes(file.type)) {
        setFileError("Receipts must be a PDF, PNG or JPEG file.");
        return;
      }
      if (file.size > EXPENSE_RECEIPT_MAX_BYTES) {
        setFileError("Receipts must be 10 MB or smaller.");
        return;
      }
    }
    try {
      await mutation.mutateAsync({
        ...values,
        description: values.description.trim(),
        reference: values.reference?.trim() || undefined,
        attachment: file,
      });
      onDone();
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.status === 413) {
          setFileError("The receipt is larger than the allowed size.");
          return;
        }
        if (error.status === 415) {
          setFileError("The receipt's content is not a valid PDF, PNG or JPEG.");
          return;
        }
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          const field = FIELDS.find((name) => name === fieldError.field);
          if (field) {
            setError(field, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (!mapped) setPageError(error.message);
        return;
      }
      setPageError("Something went wrong. Please try again.");
    }
  });

  return (
    <form
      className="flex flex-col gap-4 rounded-lg border border-border p-4"
      noValidate
      aria-busy={mutation.isPending}
      aria-labelledby="expense-form-heading"
      onSubmit={onSubmit}
    >
      <h2 id="expense-form-heading" className="text-base font-semibold text-foreground">
        Record an expense
      </h2>
      <LiveRegion message={mutation.isPending ? "Saving expense…" : ""} />
      {pageError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{pageError}</AlertDescription>
        </Alert>
      ) : null}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field id="expense-category" label="Category" error={errors.categoryId?.message}>
          <Controller
            control={control}
            name="categoryId"
            render={({ field }) => (
              <Select value={field.value || null} onValueChange={(v) => field.onChange(v ?? "")}>
                <SelectTrigger id="expense-category" className="w-full" aria-invalid={!!errors.categoryId}>
                  <SelectValue placeholder="Select a category…" />
                </SelectTrigger>
                <SelectContent>
                  {categories.map((category) => (
                    <SelectItem key={category.id} value={category.id}>
                      {category.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          />
        </Field>

        <Field id="expense-date" label="Date" error={errors.expenseDate?.message}>
          <Input id="expense-date" type="date" aria-invalid={!!errors.expenseDate} {...register("expenseDate")} />
        </Field>

        <Field id="expense-amount" label="Amount" error={errors.amount?.message} helper={AMOUNT_HELPER_TEXT}>
          <Input
            id="expense-amount"
            inputMode="decimal"
            autoComplete="off"
            aria-invalid={!!errors.amount}
            {...register("amount")}
          />
        </Field>

        <Field id="expense-method" label="Payment method" error={errors.method?.message}>
          <Controller
            control={control}
            name="method"
            render={({ field }) => (
              <Select value={field.value || null} onValueChange={(v) => field.onChange(v ?? "")}>
                <SelectTrigger id="expense-method" className="w-full" aria-invalid={!!errors.method}>
                  <SelectValue placeholder="Select a method…" />
                </SelectTrigger>
                <SelectContent>
                  {EXPENSE_METHODS.map((method) => (
                    <SelectItem key={method.value} value={method.value}>
                      {method.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          />
        </Field>

        <Field id="expense-description" label="Description" error={errors.description?.message}>
          <Input id="expense-description" aria-invalid={!!errors.description} {...register("description")} />
        </Field>

        <Field id="expense-reference" label="Reference (optional)" error={errors.reference?.message}>
          <Input id="expense-reference" autoComplete="off" {...register("reference")} />
        </Field>

        <Field
          id="expense-receipt"
          label="Receipt (optional)"
          error={fileError ?? undefined}
          helper="PDF, PNG or JPEG, up to 10 MB."
        >
          <Input
            id="expense-receipt"
            onChange={(event) => {
              setFile(event.target.files?.[0] ?? null);
              setFileError(null);
            }}
            type="file"
            accept={EXPENSE_RECEIPT_ACCEPT}
            aria-invalid={!!fileError}
          />
        </Field>
      </div>

      <div className="flex gap-2">
        <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save expense"}
        </Button>
        <Button type="button" variant="outline" onClick={onDone} disabled={mutation.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function Field({
  id,
  label,
  error,
  helper,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  helper?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {helper ? <p className="text-xs text-muted-foreground">{helper}</p> : null}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
