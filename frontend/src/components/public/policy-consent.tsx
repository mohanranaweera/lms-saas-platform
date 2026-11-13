"use client";

import Link from "next/link";
import { POLICY_PAGES, usePublicHome, type PolicyKind } from "@/lib/api/public-home";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";

/** Localized policy page titles (I18N-01); `POLICY_PAGES[kind].title` stays the English source. */
export const POLICY_TITLES: Record<PolicyKind, (t: TranslateFn) => string> = {
  terms: (t) => t("common.policy.terms"),
  refund: (t) => t("common.policy.refund"),
  privacy: (t) => t("common.policy.privacy"),
};

/**
 * "By using this service you agree to …" (LOGIN-01 Phase B, Klass parity) on
 * tenant auth pages. Links only the policies the institute has published;
 * renders nothing when neither the terms nor the privacy policy is.
 */
export function PolicyConsent() {
  const policies = usePublicHome().data?.policies;
  const t = useT();
  const links = (["terms", "privacy"] as const).filter((kind) => policies?.[POLICY_PAGES[kind].flag]);
  if (links.length === 0) return null;

  return (
    <p className="text-center text-xs text-muted-foreground" data-testid="policy-consent">
      {t("auth.policyConsent.prefix")}{" "}
      {links.map((kind, index) => (
        <span key={kind}>
          {index > 0 ? ` ${t("auth.policyConsent.and")} ` : null}
          <Link href={POLICY_PAGES[kind].href} className="font-medium text-foreground underline underline-offset-2 hover:text-primary">
            {POLICY_TITLES[kind](t)}
          </Link>
        </span>
      ))}
      {t("auth.policyConsent.suffix")}
    </p>
  );
}
