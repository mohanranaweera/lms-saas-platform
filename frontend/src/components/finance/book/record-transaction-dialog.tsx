"use client";

import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Textarea } from "@/components/ui/textarea";
import {
  FieldError,
  FinanceDialog,
  FormAlert,
  InfoNote,
  SELECT_CLASS,
  applyServerError,
  todayIso,
  useIdempotencyKey,
} from "@/components/finance/book/shared";
import { useExpenseCategories } from "@/lib/api/finance";
import {
  useFinanceAccounts,
  useRecordTransaction,
  type RecordableType,
} from "@/lib/api/finance-book";
import { recordTransactionSchema, type RecordTransactionFormValues } from "@/lib/validation/finance-book";

const TYPES: { value: RecordableType; label: string }[] = [
  { value: "INCOME", label: "Income" },
  { value: "EXPENSE", label: "Expense" },
  { value: "TRANSFER", label: "Transfer" },
];

const FIELDS = ["type", "amount", "transactionDate", "note", "categoryId", "accountId", "fromAccountId", "toAccountId"] as const;

/**
 * Record Transaction (Income / Expense / Transfer). Student payments never
 * come through here - they are posted by the backend from the ledger. The
 * amount is fixed once saved (corrections are reversals). Re-submitting the
 * same payload reuses its idempotency key, so a retry posts at most once.
 */
export function RecordTransactionDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <FinanceDialog open={open} onOpenChange={onOpenChange} title="Record transaction" testId="record-transaction-dialog">
      <RecordTransactionForm onDone={() => onOpenChange(false)} />
    </FinanceDialog>
  );
}

function RecordTransactionForm({ onDone }: { onDone: () => void }) {
  const accounts = useFinanceAccounts(false);
  const categories = useExpenseCategories(false);
  const record = useRecordTransaction();
  const keyFor = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    control,
    setError,
    formState: { errors },
  } = useForm<RecordTransactionFormValues>({
    resolver: zodResolver(recordTransactionSchema),
    defaultValues: {
      type: "INCOME",
      amount: "",
      transactionDate: todayIso(),
      note: "",
      categoryId: "",
      accountId: "",
      fromAccountId: "",
      toAccountId: "",
    },
  });
  const type = useWatch({ control, name: "type" });
  const transfer = type === "TRANSFER";
  const accountList = accounts.data ?? [];
  const categoryList = categories.data ?? [];

  const onSubmit = handleSubmit(async (input) => {
    setFormError(null);
    try {
      await record.mutateAsync({ input, idempotencyKey: keyFor(input) });
      onDone();
    } catch (e) {
      setFormError(applyServerError(e, FIELDS, setError, "Could not record the transaction."));
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={record.isPending} onSubmit={onSubmit}>
      <LiveRegion message={record.isPending ? "Recording transaction…" : ""} />
      <FormAlert message={formError} />
      {accounts.isSuccess && accountList.length === 0 ? (
        <InfoNote>Create an account on the Accounts tab before recording transactions.</InfoNote>
      ) : null}

      <fieldset className="flex flex-col gap-1.5">
        <legend className="mb-1.5 text-sm font-medium">Type</legend>
        <div className="flex flex-wrap gap-2">
          {TYPES.map((option) => (
            <label
              key={option.value}
              className="flex cursor-pointer items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-sm has-checked:border-primary has-checked:bg-primary/5"
            >
              <input type="radio" value={option.value} {...register("type")} />
              {option.label}
            </label>
          ))}
        </div>
      </fieldset>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="tx-amount">Amount</Label>
          <Input
            id="tx-amount"
            inputMode="decimal"
            aria-invalid={!!errors.amount}
            {...register("amount")}
          />
          <FieldError id="tx-amount-error" message={errors.amount?.message} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="tx-date">Transaction date</Label>
          <Input
            id="tx-date"
            type="date"
            max={todayIso()}
            aria-invalid={!!errors.transactionDate}
            {...register("transactionDate")}
          />
          <FieldError id="tx-date-error" message={errors.transactionDate?.message} />
        </div>

        {transfer ? (
          <>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="tx-from">From account</Label>
              <select id="tx-from" className={SELECT_CLASS} aria-invalid={!!errors.fromAccountId} {...register("fromAccountId")}>
                <option value="">Select…</option>
                {accountList.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </select>
              <FieldError id="tx-from-error" message={errors.fromAccountId?.message} />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="tx-to">To account</Label>
              <select
                id="tx-to"
                className={SELECT_CLASS}
                aria-invalid={!!errors.toAccountId}
                {...register("toAccountId")}
              >
                <option value="">Select…</option>
                {accountList.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </select>
              <FieldError id="tx-to-error" message={errors.toAccountId?.message} />
            </div>
          </>
        ) : (
          <>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="tx-account">Account</Label>
              <select id="tx-account" className={SELECT_CLASS} aria-invalid={!!errors.accountId} {...register("accountId")}>
                <option value="">Select…</option>
                {accountList.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.name}
                  </option>
                ))}
              </select>
              <FieldError id="tx-account-error" message={errors.accountId?.message} />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="tx-category">Category</Label>
              <select id="tx-category" className={SELECT_CLASS} aria-invalid={!!errors.categoryId} {...register("categoryId")}>
                <option value="">Select…</option>
                {categoryList.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
              <FieldError id="tx-category-error" message={errors.categoryId?.message} />
            </div>
          </>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="tx-note">Note (optional)</Label>
        <Textarea id="tx-note" rows={2} {...register("note")} />
        <FieldError id="tx-note-error" message={errors.note?.message} />
      </div>

      {type === "EXPENSE" ? (
        <InfoNote>
          The expense is also added to the Expenses list. To attach a receipt, record it on the Expenses page instead.
        </InfoNote>
      ) : null}
      <p className="text-xs text-muted-foreground">
        The amount can&apos;t be changed after saving. To correct it, reverse the transaction and record it again.
      </p>

      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={record.isPending}>
          Cancel
        </Button>
        <Button type="submit" disabled={record.isPending || (accounts.isSuccess && accountList.length === 0)}>
          {record.isPending ? "Saving…" : "Record"}
        </Button>
      </div>
    </form>
  );
}
