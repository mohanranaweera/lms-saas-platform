"use client";

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Check, Copy, Globe } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageCustomDomains } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  useAddCustomDomain,
  useCustomDomains,
  useMakePrimaryCustomDomain,
  useRemoveCustomDomain,
  useVerifyCustomDomain,
  type CustomDomain,
  type CustomDomainsOverview,
  type DnsRecord,
} from "@/lib/api/custom-domains";
import {
  customDomainSchema,
  type CustomDomainFormInput,
  type CustomDomainFormValues,
} from "@/lib/validation/custom-domains";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { DomainStatusBadge } from "@/components/custom-domains/domain-status-badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
 * Tenant Admin → Custom domain (TEN-DOM-01 / ADR-021). The institute serves
 * its site on its own address: add the domain, create the two DNS records
 * shown, then verify. Only a verified (Active) domain serves the site; the
 * platform subdomain keeps working throughout.
 *
 * Read-only Auditors see everything but the actions. The backend decides
 * every action (`CUSTOM_DOMAINS` permission, the platform switch, ownership
 * proof) - this page only reflects its answers.
 */
export default function CustomDomainSettingsPage() {
  const { session } = useAuth();
  const canManage = canManageCustomDomains(session?.role ?? null);
  const query = useCustomDomains();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Custom domain</h1>
        <p className="text-sm text-muted-foreground">
          Serve your institute&apos;s site on your own address, such as learn.yourinstitute.lk. Your
          current address keeps working.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading custom domains…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(overview) => <CustomDomainsBody overview={overview} canManage={canManage} />}
      </QueryStateBoundary>
    </div>
  );
}

