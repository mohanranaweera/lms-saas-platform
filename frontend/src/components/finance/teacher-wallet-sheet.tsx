"use client";

import { useRef, useState } from "react";
import { useForm, useWatch, type FieldValues, type Path, type UseFormSetError } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Pencil } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Tabs } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { SignedAmount } from "@/components/finance/signed-amount";
import { useAuth } from "@/lib/auth/auth-context";
import { CAP } from "@/lib/auth/capabilities";
import { canManageFinance } from "@/lib/auth/permissions";
import { useCapabilities } from "@/lib/api/my-permissions";
import { isApiClientError } from "@/lib/api/error";
import { formatMoney } from "@/lib/format";
import {
  centsOf,
  toCents,
  useAdjustWallet,
  useCreateWallet,
  useTeacherWallet,
  useWalletEntries,
  useWalletPosting,
  type TeacherWallet,
  type WalletEntry,
  type WalletEntryKind,
} from "@/lib/api/teacher-wallet";
import {
  walletAdjustSchema,
  walletCreateSchema,
  walletPostingSchema,
  type WalletAdjustFormValues,
  type WalletCreateFormValues,
  type WalletPostingFormValues,
} from "@/lib/validation/finance";

/**
 * TEACHER-WALLET-01 — Teachers → Show Wallet. Opens one teacher's wallet in a
 * side sheet (no navigation; full width on narrow screens). Balance = amount
 * payable to the teacher. Everything here is UX only: every action is
 * re-authorized and validated by the backend, which also rejects
 * account-linked entries until Finance Accounts exist.
 */

export interface WalletTeacher {
  /** The teacher's tenant user id (what settlements and wallets key on). */
  id: string;
  name: string;
}

const ACCOUNTS_UNAVAILABLE =
  "Finance accounts are not set up yet, so entries are recorded in the wallet only. No Finance entry is created.";

const SELECT_CLASS =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60 dark:bg-input/30";

function money(cents: number, currency: string): string {
  return formatMoney(cents / 100, currency);
}

function newKey(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * One idempotency key per payload: resubmitting the same payload (e.g. after a
 * network error) reuses the key, so the server posts it at most once; any
 * change to the payload gets a fresh key.
 */
function useIdempotencyKey() {
  const last = useRef<{ payload: string; key: string } | null>(null);
  return (payload: unknown): string => {
    const signature = JSON.stringify(payload);
    if (last.current?.payload !== signature) last.current = { payload: signature, key: newKey() };
    return last.current.key;
  };
}

/**
 * Maps a server error onto the form's fields where possible; returns the
 * message to show in the form-level alert (or `null` when every error landed
 * on a field). Inputs are kept so the user can correct and resubmit.
 */
function applyServerError<T extends FieldValues>(
  error: unknown,
  fields: readonly Path<T>[],
  setError: UseFormSetError<T>,
  fallback: string
): string | null {
  if (!isApiClientError(error)) return fallback;
  if (error.status === 403) return "You don't have permission to do this.";
  const mapped = error.fieldErrors.filter((f) => (fields as readonly string[]).includes(f.field));
  mapped.forEach((f) => setError(f.field as Path<T>, { type: "server", message: f.message }));
  const unmapped = error.fieldErrors.filter((f) => !(fields as readonly string[]).includes(f.field));
  if (mapped.length > 0 && unmapped.length === 0) return null;
  return unmapped[0] ? `${error.message}: ${unmapped[0].message}` : error.message;
}

function FormAlert({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <Alert variant="destructive">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  );
}

function FieldMessage({ id, message }: { id: string; message?: string }) {
  return message ? (
    <p id={id} className="text-xs text-destructive">
      {message}
    </p>
  ) : null;
}

export function TeacherWalletSheet({
  teacher,
  open,
  onOpenChange,
}: {
  teacher: WalletTeacher | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-md" data-testid="teacher-wallet-sheet">
        {/* Keyed by teacher: switching teachers resets every input and notice. */}
        {teacher ? <WalletBody key={teacher.id} teacher={teacher} onClose={() => onOpenChange(false)} /> : null}
      </SheetContent>
    </Sheet>
  );
}

interface Permissions {
  create: boolean;
  payIn: boolean;
  payOut: boolean;
  adjust: boolean;
}

function WalletBody({ teacher, onClose }: { teacher: WalletTeacher; onClose: () => void }) {
  const { session } = useAuth();
  const { can } = useCapabilities();
  const legacy = canManageFinance(session?.role ?? null);
  const permissions: Permissions = {
    create: can(CAP.TEACHERS_WALLET_CREATE, legacy),
    payIn: can(CAP.TEACHERS_WALLET_PAY_IN, legacy),
    payOut: can(CAP.TEACHERS_WALLET_PAY_OUT, legacy),
    adjust: can(CAP.TEACHERS_WALLET_ADJUST, legacy),
  };
  const wallet = useTeacherWallet(teacher.id);
  const [notice, setNotice] = useState("");
  const currency = wallet.data?.currency ?? "";

  return (
    <>
      <SheetHeader className="pr-12">
        <SheetTitle>{teacher.name} — Wallet</SheetTitle>
        <SheetDescription>
          {currency ? `Amounts in ${currency}. ` : ""}The balance is the amount payable to the teacher.
        </SheetDescription>
      </SheetHeader>

      <div className="flex flex-col gap-4 px-4 pb-6" aria-busy={wallet.isFetching}>
        <LiveRegion message={notice} />
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}

        {wallet.isPending ? (
          <p className="text-sm text-muted-foreground">Loading wallet…</p>
        ) : wallet.isError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription className="flex flex-col items-start gap-2">
              {isApiClientError(wallet.error) && wallet.error.status === 403
                ? "You don't have permission to view this wallet."
                : isApiClientError(wallet.error)
                  ? wallet.error.message
                  : "The wallet could not be loaded."}
              <Button type="button" size="sm" variant="outline" onClick={() => void wallet.refetch()}>
                Try again
              </Button>
            </AlertDescription>
          </Alert>
        ) : !wallet.data.exists ? (
          <CreateWalletForm
            teacher={teacher}
            currency={wallet.data.currency}
            canCreate={permissions.create}
            onCancel={onClose}
            onCreated={() => setNotice("Wallet created.")}
          />
        ) : (
          <ExistingWallet
            teacher={teacher}
            wallet={wallet.data}
            refreshing={wallet.isFetching}
            permissions={permissions}
            onNotice={setNotice}
          />
        )}
      </div>
    </>
  );
}

