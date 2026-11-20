"use client";

import { useCallback } from "react";
import { useCapabilities } from "@/lib/api/my-permissions";

/** I18N-01 capability keys (`Capability.key()` verbatim, doc §7). */
export const TRANSLATION_CAP = {
  VIEW: "TRANSLATIONS_VIEW",
  EDIT: "TRANSLATIONS_EDIT",
  PUBLISH: "TRANSLATIONS_PUBLISH",
} as const;

export type TranslationCapability = (typeof TRANSLATION_CAP)[keyof typeof TRANSLATION_CAP];

/**
 * UX-only gate for Settings -> Translations. Tenant Admin holds the
 * TRANSLATIONS domain area by default; Operator/Manager/seeded staff roles
 * only what a Tenant Admin granted (`GET /v1/me/permissions`). While that is
 * loading nothing extra is shown (fails closed). The backend's capability
 * checks on every `/api/v1/i18n/admin` call are the enforcement.
 */
export function useTranslationCapabilities() {
  const { role, has, query } = useCapabilities();
  const can = useCallback(
    (capability: TranslationCapability) => role === "TENANT_ADMIN" || has(capability),
    [has, role]
  );
  const failed = query.errorUpdatedAt > 0 && !query.data;
  return { can, loading: query.isPending && !failed && role !== "TENANT_ADMIN", role };
}
