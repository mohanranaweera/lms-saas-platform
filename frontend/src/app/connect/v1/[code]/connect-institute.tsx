"use client";

import { ArrowRight, Building2, SearchX, ShieldCheck, Smartphone } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { usePublicInstituteConnection } from "@/lib/api/institute-connection";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { buttonVariants } from "@/components/ui/button";

/** Only an absolute http(s) URL from our own backend is ever followed. */
function safeHttpUrl(value: string | null): string | null {
  if (!value || !URL.canParse(value)) return null;
  const url = new URL(value);
  return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
}

export function ConnectInstitute({ code }: { code: string }) {
  const query = usePublicInstituteConnection(code);

  if (query.isPending) return <LoadingState label="Finding your institute…" />;

  if (query.isError) {
    if (isApiClientError(query.error) && query.error.status === 404) {
      return (
        <div role="alert" className="flex flex-col items-center gap-3 text-center">
          <SearchX className="size-8 text-muted-foreground" aria-hidden="true" />
          <h1 className="text-lg font-semibold text-foreground">This institute link isn&apos;t valid</h1>
          <p className="text-sm text-muted-foreground">
            The QR or link may be mistyped, or the institute isn&apos;t available right now. Ask your
            institute for a new link.
          </p>
        </div>
      );
    }
    return <ErrorState message="Couldn't look up this institute. Please try again." onRetry={() => query.refetch()} />;
  }

  const institute = query.data;
  const signInUrl = safeHttpUrl(institute.signInUrl);
  const androidUrl = safeHttpUrl(institute.mobileApps.androidUrl);
  const iosUrl = safeHttpUrl(institute.mobileApps.iosUrl);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col items-center gap-3 text-center">
        {institute.logoUrl ? (
          // eslint-disable-next-line @next/next/no-img-element -- signed, short-lived logo URL
          <img src={institute.logoUrl} alt="" className="size-16 rounded-xl border border-border bg-white object-contain p-1.5" />
        ) : (
          <span className="flex size-16 items-center justify-center rounded-xl bg-muted" aria-hidden="true">
            <Building2 className="size-7 text-muted-foreground" />
          </span>
        )}
        <div>
          <p className="text-sm text-muted-foreground">You&apos;re connecting to</p>
          <h1 className="text-xl font-semibold text-foreground">{institute.instituteName}</h1>
        </div>
      </div>

      {signInUrl ? (
        <a href={signInUrl} rel="noopener" className={buttonVariants({ size: "lg", className: "w-full" })}>
          Continue to sign in
          <ArrowRight aria-hidden="true" />
        </a>
      ) : (
        <p role="status" className="rounded-lg bg-muted/50 px-3 py-2 text-center text-sm text-muted-foreground">
          Sign-in for this institute isn&apos;t available from this link yet. Please contact your institute.
        </p>
      )}

      <p className="flex items-start gap-2 text-xs text-muted-foreground">
        <ShieldCheck className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
        Sign in with your own student account. This link only tells us which institute you belong to.
      </p>

      {androidUrl || iosUrl ? (
        <div className="flex flex-wrap items-center justify-center gap-2 text-sm">
          <span className="text-muted-foreground">Get the student app:</span>
          {androidUrl ? (
            <a href={androidUrl} target="_blank" rel="noopener noreferrer" className={buttonVariants({ variant: "outline", size: "sm" })}>
              Google Play
            </a>
          ) : null}
          {iosUrl ? (
            <a href={iosUrl} target="_blank" rel="noopener noreferrer" className={buttonVariants({ variant: "outline", size: "sm" })}>
              App Store
            </a>
          ) : null}
        </div>
      ) : (
        <p className="flex items-start gap-2 text-xs text-muted-foreground">
          <Smartphone className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
          Our student mobile app isn&apos;t available yet. You can use the website on your phone.
        </p>
      )}
    </div>
  );
}
