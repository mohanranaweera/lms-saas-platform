"use client";

import { useId, useState, type FormEvent } from "react";
import { CheckCircle2, Gift, Info, Tag, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  formatCurrency,
  toAmount,
  type CheckoutQuote,
  type DiscountCodeResult,
} from "@/lib/api/klass-student";
import { DISCOUNT_CODE_MAX_LENGTH } from "./composer-model";

/** Student-facing copy per server code status (the server's own message wins when present). */
const CODE_STATUS_FALLBACK: Record<DiscountCodeResult["status"], string> = {
  APPLIED: "Discount code applied.",
  INVALID: "This discount code isn't valid.",
  NOT_ELIGIBLE: "This discount code doesn't apply to the items you selected.",
  USAGE_LIMIT_REACHED: "This discount code can't be used any more.",
  NOT_APPLIED_BETTER_DISCOUNT: "You already have a better discount, so this code wasn't applied.",
};

const RATE_LIMITED_MESSAGE = "Too many incorrect discount codes. Remove the code and try again later.";

/**
 * DISC-01 discount area of the composer summary: the discount-code field,
 * the discounts the server applied, free-course offers to accept, and server
 * notices. Nothing here decides or computes a discount - the code is sent with
 * the next quote and every amount, status and message comes back from it.
 */
