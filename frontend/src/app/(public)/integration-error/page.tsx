import Link from "next/link";

/**
 * Neutral landing page for an OAuth callback the backend could not trust
 * (unknown, expired or already-used state - MEDIA-INT-01 / ADR-018 §3). It
 * deliberately names no tenant and changes nothing: the teacher simply starts
 * the connection again from their own institute's site.
 */
export default function IntegrationErrorPage() {
  return (
    <div className="mx-auto flex w-full max-w-lg flex-col gap-4 px-4 py-16 text-center">
      <h1 className="text-xl font-semibold text-foreground">This connection link has expired</h1>
      <p className="text-sm text-muted-foreground">
        Nothing was connected. Sign in to your institute&apos;s site and start again from Settings → Integrations.
        Connection links only work once and expire after 10 minutes.
      </p>
      <p>
        <Link href="/login" className="text-sm font-medium text-foreground underline">
          Go to sign in
        </Link>
      </p>
    </div>
  );
}
