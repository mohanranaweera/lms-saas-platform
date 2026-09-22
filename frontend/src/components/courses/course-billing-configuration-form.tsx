"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { useCreateOrUpdateBillingConfiguration, type CourseBillingConfigurationResponse } from "@/lib/api/course-billing";
import { isApiClientError } from "@/lib/api/error";
import {
  courseBillingConfigurationSchema,
  toCourseBillingConfigurationRequest,
  CURRENCY_HELPER_TEXT,
  PRICE_HELPER_TEXT,
  type CourseBillingConfigurationFormValues,
} from "@/lib/validation/course";
import type { CoursePricingModel } from "@/lib/api/courses";

/**
 * Create-or-update form for `course_billing_configuration` (Wave 2,
 * `MONTHLY`/`SESSION`/`CUSTOM` pricing models only). `sessionRate` only
 * renders for `SESSION`; `requiresManualQuote` only renders for `CUSTOM` —
 * the backend rejects either field outright for any other pricing model
 * (`BillingConfigurationService#validateAgainstPricingModel`), so this form
 * never sends a field that doesn't apply.
 *
 * `configuration === null` means "not configured yet" (a 404 from `GET
 * .../billing-configuration`, not a real error — see the Billing tab page's
 * own query-classification) — the same `POST` endpoint both creates and
 * upserts, so this single form serves both states.
 */
export function CourseBillingConfigurationForm({
  courseId,
  pricingModel,
  configuration,
}: {
  courseId: string;
  pricingModel: CoursePricingModel;
  configuration: CourseBillingConfigurationResponse | null;
}) {
  const [saved, setSaved] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CourseBillingConfigurationFormValues>({
    resolver: zodResolver(courseBillingConfigurationSchema),
    defaultValues: {
      sessionRate: configuration?.sessionRate != null ? String(configuration.sessionRate) : "",
      currency: configuration?.currency ?? "",
      requiresManualQuote: configuration?.requiresManualQuote ?? false,
    },
  });

  const mutation = useCreateOrUpdateBillingConfiguration(courseId);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await mutation.mutateAsync(toCourseBillingConfigurationRequest(values));
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        const sessionRateError = error.fieldErrors.find((fieldError) => fieldError.field === "sessionRate");
        const currencyError = error.fieldErrors.find((fieldError) => fieldError.field === "currency");
        if (sessionRateError) {
          setError("sessionRate", { type: "server", message: sessionRateError.message });
        }
        if (currencyError) {
          setError("currency", { type: "server", message: currencyError.message });
        }
        if (sessionRateError || currencyError) {
          return;
        }
        setPageError(error.message);
        return;
      }
      setPageError("An unexpected error occurred. Please try again.");
    }
  });

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h3 className="text-sm font-medium text-foreground">Billing configuration</h3>
        <p className="text-xs text-muted-foreground">
          {configuration
            ? "This course's billing configuration."
            : "This course has no billing configuration yet — create one below before adding a billing period."}
        </p>
      </div>

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Billing configuration saved.</AlertDescription>
        </Alert>
      ) : null}
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <form
        className="flex flex-col gap-4"
        noValidate
        aria-busy={mutation.isPending}
        onSubmit={onSubmit}
      >
        <span role="status" aria-live="polite" className="sr-only">
          {mutation.isPending ? "Saving billing configuration…" : ""}
        </span>

        {pricingModel === "SESSION" ? (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`billing-config-${courseId}-sessionRate`}>Session rate</Label>
            <Input
              id={`billing-config-${courseId}-sessionRate`}
              inputMode="decimal"
              autoComplete="off"
              disabled={mutation.isPending}
              aria-invalid={!!errors.sessionRate}
              aria-describedby={`billing-config-${courseId}-sessionRate-helper`}
              {...register("sessionRate")}
            />
            <p id={`billing-config-${courseId}-sessionRate-helper`} className="text-xs text-muted-foreground">
              {PRICE_HELPER_TEXT}
            </p>
            {errors.sessionRate ? (
              <p role="alert" className="text-xs text-destructive">
                {errors.sessionRate.message}
              </p>
            ) : null}
          </div>
        ) : null}

        <div className="flex flex-col gap-1.5 sm:w-40">
          <Label htmlFor={`billing-config-${courseId}-currency`}>Currency</Label>
          <Input
            id={`billing-config-${courseId}-currency`}
            autoComplete="off"
            maxLength={3}
            disabled={mutation.isPending}
            aria-invalid={!!errors.currency}
            aria-describedby={`billing-config-${courseId}-currency-helper`}
            {...register("currency")}
          />
          <p id={`billing-config-${courseId}-currency-helper`} className="text-xs text-muted-foreground">
            {CURRENCY_HELPER_TEXT}
          </p>
          {errors.currency ? (
            <p role="alert" className="text-xs text-destructive">
              {errors.currency.message}
            </p>
          ) : null}
        </div>

        {pricingModel === "CUSTOM" ? (
          <div className="flex items-center gap-2">
            <input
              id={`billing-config-${courseId}-requiresManualQuote`}
              type="checkbox"
              className="size-4 rounded border-input"
              disabled={mutation.isPending}
              {...register("requiresManualQuote")}
            />
            <Label htmlFor={`billing-config-${courseId}-requiresManualQuote`} className="font-normal">
              Requires a manual quote (checkout happens via a staff/manual process, not self-serve)
            </Label>
          </div>
        ) : null}

        <Button type="submit" className="w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : configuration ? "Save changes" : "Create billing configuration"}
        </Button>
      </form>
    </div>
  );
}
