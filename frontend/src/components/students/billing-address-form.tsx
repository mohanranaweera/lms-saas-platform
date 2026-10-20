"use client";

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { isApiClientError } from "@/lib/api/error";
import {
  useOwnBillingAddress,
  useUpdateOwnBillingAddress,
  type StudentBillingAddress,
} from "@/lib/api/students";
import { billingAddressSchema, type BillingAddressFormValues } from "@/lib/validation/students";

/**
 * The student's own billing address (PAY-GW-01 follow-up) -
 * `GET`/`PUT /v1/students/me/billing-address`, no id anywhere. Required for
 * online (PayHere) payment and reused as the postage delivery address in the
 * payment composer; the backend re-validates both fields.
 *
 * Used on My Profile (default) and inline in the composer (`variant="inline"`,
 * own `idPrefix` so the ids never clash with a profile page underneath, and
 * `onSaved` so the composer can re-quote).
 */
export function BillingAddressForm({
  idPrefix = "billing",
  variant = "page",
  heading = "Billing address",
  description = "Needed to pay online. It is shared with the payment gateway only when you pay.",
  onSaved,
  onCancel,
}: {
  idPrefix?: string;
  variant?: "page" | "inline";
  heading?: string;
  description?: string;
  onSaved?: (address: StudentBillingAddress) => void;
  onCancel?: () => void;
} = {}) {
  const addressQuery = useOwnBillingAddress();
  const updateMutation = useUpdateOwnBillingAddress();
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const inline = variant === "inline";
  const ids = {
    heading: `${idPrefix}-address-heading`,
    line: `${idPrefix}-address-line`,
    city: `${idPrefix}-city`,
  };

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<BillingAddressFormValues>({
    resolver: zodResolver(billingAddressSchema),
    defaultValues: { addressLine: "", city: "" },
  });

  useEffect(() => {
    if (addressQuery.data) {
      reset({ addressLine: addressQuery.data.addressLine ?? "", city: addressQuery.data.city ?? "" });
    }
  }, [addressQuery.data, reset]);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      const result = await updateMutation.mutateAsync(values);
      setSaved(true);
      onSaved?.(result);
    } catch (error) {
      if (isApiClientError(error)) {
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "addressLine" || fieldError.field === "city") {
            setError(fieldError.field, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (!mapped) {
          setPageError({ message: error.message, code: error.code });
        }
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  if (addressQuery.isPending) {
    return <LoadingState label="Loading your billing address…" />;
  }

  return (
    <section
      aria-labelledby={ids.heading}
      className={inline ? "flex flex-col gap-3" : "flex max-w-xl flex-col gap-4"}
      data-testid={inline ? `${idPrefix}-address-form` : undefined}
    >
      <div>
        {inline ? (
          <h4 id={ids.heading} className="text-sm font-medium text-foreground">
            {heading}
          </h4>
        ) : (
          <h2 id={ids.heading} className="text-lg font-medium text-foreground">
            {heading}
          </h2>
        )}
        <p className={inline ? "text-xs text-muted-foreground" : "text-sm text-muted-foreground"}>{description}</p>
      </div>

      {addressQuery.isError ? (
        <ErrorState
          message="We couldn't load your billing address."
          onRetry={() => addressQuery.refetch()}
        />
      ) : null}

      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}

      <form onSubmit={onSubmit} noValidate className={inline ? "flex flex-col gap-3" : "flex flex-col gap-4"}>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.line}>Address</Label>
          <Input
            id={ids.line}
            autoComplete="street-address"
            aria-invalid={errors.addressLine ? true : undefined}
            aria-describedby={errors.addressLine ? `${ids.line}-error` : undefined}
            {...register("addressLine")}
          />
          {errors.addressLine ? (
            <p id={`${ids.line}-error`} role="alert" className="text-sm text-destructive">
              {errors.addressLine.message}
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.city}>City</Label>
          <Input
            id={ids.city}
            autoComplete="address-level2"
            aria-invalid={errors.city ? true : undefined}
            aria-describedby={errors.city ? `${ids.city}-error` : undefined}
            {...register("city")}
          />
          {errors.city ? (
            <p id={`${ids.city}-error`} role="alert" className="text-sm text-destructive">
              {errors.city.message}
            </p>
          ) : null}
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <Button
            type="submit"
            size={inline ? "sm" : "default"}
            disabled={updateMutation.isPending || (!isDirty && !inline)}
            aria-busy={updateMutation.isPending}
            data-testid={inline ? `${idPrefix}-address-save` : undefined}
          >
            {updateMutation.isPending ? "Saving…" : "Save address"}
          </Button>
          {onCancel ? (
            <Button type="button" size={inline ? "sm" : "default"} variant="outline" onClick={onCancel}>
              Cancel
            </Button>
          ) : null}
          {saved ? (
            <p role="status" className="text-sm text-muted-foreground">
              Billing address saved.
            </p>
          ) : null}
        </div>
      </form>
    </section>
  );
}
