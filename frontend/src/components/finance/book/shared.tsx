"use client";

import { useRef, type ReactNode } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import type { FieldValues, Path, UseFormSetError } from "react-hook-form";
import { AlertCircle, Info, XIcon } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth/auth-context";
import { CAP } from "@/lib/auth/capabilities";
import { canManageFinance, canViewFinance, canViewPaymentDashboard } from "@/lib/auth/permissions";
import { useCapabilities } from "@/lib/api/my-permissions";
import { isApiClientError } from "@/lib/api/error";
import type { TransactionType } from "@/lib/api/finance-book";
import { cn } from "@/lib/utils";

/** Native `<select>` styling (matches `input.tsx`); native selects are keyboard- and test-friendly. */
export const SELECT_CLASS =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:bg-input/30";

/**
 * Every FIN-01 gate in one place. UX only: each answer decides what renders;
 * every endpoint re-checks the same capability server-side. Static-role
 * fallbacks: reads = `canViewFinance`, writes = `canManageFinance`, routing and
 * reconcile = Tenant Admin only.
 */
export function useFinanceGates() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const { can } = useCapabilities();
  const view = canViewFinance(role);
  const manage = canManageFinance(role);
  const tenantAdmin = role === "TENANT_ADMIN";
  return {
    incomeView: can(CAP.FINANCE_VIEW_INCOME_SUMMARY, view),
    budgetView: can(CAP.FINANCE_DASHBOARD_ANNUAL_STATS, view),
    bookView: can(CAP.FINANCE_DASHBOARD_TRANSACTIONS, view),
    schedulesView: can(CAP.FINANCE_VIEW_SCHEDULES, view),
    categoriesView: can(CAP.FINANCE_VIEW_CATEGORY, view),
    categoryAdd: can(CAP.FINANCE_ADD_CATEGORY, manage),
    categoryArchive: can([CAP.FINANCE_DELETE_CATEGORY, CAP.FINANCE_ADD_CATEGORY], manage),
    accountsManage: can(CAP.FINANCE_ACCOUNTS_MANAGE, manage),
    routing: can(CAP.FINANCE_ROUTING_CONFIGURE, tenantAdmin),
    record: can(CAP.FINANCE_RECORD_TRANSACTION, manage),
    adjust: can(CAP.FINANCE_ADJUST_ACCOUNT_BALANCE, manage),
    reverse: can(CAP.FINANCE_REVERSE_TRANSACTION, manage),
    schedulesManage: can(CAP.FINANCE_MANAGE_SCHEDULES, manage),
    payments: can([CAP.PAYMENTS_VIEW, CAP.FINANCE_DASHBOARD_PENDING_PAYMENTS], canViewPaymentDashboard(role)),
    teacherIncomeReport: can(CAP.REPORTS_TEACHER_INCOME, view),
    registrationsReport: can(CAP.REPORTS_REGISTRATIONS, view),
  };
}

export type FinanceGates = ReturnType<typeof useFinanceGates>;

function newKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * One idempotency key per payload: re-submitting the same payload (e.g. after
 * a network error) reuses the key so the server posts it at most once; any
 * change to the payload gets a fresh key. Dialog bodies unmount on close, so
 * every open starts with a fresh key.
 */
export function useIdempotencyKey() {
  const last = useRef<{ payload: string; key: string } | null>(null);
  return (payload: unknown): string => {
    const signature = JSON.stringify(payload);
    if (last.current?.payload !== signature) last.current = { payload: signature, key: newKey() };
    return last.current.key;
  };
}

/**
 * Maps a server error onto form fields (400 `fieldErrors`) where possible and
 * returns the form-level message (403/404/409 or unmapped fields), or `null`
 * when every error landed on a field.
 */
export function applyServerError<T extends FieldValues>(
  error: unknown,
  fields: readonly Path<T>[],
  setError: UseFormSetError<T>,
  fallback: string
): string | null {
  if (!isApiClientError(error)) return fallback;
  if (error.status === 403) return "You don't have permission to do this.";
  const known = fields as readonly string[];
  const mapped = error.fieldErrors.filter((f) => known.includes(f.field));
  mapped.forEach((f) => setError(f.field as Path<T>, { type: "server", message: f.message }));
  const unmapped = error.fieldErrors.filter((f) => !known.includes(f.field));
  if (mapped.length > 0 && unmapped.length === 0) return null;
  return unmapped[0] ? `${error.message}: ${unmapped[0].message}` : error.message;
}

