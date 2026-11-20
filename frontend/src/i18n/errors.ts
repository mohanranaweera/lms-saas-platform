import { isApiClientError } from "@/lib/api/error";
import type { TranslateFn } from "./i18n-provider";
import { languageOf } from "./icu";
import { SOURCE_LOCALE } from "./locales";

/**
 * API error localization (docs/architecture/i18n-translation-management.md §6):
 * the server keeps stable codes plus an English `message`; the client
 * localizes exactly once via a registered `errors.<CODE>` key and otherwise
 * falls back to the server message. Every code is listed literally (not built
 * by string concatenation) so the catalog extractor can see each key.
 */
const ERROR_MESSAGES: Record<string, (t: TranslateFn) => string> = {
  INVALID_CREDENTIALS: (t) => t("errors.INVALID_CREDENTIALS"),
  TENANT_UNAVAILABLE: (t) => t("errors.TENANT_UNAVAILABLE"),
  USER_SUSPENDED: (t) => t("errors.USER_SUSPENDED"),
  DEVICE_LIMIT_REACHED: (t) => t("errors.DEVICE_LIMIT_REACHED"),
  TOO_MANY_REQUESTS: (t) => t("errors.TOO_MANY_REQUESTS"),
  MOBILE_LOGIN_DISABLED: (t) => t("errors.MOBILE_LOGIN_DISABLED"),
  NETWORK_ERROR: (t) => t("errors.NETWORK_ERROR"),
  UNKNOWN_ERROR: (t) => t("errors.UNKNOWN_ERROR"),
  SELF_ENROLLMENT_DISABLED: (t) => t("errors.SELF_ENROLLMENT_DISABLED"),
  TRANSLATION_INVALID: (t) => t("errors.TRANSLATION_INVALID"),
  TRANSLATION_DRAFT_STALE: (t) => t("errors.TRANSLATION_DRAFT_STALE"),
  TRANSLATION_RELEASE_STALE: (t) => t("errors.TRANSLATION_RELEASE_STALE"),
  NOTHING_TO_PUBLISH: (t) => t("errors.NOTHING_TO_PUBLISH"),
  UNKNOWN_MESSAGE_KEY: (t) => t("errors.UNKNOWN_MESSAGE_KEY"),
  LOCALE_NOT_SUPPORTED: (t) => t("errors.LOCALE_NOT_SUPPORTED"),
  LOCALE_NOT_ENABLED: (t) => t("errors.LOCALE_NOT_ENABLED"),
  PO_IMPORT_INVALID: (t) => t("errors.PO_IMPORT_INVALID"),
  PAYLOAD_TOO_LARGE: (t) => t("errors.PAYLOAD_TOO_LARGE"),
};

/** Localized message for an error code, or `null` when the code has no registered key. */
export function localizedErrorCode(t: TranslateFn, code: string | null | undefined): string | null {
  if (!code) return null;
  const entry = Object.prototype.hasOwnProperty.call(ERROR_MESSAGES, code) ? ERROR_MESSAGES[code] : undefined;
  return entry ? entry(t) : null;
}

/**
 * Localized text for any thrown error. In the source language (English) the
 * server's own English message is kept as-is - it is already English and can
 * be more specific (e.g. the configured device limit). In other languages the
 * registered `errors.<CODE>` text is used, else the server message, else the
 * generic unknown-error text.
 */
export function localizeApiError(t: TranslateFn, error: unknown): string {
  if (isApiClientError(error)) {
    const english = languageOf(t.locale ?? SOURCE_LOCALE) === languageOf(SOURCE_LOCALE);
    if (english && error.message) return error.message;
    return localizedErrorCode(t, error.code) ?? (error.message || t("errors.UNKNOWN_ERROR"));
  }
  return t("errors.UNKNOWN_ERROR");
}
