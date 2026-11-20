import { ExternalLink } from "lucide-react";
import type { StorefrontAdmin } from "@/lib/api/teacher-storefronts";

function UrlRow({ label, url, testId }: { label: string; url: string | null; testId: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd className="text-sm break-all text-foreground" data-testid={testId}>
        {url ? (
          <a href={url} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">
            {url}
            <ExternalLink className="size-3.5 shrink-0" aria-hidden="true" />
            <span className="sr-only">(opens in a new tab)</span>
          </a>
        ) : (
          "—"
        )}
      </dd>
    </div>
  );
}

/** Preview/published URLs (server-built) and every address this storefront used before. */
export function StorefrontAddresses({ storefront }: { storefront: StorefrontAdmin }) {
  if (!storefront.id) return null;
  return (
    <section className="flex flex-col gap-4 rounded-xl border border-border bg-card p-5 shadow-sm" aria-labelledby="storefront-addresses-heading">
      <h2 id="storefront-addresses-heading" className="text-base font-semibold text-foreground">
        Addresses
      </h2>
      <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <UrlRow label="Preview URL" url={storefront.previewUrl} testId="storefront-preview-url" />
        <UrlRow
          label={storefront.enabled ? "Published URL" : "Published URL (turned off)"}
          url={storefront.enabled ? storefront.publishedUrl : null}
          testId="storefront-published-url"
        />
      </dl>
      <div>
        <h3 className="text-sm font-medium text-foreground">Previous addresses</h3>
        {storefront.previousSlugs.length === 0 ? (
          <p className="text-sm text-muted-foreground">This storefront hasn&apos;t changed its address.</p>
        ) : (
          <>
            <p className="text-xs text-muted-foreground">
              While the storefront is on, these addresses redirect to the current one. They can&apos;t be given to
              another teacher.
            </p>
            <ul className="mt-2 flex flex-wrap gap-2" data-testid="storefront-previous-slugs">
              {storefront.previousSlugs.map((slug) => (
                <li key={slug} className="rounded-md border border-border bg-muted px-2 py-0.5 font-mono text-xs text-foreground">
                  {slug}
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </section>
  );
}
