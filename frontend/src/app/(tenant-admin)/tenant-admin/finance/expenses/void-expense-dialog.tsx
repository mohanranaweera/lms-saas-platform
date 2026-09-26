"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Ban } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import { useVoidExpense, type Expense } from "@/lib/api/finance";
import { formatMoney } from "@/lib/format";
import { reasonSchema, type ReasonFormValues } from "@/lib/validation/finance";

/**
 * Void confirmation (Wave 7). Expenses are financial history: voiding keeps
 * the record (visible with "Show voided") and is one-way; a mistake is fixed
 * by recording a new expense. Rendered only for `canManageFinance(role)` — UX
 * convenience; the backend independently 403s any other role.
 */
export function VoidExpenseDialog({ expense }: { expense: Expense }) {
  const [open, setOpen] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useVoidExpense();
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<ReasonFormValues>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: "" } });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({ id: expense.id, reason: values.reason.trim() });
      setOpen(false);
      reset();
    } catch (error) {
      if (isApiClientError(error)) {
        const reasonError = error.fieldErrors.find((f) => f.field === "reason");
        if (reasonError) setError("reason", { type: "server", message: reasonError.message });
        else setPageError(error.message);
        return;
      }
      setPageError("Something went wrong. Please try again.");
    }
  });

  const inputId = `void-reason-${expense.id}`;
  return (
    <AlertDialog
      open={open}
      onOpenChange={(nextOpen, details) => {
        if (details.reason === "escape-key" && mutation.isPending) {
          details.cancel();
          return;
        }
        setOpen(nextOpen);
        if (!nextOpen) {
          mutation.reset();
          setPageError(null);
          reset();
        }
      }}
    >
      <AlertDialogTrigger
        render={
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-label={`Void expense ${expense.description} (${formatMoney(expense.amount)})`}
          />
        }
      >
        <Ban aria-hidden="true" />
        Void
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Void this expense?</AlertDialogTitle>
          <AlertDialogDescription>
            {expense.description} — {formatMoney(expense.amount, expense.currency)} on{" "}
            {expense.expenseDate}. The record is kept for history and excluded from totals. This
            cannot be undone.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {pageError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{pageError}</AlertDescription>
          </Alert>
        ) : null}
        <form className="flex flex-col gap-3" noValidate aria-busy={mutation.isPending} onSubmit={onSubmit}>
          <LiveRegion message={mutation.isPending ? "Voiding expense…" : ""} />
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={inputId}>Reason</Label>
            <Input
              id={inputId}
              autoComplete="off"
              disabled={mutation.isPending}
              aria-invalid={!!errors.reason}
              aria-describedby={errors.reason ? `${inputId}-error` : undefined}
              {...register("reason")}
            />
            {errors.reason ? (
              <p id={`${inputId}-error`} role="alert" className="text-xs text-destructive">
                {errors.reason.message}
              </p>
            ) : null}
          </div>
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" disabled={mutation.isPending} />}>
              Cancel
            </AlertDialogClose>
            <Button type="submit" variant="destructive" disabled={mutation.isPending}>
              {mutation.isPending ? "Voiding…" : "Void expense"}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