function CreateWalletForm({
  teacher,
  currency,
  canCreate,
  onCancel,
  onCreated,
}: {
  teacher: WalletTeacher;
  currency: string;
  canCreate: boolean;
  onCancel: () => void;
  onCreated: () => void;
}) {
  const create = useCreateWallet(teacher.id);
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<WalletCreateFormValues>({
    resolver: zodResolver(walletCreateSchema),
    defaultValues: { openingBalance: "0" },
  });
  const opening = useWatch({ control, name: "openingBalance" });
  const cents = toCents(opening ?? "");

  if (!canCreate) {
    return <p className="text-sm text-muted-foreground">{teacher.name} has no wallet yet.</p>;
  }

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await create.mutateAsync({ openingBalance: values.openingBalance, currency });
      onCreated();
    } catch (error) {
      setFormError(applyServerError(error, ["openingBalance"], setError, "The wallet could not be created."));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4" aria-label="Create wallet">
      <p className="text-sm text-muted-foreground">
        {teacher.name} has no wallet yet. The initial balance is recorded once as the opening balance. It is not a
        payment, and zero is allowed.
      </p>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="wallet-opening">Initial balance ({currency})</Label>
        <Input
          id="wallet-opening"
          inputMode="decimal"
          aria-invalid={Boolean(errors.openingBalance)}
          aria-describedby="wallet-opening-hint wallet-opening-error"
          {...register("openingBalance")}
        />
        <p id="wallet-opening-hint" className="text-xs text-muted-foreground">
          {cents !== null ? `Opening balance: ${money(cents, currency)}. No cash movement is recorded.` : null}
        </p>
        <FieldMessage id="wallet-opening-error" message={errors.openingBalance?.message} />
      </div>
      <FormAlert message={formError} />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? "Creating…" : "Create Wallet"}
        </Button>
      </div>
    </form>
  );
}

