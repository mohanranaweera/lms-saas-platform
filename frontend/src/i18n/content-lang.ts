"use client";

import { useI18n } from "./i18n-provider";
import { sameLanguage } from "./locales";

/**
 * `lang` attribute for tenant-authored text (course title/description) whose
 * declared `contentLocale` differs from the UI language (I18N-01 §8), so
 * screen readers and font fallback use the right language. `undefined` when
 * the language is not declared (legacy rows) or matches the UI.
 */
export function useContentLang(contentLocale: string | null | undefined): string | undefined {
  const { locale } = useI18n();
  return contentLocale && !sameLanguage(contentLocale, locale) ? contentLocale : undefined;
}