export function errorMessage(error: unknown, fallback = "Something went wrong. Please try again."): string {
  if (!isApiClientError(error)) return fallback;
  if (error.status === 403) return "You don't have permission to do this.";
  return error.message;
}

export function FormAlert({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <Alert variant="destructive">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}

export function FieldError({ id, message }: { id: string; message?: string | null }) {
  if (!message) return null;
  return (
    <p id={id} role="alert" className="text-xs text-destructive">
      {message}
    </p>
  );
}

export function InfoNote({ children, className, testId }: { children: ReactNode; className?: string; testId?: string }) {
  return (
    <div
      role="note"
      data-testid={testId}
      className={cn(
        "flex items-start gap-2 rounded-lg border border-blue-200 bg-blue-50 px-3 py-2 text-sm text-blue-900 dark:border-blue-900 dark:bg-blue-950 dark:text-blue-100",
        className
      )}
    >
      <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <div className="min-w-0">{children}</div>
    </div>
  );
}

/**
 * Modal shell for the Finance dialogs: full-screen on mobile, centered card
 * from `sm`. The body mounts only while open (fresh form state and
 * idempotency key every time). Dismissal is blocked while `busy`.
 */
export function FinanceDialog({
  open,
  onOpenChange,
  title,
  description,
  busy = false,
  testId,
  children,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  busy?: boolean;
  testId?: string;
  children: ReactNode;
}) {
  return (
    <DialogPrimitive.Root
      open={open}
      onOpenChange={(next) => {
        if (!next && busy) return;
        onOpenChange(next);
      }}
    >
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/40 transition-opacity duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0" />
        <DialogPrimitive.Popup
          data-testid={testId}
          className="fixed inset-0 z-50 flex flex-col bg-popover text-sm text-popover-foreground shadow-lg outline-none transition duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0 sm:inset-auto sm:top-1/2 sm:left-1/2 sm:max-h-[calc(100dvh-2rem)] sm:w-[calc(100%-2rem)] sm:max-w-xl sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-xl sm:border sm:border-border"
        >
          <div className="flex items-start justify-between gap-3 border-b border-border px-4 py-3">
            <div>
              <DialogPrimitive.Title className="text-base font-semibold text-foreground">{title}</DialogPrimitive.Title>
              {description ? (
                <DialogPrimitive.Description className="text-sm text-muted-foreground">
                  {description}
                </DialogPrimitive.Description>
              ) : null}
            </div>
            <DialogPrimitive.Close
              disabled={busy}
              render={<Button type="button" variant="ghost" size="icon-sm" aria-label="Close" />}
            >
              <XIcon aria-hidden="true" />
            </DialogPrimitive.Close>
          </div>
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-4">{open ? children : null}</div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

const TYPE_LABEL: Record<TransactionType, string> = {
  OPENING: "Opening",
  INCOME: "Income",
  EXPENSE: "Expense",
  TRANSFER: "Transfer",
  ADJUSTMENT: "Adjustment",
  REFUND: "Refund",
  REVERSAL: "Reversal",
};

const TYPE_CLASS: Record<TransactionType, string> = {
  INCOME: "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-200",
  EXPENSE: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200",
  TRANSFER: "bg-blue-100 text-blue-800 dark:bg-blue-950 dark:text-blue-200",
  ADJUSTMENT: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
  REFUND: "border-red-500 text-red-700 dark:text-red-300",
  REVERSAL: "bg-muted text-muted-foreground",
  OPENING: "bg-muted text-muted-foreground",
};

/** Type chip: the text label is always present (never colour alone). */
export function TransactionTypeBadge({ type }: { type: TransactionType }) {
  return (
    <Badge variant="outline" className={cn(type === "REFUND" ? "" : "border-transparent", TYPE_CLASS[type])}>
      {TYPE_LABEL[type]}
    </Badge>
  );
}

export function transactionTypeLabel(type: TransactionType): string {
  return TYPE_LABEL[type];
}

/** Today's calendar date in the browser (UX default only; the server validates against the tenant's zone). */
export function todayIso(): string {
  const now = new Date();
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 10);
}

/** Positive money with up to 2 decimals (UX check; the server re-validates). */
export const POSITIVE_MONEY = /^\d{1,12}(\.\d{1,2})?$/;

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
  if (!y || !m || !d) return iso;
  return new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeZone: "UTC" }).format(
    new Date(Date.UTC(y, m - 1, d))
  );
}
