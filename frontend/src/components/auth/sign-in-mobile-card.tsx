"use client";

import { useState } from "react";
import { Smartphone } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { isApiClientError } from "@/lib/api/error";
import { useLoginMobile, useSaveLoginMobile, type LoginMobilePath } from "@/lib/api/login-mobile";

/**
 * ADR-031: view and change one account's sign-in mobile. The backend decides
 * everything (format, institute-wide uniqueness, permission); this card only
 * shows the result and maps `loginMobile` field errors onto the input.
 */
export function SignInMobileCard({
  path,
  title = "Sign-in mobile",
  description,
  canEdit,
  idPrefix,
}: {
  path: LoginMobilePath;
  title?: string;
  description: string;
  canEdit: boolean;
  idPrefix: string;
}) {
  const query = useLoginMobile(path);

  return (
    <section
      aria-labelledby={`${idPrefix}-heading`}
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
      data-testid={`${idPrefix}-card`}
    >
      <div className="flex items-start gap-3">
        <Smartphone className="mt-0.5 size-5 text-muted-foreground" aria-hidden="true" />
        <div>
          <h2 id={`${idPrefix}-heading`} className="text-base font-medium text-foreground">
            {title}
          </h2>
          <p className="text-sm text-muted-foreground">{description}</p>
        </div>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading sign-in mobile…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="The sign-in mobile could not be loaded."
      >
        {(data) => <SignInMobileForm path={path} current={data.loginMobile} canEdit={canEdit} idPrefix={idPrefix} />}
      </QueryStateBoundary>
    </section>
  );
}

function SignInMobileForm({
  path,
  current,
  canEdit,
  idPrefix,
}: {
  path: LoginMobilePath;
  current: string | null;
  canEdit: boolean;
  idPrefix: string;
}) {
  const mutation = useSaveLoginMobile(path);
  const [value, setValue] = useState(current ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const inputId = `${idPrefix}-input`;

  async function save(next: string | null) {
    setError(null);
    setSaved(null);
    try {
      const result = await mutation.mutateAsync(next);
      setValue(result.loginMobile ?? "");
      setSaved(result.loginMobile ? "Sign-in mobile saved." : "Mobile sign-in removed for this account.");
    } catch (failure) {
      if (isApiClientError(failure)) {
        const fieldError = failure.fieldErrors.find((entry) => entry.field === "loginMobile");
        setError(
          fieldError?.message ??
            (failure.status === 403 ? "You don't have permission to change this." : failure.message)
        );
        return;
      }
      setError("Something went wrong. Please try again.");
    }
  }

  if (!canEdit) {
    return (
      <p className="text-sm text-foreground" data-testid={`${idPrefix}-value`}>
        {current ?? "Not set — signs in with email only."}
      </p>
    );
  }

  return (
    <form
      noValidate
      aria-busy={mutation.isPending}
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        void save(value.trim() === "" ? null : value.trim());
      }}
    >
      <Label htmlFor={inputId}>Mobile number</Label>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          id={inputId}
          type="tel"
          inputMode="tel"
          autoComplete="off"
          placeholder="e.g. 077 123 4567"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={`${inputId}-help${error ? ` ${inputId}-error` : ""}`}
          disabled={mutation.isPending}
          className="sm:max-w-xs"
        />
        <div className="flex gap-2">
          <Button type="submit" disabled={mutation.isPending} data-testid={`${idPrefix}-save`}>
            {mutation.isPending ? "Saving…" : "Save"}
          </Button>
          {current ? (
            <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => void save(null)}>
              Remove
            </Button>
          ) : null}
        </div>
      </div>
      <p id={`${inputId}-help`} className="text-xs text-muted-foreground">
        {current ? `Currently ${current}. ` : "Not set — signs in with email only. "}
        Each number can belong to only one account in your institute.
      </p>
      {error ? (
        <p id={`${inputId}-error`} role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
      {saved ? (
        <p role="status" className="text-sm text-muted-foreground">
          {saved}
        </p>
      ) : null}
    </form>
  );
}
