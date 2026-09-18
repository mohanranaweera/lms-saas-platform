"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";
import { LiveRegion } from "@/components/ui/live-region";
import { BrandingPreview } from "@/components/settings/branding-preview";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { useUpdateTenantConfigDomain, type TenantConfigProperty } from "@/lib/api/tenant-config";
import {
  brandingConfigSchema,
  toChangedConfigEntries,
  type BrandingConfigFormValues,
} from "@/lib/validation/tenant-config";

interface FieldConfig {
  name: keyof BrandingConfigFormValues;
  label: string;
  type: string;
  helperText?: string;
}

const FIELDS: FieldConfig[] = [
  { name: "primary_color", label: "Primary color", type: "text", helperText: 'A hex color, e.g. "#0F172A".' },
  {
    name: "secondary_color",
    label: "Secondary color",
    type: "text",
    helperText:
      'A hex color, e.g. "#F59E0B". Must contrast at least 4.5:1 against the primary color (checked on save).',
  },
  { name: "logo_url", label: "Logo URL", type: "url", helperText: "A direct http(s) link to your logo image." },
  { name: "favicon_url", label: "Favicon URL", type: "url", helperText: "A direct http(s) link to your favicon." },
];

const FIELD_LABELS: Record<string, string> = Object.fromEntries(
  FIELDS.map((field) => [field.name, field.label])
);
const KNOWN_FIELD_NAMES = new Set<string>(FIELDS.map((field) => field.name));

/** Converts the fetched `BRANDING` property list into this form's flat string values, treating a `null`/unset value as `""`. */
function propertiesToFormValues(properties: TenantConfigProperty[]): BrandingConfigFormValues {
  const byKey = new Map(properties.map((property) => [property.key, property.value]));
  const asString = (key: string) => {
    const value = byKey.get(key);
    return value === null || value === undefined ? "" : String(value);
  };
  return {
    primary_color: asString("primary_color"),
    secondary_color: asString("secondary_color"),
    logo_url: asString("logo_url"),
    favicon_url: asString("favicon_url"),
  };
}

/**
 * The Branding settings form, mounted only once
 * `useTenantConfigDomain("BRANDING")` has resolved to real property data
 * (see `page.tsx`'s `QueryStateBoundary`). `canManage=false` (Read-only
 * Auditor) renders every field disabled via a `<fieldset disabled>` and
 * omits the Save action entirely — the caller still sees every current
 * value and the live preview, since they hold `VIEW`.
 *
 * `instituteName` is passed in separately (from the `GENERAL` domain, fetched
 * by the page) purely so the preview panel has a name to render — this form
 * never reads or writes `GENERAL`'s `institute_name` itself.
 */
export function BrandingConfigForm({
  properties,
  canManage,
  instituteName,
}: {
  properties: TenantConfigProperty[];
  canManage: boolean;
  instituteName: string;
}) {
  const currentValues = Object.fromEntries(properties.map((property) => [property.key, property.value]));
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);
  const [saved, setSaved] = useState(false);

  const {
    register,
    handleSubmit,
    watch,
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<BrandingConfigFormValues>({
    resolver: zodResolver(brandingConfigSchema),
    defaultValues: propertiesToFormValues(properties),
  });

  const mutation = useUpdateTenantConfigDomain("BRANDING");
  const liveValues = watch();

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    const changes = toChangedConfigEntries(values, currentValues);
    if (Object.keys(changes).length === 0) {
      setSaved(true);
      return;
    }
    try {
      const updated = await mutation.mutateAsync(changes);
      reset(propertiesToFormValues(updated));
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
          );
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              // Includes the backend's WCAG AA contrast rejection on
              // `secondary_color` — this has no client-side equivalent (see
              // `lib/validation/tenant-config.ts`'s own doc comment), so it
              // only ever surfaces here, from the real response.
              setError(fieldError.field as keyof BrandingConfigFormValues, {
                type: "server",
                message: fieldError.message,
              });
            }
          }
          if (unmapped.length > 0) {
            setPageError({
              message: "Some information couldn't be validated.",
              code: error.code,
              fieldErrors: unmapped,
            });
          }
          return;
        }

        setPageError({ message: error.message, code: error.code });
        return;
      }

      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <form
        className="flex flex-col gap-4"
        onSubmit={onSubmit}
        aria-busy={mutation.isPending}
        noValidate
      >
        <LiveRegion
          message={
            mutation.isPending
              ? "Saving branding settings…"
              : saved
                ? "Branding settings saved."
                : ""
          }
        />

        {pageError ? (
          <ErrorState
            message={pageError.message}
            code={pageError.code}
            fieldErrors={pageError.fieldErrors}
            fieldLabels={FIELD_LABELS}
            onRetry={() => {
              setPageError(null);
              void onSubmit();
            }}
          />
        ) : null}

        <fieldset disabled={!canManage || mutation.isPending} className="flex flex-col gap-4">
          <legend className="sr-only">Branding settings</legend>
          {FIELDS.map((field) => {
            const fieldError = errors[field.name];
            const errorId = `branding-config-${field.name}-error`;
            const helperId = field.helperText ? `branding-config-${field.name}-helper` : undefined;

            return (
              <div key={field.name} className="flex flex-col gap-1.5">
                <Label htmlFor={`branding-config-${field.name}`}>{field.label}</Label>
                <div className="flex items-center gap-2">
                  <Input
                    id={`branding-config-${field.name}`}
                    type={field.type === "text" ? "text" : field.type}
                    readOnly={!canManage}
                    aria-invalid={fieldError ? true : undefined}
                    aria-describedby={
                      [helperId, fieldError ? errorId : undefined].filter(Boolean).join(" ") ||
                      undefined
                    }
                    {...register(field.name)}
                  />
                  {(field.name === "primary_color" || field.name === "secondary_color") &&
                  /^#[0-9A-Fa-f]{6}$/.test(liveValues[field.name]) ? (
                    <span
                      aria-hidden="true"
                      className="size-8 shrink-0 rounded-md border border-border"
                      style={{ backgroundColor: liveValues[field.name] }}
                    />
                  ) : null}
                </div>
                {field.helperText ? (
                  <p id={helperId} className="text-xs text-muted-foreground">
                    {field.helperText}
                  </p>
                ) : null}
                {fieldError ? (
                  <p id={errorId} role="alert" className="text-xs text-destructive">
                    {fieldError.message}
                  </p>
                ) : null}
              </div>
            );
          })}
        </fieldset>

        {canManage ? (
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={mutation.isPending || !isDirty}>
              {mutation.isPending ? "Saving…" : "Save changes"}
            </Button>
            {saved && !mutation.isPending ? (
              <span className="text-sm text-muted-foreground">Saved.</span>
            ) : null}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">
            You have view-only access to institute configuration.
          </p>
        )}
      </form>

      <BrandingPreview
        instituteName={instituteName}
        primaryColor={liveValues.primary_color}
        secondaryColor={liveValues.secondary_color}
        logoUrl={liveValues.logo_url}
      />
    </div>
  );
}
