"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Archive, ArchiveRestore, Landmark, Pencil, Plus, Settings } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FinanceHubFrame } from "@/components/finance/finance-tabs";
import {
  FieldError,
  FinanceDialog,
  FormAlert,
  InfoNote,
  SELECT_CLASS,
  applyServerError,
  errorMessage,
  useFinanceGates,
  useIdempotencyKey,
} from "@/components/finance/book/shared";
import { formatMoney } from "@/lib/format";
import {
  useAdjustAccount,
  useCreateAccount,
  useFinanceAccounts,
  useFinanceRouting,
  useSaveRouting,
  useSetAccountArchived,
  useUpdateAccount,
  type FinanceAccount,
  type RoutingInput,
  type RoutingMethodCode,
} from "@/lib/api/finance-book";
import {
  accountSchema,
  adjustmentSchema,
  type AccountFormValues,
  type AdjustmentFormValues,
} from "@/lib/validation/finance-book";

const METHOD_LABEL: Record<RoutingMethodCode, string> = {
  CASH: "Cash",
  CARD: "Card",
  BANK_TRANSFER: "Bank transfer",
  GATEWAY: "Online gateway",
  MANUAL_SLIP: "Bank slip",
  OTHER: "Other",
};

/**
 * FIN-01 Accounts tab (Klass parity): account cards with the current balance,
 * New Account, Edit (name/description only), balance adjustment (pencil,
 * reason required, stale balance = 409), archive/restore, and payment-method
 * routing ("Configure", Tenant Admin by default). Balances are server
 * figures; nothing here computes one.
 */
export default function FinanceAccountsPage() {
  const gates = useFinanceGates();
  const [showArchived, setShowArchived] = useState(false);
  const [dialog, setDialog] = useState<
    | { kind: "new" }
    | { kind: "edit"; account: FinanceAccount }
    | { kind: "adjust"; account: FinanceAccount }
    | { kind: "routing" }
    | null
  >(null);
  const query = useFinanceAccounts(showArchived);
  const close = () => setDialog(null);

  return (
    <FinanceHubFrame
      title="Accounts"
      description="Cash, bank and card accounts. Confirmed student payments are posted to the account routed for their payment method."
      actions={
        <>
          {gates.routing ? (
            <Button type="button" variant="outline" onClick={() => setDialog({ kind: "routing" })}>
              <Settings aria-hidden="true" />
              Configure
            </Button>
          ) : null}
          {gates.accountsManage ? (
            <Button type="button" onClick={() => setDialog({ kind: "new" })}>
              <Plus aria-hidden="true" />
              New account
            </Button>
          ) : null}
        </>
      }
    >
      <div className="flex items-center gap-2">
        <Switch id="accounts-show-archived" checked={showArchived} onCheckedChange={setShowArchived} />
        <Label htmlFor="accounts-show-archived">Show archived accounts</Label>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading accounts…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: "No accounts yet",
          description: gates.accountsManage
            ? "Create an account (for example Cash in hand or Bank) to start keeping the book."
            : "Your finance team hasn't created any accounts yet.",
          action: gates.accountsManage ? { label: "New account", onClick: () => setDialog({ kind: "new" }) } : undefined,
        }}
      >
        {(accounts) => (
          <ul className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3" aria-label="Finance accounts">
            {accounts.map((account) => (
              <li key={account.id}>
                <AccountCard
                  account={account}
                  canManage={gates.accountsManage}
                  canAdjust={gates.adjust}
                  onEdit={() => setDialog({ kind: "edit", account })}
                  onAdjust={() => setDialog({ kind: "adjust", account })}
                />
              </li>
            ))}
          </ul>
        )}
      </QueryStateBoundary>

      <FinanceDialog
        open={dialog?.kind === "new" || dialog?.kind === "edit"}
        onOpenChange={(open) => !open && close()}
        title={dialog?.kind === "edit" ? `Edit ${dialog.account.name}` : "New account"}
        testId="account-dialog"
      >
        <AccountForm account={dialog?.kind === "edit" ? dialog.account : null} onDone={close} />
      </FinanceDialog>
      <FinanceDialog
        open={dialog?.kind === "adjust"}
        onOpenChange={(open) => !open && close()}
        title="Adjust balance"
        description="Records an adjustment entry for the difference. Past entries are never changed."
        testId="adjust-dialog"
      >
        {dialog?.kind === "adjust" ? <AdjustForm account={dialog.account} onDone={close} /> : null}
      </FinanceDialog>
      <FinanceDialog
        open={dialog?.kind === "routing"}
        onOpenChange={(open) => !open && close()}
        title="Payment account configuration"
        testId="routing-dialog"
      >
        <RoutingForm onDone={close} />
      </FinanceDialog>
    </FinanceHubFrame>
  );
}

