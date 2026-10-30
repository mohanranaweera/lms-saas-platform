"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ErrorState } from "@/components/states/error-state";
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { useUpdateTenantConfigDomain, type TenantConfigProperty } from "@/lib/api/tenant-config";
import {
  MAX_INTRODUCTION_LENGTH,
  generalConfigSchema,
  toChangedConfigEntries,
  type GeneralConfigFormValues,
} from "@/lib/validation/tenant-config";
import { cn } from "@/lib/utils";

interface FieldConfig {
  name: keyof GeneralConfigFormValues;
  label: string;
  type: string;
  autoComplete?: string;
  helperText?: string;
  placeholder?: string;
  /** Multi-line input spanning both grid columns. */
  multiline?: boolean;
}

/** Institute identity and defaults. Default currency lives on Settings → Payments (SET-PAY-01). */
const DETAIL_FIELDS: FieldConfig[] = [
  { name: "institute_name", label: "Institute name", type: "text", autoComplete: "organization" },
  {
    name: "default_timezone",
    label: "Default time zone",
    type: "text",
    helperText: 'An IANA time zone id, e.g. "Asia/Colombo".',
  },
];

/** SET-PAY-01 (Klass "Institute Public Information"): all shown on the public home page. */
const PUBLIC_FIELDS: FieldConfig[] = [
  { name: "support_phone", label: "Contact phone number", type: "tel", autoComplete: "tel" },
  { name: "support_email", label: "Contact email", type: "email", autoComplete: "email" },
  { name: "address_line1", label: "Address line 1", type: "text", autoComplete: "address-line1" },
  { name: "address_line2", label: "Address line 2", type: "text", autoComplete: "address-line2" },
  { name: "city", label: "City", type: "text", autoComplete: "address-level2" },
  { name: "postal_code", label: "Postal code", type: "text", autoComplete: "postal-code" },
  {
    name: "institute_introduction",
    label: "Institute introduction",
    type: "text",
    multiline: true,
    helperText: `This will show on your public page. Up to ${MAX_INTRODUCTION_LENGTH} characters.`,
  },
  { name: "facebook_url", label: "Facebook", type: "url", placeholder: "https://facebook.com/your-page" },
  { name: "youtube_url", label: "YouTube", type: "url", placeholder: "https://youtube.com/@your-channel" },
];

const FIELDS: FieldConfig[] = [...DETAIL_FIELDS, ...PUBLIC_FIELDS];

const FIELD_LABELS: Record<string, string> = Object.fromEntries(
  FIELDS.map((field) => [field.name, field.label])
);
const KNOWN_FIELD_NAMES = new Set<string>(FIELDS.map((field) => field.name));

/** Converts the fetched `GENERAL` property list into this form's flat string values, treating a `null`/unset value as `""`. */
function propertiesToFormValues(properties: TenantConfigProperty[]): GeneralConfigFormValues {
  const byKey = new Map(properties.map((property) => [property.key, property.value]));
  const asString = (key: string) => {
    const value = byKey.get(key);
    return value === null || value === undefined ? "" : String(value);
  };
  return {
    institute_name: asString("institute_name"),
    support_email: asString("support_email"),
    support_phone: asString("support_phone"),
    default_timezone: asString("default_timezone"),
    address_line1: asString("address_line1"),
    address_line2: asString("address_line2"),
    city: asString("city"),
    postal_code: asString("postal_code"),
    institute_introduction: asString("institute_introduction"),
    facebook_url: asString("facebook_url"),
    youtube_url: asString("youtube_url"),
  };
}

