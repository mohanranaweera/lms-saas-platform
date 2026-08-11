"use client";

import { useState } from "react";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  tenantRegistrationSchema,
  type TenantRegistrationFormValues,
} from "@/lib/validation/tenant-registration";
import { useRegisterTenant, type TenantRegistrationResult } from "@/lib/api/tenant-registrations";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { ErrorState } from "@/components/states/error-state";
import { CheckCircle2 } from "lucide-react";

/**
 * Public tenant self-registration entry point. Deliberately NOT at `/register` —
 * that path is already owned by `app/(auth)/register/page.tsx`, an unrelated,
 * pre-existing disabled placeholder for a future generic user-account-creation
 * flow. This screen only ever submits a registration application; it never
 * activates/approves a tenant — approval is a Platform Admin, backend-confirmed
 * event from a module that doesn't exist yet (see `(platform-admin)/.../tenants`).
 */

interface FieldConfig {
  name: keyof TenantRegistrationFormValues;
  label: string;
  type?: string;
  autoComplete?: string;
  helperText?: string;
  optional?: boolean;
}

const FIELDS: FieldConfig[] = [
  {
    name: "name",
    label: "Institute name",
    autoComplete: "organization",
  },
  {
    name: "subdomain",
    label: "Subdomain",
    autoComplete: "off",
    helperText:
      "Lowercase letters, numbers, and hyphens only — this becomes part of your institute's web address.",
  },
  {
    name: "requestedPlan",
    label: "Requested plan",
    autoComplete: "off",
    helperText: "Tell us which plan you're interested in — our team will confirm availability.",
  },
  {
    name: "contactName",
    label: "Contact name",
    autoComplete: "name",
  },
  {
    name: "contactEmail",
    label: "Contact email",
    type: "email",
    autoComplete: "email",
  },
  {
    name: "contactPhone",
    label: "Contact phone",
    type: "tel",
    autoComplete: "tel",
    optional: true,
  },
];

const KNOWN_FIELD_NAMES = new Set<string>(FIELDS.map((field) => field.name));

/**
 * Matches `CardTitle`'s styling (`components/ui/card.tsx`) but rendered as a
 * native `<h1>` rather than `CardTitle`'s `<div>` — this page has no other
 * page-level heading, so its card title IS the page heading and needs real
 * `<h1>` semantics, not a `div role="heading"` approximation.
 */
const PAGE_HEADING_CLASS = "font-heading text-base leading-snug font-medium";

export default function RegisterInstitutePage() {
  const [submitted, setSubmitted] = useState<TenantRegistrationResult | null>(null);
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<TenantRegistrationFormValues>({
    resolver: zodResolver(tenantRegistrationSchema),
    defaultValues: {
      name: "",
      subdomain: "",
      requestedPlan: "",
      contactName: "",
      contactEmail: "",
      contactPhone: "",
    },
  });

  const mutation = useRegisterTenant();

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      const result = await mutation.mutateAsync({
        ...values,
        contactPhone: values.contactPhone?.trim() ? values.contactPhone.trim() : undefined,
      });
      setSubmitted(result);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
          );
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              setError(fieldError.field as keyof TenantRegistrationFormValues, {
                type: "server",
                message: fieldError.message,
              });
            }
          }
          // Every known field error is now visible inline. Any error the
          // backend attributed to a field this form doesn't render would
          // otherwise be attached to a `setError` key nothing on screen
          // displays, silently leaving the user with no visible feedback —
          // surface those via the page-level error instead.
          if (unmapped.length > 0) {
            setPageError({
              message: "Some information couldn't be validated.",
              code: error.code,
              fieldErrors: unmapped,
            });
          }
          return;
        }

        if (error.code === "CONFLICT") {
          // TenantRegistrationService only ever raises CONFLICT for a
          // duplicate subdomain today (see backend TenantRegistrationService),
          // so attributing it to this field is safe. If a second CONFLICT
          // reason is ever added to this endpoint, this mapping must be
          // revisited rather than assumed to still be subdomain-only.
          setError("subdomain", { type: "server", message: error.message });
          return;
        }

        setPageError({ message: error.message, code: error.code });
        return;
      }

      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <section className="flex flex-1 flex-col items-center justify-center px-4 py-12 sm:px-6">
      <div className="w-full max-w-md">
        {submitted ? (
          <Card>
            <CardHeader>
              <h1 data-slot="card-title" className={PAGE_HEADING_CLASS}>
                Application submitted
              </h1>
              <CardDescription>
                Your institute&apos;s registration has been received and is pending review.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              <div
                role="status"
                className="flex items-start gap-3 rounded-md border border-border bg-muted/40 px-3 py-3 text-sm text-foreground"
              >
                <CheckCircle2
                  className="mt-0.5 size-5 shrink-0 text-foreground"
                  aria-hidden="true"
                />
                <p>
                  Thanks — we&apos;ve recorded your application for{" "}
                  <span className="font-medium">{submitted.subdomain}</span>. A Platform Admin
                  will review it; your institute is{" "}
                  <span className="font-medium">not yet active</span> and no one can sign in
                  until the review is complete. We&apos;ll be in touch at the contact email you
                  provided.
                </p>
              </div>
              <Link
                href="/"
                className="text-center text-sm font-medium text-foreground hover:underline"
              >
                Back to home
              </Link>
            </CardContent>
          </Card>
        ) : (
          <Card>
            <CardHeader>
              <h1 data-slot="card-title" className={PAGE_HEADING_CLASS}>
                Register your institute
              </h1>
              <CardDescription>
                Tell us about your institute. Our team reviews every application before it goes
                live.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-4">
              {pageError ? (
                <ErrorState
                  message={pageError.message}
                  code={pageError.code}
                  fieldErrors={pageError.fieldErrors}
                  onRetry={() => setPageError(null)}
                />
              ) : null}
              <form
                className="flex flex-col gap-4"
                onSubmit={onSubmit}
                aria-busy={mutation.isPending}
                noValidate
              >
                <span role="status" aria-live="polite" className="sr-only">
                  {mutation.isPending ? "Submitting your application…" : ""}
                </span>
                <fieldset disabled={mutation.isPending} className="flex flex-col gap-4">
                  <legend className="sr-only">Register your institute</legend>
                  {FIELDS.map((field) => {
                    const fieldError = errors[field.name];
                    const errorId = `register-institute-${field.name}-error`;
                    const helperId = field.helperText
                      ? `register-institute-${field.name}-helper`
                      : undefined;

                    return (
                      <div key={field.name} className="flex flex-col gap-1.5">
                        <Label htmlFor={`register-institute-${field.name}`}>
                          {field.label}
                          {field.optional ? (
                            <span className="font-normal text-muted-foreground">
                              {" "}
                              (optional)
                            </span>
                          ) : null}
                        </Label>
                        <Input
                          id={`register-institute-${field.name}`}
                          type={field.type ?? "text"}
                          autoComplete={field.autoComplete}
                          aria-invalid={fieldError ? true : undefined}
                          aria-describedby={
                            [helperId, fieldError ? errorId : undefined]
                              .filter(Boolean)
                              .join(" ") || undefined
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
                  <Button type="submit" className="w-full" disabled={mutation.isPending}>
                    {mutation.isPending ? "Submitting…" : "Submit application"}
                  </Button>
                </fieldset>
              </form>
            </CardContent>
          </Card>
        )}
      </div>
    </section>
  );
}