function ExistingWallet({
  teacher,
  wallet,
  refreshing,
  permissions,
  onNotice,
}: {
  teacher: WalletTeacher;
  wallet: TeacherWallet;
  refreshing: boolean;
  permissions: Permissions;
  onNotice: (message: string) => void;
}) {
  const [tab, setTab] = useState<"payout" | "payin">("payout");
  const [editing, setEditing] = useState(false);
  const balanceCents = centsOf(wallet.balance ?? 0);

  return (
    <div className="flex flex-col gap-4">
      <section aria-labelledby="wallet-balance-label" className="rounded-lg border border-border bg-card p-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p id="wallet-balance-label" className="text-xs font-medium uppercase text-muted-foreground">
              Amount payable
            </p>
            <p className="text-2xl font-semibold text-foreground" data-testid="wallet-balance">
              <SignedAmount value={wallet.balance ?? 0} currency={wallet.currency} />
            </p>
          </div>
          {permissions.adjust && !editing ? (
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              aria-label={`Adjust ${teacher.name}'s wallet balance`}
              onClick={() => setEditing(true)}
            >
              <Pencil aria-hidden="true" />
            </Button>
          ) : null}
        </div>
        <dl className="mt-2 grid grid-cols-2 gap-x-3 text-xs text-muted-foreground">
          <dt>Unpaid settlements</dt>
          <dd className="text-right">{formatMoney(wallet.unpaidSettlements ?? 0, wallet.currency)}</dd>
          <dt>Wallet entries</dt>
          <dd className="text-right">
            <SignedAmount value={wallet.walletEntries ?? 0} currency={wallet.currency} />
          </dd>
        </dl>
        {refreshing ? <p className="mt-2 text-xs text-muted-foreground">Refreshing balance…</p> : null}
        {editing ? (
          <AdjustBalanceForm
            teacherId={teacher.id}
            wallet={wallet}
            balanceCents={balanceCents}
            onDone={(message) => {
              setEditing(false);
              if (message) onNotice(message);
            }}
          />
        ) : null}
      </section>

      <Tabs
        aria-label="Wallet entries"
        value={tab}
        onValueChange={(value) => setTab(value as "payout" | "payin")}
        items={[
          {
            value: "payout",
            label: "Pay Out",
            content: (
              <PostingPanel
                teacherId={teacher.id}
                wallet={wallet}
                balanceCents={balanceCents}
                kind="PAY_OUT"
                allowed={permissions.payOut}
                active={tab === "payout"}
                onNotice={onNotice}
              />
            ),
          },
          {
            value: "payin",
            label: "Pay In",
            content: (
              <PostingPanel
                teacherId={teacher.id}
                wallet={wallet}
                balanceCents={balanceCents}
                kind="PAY_IN"
                allowed={permissions.payIn}
                active={tab === "payin"}
                onNotice={onNotice}
              />
            ),
          },
        ]}
      />
    </div>
  );
}

function AdjustBalanceForm({
  teacherId,
  wallet,
  balanceCents,
  onDone,
}: {
  teacherId: string;
  wallet: TeacherWallet;
  balanceCents: number;
  onDone: (message: string | null) => void;
}) {
  const adjust = useAdjustWallet(teacherId);
  const keyFor = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  // The balance the user is adjusting from; if the server balance moved, the request is a 409 (stale).
  const [seenCents] = useState(balanceCents);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<WalletAdjustFormValues>({
    resolver: zodResolver(walletAdjustSchema),
    defaultValues: { targetBalance: (balanceCents / 100).toFixed(2), reason: "" },
  });
  const target = useWatch({ control, name: "targetBalance" });
  const targetCents = toCents(target ?? "");
  const difference = targetCents === null ? null : targetCents - seenCents;
  const stale = seenCents !== balanceCents;

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    if (difference === 0) {
      setError("targetBalance", { type: "manual", message: "This is already the current balance." });
      return;
    }
    const payload = { targetBalance: values.targetBalance, expectedBalance: seenCents / 100, reason: values.reason };
    try {
      await adjust.mutateAsync({ ...payload, idempotencyKey: keyFor(payload) });
      onDone("Balance adjusted.");
    } catch (error) {
      setFormError(
        applyServerError(error, ["targetBalance", "reason"], setError, "The balance could not be adjusted.")
      );
    }
  });

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="mt-4 flex flex-col gap-3 border-t border-border pt-4"
      aria-label="Adjust balance"
    >
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="wallet-target">New balance ({wallet.currency})</Label>
        <Input
          id="wallet-target"
          inputMode="decimal"
          aria-invalid={Boolean(errors.targetBalance)}
          aria-describedby="wallet-target-effect wallet-target-error"
          {...register("targetBalance")}
        />
        <p id="wallet-target-effect" className="text-xs text-muted-foreground" data-testid="adjust-effect">
          {difference === null || difference === 0
            ? null
            : `${money(seenCents, wallet.currency)} → ${money(targetCents!, wallet.currency)} (adjustment ${difference > 0 ? "+" : "−"}${money(Math.abs(difference), wallet.currency)}). Recorded as a new entry; history is kept.`}
        </p>
        <FieldMessage id="wallet-target-error" message={errors.targetBalance?.message} />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="wallet-adjust-reason">Reason</Label>
        <Textarea
          id="wallet-adjust-reason"
          maxLength={500}
          aria-invalid={Boolean(errors.reason)}
          aria-describedby="wallet-adjust-reason-error"
          {...register("reason")}
        />
        <FieldMessage id="wallet-adjust-reason-error" message={errors.reason?.message} />
      </div>
      {stale ? (
        <p className="text-xs text-destructive">
          The balance changed while you were editing. Cancel and reopen to adjust from the new balance.
        </p>
      ) : null}
      <FormAlert message={formError} />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={() => onDone(null)}>
          Cancel
        </Button>
        <Button type="submit" disabled={adjust.isPending}>
          {adjust.isPending ? "Saving…" : "Save adjustment"}
        </Button>
      </div>
    </form>
  );
}

