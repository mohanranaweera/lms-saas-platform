import catalog from "./generated/catalog.json";

/**
 * I18N-01 locale registry, read from the GENERATED catalog
 * (`node scripts/i18n/catalog.mjs generate`; never edit the JSON by hand).
 * docs/architecture/i18n-translation-management.md §1.
 */

export interface LocaleInfo {
  tag: string;
  language: string;
  autonym: string;
  dir: "ltr" | "rtl";
  aliases: string[];
}

export const SOURCE_LOCALE: string = catalog.sourceLocale;

export const LOCALES: readonly LocaleInfo[] = catalog.locales.map((locale) => ({
  ...locale,
  dir: locale.dir === "rtl" ? "rtl" : "ltr",
}));

/** Bundled English source (`key -> text`), rendered until a server catalog arrives and as the fallback. */
export const SOURCE_MESSAGES: Readonly<Record<string, string>> = catalog.messages;

/** Platform catalog version of the bundled source (`<platformHash>` part of a server `version`). */
export const BUNDLED_CATALOG_VERSION: string = catalog.version;

const BY_TAG = new Map(LOCALES.map((locale) => [locale.tag.toLowerCase(), locale]));

export function localeInfo(tag: string | null | undefined): LocaleInfo | null {
  if (!tag) return null;
  return BY_TAG.get(tag.toLowerCase()) ?? null;
}

/**
 * Canonical tag for `input`: an exact tag match (case-insensitive), else a
 * match on the language subtag (`si`, `si-XX`, `ta-IN`, `en-US` -> `en-LK`).
 * `null` when unsupported or malformed. Enablement for the tenant is NOT
 * checked here - the server decides that (§2: unknown/not-enabled values are
 * ignored and the chain continues).
 */
export function canonicalizeLocale(input: string | null | undefined): string | null {
  if (typeof input !== "string") return null;
  const value = input.trim().replace(/_/g, "-");
  if (!value || value.length > 35 || !/^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$/.test(value)) return null;
  const exact = localeInfo(value);
  if (exact) return exact.tag;
  const language = value.split("-")[0].toLowerCase();
  const match = LOCALES.find(
    (locale) => locale.language === language || locale.aliases.some((alias) => alias.toLowerCase() === language)
  );
  return match?.tag ?? null;
}

/** `dir` attribute for a tag (defaults to `ltr`). */
export function localeDir(tag: string | null | undefined): "ltr" | "rtl" {
  return localeInfo(tag)?.dir ?? "ltr";
}

/** The language's own name, or the tag itself when unknown. */
export function localeAutonym(tag: string): string {
  return localeInfo(tag)?.autonym ?? tag;
}

/** True when two locale tags share a language (e.g. content `si-LK` vs UI `si-LK`). */
export function sameLanguage(a: string | null | undefined, b: string | null | undefined): boolean {
  if (!a || !b) return false;
  return a.split("-")[0].toLowerCase() === b.split("-")[0].toLowerCase();
}
