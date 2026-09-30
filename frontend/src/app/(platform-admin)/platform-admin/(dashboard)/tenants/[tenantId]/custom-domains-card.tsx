"use client";

import { useState } from "react";
import { AlertCircle } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  usePlatformCustomDomains,
  usePlatformSetCustomDomainsEnabled,
  usePlatformSuspendCustomDomain,
  type CustomDomain,
  type CustomDomainsOverview,
} from "@/lib/api/custom-domains";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { DomainStatusBadge } from "@/components/custom-domains/domain-status-badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";

/**
 * Platform Admin view of one institute's custom domains (TEN-DOM-01 /
 * ADR-021 decision 5): switch the feature on or off, and suspend a domain
 * with a required reason. The institute is named on every action, per the
 * UI rules for cross-tenant platform actions. The backend enforces the
 * Platform Admin role on every call.
 */
export function CustomDomainsCard({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const query = usePlatformCustomDomains(tenantId);

  return (
    <section aria-labelledby="custom-domains-heading" className="rounded-lg border border-border">
      <div className="border-b border-border px-4 py-3">
        <h2 id="custom-domains-heading" className="text-base font-semibold text-foreground">
          Custom domains
        </h2>
        <p className="text-sm text-muted-foreground">{tenantName}&apos;s own web addresses.</p>
      </div>
      <div className="px-4 py-3">
        {query.isPending ? (
          <LoadingState label="Loading custom domains…" />
        ) : query.isError ? (
          <ErrorState
            message={isApiClientError(query.error) ? query.error.message : "Couldn't load custom domains."}
            onRetry={() => query.refetch()}
          />
        ) : (
          <CustomDomainsDetails tenantId={tenantId} tenantName={tenantName} overview={query.data} />
        )}
      </div>
    </section>
  );
}

function CustomDomainsDetails({
  tenantId,
  tenantName,
  overview,
}: {
  tenantId: string;
  tenantName: string;
  overview: CustomDomainsOverview;
}) {
  return (
    <div className="flex flex-col gap-4">
      {!overview.available ? (
        <Alert role="status">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Custom domains aren&apos;t configured on this platform (PLATFORM_DOMAIN and
            CUSTOM_DOMAIN_CNAME_TARGET), so no domain can be added or resolved yet.
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-wrap items-center gap-3 text-sm">
        <span className="text-foreground">
          Custom domains are <strong>{overview.enabled ? "on" : "off"}</strong> for {tenantName}.
        </span>
        <EnabledSwitch tenantId={tenantId} tenantName={tenantName} enabled={overview.enabled} />
      </div>

      {overview.domains.length === 0 ? (
        <p className="text-sm text-muted-foreground">{tenantName} has not added a custom domain.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-border rounded-md border border-border">
          {overview.domains.map((domain) => (
            <li key={domain.id} className="flex flex-col gap-2 px-3 py-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex min-w-0 flex-col gap-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="break-all font-mono text-sm text-foreground">{domain.hostname}</span>
                  <DomainStatusBadge status={domain.status} />
                  {domain.primary ? <Badge variant="secondary">Primary</Badge> : null}
                </div>
                {domain.status === "SUSPENDED" && domain.suspendedReason ? (
                  <p className="text-sm text-muted-foreground">Suspended: {domain.suspendedReason}</p>
                ) : null}
              </div>
              {domain.status !== "SUSPENDED" ? (
                <SuspendAction tenantId={tenantId} tenantName={tenantName} domain={domain} />
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function EnabledSwitch({ tenantId, tenantName, enabled }: { tenantId: string; tenantName: string; enabled: boolean }) {
  const mutation = usePlatformSetCustomDomainsEnabled(tenantId);
  const [open, setOpen] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function setEnabled(next: boolean) {
    setErrorMessage(null);
    try {
      await mutation.mutateAsync(next);
      setOpen(false);
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  }

  if (!enabled) {
    return (
      <div className="flex flex-col gap-1">
        <Button type="button" size="sm" onClick={() => setEnabled(true)} disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Switching on…" : `Switch on for ${tenantName}`}
        </Button>
        {errorMessage ? (
          <p role="alert" className="text-sm text-destructive">
            {errorMessage}
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" size="sm" variant="outline" />}>
        Switch off for {tenantName}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Switch off custom domains for {tenantName}?</AlertDialogTitle>
          <AlertDialogDescription>
            {tenantName}&apos;s custom domains stop serving the site straight away, and links go back to
            its platform address. Its domains are kept and work again if you switch this back on.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={() => setEnabled(false)}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Switching off…" : `Switch off for ${tenantName}`}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function SuspendAction({ tenantId, tenantName, domain }: { tenantId: string; tenantName: string; domain: CustomDomain }) {
  const mutation = usePlatformSuspendCustomDomain(tenantId);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const reasonId = `suspend-reason-${domain.id}`;

  async function handleConfirm() {
    setErrorMessage(null);
    if (!reason.trim()) {
      setErrorMessage("A reason is required.");
      return;
    }
    try {
      await mutation.mutateAsync({ domainId: domain.id, reason: reason.trim() });
      setOpen(false);
      setReason("");
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" size="sm" variant="destructive" className="w-fit" />}>
        Suspend
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>
            Suspend {domain.hostname} for {tenantName}?
          </AlertDialogTitle>
          <AlertDialogDescription>
            The domain stops serving {tenantName}&apos;s site straight away. {tenantName} can&apos;t verify or
            remove it until the platform restores it.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={reasonId}>Reason (required)</Label>
          <Textarea
            id={reasonId}
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
            aria-invalid={errorMessage === "A reason is required." ? true : undefined}
          />
        </div>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Suspending…" : "Suspend domain"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
