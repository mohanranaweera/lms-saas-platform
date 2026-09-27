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
import { useOwnBillingAddress, useUpdateOwnBillingAddress } from "@/lib/api/students";
import { billingAddressSchema, type BillingAddressFormValues } from "@/lib/validation/students";

/**
 * The student's own billing address (PAY-GW-01 follow-up) -
 * `GET`/`PUT /v1/students/me/billing-address`, no id anywhere. Required for
 * online (PayHere) payment; the backend re-validates both fields.
 */
export function BillingAddressForm() {
  const addressQuery = useOwnBillingAddress();
  const updateMutation = useUpdateOwnBillingAddress();
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);

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
      await updateMutation.mutateAsync(values);
      setSaved(true);
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
    <section aria-labelledby="billing-address-heading" className="flex max-w-xl flex-col gap-4">
      <div>
        <h2 id="billing-address-heading" className="text-lg font-medium text-foreground">
          Billing address
        </h2>
        <p className="text-sm text-muted-foreground">
          Needed to pay online. It is shared with the payment gateway only when you pay.
        </p>
      </div>

      {addressQuery.isError ? (
        <ErrorState
          message="We couldn't load your billing address."
          onRetry={() => addressQuery.refetch()}
        />
      ) : null}

      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}

      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="billing-address-line">Address</Label>
          <Input
            id="billing-address-line"
            autoComplete="street-address"
            aria-invalid={errors.addressLine ? true : undefined}
            aria-describedby={errors.addressLine ? "billing-address-line-error" : undefined}
            {...register("addressLine")}
          />
          {errors.addressLine ? (
            <p id="billing-address-line-error" role="alert" className="text-sm text-destructive">
              {errors.addressLine.message}
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="billing-city">City</Label>
          <Input
            id="billing-city"
            autoComplete="address-level2"
            aria-invalid={errors.city ? true : undefined}
            aria-describedby={errors.city ? "billing-city-error" : undefined}
            {...register("city")}
          />
          {errors.city ? (
            <p id="billing-city-error" role="alert" className="text-sm text-destructive">
              {errors.city.message}
            </p>
          ) : null}
        </div>

        <div className="flex items-center gap-3">
          <Button
            type="submit"
            disabled={updateMutation.isPending || !isDirty}
            aria-busy={updateMutation.isPending}
          >
            {updateMutation.isPending ? "Saving…" : "Save address"}
          </Button>
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
