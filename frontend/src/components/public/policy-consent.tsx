"use client";

import Link from "next/link";
import { POLICY_PAGES, usePublicHome } from "@/lib/api/public-home";

/**
 * "By using this service you agree to …" (LOGIN-01 Phase B, Klass parity) on
 * tenant auth pages. Links only the policies the institute has published;
 * renders nothing when neither the terms nor the privacy policy is.
 */
export function PolicyConsent() {
  const policies = usePublicHome().data?.policies;
  const links = (["terms", "privacy"] as const).filter((kind) => policies?.[POLICY_PAGES[kind].flag]);
  if (links.length === 0) return null;

  return (
    <p className="text-center text-xs text-muted-foreground" data-testid="policy-consent">
      By using this service you agree to the{" "}
      {links.map((kind, index) => (
        <span key={kind}>
          {index > 0 ? " and " : null}
          <Link href={POLICY_PAGES[kind].href} className="font-medium text-foreground underline underline-offset-2 hover:text-primary">
            {POLICY_PAGES[kind].title}
          </Link>
        </span>
      ))}
      .
    </p>
  );
}
