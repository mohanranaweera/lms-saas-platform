"use client";

import { ExternalLink, Store } from "lucide-react";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { StorefrontStatusBadge, storefrontStatus } from "@/components/storefront/storefront-status-badge";
import { isApiClientError } from "@/lib/api/error";
import { useMyStorefront } from "@/lib/api/teacher-storefronts";

/**
 * STORE-01 / ADR-037 decision 1: the teacher's own storefront, read-only.
 * Only the institute (TEACHER_STOREFRONT_MANAGE) can turn it on, rename it or
 * choose its courses; editing this profile grants no storefront rights.
 */
export function TeacherStorefrontStatus() {
  const query = useMyStorefront();

  return (
    <section
      className="flex flex-col gap-3 rounded-xl border border-border bg-card p-5 shadow-sm"
      aria-labelledby="teacher-storefront-heading"
      data-testid="teacher-storefront-status"
    >
      <div className="flex items-center gap-2">
        <Store className="size-5 text-primary" aria-hidden="true" />
        <h2 id="teacher-storefront-heading" className="text-base font-semibold text-foreground">
          My storefront
        </h2>
      </div>
      <StorefrontBody query={query} />
    </section>
  );
}

function StorefrontBody({ query }: { query: ReturnType<typeof useMyStorefront> }) {
  if (query.status === "pending") return <LoadingState label="Loading your storefront…" className="py-4" />;
  if (query.status === "error") {
    if (isApiClientError(query.error) && query.error.status === 404) {
      return (
        <p className="text-sm text-muted-foreground" data-testid="teacher-storefront-none">
          Your personal homepage isn&apos;t set up. Ask your institute admin if you&apos;d like one.
        </p>
      );
    }
    return (
      <ErrorState
        message="We couldn't load your storefront."
        code={isApiClientError(query.error) ? query.error.code : undefined}
        onRetry={() => void query.refetch()}
      />
    );
  }

  const storefront = query.data;
  const status = storefrontStatus(storefront);
  const published = storefront.products.filter((product) => product.published).length;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm text-muted-foreground">Status:</span>
        <StorefrontStatusBadge status={status} />
      </div>
      {status === "LIVE" && storefront.publishedUrl ? (
        <p className="text-sm">
          <span className="text-muted-foreground">Address: </span>
          <a
            href={storefront.publishedUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 break-all text-primary hover:underline"
            data-testid="teacher-storefront-url"
          >
            {storefront.publishedUrl}
            <ExternalLink className="size-3.5 shrink-0" aria-hidden="true" />
            <span className="sr-only">(opens in a new tab)</span>
          </a>
        </p>
      ) : (
        <p className="text-sm text-muted-foreground">Your storefront is turned off. Your institute admin can turn it on.</p>
      )}
      <p className="text-sm text-muted-foreground">
        {published} {published === 1 ? "course is" : "courses are"} published on it. Your institute admin chooses
        which courses it sells.
      </p>
    </div>
  );
}
