"use client";

import Link from "next/link";
import { FileText } from "lucide-react";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { POLICY_PAGES, usePublicHome, usePublicPolicy, type PolicyKind } from "@/lib/api/public-home";
import { TENANT_FALLBACK_NAME } from "@/lib/branding/names";

/**
 * One of the host institute's policies (LOGIN-01 Phase B) on the public site.
 * The text is the institute's own plain text, rendered as text only (never
 * HTML), with its paragraphs and line breaks kept.
 */
export function PolicyPage({ kind }: { kind: PolicyKind }) {
  const { title } = POLICY_PAGES[kind];
  const policy = usePublicPolicy(kind);
  const name = usePublicHome().data?.instituteName?.trim() || TENANT_FALLBACK_NAME;

  return (
    <article className="mx-auto w-full max-w-3xl px-4 py-12 sm:px-6 lg:px-8" aria-labelledby="policy-heading" data-testid={`policy-${kind}`}>
      <p className="text-xs font-semibold uppercase tracking-widest text-primary">{name}</p>
      <h1 id="policy-heading" className="mt-2 text-3xl font-bold tracking-tight">
        {title}
      </h1>
      <div className="mt-8">
        {policy.isPending ? (
          <LoadingState label={`Loading ${title.toLowerCase()}…`} />
        ) : policy.isError ? (
          <ErrorState message={`We couldn't load the ${title.toLowerCase()}.`} onRetry={() => void policy.refetch()} />
        ) : policy.data.text ? (
          <div className="whitespace-pre-line break-words leading-relaxed text-foreground" data-testid="policy-text">
            {policy.data.text}
          </div>
        ) : (
          <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border px-6 py-12 text-center" data-testid="policy-empty">
            <FileText className="size-8 text-muted-foreground" aria-hidden="true" />
            <p className="font-medium">
              {name} hasn&apos;t published its {title.toLowerCase()} yet.
            </p>
            <p className="text-sm text-muted-foreground">
              Questions? <Link href="/#support" className="font-medium text-primary underline-offset-4 hover:underline">Contact the institute</Link>.
            </p>
          </div>
        )}
      </div>
    </article>
  );
}