export function ComposerDiscountPanel({
  quote,
  appliedCode,
  rateLimited,
  busy,
  onApplyCode,
  onRemoveCode,
  onAcceptReward,
}: {
  /** The latest successful quote, if any. */
  quote: CheckoutQuote | undefined;
  appliedCode: string | null;
  /** The last quote answered 429 `DISCOUNT_CODE_RATE_LIMITED`. */
  rateLimited: boolean;
  busy: boolean;
  onApplyCode: (code: string) => void;
  onRemoveCode: () => void;
  onAcceptReward: (ruleId: string) => void;
}) {
  const discounts = quote?.discounts ?? [];
  const offers = quote?.rewardOffers ?? [];
  const notices = quote?.notices ?? [];
  // Only a result for the code currently applied is shown (never a stale one).
  const codeResult =
    quote?.codeResult && appliedCode && quote.codeResult.code.toUpperCase() === appliedCode.toUpperCase()
      ? quote.codeResult
      : null;

  return (
    <div className="flex flex-col gap-3" data-testid="composer-discounts">
      <DiscountCodeField
        appliedCode={appliedCode}
        codeResult={codeResult}
        rateLimited={rateLimited}
        busy={busy}
        onApply={onApplyCode}
        onRemove={onRemoveCode}
      />

      {discounts.length > 0 && quote ? (
        <ul className="flex flex-col gap-1.5" aria-label="Applied discounts" data-testid="composer-applied-discounts">
          {discounts.map((discount) => (
            <li
              key={`${discount.ruleId}-${discount.lineNos.join(",")}`}
              className="flex flex-col gap-0.5 rounded-md border border-emerald-200 bg-emerald-50 p-2 text-xs dark:border-emerald-900 dark:bg-emerald-950"
              data-testid="composer-applied-discount"
            >
              <span className="flex items-start justify-between gap-2">
                <span className="flex items-center gap-1 font-medium text-emerald-800 dark:text-emerald-200">
                  <CheckCircle2 className="size-3.5 shrink-0" aria-hidden="true" />
                  {discount.name}
                </span>
                <span className="shrink-0 font-medium text-emerald-800 dark:text-emerald-200">
                  − {formatCurrency(discount.amount, quote.currency)}
                </span>
              </span>
              {discount.explanation ? <span className="text-muted-foreground">{discount.explanation}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}

      {offers.length > 0 && quote ? (
        <ul className="flex flex-col gap-2" aria-label="Free course offers" data-testid="composer-reward-offers">
          {offers.map((offer) => (
            <li
              key={offer.ruleId}
              className="flex flex-col gap-1.5 rounded-md border border-blue-200 bg-blue-50 p-2 text-xs dark:border-blue-900 dark:bg-blue-950"
              data-testid="composer-reward-offer"
            >
              <span className="flex items-center gap-1 font-medium text-blue-800 dark:text-blue-200">
                <Gift className="size-3.5 shrink-0" aria-hidden="true" />
                {offer.name}
              </span>
              <span className="text-foreground">
                {offer.courseTitle ?? "A course"} free for {offer.months.map((month) => month.label).join(", ")}
                {toAmount(offer.originalValue) > 0 ? ` (worth ${formatCurrency(offer.originalValue, quote.currency)})` : ""}.
              </span>
              {toAmount(offer.facilityFee) > 0 ? (
                <span className="text-muted-foreground">
                  A facility fee of {formatCurrency(offer.facilityFee, quote.currency)} still applies.
                </span>
              ) : null}
              {offer.explanation ? <span className="text-muted-foreground">{offer.explanation}</span> : null}
              <Button
                type="button"
                size="sm"
                variant="outline"
                className="w-fit"
                onClick={() => onAcceptReward(offer.ruleId)}
                disabled={busy}
                data-testid="composer-reward-accept"
              >
                Add free months<span className="sr-only"> of {offer.courseTitle ?? offer.name}</span>
              </Button>
            </li>
          ))}
        </ul>
      ) : null}

      {notices.length > 0 ? (
        <ul className="flex flex-col gap-1" aria-label="Discount notices" data-testid="composer-discount-notices">
          {notices.map((notice) => (
            <li key={notice} className="flex items-start gap-1 text-xs text-muted-foreground">
              <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
              {notice}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function DiscountCodeField({
  appliedCode,
  codeResult,
  rateLimited,
  busy,
  onApply,
  onRemove,
}: {
  appliedCode: string | null;
  codeResult: DiscountCodeResult | null;
  rateLimited: boolean;
  busy: boolean;
  onApply: (code: string) => void;
  onRemove: () => void;
}) {
  const id = useId();
  const [draft, setDraft] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const code = draft.trim();
    if (!code) {
      setLocalError("Enter a discount code.");
      return;
    }
    setLocalError(null);
    onApply(code);
    setDraft("");
  };

  if (appliedCode) {
    const applied = !rateLimited && codeResult?.status === "APPLIED";
    const failed = rateLimited || (codeResult !== null && !applied);
    const message = rateLimited
      ? RATE_LIMITED_MESSAGE
      : codeResult
        ? codeResult.message || CODE_STATUS_FALLBACK[codeResult.status]
        : "Checking code…";
    return (
      <div className="flex flex-col gap-1" data-testid="composer-code-applied">
        <div className="flex items-center justify-between gap-2 rounded-md border border-border bg-background px-2 py-1.5">
          <span className="flex min-w-0 items-center gap-1.5 text-sm">
            <Tag className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="sr-only">Discount code </span>
            <span className="truncate font-mono uppercase text-foreground" data-testid="composer-code-value">
              {appliedCode}
            </span>
          </span>
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            aria-label={`Remove discount code ${appliedCode}`}
            onClick={onRemove}
            disabled={busy}
            data-testid="composer-code-remove"
          >
            <XIcon aria-hidden="true" />
          </Button>
        </div>
        <p
          role={failed ? "alert" : "status"}
          className={`text-xs ${
            applied ? "text-emerald-700 dark:text-emerald-300" : failed ? "text-destructive" : "text-muted-foreground"
          }`}
          data-testid="composer-code-status"
          data-status={rateLimited ? "RATE_LIMITED" : (codeResult?.status ?? "PENDING")}
        >
          {message}
        </p>
      </div>
    );
  }

  return (
    <form className="flex flex-col gap-1" onSubmit={submit} noValidate data-testid="composer-code-form">
      <Label htmlFor={`${id}-code`} className="text-xs">
        Discount code
      </Label>
      <div className="flex gap-2">
        <Input
          id={`${id}-code`}
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            if (localError) setLocalError(null);
          }}
          maxLength={DISCOUNT_CODE_MAX_LENGTH}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          className="uppercase"
          placeholder="Enter code"
          aria-invalid={!!localError}
          aria-describedby={localError ? `${id}-error` : undefined}
          disabled={busy}
          data-testid="composer-code-input"
        />
        <Button type="submit" variant="outline" disabled={busy} data-testid="composer-code-apply">
          Apply
        </Button>
      </div>
      {localError ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {localError}
        </p>
      ) : null}
    </form>
  );
}
