"use client";

import { useState } from "react";
import Link from "next/link";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { ExternalLink } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import {
  useTenantConfigDomain,
  useUpdateTenantConfigDomain,
  type TenantConfigProperty,
} from "@/lib/api/tenant-config";
import { isApiClientError } from "@/lib/api/error";
import { POLICY_PAGES, type PolicyKind } from "@/lib/api/public-home";
import {
  MAX_POLICY_LENGTH,
  policiesConfigSchema,
  toChangedConfigEntries,
  type PoliciesConfigFormValues,
} from "@/lib/validation/tenant-config";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ErrorState } from "@/components/states/error-state";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Textarea } from "@/components/ui/textarea";

const FIELDS: { name: keyof PoliciesConfigFormValues; kind: PolicyKind; help: string }[] = [
  { name: "terms_of_service", kind: "terms", help: "The rules for using your institute's site and classes." },
  { name: "refund_policy", kind: "refund", help: "When and how students can get their money back." },
  { name: "privacy_policy", kind: "privacy", help: "What student information you collect and how you use it." },
];

function toFormValues(properties: TenantConfigProperty[]): PoliciesConfigFormValues {
  const read = (key: string) => {
    const value = properties.find((property) => property.key === key)?.value;
    return typeof value === "string" ? value : "";
  };
  return {
    terms_of_service: read("terms_of_service"),
    refund_policy: read("refund_policy"),
    privacy_policy: read("privacy_policy"),
  };
}

/**
 * Settings → Policies (LOGIN-01 Phase B). The institute's Terms of Service,
 * Refund Policy and Privacy Policy as plain text (`GENERAL.terms_of_service`,
 * `refund_policy`, `privacy_policy`), published on /terms, /refund-policy and
 * /privacy and linked from the sign-in pages and the public footer.
 * `BRANDING_SETTINGS` VIEW/CREATE_EDIT server-side (audit-logged);
 * `canManageInstituteConfig` only picks editable vs read-only.
 */
export default function PolicySettingsPage() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useTenantConfigDomain("GENERAL");

  return (
    <div className="flex flex-col gap-6">
      <SettingsHeader
        active="/tenant-admin/settings/policies"
        description="Your Terms of Service, Refund Policy and Privacy Policy, shown on your public site."
      />
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading policies…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(properties) => <PoliciesForm properties={properties} canManage={canManage} />}
      </QueryStateBoundary>
    </div>
  );
}

function PoliciesForm({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const mutation = useUpdateTenantConfigDomain("GENERAL");
  const current = toFormValues(properties);
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    control,
    formState: { errors, isDirty },
  } = useForm<PoliciesConfigFormValues>({
    resolver: zodResolver(policiesConfigSchema),
    defaultValues: current,
  });
  const values = useWatch({ control });

  const onSubmit = handleSubmit(async (formValues) => {
    setPageError(null);
    setSaved(false);
    // Only surrounding whitespace is trimmed; the text's own line breaks are kept.
    const trimmed = Object.fromEntries(
      Object.entries(formValues).map(([key, value]) => [key, value.trim()])
    ) as PoliciesConfigFormValues;
    const changes = toChangedConfigEntries(trimmed, current);
    if (Object.keys(changes).length === 0) {
      reset(formValues);
      return;
    }
    try {
      const updated = await mutation.mutateAsync(changes);
      reset(toFormValues(updated));
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          if (FIELDS.some((field) => field.name === fieldError.field)) {
            setError(fieldError.field as keyof PoliciesConfigFormValues, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (error.status === 403) {
          setPageError({ message: "You don't have permission to change the policies.", code: error.code });
        } else if (!mapped) {
          setPageError({ message: error.message, code: error.code });
        }
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate aria-busy={mutation.isPending} className="flex max-w-4xl flex-col gap-6">
      <LiveRegion message={mutation.isPending ? "Saving policies…" : saved ? "Policies saved." : ""} />
      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}

      <fieldset disabled={!canManage || mutation.isPending} className="flex flex-col gap-6">
        <legend className="sr-only">Institute policies</legend>
        {FIELDS.map((field) => {
          const page = POLICY_PAGES[field.kind];
          const inputId = `policy-${field.name}`;
          const length = values[field.name]?.length ?? 0;
          const fieldError = errors[field.name];
          const published = Boolean(current[field.name]);
          return (
            <section key={field.name} className="flex flex-col gap-2 rounded-lg border border-border p-4" data-testid={`policy-field-${field.kind}`}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <Label htmlFor={inputId} className="text-base">
                    {page.title}
                  </Label>
                  <p id={`${inputId}-help`} className="text-sm text-muted-foreground">
                    {field.help} Leave empty to unpublish.
                  </p>
                </div>
                {published ? (
                  <Link
                    href={page.href}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                  >
                    View public page
                    <ExternalLink className="size-3.5" aria-hidden="true" />
                    <span className="sr-only">(opens in a new tab)</span>
                  </Link>
                ) : (
                  <span className="text-xs text-muted-foreground">Not published</span>
                )}
              </div>
              <Textarea
                id={inputId}
                rows={10}
                readOnly={!canManage}
                aria-invalid={fieldError ? true : undefined}
                aria-describedby={`${inputId}-help ${inputId}-count${fieldError ? ` ${inputId}-error` : ""}`}
                {...register(field.name)}
              />
              <p id={`${inputId}-count`} className="text-right text-xs text-muted-foreground">
                {length.toLocaleString()} / {MAX_POLICY_LENGTH.toLocaleString()} characters
              </p>
              {fieldError ? (
                <p id={`${inputId}-error`} role="alert" className="text-sm text-destructive">
                  {fieldError.message}
                </p>
              ) : null}
            </section>
          );
        })}
      </fieldset>

      {canManage ? (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={mutation.isPending || !isDirty} data-testid="policies-save">
            {mutation.isPending ? "Saving…" : "Save policies"}
          </Button>
          {saved && !mutation.isPending ? <span className="text-sm text-muted-foreground">Saved.</span> : null}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">You have view-only access to institute configuration.</p>
      )}
    </form>
  );
}
