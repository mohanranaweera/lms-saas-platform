/**
 * Module-level holder for the UI locale the I18nProvider resolved, read by
 * `apiFetch` to send `X-LMS-Locale` (docs/architecture/i18n-translation-management.md
 * §2 step 1). `null` until the provider has resolved a locale, so no header is
 * sent before resolution. The header only selects the language of a response;
 * the server never persists anything from it.
 */
let requestLocale: string | null = null;

export const LOCALE_HEADER = "X-LMS-Locale";

export function getRequestLocale(): string | null {
  return requestLocale;
}

export function setRequestLocale(locale: string | null): void {
  requestLocale = locale;
}