/**
 * The General settings form itself, mounted only once
 * `useTenantConfigDomain("GENERAL")` has resolved to real property data (see
 * `page.tsx`'s `QueryStateBoundary` — this mirrors
 * `create-student-sheet.tsx`'s "mount fresh with server-derived
 * `defaultValues`" pattern). `canManage=false` (Read-only Auditor, per
 * `canManageInstituteConfig`) renders every field disabled via a `<fieldset
 * disabled>` and omits the Save action entirely — the caller does still see
 * every current value, since they hold `VIEW`.
 *
 * SET-PAY-01 adds the Klass "Institute public information" group (address,
 * introduction, social links). An emptied optional field is sent as `null`
 * (cleared), see `toChangedConfigEntries`.
 */
export function GeneralConfigForm({
  properties,
  canManage,
}: {
  properties: TenantConfigProperty[];
  canManage: boolean;
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
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<GeneralConfigFormValues>({
    resolver: zodResolver(generalConfigSchema),
    defaultValues: propertiesToFormValues(properties),
  });

  const mutation = useUpdateTenantConfigDomain("GENERAL");

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    // Surrounding whitespace never reaches the backend (a padded URL would fail its check).
    const trimmed = Object.fromEntries(
      Object.entries(values).map(([key, value]) => [key, value.trim()])
    ) as GeneralConfigFormValues;
    const changes = toChangedConfigEntries(trimmed, currentValues);
    if (Object.keys(changes).length === 0) {
      // Nothing actually changed (e.g. Save clicked with a pristine form) —
      // no request needed, and the disabled Save button below normally
      // prevents this anyway (`isDirty` gate), but this stays correct if
      // reached some other way.
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
              setError(fieldError.field as keyof GeneralConfigFormValues, {
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

        // A stale UI could still let a save through even when Save is hidden
        // client-side (Read-only Auditor) — a real 403 must still be handled
        // gracefully here, not crash the form.
        setPageError({ message: error.message, code: error.code });
        return;
      }

      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  /** Plain render helper (not a component), so inputs never remount between renders. */
  const renderField = (field: FieldConfig) => {
    const fieldError = errors[field.name];
    const inputId = `general-config-${field.name}`;
    const errorId = `${inputId}-error`;
    const helperId = field.helperText ? `${inputId}-helper` : undefined;
    const describedBy = [helperId, fieldError ? errorId : undefined].filter(Boolean).join(" ") || undefined;

    return (
      <div key={field.name} className={cn("flex flex-col gap-1.5", field.multiline && "md:col-span-2")}>
        <Label htmlFor={inputId}>{field.label}</Label>
        {field.multiline ? (
          <Textarea
            id={inputId}
            rows={4}
            readOnly={!canManage}
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={describedBy}
            {...register(field.name)}
          />
        ) : (
          <Input
            id={inputId}
            type={field.type}
            autoComplete={field.autoComplete}
            placeholder={field.placeholder}
            readOnly={!canManage}
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={describedBy}
            {...register(field.name)}
          />
        )}
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
  };

  return (
    <form
      className="flex max-w-4xl flex-col gap-4"
      onSubmit={onSubmit}
      aria-busy={mutation.isPending}
      noValidate
    >
      <LiveRegion
        message={
          mutation.isPending
            ? "Saving general settings…"
            : saved
              ? "General settings saved."
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

      <fieldset disabled={!canManage || mutation.isPending} className="flex flex-col gap-6">
        <legend className="sr-only">General settings</legend>

        <section aria-labelledby="general-details-heading" className="flex flex-col gap-4 rounded-lg border border-border p-4">
          <h2 id="general-details-heading" className="text-base font-medium text-foreground">
            Institute details
          </h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">{DETAIL_FIELDS.map(renderField)}</div>
        </section>

        <section
          aria-labelledby="general-public-heading"
          className="flex flex-col gap-4 rounded-lg border border-border p-4"
          data-testid="institute-public-information"
        >
          <div>
            <h2 id="general-public-heading" className="text-base font-medium text-foreground">
              Institute public information
            </h2>
            <p className="text-sm text-muted-foreground">Shown to visitors on your public home page.</p>
          </div>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">{PUBLIC_FIELDS.map(renderField)}</div>
        </section>
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
  );
}
