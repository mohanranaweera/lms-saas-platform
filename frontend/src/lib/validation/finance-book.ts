import { z } from "zod";

/**
 * FIN-01 account-book form schemas (`docs/api/finance-expense-management.md`,
 * "Finance account book"). UX convenience only: the backend re-validates
 * every request and its 400 `fieldErrors` are mapped back onto these same
 * field names; server-only rules (unique names, stale balances, archived or
 * foreign accounts) arrive as 409s.
 */

const POSITIVE_MONEY = /^\d{1,12}(\.\d{1,2})?$/;
const SIGNED_MONEY = /^-?\d{1,12}(\.\d{1,2})?$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

const note = z.string().max(500, "Up to 500 characters.");
const reason = (message: string) =>
  z
    .string()
    .refine((v) => v.trim().length >= 3, message)
    .refine((v) => v.length <= 500, "Up to 500 characters.");
const positiveAmount = z
  .string()
  .regex(POSITIVE_MONEY, "Enter a positive amount with up to 2 decimals.")
  .refine((v) => Number(v) > 0, "Enter an amount greater than zero.");

export const accountSchema = z.object({
  name: z
    .string()
    .refine((v) => v.trim().length > 0, "Enter a name.")
    .refine((v) => v.length <= 120, "Up to 120 characters."),
  description: note,
  openingBalance: z.string().regex(POSITIVE_MONEY, "Enter 0 or a positive amount with up to 2 decimals."),
});
export type AccountFormValues = z.infer<typeof accountSchema>;

export function adjustmentSchema(currentBalance: number) {
  return z.object({
    targetBalance: z
      .string()
      .regex(SIGNED_MONEY, "Enter an amount with up to 2 decimals.")
      .refine((v) => Number(v) !== currentBalance, "The new balance equals the current balance."),
    reason: reason("Explain why the balance is being adjusted."),
  });
}
export type AdjustmentFormValues = z.infer<ReturnType<typeof adjustmentSchema>>;

const recordableType = z.enum(["INCOME", "EXPENSE", "TRANSFER"]);

/** Shared account/category rules: transfers need two different accounts; income and expense need one account and a category. */
function requireAccounts(
  value: { type: z.infer<typeof recordableType>; accountId: string; fromAccountId: string; toAccountId: string; categoryId: string },
  ctx: z.RefinementCtx
) {
  if (value.type === "TRANSFER") {
    if (!value.fromAccountId) ctx.addIssue({ code: "custom", path: ["fromAccountId"], message: "Choose the account the money leaves." });
    if (!value.toAccountId) ctx.addIssue({ code: "custom", path: ["toAccountId"], message: "Choose the account the money goes to." });
    else if (value.toAccountId === value.fromAccountId)
      ctx.addIssue({ code: "custom", path: ["toAccountId"], message: "Choose two different accounts." });
    return;
  }
  if (!value.accountId) ctx.addIssue({ code: "custom", path: ["accountId"], message: "Choose an account." });
  if (!value.categoryId) ctx.addIssue({ code: "custom", path: ["categoryId"], message: "Choose a category." });
}

export const recordTransactionSchema = z
  .object({
    type: recordableType,
    amount: positiveAmount,
    transactionDate: z.string().regex(ISO_DATE, "Choose a date."),
    note,
    categoryId: z.string(),
    accountId: z.string(),
    fromAccountId: z.string(),
    toAccountId: z.string(),
  })
  .superRefine(requireAccounts);
export type RecordTransactionFormValues = z.infer<typeof recordTransactionSchema>;

export const editTransactionSchema = z.object({ note, categoryId: z.string() });
export type EditTransactionFormValues = z.infer<typeof editTransactionSchema>;

export const reverseTransactionSchema = z.object({ reason: reason("Explain why this is being reversed.") });
export type ReverseTransactionFormValues = z.infer<typeof reverseTransactionSchema>;

export const scheduleSchema = z
  .object({
    name: z
      .string()
      .refine((v) => v.trim().length > 0, "Enter a name.")
      .refine((v) => v.length <= 120, "Up to 120 characters."),
    type: recordableType,
    amount: positiveAmount,
    accountId: z.string(),
    fromAccountId: z.string(),
    toAccountId: z.string(),
    categoryId: z.string(),
    note,
    scheduleType: z.enum(["ONE_TIME", "MONTHLY"]),
    scheduledDate: z.string(),
    dayOfMonth: z.string(),
  })
  .superRefine((value, ctx) => {
    requireAccounts(value, ctx);
    if (value.scheduleType === "ONE_TIME" && !ISO_DATE.test(value.scheduledDate))
      ctx.addIssue({ code: "custom", path: ["scheduledDate"], message: "Choose a date." });
    if (value.scheduleType === "MONTHLY") {
      const day = Number(value.dayOfMonth);
      if (!/^\d{1,2}$/.test(value.dayOfMonth) || day < 1 || day > 31)
        ctx.addIssue({ code: "custom", path: ["dayOfMonth"], message: "Enter a day from 1 to 31." });
    }
  });
export type ScheduleFormValues = z.infer<typeof scheduleSchema>;

export const reconcileSchema = z.object({ from: z.string().regex(ISO_DATE, "Choose the date to start from.") });
export type ReconcileFormValues = z.infer<typeof reconcileSchema>;
