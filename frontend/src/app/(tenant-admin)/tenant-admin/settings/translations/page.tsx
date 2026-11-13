"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig, canViewInstituteConfig } from "@/lib/auth/permissions";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";
import { useNamespaces, useT } from "@/i18n/i18n-provider";
import { LanguageSettingsSection } from "./language-settings-section";
import { TranslationsWorkspace } from "./translations-workspace";

/**
 * Settings -> Translations (I18N-01, docs/architecture/i18n-translation-management.md
 * §5-§7). Language settings use the existing tenant-config framework and
 * institute-settings permission; the translation editor uses
 * `/api/v1/i18n/admin` (TRANSLATIONS_VIEW / _EDIT / _PUBLISH). Every request
 * is authorized server-side; the role/capability checks here only decide what
 * renders, and a 403 renders the permission-denied state.
 */
export default function TranslationsSettingsPage() {
  useNamespaces(["i18nAdmin"]);
  const t = useT();
  const { session } = useAuth();
  const role = session?.role ?? null;

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <SettingsHeader active="/tenant-admin/settings/translations" description={t("i18nAdmin.page.description")} />
      {canViewInstituteConfig(role) ? <LanguageSettingsSection canManage={canManageInstituteConfig(role)} /> : null}
      <TranslationsWorkspace />
    </div>
  );
}
