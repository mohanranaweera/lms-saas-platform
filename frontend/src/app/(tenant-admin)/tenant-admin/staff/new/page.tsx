"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  staffCreateSchema,
  STAFF_ROLE_OPTIONS,
  type StaffCreateFormValues,
} from "@/lib/validation/staff";
import { useCreateStaff, type StaffCreated } from "@/lib/api/staff";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";

/**
 * Add Staff (MVP-005) — a create-only form, so per `.claude/rules/frontend.md`
 * it has no empty state of its own (there's no "empty" concept for a form).
 * A Tenant Admin who lacks permission still gets a real, backend-verified
 * 403 on submit — never a client-guessed permission block — rendered via
 * `PermissionDeniedState` (see `onSubmit`'s FORBIDDEN branch) rather than the
 * generic retry-oriented error box, since retrying an unauthorized submit
 * would just fail identically.
 *
 * The backend generates the staff member's temporary password itself (this
 * form never collects one) and returns it exactly once, on the create
 * response (`StaffCreated.temporaryPassword`) — it is never retrievable
 * again afterward. On success this page holds that result in local state and
 * shows a one-time reveal panel (see `CreatedStaffReveal`) instead of
 * immediately redirecting, so the admin has a chance to copy/relay it before
 * navigating away.
 */

const KNOWN_FIELD_NAMES = new Set<keyof StaffCreateFormValues>(["name", "email", "roleCode"]);

export default function NewStaffPage() {
  const router = useRouter();
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);
  const [permissionError, setPermissionError] = useState<ApiClientError | null>(null);
  const [created, setCreated] = useState<StaffCreated | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<StaffCreateFormValues>({
    resolver: zodResolver(staffCreateSchema),
    defaultValues: { name: "", email: "" },
  });

  const mutation = useCreateStaff();

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      const result = await mutation.mutateAsync(values);
      // Real, backend-confirmed data — the staff account is genuinely active
      // immediately (no approval workflow here, unlike tenant registration).
      // The backend returns the generated password exactly once, on this
      // response — hold it in local state and show the one-time reveal
      // panel instead of navigating away immediately, so the admin has a
      // chance to copy/relay it first.
      setCreated(result);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.status === 403) {
          // A retry-oriented generic error box would be misleading here — an
          // unauthorized submit fails identically on retry. The real
          // enforcement already happened server-side; this only changes what
          // the UI shows for it.
          setPermissionError(error);
          return;
        }

        if (error.code === "CONFLICT") {
          // StaffService only ever raises CONFLICT for a duplicate email
          // within the tenant (see backend StaffService#createStaff), so
          // attributing it to this field is safe — same pattern as
          // register-institute's duplicate-subdomain handling.
          setError("email", { type: "server", message: error.message });
          return;
        }

        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field as keyof StaffCreateFormValues)
          );
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field as keyof StaffCreateFormValues)) {
              setError(fieldError.field as keyof StaffCreateFormValues, {
                type: "server",
                message: fieldError.message,
              });
            }
          }
          // Every known field error is now visible inline; surface anything
          // the backend attributed to a field this form doesn't render via
          // the page-level error instead of losing it silently.
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

  if (permissionError) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Add staff</h1>
        </div>
        <PermissionDeniedState error={permissionError} dashboardHref="/tenant-admin/staff" />
      </div>
    );
  }

  if (created) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Add staff</h1>
        </div>
        <CreatedStaffReveal
          created={created}
          onContinue={() => router.push(`/tenant-admin/staff/${created.id}`)}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Add staff</h1>
        <p className="text-sm text-muted-foreground">
          Create a staff account for your institute.
        </p>
      </div>

      <Card className="max-w-lg">
        <CardHeader>
          <h2 data-slot="card-title" className="font-heading text-base leading-snug font-medium">
            Staff details
          </h2>
          <CardDescription>All fields are required.</CardDescription>
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
              {mutation.isPending ? "Creating staff account…" : ""}
            </span>
            <fieldset disabled={mutation.isPending} className="flex flex-col gap-4">
              <legend className="sr-only">Staff details</legend>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="staff-name">Name</Label>
                <Input
                  id="staff-name"
                  autoComplete="name"
                  aria-invalid={!!errors.name}
                  aria-describedby={errors.name ? "staff-name-error" : undefined}
                  {...register("name")}
                />
                {errors.name ? (
                  <p id="staff-name-error" role="alert" className="text-xs text-destructive">
                    {errors.name.message}
                  </p>
                ) : null}
              </div>

              <div className="flex flex-col gap-1.5">
                <Label htmlFor="staff-email">Email</Label>
                <Input
                  id="staff-email"
                  type="email"
                  autoComplete="email"
                  aria-invalid={!!errors.email}
                  aria-describedby={errors.email ? "staff-email-error" : undefined}
                  {...register("email")}
                />
                {errors.email ? (
                  <p id="staff-email-error" role="alert" className="text-xs text-destructive">
                    {errors.email.message}
                  </p>
                ) : null}
              </div>

              <fieldset
                className="flex flex-col gap-2"
                aria-invalid={!!errors.roleCode}
                aria-describedby={errors.roleCode ? "staff-role-error" : undefined}
              >
                <legend className="text-sm font-medium text-foreground">Role</legend>
                <div className="flex flex-col gap-1.5">
                  {STAFF_ROLE_OPTIONS.map((option) => (
                    <label
                      key={option.value}
                      className="flex items-center gap-2 text-sm text-foreground"
                    >
                      <input
                        type="radio"
                        value={option.value}
                        className="size-4"
                        {...register("roleCode")}
                      />
                      {option.label}
                    </label>
                  ))}
                </div>
                {errors.roleCode ? (
                  <p id="staff-role-error" role="alert" className="text-xs text-destructive">
                    {errors.roleCode.message}
                  </p>
                ) : null}
              </fieldset>

              <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
                {mutation.isPending ? "Creating…" : "Create staff account"}
              </Button>
            </fieldset>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * One-time reveal of the backend-generated temporary password. This value is
 * never retrievable again after this render (not on `GET /v1/staff` or
 * `GET /v1/staff/{id}`) — the copy below says so explicitly, and there is no
 * way back to this screen once the admin continues on.
 */
function CreatedStaffReveal({
  created,
  onContinue,
}: {
  created: StaffCreated;
  onContinue: () => void;
}) {
  const [copied, setCopied] = useState(false);

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(created.temporaryPassword);
      setCopied(true);
    } catch {
      // Clipboard access can fail (unsupported browser, denied permission,
      // insecure context) — the password is still visible/selectable in the
      // field below, so this is a soft failure, not a blocker.
      setCopied(false);
    }
  };

  return (
    <Card className="max-w-lg">
      <CardHeader>
        <h2 data-slot="card-title" className="font-heading text-base leading-snug font-medium">
          Staff account created
        </h2>
        <CardDescription>
          Share this temporary password with {created.name} now — it will not be shown again.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="staff-temporary-password">Temporary password</Label>
          <div role="status" aria-live="polite" className="flex flex-row gap-2">
            <Input
              id="staff-temporary-password"
              readOnly
              value={created.temporaryPassword}
              onFocus={(event) => event.currentTarget.select()}
              className="font-mono"
            />
            <Button type="button" variant="outline" onClick={onCopy}>
              {copied ? "Copied" : "Copy"}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">
            This is {created.name}&apos;s temporary password for signing in as {created.email}.
            It is shown only once and cannot be retrieved again — the staff member must change it
            the first time they sign in.
          </p>
        </div>

        <Button type="button" onClick={onContinue}>
          Continue to staff details
        </Button>
      </CardContent>
    </Card>
  );
}
