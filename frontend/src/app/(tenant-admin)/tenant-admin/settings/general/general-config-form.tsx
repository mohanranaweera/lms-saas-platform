"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { useUpdateTenantConfigDomain, type TenantConfigProperty } from "@/lib/api/tenant-config";
import {
  generalConfigSchema,
  toChangedConfigEntries,
  type GeneralConfigFormValues,
} from "@/lib/validation/tenant-config";

interface FieldConfig {
  name: keyof GeneralConfigFormValues;
  label: string;
  type: string;
  autoComplete?: string;
  helperText?: string;
}

const FIELDS: FieldConfig[] = [
  { name: "institute_name", label: "Institute name", type: "text", autoComplete: "organization" },
  { name: "support_email", label: "Support email", type: "email", autoComplete: "email" },
  { name: "support_phone", label: "Support phone", type: "tel", autoComplete: "tel" },
  {
    name: "default_timezone",
    label: "Default time zone",
    type: "text",
    helperText: 'An IANA time zone id, e.g. "Asia/Colombo". Defaults to "UTC" if left blank.',
  },
  {
    name: "default_currency",
    label: "Default currency",
    type: "text",
    helperText: 'A 3-letter ISO currency code, e.g. "USD". Defaults to "USD" if left blank.',
  },
];

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
    default_currency: asString("default_currency"),
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
    const changes = toChangedConfigEntries(values, currentValues);
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

  return (
    <form
      className="flex max-w-xl flex-col gap-4"
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

      <fieldset disabled={!canManage || mutation.isPending} className="flex flex-col gap-4">
        <legend className="sr-only">General settings</legend>
        {FIELDS.map((field) => {
          const fieldError = errors[field.name];
          const errorId = `general-config-${field.name}-error`;
          const helperId = field.helperText ? `general-config-${field.name}-helper` : undefined;

          return (
            <div key={field.name} className="flex flex-col gap-1.5">
              <Label htmlFor={`general-config-${field.name}`}>{field.label}</Label>
              <Input
                id={`general-config-${field.name}`}
                type={field.type}
                autoComplete={field.autoComplete}
                readOnly={!canManage}
                aria-invalid={fieldError ? true : undefined}
                aria-describedby={
                  [helperId, fieldError ? errorId : undefined].filter(Boolean).join(" ") || undefined
                }
                {...register(field.name)}
              />
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
  );
}
