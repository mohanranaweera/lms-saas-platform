"use client";

import { useMemo } from "react";
import { formatDateTime, formatMoney } from "@/lib/format";
import { useI18n } from "./i18n-provider";
import { SOURCE_LOCALE } from "./locales";

/**
 * Locale-aware display formatting for the resolved UI locale. Only the
 * presentation changes: stored values, currencies, time zones and every
 * calculation stay exactly as the backend sent them (the tenant's own
 * currency/time zone are passed in by the caller). In the source locale the
 * existing helpers' output is kept byte-for-byte, so English pages do not
 * change.
 */
export function useLocaleFormat() {
  const { locale } = useI18n();
  return useMemo(() => {
    const intlLocale = locale === SOURCE_LOCALE ? undefined : locale;
    return {
      locale,
      formatDateTime: (iso: string) => formatDateTime(iso, intlLocale),
      formatMoney: (amount: number, currency?: string | null) => formatMoney(amount, currency, intlLocale),
      formatNumber: (value: number) => new Intl.NumberFormat(locale).format(value),
      formatDate: (iso: string | number | Date, options: Intl.DateTimeFormatOptions = { dateStyle: "medium" }, timeZone?: string) => {
        const date = iso instanceof Date ? iso : new Date(iso);
        if (Number.isNaN(date.getTime())) return "";
        try {
          return new Intl.DateTimeFormat(locale, { ...options, timeZone: timeZone || undefined }).format(date);
        } catch {
          return new Intl.DateTimeFormat(locale, options).format(date);
        }
      },
    };
  }, [locale]);
}