function formatPeriod(start: string | null, end: string | null): string {
  return start && end ? ` · ${start} to ${end}` : "";
}

/** Read-only settlement line titles (the settlement itself is managed on Teacher Payouts). */
function settlementLabel(entry: WalletEntry): string {
  const period = formatPeriod(entry.periodStart, entry.periodEnd);
  if (entry.event === "SETTLEMENT_PAID") return `Settlement paid${period}`;
  if (entry.event === "SETTLEMENT_ADJUSTED") return "Settlement adjustment";
  return `Settlement earned${period}`;
}

const POSTING_FIELDS = ["amount", "description", "accountId", "categoryId"] as const;

function PostingPanel({
  teacherId,
  wallet,
  balanceCents,
  kind,
  allowed,
  active,
  onNotice,
}: {
  teacherId: string;
  wallet: TeacherWallet;
  balanceCents: number;
  kind: Extract<WalletEntryKind, "PAY_IN" | "PAY_OUT">;
  allowed: boolean;
  active: boolean;
  onNotice: (message: string) => void;
}) {
  const isPayOut = kind === "PAY_OUT";
  const posting = useWalletPosting(teacherId, isPayOut ? "pay-outs" : "pay-ins");
  const entries = useWalletEntries(teacherId, kind, active);
  const keyFor = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const idPrefix = isPayOut ? "payout" : "payin";
  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    reset,
    formState: { errors },
  } = useForm<WalletPostingFormValues>({
    resolver: zodResolver(walletPostingSchema),
    defaultValues: { amount: "", description: "", accountId: "", categoryId: "" },
  });
  const [amount, accountId] = useWatch({ control, name: ["amount", "accountId"] });
  const cents = toCents(amount ?? "");
  const validAmount = cents !== null && cents > 0;
  const afterCents = validAmount ? balanceCents + (isPayOut ? -cents : cents) : null;
  const overdraw = afterCents !== null && afterCents < 0;
  const accountField = register("accountId", {
    // A category is only valid for its account: clear it whenever the account changes.
    onChange: () => setValue("categoryId", ""),
  });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    if (overdraw) {
      setError("amount", { type: "manual", message: `This exceeds the amount payable (${money(balanceCents, wallet.currency)}).` });
      return;
    }
    const payload = {
      amount: values.amount,
      description: values.description,
      accountId: values.accountId || null,
      categoryId: values.accountId && values.categoryId ? values.categoryId : null,
      expectedBalance: balanceCents / 100,
      currency: wallet.currency,
    };
    try {
      const result = await posting.mutateAsync({ ...payload, idempotencyKey: keyFor(payload) });
      reset();
      onNotice(
        `${isPayOut ? "Pay out" : "Pay in"} of ${formatMoney(Math.abs(result.entry.amount), wallet.currency)} recorded. New balance ${formatMoney(result.entry.balanceAfter ?? result.wallet.balance ?? 0, wallet.currency)}.`
      );
    } catch (error) {
      setFormError(applyServerError(error, POSTING_FIELDS, setError, "The entry could not be recorded."));
    }
  });

  return (
    <div className="flex flex-col gap-5">
      {allowed ? (
        <form
          onSubmit={onSubmit}
          noValidate
          className="flex flex-col gap-3"
          aria-label={isPayOut ? "Record pay out" : "Record pay in"}
        >
          <p className="text-xs text-muted-foreground">
            {isPayOut
              ? "Records money already paid to the teacher. It reduces the amount payable and does not send any funds."
              : "Increases the amount payable to the teacher."}
          </p>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-amount`}>Amount ({wallet.currency})</Label>
            <Input
              id={`${idPrefix}-amount`}
              inputMode="decimal"
              aria-invalid={Boolean(errors.amount)}
              aria-describedby={`${idPrefix}-effect ${idPrefix}-amount-error`}
              {...register("amount")}
            />
            <FieldMessage id={`${idPrefix}-amount-error`} message={errors.amount?.message} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-description`}>Description</Label>
            <Textarea
              id={`${idPrefix}-description`}
              maxLength={500}
              aria-invalid={Boolean(errors.description)}
              aria-describedby={`${idPrefix}-description-error`}
              {...register("description")}
            />
            <FieldMessage id={`${idPrefix}-description-error`} message={errors.description?.message} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-account`}>Expense Account (optional)</Label>
            <select
              id={`${idPrefix}-account`}
              disabled
              aria-describedby={`${idPrefix}-account-hint`}
              className={SELECT_CLASS}
              {...accountField}
            >
              <option value="">None (wallet only)</option>
            </select>
            <p id={`${idPrefix}-account-hint`} className="text-xs text-muted-foreground">
              {ACCOUNTS_UNAVAILABLE}
            </p>
            <FieldMessage id={`${idPrefix}-account-error`} message={errors.accountId?.message} />
          </div>
          {accountId ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${idPrefix}-category`}>Category (optional)</Label>
              <select id={`${idPrefix}-category`} className={SELECT_CLASS} {...register("categoryId")}>
                <option value="">No category</option>
              </select>
              <FieldMessage id={`${idPrefix}-category-error`} message={errors.categoryId?.message} />
            </div>
          ) : null}

          <div
            id={`${idPrefix}-effect`}
            className="rounded-md bg-muted px-3 py-2 text-sm"
            data-testid={`${idPrefix}-effect`}
          >
            {afterCents === null ? (
              <span className="text-muted-foreground">Enter an amount to see the effect on the balance.</span>
            ) : overdraw ? (
              <span className="text-destructive">
                This exceeds the amount payable ({money(balanceCents, wallet.currency)}).
              </span>
            ) : (
              <>
                <span>
                  Balance {money(balanceCents, wallet.currency)} → {money(afterCents, wallet.currency)}
                </span>
                <span className="block text-xs text-muted-foreground">
                  Wallet only — no Finance entry will be recorded.
                </span>
              </>
            )}
          </div>

          <FormAlert message={formError} />
          <Button type="submit" className="self-end" disabled={overdraw || posting.isPending}>
            {posting.isPending ? "Recording…" : isPayOut ? "Record Pay Out" : "Record Pay In"}
          </Button>
        </form>
      ) : (
        <p className="text-sm text-muted-foreground">
          You can view {isPayOut ? "payouts" : "pay-ins"} but not record them.
        </p>
      )}

      <section aria-label={isPayOut ? "Recent payouts" : "Recent pay-ins"} className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-foreground">{isPayOut ? "Recent payouts" : "Recent pay-ins"}</h3>
        {entries.isPending ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : entries.isError ? (
          <p role="alert" className="text-sm text-destructive">
            Activity could not be loaded.
          </p>
        ) : entries.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {isPayOut ? "No payouts have been recorded for this teacher." : "No pay-ins have been recorded for this teacher."}
          </p>
        ) : (
          <ul className="flex flex-col divide-y divide-border rounded-lg border border-border">
            {entries.data.map((entry) => (
              <li key={`${entry.event}-${entry.id}`} className="flex flex-col gap-0.5 px-3 py-2 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <time dateTime={entry.createdAt} className="text-muted-foreground">
                    {new Date(entry.createdAt).toLocaleDateString()}
                  </time>
                  <SignedAmount value={entry.amount} currency={entry.currency} />
                </div>
                {entry.source === "SETTLEMENT" ? (
                  <p className="text-foreground">
                    {settlementLabel(entry)}
                    <span className="ml-1.5 rounded border border-border px-1 text-xs text-muted-foreground">
                      Teacher Payouts
                    </span>
                  </p>
                ) : null}
                {entry.description ? <p className="break-words text-foreground">{entry.description}</p> : null}
                <p className="text-xs text-muted-foreground">
                  {entry.balanceBefore !== null && entry.balanceAfter !== null
                    ? `Balance ${formatMoney(entry.balanceBefore, entry.currency)} → ${formatMoney(entry.balanceAfter, entry.currency)}`
                    : "Recorded on Teacher Payouts; included in the balance automatically"}
                  {entry.payoutReference ? ` · Ref ${entry.payoutReference}` : ""}
                  {entry.accountName ? ` · ${entry.accountName}` : ""}
                  {entry.categoryName ? ` / ${entry.categoryName}` : ""}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