function CustomDomainsBody({ overview, canManage }: { overview: CustomDomainsOverview; canManage: boolean }) {
  const canAdd = canManage && overview.available && overview.enabled && overview.domains.length < overview.maxDomains;

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      {!overview.available ? (
        <Alert role="status">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Custom domains aren&apos;t available on this platform yet. Your institute is reachable at its
            current address.
          </AlertDescription>
        </Alert>
      ) : !overview.enabled ? (
        <Alert role="status">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Custom domains aren&apos;t enabled for your institute. Contact the platform team to have them
            switched on.
          </AlertDescription>
        </Alert>
      ) : null}

      {canAdd ? <AddDomainForm /> : null}
      {canManage && overview.enabled && overview.available && !canAdd ? (
        <p className="text-sm text-muted-foreground">
          You have the maximum of {overview.maxDomains} domains. Remove one to add another.
        </p>
      ) : null}

      {overview.domains.length === 0 ? (
        <EmptyState
          icon={<Globe aria-hidden="true" className="size-6 text-muted-foreground" />}
          title="No custom domain yet"
          description={
            canManage && overview.enabled && overview.available
              ? "Add your domain above, then create the DNS records we show you at your domain provider."
              : "Your institute uses its platform address only."
          }
        />
      ) : (
        <section aria-labelledby="domains-heading" className="flex flex-col gap-4">
          <h2 id="domains-heading" className="text-base font-medium text-foreground">
            Your domains
          </h2>
          <p className="text-sm text-muted-foreground">
            Emails, payment pages and sign-in links use your primary domain. People signed in on another
            address sign in again on the new one.
          </p>
          <ul className="flex flex-col gap-4">
            {overview.domains.map((domain) => (
              <li key={domain.id}>
                <DomainCard domain={domain} canManage={canManage} featureOn={overview.enabled && overview.available} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {!canManage ? (
        <p className="text-sm text-muted-foreground">Only a Tenant Admin can change custom domains.</p>
      ) : null}
    </div>
  );
}

function AddDomainForm() {
  const addMutation = useAddCustomDomain();
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<CustomDomainFormInput, unknown, CustomDomainFormValues>({
    resolver: zodResolver(customDomainSchema),
    defaultValues: { hostname: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await addMutation.mutateAsync(values.hostname);
      reset({ hostname: "" });
    } catch (error) {
      if (isApiClientError(error)) {
        const fieldError = error.fieldErrors.find((item) => item.field === "hostname");
        if (fieldError) {
          setError("hostname", { type: "server", message: `Domain ${fieldError.message}.` });
        } else {
          setPageError({ message: error.message, code: error.code });
        }
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <section aria-labelledby="add-domain-heading" className="flex flex-col gap-3">
      <h2 id="add-domain-heading" className="text-base font-medium text-foreground">
        Add a domain
      </h2>
      {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3 sm:flex-row sm:items-start">
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor="custom-domain-hostname">Domain</Label>
          <Input
            id="custom-domain-hostname"
            placeholder="learn.yourinstitute.lk"
            autoComplete="off"
            spellCheck={false}
            aria-invalid={errors.hostname ? true : undefined}
            aria-describedby={errors.hostname ? "custom-domain-hostname-error" : "custom-domain-hostname-help"}
            {...register("hostname")}
          />
          {errors.hostname ? (
            <p id="custom-domain-hostname-error" role="alert" className="text-sm text-destructive">
              {errors.hostname.message}
            </p>
          ) : (
            <p id="custom-domain-hostname-help" className="text-sm text-muted-foreground">
              A subdomain of a domain you own works best, such as learn.yourinstitute.lk.
            </p>
          )}
        </div>
        <Button
          type="submit"
          className="sm:mt-6"
          disabled={addMutation.isPending}
          aria-busy={addMutation.isPending}
        >
          {addMutation.isPending ? "Adding…" : "Add domain"}
        </Button>
      </form>
    </section>
  );
}

function DomainCard({
  domain,
  canManage,
  featureOn,
}: {
  domain: CustomDomain;
  canManage: boolean;
  featureOn: boolean;
}) {
  const suspended = domain.status === "SUSPENDED";
  const headingId = `domain-${domain.id}-heading`;

  return (
    <article aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 id={headingId} className="break-all font-mono text-base font-medium text-foreground">
          {domain.hostname}
        </h3>
        <DomainStatusBadge status={domain.status} />
        {domain.primary ? <Badge variant="secondary">Primary</Badge> : null}
      </div>

      {suspended ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            The platform team suspended this domain
            {domain.suspendedReason ? ` (${domain.suspendedReason})` : ""}. It no longer serves your site.
            Contact them to restore it.
          </AlertDescription>
        </Alert>
      ) : (
        <>
          {domain.status !== "ACTIVE" && domain.lastError ? (
            <Alert role="status">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{domain.lastError}</AlertDescription>
            </Alert>
          ) : null}
          {domain.primary && domain.status === "DNS_BROKEN" ? (
            <p className="text-sm text-muted-foreground">
              While the records are missing, emails and payment pages link to your platform address instead.
            </p>
          ) : null}
          {domain.status === "ACTIVE" ? (
            <p className="text-sm text-muted-foreground">
              Serving your site{domain.verifiedAt ? ` since ${formatDateTime(domain.verifiedAt)}` : ""}. Keep
              both DNS records in place: we check them daily.
            </p>
          ) : (
            <p className="text-sm text-muted-foreground">
              Create these two records at your domain provider, then select Verify. DNS changes can take a
              few minutes to an hour to appear.
            </p>
          )}
          <DnsRecordsList records={domain.dnsRecords} />
        </>
      )}

      {domain.lastCheckedAt ? (
        <p className="text-xs text-muted-foreground">Last checked {formatDateTime(domain.lastCheckedAt)}</p>
      ) : null}

      {canManage && !suspended ? <DomainActions domain={domain} featureOn={featureOn} /> : null}
    </article>
  );
}

function DnsRecordsList({ records }: { records: DnsRecord[] }) {
  return (
    <dl className="flex flex-col gap-3">
      {records.map((record) => (
        <div key={record.type} className="grid grid-cols-1 gap-1 rounded-md bg-muted/40 p-3 text-sm md:grid-cols-[5rem_1fr_1fr] md:gap-3">
          <dt className="font-medium text-foreground">{record.type}</dt>
          <dd className="flex min-w-0 flex-col gap-0.5">
            <span className="text-xs text-muted-foreground">Name</span>
            <span className="flex items-center gap-1">
              <code className="break-all">{record.name}</code>
              <CopyButton value={record.name} label={`Copy ${record.type} name`} />
            </span>
          </dd>
          <dd className="flex min-w-0 flex-col gap-0.5">
            <span className="text-xs text-muted-foreground">Value</span>
            {record.value ? (
              <span className="flex items-center gap-1">
                <code className="break-all">{record.value}</code>
                <CopyButton value={record.value} label={`Copy ${record.type} value`} />
              </span>
            ) : (
              <span className="text-muted-foreground">Not configured by the platform yet</span>
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 2000);
    return () => window.clearTimeout(timer);
  }, [copied]);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <>
      <Button type="button" variant="ghost" size="icon-sm" aria-label={label} onClick={copy}>
        {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
      </Button>
      <span className="sr-only" aria-live="polite">
        {copied ? "Copied" : ""}
      </span>
    </>
  );
}

function DomainActions({ domain, featureOn }: { domain: CustomDomain; featureOn: boolean }) {
  const verifyMutation = useVerifyCustomDomain();
  const primaryMutation = useMakePrimaryCustomDomain();
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  async function verify() {
    setMessage(null);
    try {
      const result = await verifyMutation.mutateAsync(domain.id);
      setMessage(
        result.status === "ACTIVE"
          ? { text: `${result.hostname} is verified and serving your site.`, error: false }
          : { text: "Not verified yet. Check the records shown above, then try again in a few minutes.", error: true }
      );
    } catch (error) {
      setMessage({ text: isApiClientError(error) ? error.message : "Something went wrong. Please try again.", error: true });
    }
  }

  async function makePrimary() {
    setMessage(null);
    try {
      await primaryMutation.mutateAsync(domain.id);
      setMessage({ text: `${domain.hostname} is now your primary domain.`, error: false });
    } catch (error) {
      setMessage({ text: isApiClientError(error) ? error.message : "Something went wrong. Please try again.", error: true });
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        {featureOn ? (
          <Button
            type="button"
            variant={domain.status === "ACTIVE" ? "outline" : "default"}
            onClick={verify}
            disabled={verifyMutation.isPending}
            aria-busy={verifyMutation.isPending}
          >
            {verifyMutation.isPending ? "Checking DNS…" : domain.status === "ACTIVE" ? "Check again" : "Verify"}
          </Button>
        ) : null}
        {featureOn && domain.status === "ACTIVE" && !domain.primary ? (
          <Button
            type="button"
            variant="outline"
            onClick={makePrimary}
            disabled={primaryMutation.isPending}
            aria-busy={primaryMutation.isPending}
          >
            {primaryMutation.isPending ? "Saving…" : "Make primary"}
          </Button>
        ) : null}
        <RemoveDomainAction domain={domain} />
      </div>
      {message ? (
        <p role={message.error ? "alert" : "status"} className={message.error ? "text-sm text-destructive" : "text-sm text-muted-foreground"}>
          {message.text}
        </p>
      ) : null}
    </div>
  );
}

function RemoveDomainAction({ domain }: { domain: CustomDomain }) {
  const removeMutation = useRemoveCustomDomain();
  const [open, setOpen] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  async function handleConfirm() {
    setErrorMessage(null);
    try {
      await removeMutation.mutateAsync(domain.id);
      setOpen(false);
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" variant="outline" />}>Remove</AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remove {domain.hostname}?</AlertDialogTitle>
          <AlertDialogDescription>
            Your site stops answering on this address straight away
            {domain.primary ? ", and links in emails go back to your platform address" : ""}. You can add it
            again later.
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
            onClick={handleConfirm}
            disabled={removeMutation.isPending}
            aria-busy={removeMutation.isPending}
          >
            {removeMutation.isPending ? "Removing…" : "Remove domain"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
