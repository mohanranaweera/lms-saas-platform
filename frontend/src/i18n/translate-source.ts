import type { TranslateFn } from "./i18n-provider";
import { SOURCE_MESSAGES } from "./locales";

/**
 * I18N-01: translate an English UI string produced by a plain (non-React)
 * helper - e.g. a fixed error-copy map in a `.ts` module - by looking up the
 * registered key whose English source is exactly that text. Unregistered
 * text (a server message, tenant data) is returned unchanged. The keys used
 * this way are listed in `./source-strings.ts` so the catalog extractor sees
 * them; only parameter-free messages can be looked up.
 */
let index: Map<string, string> | null = null;

function sourceIndex(): Map<string, string> {
  if (!index) {
    index = new Map();
    for (const [key, source] of Object.entries(SOURCE_MESSAGES)) {
      if (!index.has(source)) index.set(source, key);
    }
  }
  return index;
}

export function translateSource(t: TranslateFn, text: string): string;
export function translateSource(t: TranslateFn, text: string | null | undefined): string | null | undefined;
export function translateSource(t: TranslateFn, text: string | null | undefined): string | null | undefined {
  if (!text) return text;
  const key = sourceIndex().get(text);
  return key ? t(key) : text;
}

/** Literal-key marker for keys used only through `translateSource` (scanned by the catalog extractor). */
export function tKey(key: string): string {
  return key;
}
