"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, BarChart3 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { isApiClientError } from "@/lib/api/error";
import { useUpdateTenantConfigDomain, type TenantConfigProperty } from "@/lib/api/tenant-config";
import {
  analyticsConfigSchema,
  configValue,
  type AnalyticsConfigFormInput,
  type AnalyticsConfigFormValues,
} from "@/lib/validation/tenant-config";

const INVALID_ID_MESSAGE = 'Enter a Google Analytics 4 measurement ID, e.g. "G-ABC123XYZ9".';

/**
 * Settings → Configs → Google Analytics (SET-CFG-01). Saves
 * `GENERAL.ga_measurement_id`; an empty field clears it (`null`). The tag is
 * then loaded on the public site and the student portal. The id is public by
 * nature (it is in every tracked page's source), so it is shown in clear.
 * The Zod schema is UX only — the backend validates the same pattern and its
 * 400 is shown on the field.
 */
export function AnalyticsSection({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const mutation = useUpdateTenantConfigDomain("GENERAL");
  const current = configValue(properties, "ga_measurement_id");
  const saved = typeof current === "string" ? current : "";
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const form = useForm<AnalyticsConfigFormInput, unknown, AnalyticsConfigFormValues>({
    resolver: zodResolver(analyticsConfigSchema),
    defaultValues: { ga_measurement_id: saved },
  });
  const fieldError = form.formState.errors.ga_measurement_id?.message;

  const onSubmit = form.handleSubmit(async ({ ga_measurement_id: id }) => {
    setErrorMessage(null);
    setStatus(null);
    if (id === saved) {
      return;
    }
    try {
      await mutation.mutateAsync({ ga_measurement_id: id === "" ? null : id });
      form.reset({ ga_measurement_id: id });
      setStatus(id === "" ? "Google Analytics removed." : "Google Analytics measurement ID saved.");
    } catch (error) {
      if (isApiClientError(error) && error.status === 400) {
        form.setError("ga_measurement_id", { message: INVALID_ID_MESSAGE });
        return;
      }
      setErrorMessage(
        isApiClientError(error)
          ? error.status === 403
            ? "You don't have permission to change configurations."
            : error.message
          : "Something went wrong. Please try again."
      );
    }
  });

  return (
    <section
      aria-labelledby="system-configurations-heading"
      className="flex max-w-4xl flex-col gap-4 rounded-lg border border-border p-4"
      data-testid="system-configurations"
    >
      <div>
        <h2 id="system-configurations-heading" className="text-base font-medium text-foreground">
          System configurations
        </h2>
        <p className="text-sm text-muted-foreground">Track visits to your public site and student portal.</p>
      </div>

      <form className="flex flex-col gap-3" onSubmit={(event) => void onSubmit(event)} noValidate>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="ga-measurement-id" className="flex items-center gap-1.5">
            <BarChart3 className="size-4 text-muted-foreground" aria-hidden="true" />
            Google Analytics measurement ID
          </Label>
          <Input
            id="ga-measurement-id"
            placeholder="G-XXXXXXXXXX"
            autoComplete="off"
            spellCheck={false}
            maxLength={22}
            disabled={!canManage || mutation.isPending}
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? "ga-measurement-id-error" : "ga-measurement-id-help"}
            className="sm:max-w-xs"
            {...form.register("ga_measurement_id")}
          />
          {fieldError ? (
            <p id="ga-measurement-id-error" role="alert" className="text-xs text-destructive">
              {fieldError}
            </p>
          ) : (
            <p id="ga-measurement-id-help" className="text-xs text-muted-foreground">
              Measurement ID used to track portal activity. Find it in Google Analytics under Admin → Data streams.
              Leave empty to turn tracking off.
            </p>
          )}
        </div>

        {canManage ? (
          <div>
            <Button
              type="submit"
              disabled={!form.formState.isDirty || mutation.isPending}
              aria-busy={mutation.isPending}
            >
              {mutation.isPending ? "Saving…" : "Save"}
            </Button>
          </div>
        ) : null}
      </form>

      {errorMessage ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{errorMessage}</AlertDescription>
        </Alert>
      ) : null}
      {status ? (
        <p role="status" className="text-sm text-muted-foreground">
          {status}
        </p>
      ) : null}
    </section>
  );
}
