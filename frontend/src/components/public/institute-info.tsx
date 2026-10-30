import type { PublicInstituteInfo } from "@/lib/api/public-home";
import { cn } from "@/lib/utils";

/**
 * SET-PAY-01: rendering helpers for the institute's public information on the
 * tenant public site (About block on the home page, address and social links
 * in the footer). Text is rendered as text only.
 */

/** Non-empty trimmed address lines, the city and postal code joined ("Alawwa 60280"). */
export function instituteAddressLines(info: PublicInstituteInfo | null | undefined): string[] {
  if (!info) return [];
  const cityLine = [info.city, info.postalCode].map((part) => part?.trim()).filter(Boolean).join(" ");
  return [info.addressLine1, info.addressLine2, cityLine].map((line) => line?.trim() ?? "").filter(Boolean);
}

/**
 * Defence in depth: the backend only stores `https` links on these hosts, but a
 * link is rendered only if it still passes the same check here.
 */
function safeHttpsUrl(value: string | null | undefined, hosts: readonly string[]): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase();
    return url.protocol === "https:" && hosts.some((allowed) => host === allowed || host.endsWith(`.${allowed}`))
      ? url.toString()
      : null;
  } catch {
    return null;
  }
}

function FacebookIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
      <path d="M24 12.07C24 5.41 18.63 0 12 0S0 5.4 0 12.07C0 18.1 4.39 23.1 10.13 24v-8.44H7.08v-3.49h3.04V9.41c0-3.02 1.8-4.7 4.54-4.7 1.31 0 2.68.24 2.68.24v2.97h-1.5c-1.5 0-1.96.93-1.96 1.89v2.26h3.33l-.53 3.49h-2.8V24C19.62 23.1 24 18.1 24 12.07" />
    </svg>
  );
}

function YouTubeIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
      <path d="M23.5 6.19a3.02 3.02 0 0 0-2.12-2.14C19.5 3.55 12 3.55 12 3.55s-7.5 0-9.38.5A3.02 3.02 0 0 0 .5 6.19C0 8.07 0 12 0 12s0 3.93.5 5.81a3.02 3.02 0 0 0 2.12 2.14c1.88.5 9.38.5 9.38.5s7.5 0 9.38-.5a3.02 3.02 0 0 0 2.12-2.14C24 15.93 24 12 24 12s0-3.93-.5-5.81M9.55 15.57V8.43L15.82 12z" />
    </svg>
  );
}

/** Facebook / YouTube links, or nothing when neither is set. */
export function InstituteSocialLinks({
  info,
  instituteName,
  className,
}: {
  info: PublicInstituteInfo | null | undefined;
  instituteName: string;
  className?: string;
}) {
  const links = [
    { label: "Facebook", url: safeHttpsUrl(info?.facebookUrl, ["facebook.com", "fb.com"]), Icon: FacebookIcon },
    { label: "YouTube", url: safeHttpsUrl(info?.youtubeUrl, ["youtube.com", "youtu.be"]), Icon: YouTubeIcon },
  ].filter((link): link is { label: string; url: string; Icon: typeof FacebookIcon } => link.url !== null);

  if (links.length === 0) return null;
  return (
    <ul className={cn("flex items-center gap-2", className)} data-testid="institute-social-links">
      {links.map(({ label, url, Icon }) => (
        <li key={label}>
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            aria-label={`${instituteName} on ${label} (opens in a new tab)`}
            className="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors hover:bg-primary hover:text-primary-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            <Icon className="size-4" />
          </a>
        </li>
      ))}
    </ul>
  );
}
