import { apiFetch } from "@/lib/api/client";
import { LOCALE_HEADER } from "./locale-store";

/**
 * Typed client for the I18N-01 public runtime endpoints
 * (docs/architecture/i18n-translation-management.md §6). Tenant identity is
 * resolved by the server from the host / authenticated context; nothing here
 * sends a tenant id.
 */

export interface SupportedLocale {
  tag: string;
  autonym: string;
  dir: "ltr" | "rtl";
}

/** `GET /api/v1/public/i18n/settings` */
export interface I18nSettings {
  supportedLocales: SupportedLocale[];
  enabledLocales: string[];
  defaultLocale: string;
  browserLanguageDetection: boolean;
}

export type LocaleResolvedFrom = "EXPLICIT" | "USER" | "COOKIE" | "BROWSER" | "TENANT_DEFAULT" | "PLATFORM";

/** `GET /api/v1/public/i18n/catalog` and `GET /api/v1/i18n/admin/preview-catalog` */
export interface I18nCatalog {
  locale: string;
  resolvedFrom?: LocaleResolvedFrom;
  version: string;
  namespaces: string[];
  messages: Record<string, string>;
}

/** `GET /api/v1/me/locale` */
export interface MyLocaleResponse {
  locale: string | null;
}

export function fetchI18nSettings(): Promise<I18nSettings> {
  return apiFetch<I18nSettings>("/v1/public/i18n/settings", undefined, { skipLocaleHeader: true });
}

function catalogQuery(locale: string | null, namespaces: readonly string[]): string {
  const params = new URLSearchParams();
  if (locale) params.set("locale", locale);
  params.set("ns", namespaces.join(","));
  return params.toString();
}

/**
 * Effective catalog. `locale` is sent only when it came from an explicit
 * source (URL, switcher, saved user preference, cookie); with `null` the server
 * resolves the locale itself and the client adopts the returned `locale`.
 */
export function fetchI18nCatalog(locale: string | null, namespaces: readonly string[]): Promise<I18nCatalog> {
  return apiFetch<I18nCatalog>(
    `/v1/public/i18n/catalog?${catalogQuery(locale, namespaces)}`,
    locale ? { headers: { [LOCALE_HEADER]: locale } } : undefined,
    { skipLocaleHeader: !locale }
  );
}

export const PREVIEW_CATALOG_PATH = "/v1/i18n/admin/preview-catalog";

export function previewCatalogPath(locale: string, namespaces: readonly string[]): string {
  return `${PREVIEW_CATALOG_PATH}?${catalogQuery(locale, namespaces)}`;
}

export const ME_LOCALE_PATH = "/v1/me/locale";