function AccountCard({
  account,
  canManage,
  canAdjust,
  onEdit,
  onAdjust,
}: {
  account: FinanceAccount;
  canManage: boolean;
  canAdjust: boolean;
  onEdit: () => void;
  onAdjust: () => void;
}) {
  const archive = useSetAccountArchived();
  const [error, setError] = useState<string | null>(null);
  return (
    <Card data-testid={`account-card-${account.id}`} className="h-full">
      <CardContent className="flex flex-col gap-3">
        <div className="flex items-start gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Landmark className="size-5" aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="truncate text-base font-semibold text-foreground">{account.name}</h3>
            <p className="truncate text-sm text-muted-foreground">{account.description ?? "No description"}</p>
          </div>
          {account.archived ? <Badge variant="outline">Archived</Badge> : null}
        </div>
        <div>
          <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">Current balance</p>
          <div className="flex items-center gap-1">
            <p className="text-2xl font-semibold text-primary tabular-nums" data-testid="account-balance">
              {formatMoney(account.balance, account.currency)}
            </p>
            {canAdjust && !account.archived ? (
              <Button type="button" variant="ghost" size="icon-sm" aria-label={`Adjust balance of ${account.name}`} onClick={onAdjust}>
                <Pencil aria-hidden="true" />
              </Button>
            ) : null}
          </div>
        </div>
        {account.mappedMethods.length > 0 ? (
          <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            <span>Receives:</span>
            {account.mappedMethods.map((method) => (
              <Badge key={method} variant="secondary">
                {METHOD_LABEL[method] ?? method}
              </Badge>
            ))}
          </div>
        ) : null}
        {error ? (
          <p role="alert" className="text-xs text-destructive">
            {error}
          </p>
        ) : null}
      </CardContent>
      {canManage ? (
        <CardFooter className="flex justify-end gap-2 border-t border-border py-2">
          {!account.archived ? (
            <Button type="button" variant="ghost" size="sm" aria-label={`Edit ${account.name}`} onClick={onEdit}>
              <Pencil aria-hidden="true" />
              Edit
            </Button>
          ) : null}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className={account.archived ? undefined : "text-destructive"}
            disabled={archive.isPending}
            aria-label={`${account.archived ? "Restore" : "Archive"} ${account.name}`}
            onClick={async () => {
              setError(null);
              try {
                await archive.mutateAsync({ id: account.id, archived: !account.archived });
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            {account.archived ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
            {account.archived ? "Restore" : "Archive"}
          </Button>
        </CardFooter>
      ) : null}
    </Card>
  );
}

function AccountForm({ account, onDone }: { account: FinanceAccount | null; onDone: () => void }) {
  const create = useCreateAccount();
  const update = useUpdateAccount();
  const pending = create.isPending || update.isPending;
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<AccountFormValues>({
    resolver: zodResolver(accountSchema),
    defaultValues: { name: account?.name ?? "", description: account?.description ?? "", openingBalance: "0" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      if (account) await update.mutateAsync({ id: account.id, name: values.name, description: values.description });
      else await create.mutateAsync(values);
      onDone();
    } catch (e) {
      setFormError(
        applyServerError(e, ["name", "description", "openingBalance"], setError, "Could not save the account.")
      );
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={pending} onSubmit={onSubmit}>
      <LiveRegion message={pending ? "Saving account…" : ""} />
      <FormAlert message={formError} />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="account-name">Name</Label>
        <Input
          id="account-name"
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? "account-name-error" : undefined}
          {...register("name")}
        />
        <FieldError id="account-name-error" message={errors.name?.message} />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="account-description">Description (optional)</Label>
        <Textarea id="account-description" rows={2} {...register("description")} />
        <FieldError id="account-description-error" message={errors.description?.message} />
      </div>
      {account ? (
        <InfoNote>The balance can&apos;t be edited here. Use the pencil next to the balance to record an adjustment.</InfoNote>
      ) : (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="account-opening">Opening balance</Label>
          <Input
            id="account-opening"
            inputMode="decimal"
            aria-invalid={!!errors.openingBalance}
            aria-describedby="account-opening-hint"
            {...register("openingBalance")}
          />
          <p id="account-opening-hint" className="text-xs text-muted-foreground">
            The money already in this account today. Recorded once, as the opening entry.
          </p>
          <FieldError id="account-opening-error" message={errors.openingBalance?.message} />
        </div>
      )}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={pending}>
          Cancel
        </Button>
        <Button type="submit" disabled={pending}>
          {pending ? "Saving…" : account ? "Save" : "Create account"}
        </Button>
      </div>
    </form>
  );
}

function AdjustForm({ account, onDone }: { account: FinanceAccount; onDone: () => void }) {
  const adjust = useAdjustAccount();
  const keyFor = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<AdjustmentFormValues>({
    resolver: zodResolver(adjustmentSchema(account.balance)),
    defaultValues: { targetBalance: account.balance.toFixed(2), reason: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const input = { id: account.id, targetBalance: values.targetBalance, expectedBalance: account.balance, reason: values.reason };
    try {
      await adjust.mutateAsync({ ...input, idempotencyKey: keyFor(input) });
      onDone();
    } catch (e) {
      setFormError(applyServerError(e, ["targetBalance", "reason"], setError, "Could not record the adjustment."));
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={adjust.isPending} onSubmit={onSubmit}>
      <LiveRegion message={adjust.isPending ? "Recording adjustment…" : ""} />
      <FormAlert message={formError} />
      <p className="text-sm">
        <span className="text-muted-foreground">{account.name} balance now: </span>
        <span className="font-semibold tabular-nums">{formatMoney(account.balance, account.currency)}</span>
      </p>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="adjust-target">New balance</Label>
        <Input
          id="adjust-target"
          inputMode="decimal"
          aria-invalid={!!errors.targetBalance}
          {...register("targetBalance")}
        />
        <FieldError id="adjust-target-error" message={errors.targetBalance?.message} />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="adjust-reason">Reason</Label>
        <Textarea
          id="adjust-reason"
          rows={2}
          aria-invalid={!!errors.reason}
          {...register("reason")}
        />
        <FieldError id="adjust-reason-error" message={errors.reason?.message} />
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={adjust.isPending}>
          Cancel
        </Button>
        <Button type="submit" disabled={adjust.isPending}>
          {adjust.isPending ? "Saving…" : "Record adjustment"}
        </Button>
      </div>
    </form>
  );
}

const ROUTING_FIELDS: { key: keyof RoutingInput; label: string; hint: string }[] = [
  { key: "cashAccountId", label: "Cash account", hint: "Cash payments" },
  { key: "cardAccountId", label: "Card account", hint: "Card payments" },
  { key: "bankOnlineAccountId", label: "Bank / online account", hint: "Bank transfers, bank slips and online gateway payments" },
  { key: "otherAccountId", label: "Other account", hint: "Any other payment method" },
];

function RoutingForm({ onDone }: { onDone: () => void }) {
  const routing = useFinanceRouting();
  const accounts = useFinanceAccounts(false);
  return (
    <QueryStateBoundary query={routing} loadingLabel="Loading configuration…" loginPath="/login">
      {(current) => (
        <QueryStateBoundary query={accounts} loadingLabel="Loading accounts…" loginPath="/login">
          {(list) => <RoutingFields current={current} accounts={list} onDone={onDone} />}
        </QueryStateBoundary>
      )}
    </QueryStateBoundary>
  );
}

function RoutingFields({
  current,
  accounts,
  onDone,
}: {
  current: RoutingInput;
  accounts: FinanceAccount[];
  onDone: () => void;
}) {
  const save = useSaveRouting();
  const [values, setValues] = useState<RoutingInput>({
    cashAccountId: current.cashAccountId,
    cardAccountId: current.cardAccountId,
    bankOnlineAccountId: current.bankOnlineAccountId,
    otherAccountId: current.otherAccountId,
  });
  const [error, setError] = useState<string | null>(null);

  return (
    <form
      className="flex flex-col gap-4"
      noValidate
      aria-busy={save.isPending}
      onSubmit={async (event) => {
        event.preventDefault();
        setError(null);
        try {
          await save.mutateAsync(values);
          onDone();
        } catch (e) {
          setError(errorMessage(e, "Could not save the configuration."));
        }
      }}
    >
      <p className="text-sm text-muted-foreground">
        Choose the account each payment method is posted to. Confirmed student payments for that method are
        credited to it automatically. Payments with no account here wait under &quot;Unposted&quot; on the
        Transactions tab.
      </p>
      <FormAlert message={error} />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {ROUTING_FIELDS.map((field) => (
          <div key={field.key} className="flex flex-col gap-1.5">
            <Label htmlFor={`routing-${field.key}`}>{field.label}</Label>
            <select
              id={`routing-${field.key}`}
              className={SELECT_CLASS}
              aria-describedby={`routing-${field.key}-hint`}
              value={values[field.key] ?? ""}
              onChange={(event) => setValues((v) => ({ ...v, [field.key]: event.target.value || null }))}
            >
              <option value="">Not set</option>
              {accounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.name}
                </option>
              ))}
            </select>
            <p id={`routing-${field.key}-hint`} className="text-xs text-muted-foreground">
              {field.hint}
            </p>
          </div>
        ))}
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? "Saving…" : "Save"}
        </Button>
      </div>
    </form>
  );
}
