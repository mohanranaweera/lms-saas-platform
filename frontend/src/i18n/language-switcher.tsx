"use client";

import { useId, useState } from "react";
import { Languages } from "lucide-react";
import { cn } from "@/lib/utils";
import { useI18n } from "./i18n-provider";
import { localizeApiError } from "./errors";
import { localeAutonym } from "./locales";

/**
 * Language switcher (I18N-01). A labelled native `<select>` (keyboard and
 * screen-reader friendly everywhere, including mobile pickers) listing only
 * the tenant-enabled locales by their own names; hidden when the tenant
 * enabled a single language. Choosing a language is the only action that
 * persists a preference (§2): `PUT /me/locale` when signed in, the
 * `lms_locale` cookie always.
 */
export function LanguageSwitcher({ className, compact = false }: { className?: string; compact?: boolean }) {
  const { t, locale, enabledLocales, changeLocale } = useI18n();
  const id = useId();
  const [pending, setPending] = useState(false);
  // Rendered from the current `t`, so it is announced in the new language once it loads.
  const [announced, setAnnounced] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The chosen language stays selected while its catalog loads.
  const [target, setTarget] = useState<string | null>(null);
  if (target !== null && target === locale) setTarget(null);

  if (enabledLocales.length <= 1) return null;

  const current = enabledLocales.includes(locale) ? locale : enabledLocales[0];
  const value = target && enabledLocales.includes(target) ? target : current;

  async function onChange(next: string) {
    if (next === value) return;
    setPending(true);
    setError(null);
    setTarget(next);
    try {
      await changeLocale(next);
      setAnnounced(next);
    } catch (err) {
      setTarget(null);
      setError(localizeApiError(t, err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div className={cn("relative flex min-w-0 items-center", className)} data-testid="language-switcher">
      <label htmlFor={id} className="sr-only">
        {t("common.language.label")}
      </label>
      <Languages className="pointer-events-none absolute left-2 size-4 text-muted-foreground" aria-hidden="true" />
      <select
        id={id}
        value={value}
        disabled={pending}
        aria-busy={pending}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(event) => void onChange(event.target.value)}
        className={cn(
          "h-8 min-w-0 max-w-[10rem] cursor-pointer appearance-none truncate rounded-lg border border-input bg-background py-1 pr-2 pl-7 text-sm text-foreground outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-wait disabled:opacity-70",
          compact && "max-w-[7.5rem]"
        )}
      >
        {enabledLocales.map((tag) => (
          <option key={tag} value={tag} lang={tag}>
            {localeAutonym(tag)}
          </option>
        ))}
      </select>
      <span className="sr-only" role="status" aria-live="polite" data-testid="language-switcher-status">
        {announced ? t("common.language.changed", { language: localeAutonym(announced) }) : ""}
      </span>
      {error ? (
        <span id={`${id}-error`} role="alert" className="absolute top-full right-0 z-50 mt-1 w-max max-w-[16rem] rounded-md border border-destructive/40 bg-background px-2 py-1 text-xs text-destructive shadow">
          {error}
        </span>
      ) : null}
    </div>
  );
}
