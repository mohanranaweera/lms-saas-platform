"use client";

import { useId, useState } from "react";
import { AlertCircle, Download } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { useExportTranslations, type ExportFormat, type ExportScope } from "@/lib/api/translations";
import { useT } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { LOCALES } from "@/i18n/locales";

/** Export (doc §5): explicit locale + scope (published overrides only, or everything served) + PO/MO. */
export function ExportPanel({ defaultLocale }: { defaultLocale: string }) {
  const t = useT();
  const id = useId();
  const mutation = useExportTranslations();
  const [locale, setLocale] = useState(defaultLocale);
  const [scope, setScope] = useState<ExportScope>("OVERRIDES");
  const [format, setFormat] = useState<ExportFormat>("po");
  // Follow the workspace's locale when it changes.
  const [followed, setFollowed] = useState(defaultLocale);
  if (followed !== defaultLocale) {
    setFollowed(defaultLocale);
    setLocale(defaultLocale);
  }

  return (
    <section aria-labelledby={`${id}-heading`} className="flex min-w-0 flex-col gap-3 rounded-lg border border-border p-4" data-testid="export-panel">
      <div>
        <h3 id={`${id}-heading`} className="flex items-center gap-2 text-sm font-medium text-foreground">
          <Download className="size-4" aria-hidden="true" />
          {t("i18nAdmin.export.heading")}
        </h3>
        <p className="text-xs text-muted-foreground">{t("i18nAdmin.export.description")}</p>
      </div>
      <div className="grid min-w-0 gap-3 sm:grid-cols-3">
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor={`${id}-locale`}>{t("i18nAdmin.filter.locale")}</Label>
          <select
            id={`${id}-locale`}
            value={locale}
            onChange={(event) => setLocale(event.target.value)}
            className="h-9 min-w-0 rounded-lg border border-input bg-background px-2 text-sm"
          >
            {LOCALES.map((entry) => (
              <option key={entry.tag} value={entry.tag} lang={entry.tag}>
                {entry.autonym} ({entry.tag})
              </option>
            ))}
          </select>
        </div>
        <fieldset className="flex min-w-0 flex-col gap-1.5">
          <legend className="mb-1.5 text-sm font-medium">{t("i18nAdmin.export.scope")}</legend>
          {(["OVERRIDES", "EFFECTIVE"] as const).map((value) => (
            <label key={value} className="flex items-center gap-2 text-sm">
              <input type="radio" name={`${id}-scope`} value={value} checked={scope === value} onChange={() => setScope(value)} className="accent-primary" />
              {value === "OVERRIDES" ? t("i18nAdmin.export.scopeOverrides") : t("i18nAdmin.export.scopeEffective")}
            </label>
          ))}
        </fieldset>
        <fieldset className="flex min-w-0 flex-col gap-1.5">
          <legend className="mb-1.5 text-sm font-medium">{t("i18nAdmin.export.format")}</legend>
          {(["po", "mo"] as const).map((value) => (
            <label key={value} className="flex items-center gap-2 text-sm">
              <input type="radio" name={`${id}-format`} value={value} checked={format === value} onChange={() => setFormat(value)} className="accent-primary" />
              {value === "po" ? t("i18nAdmin.export.formatPo") : t("i18nAdmin.export.formatMo")}
            </label>
          ))}
        </fieldset>
      </div>
      <div>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={mutation.isPending}
          aria-busy={mutation.isPending}
          onClick={() => mutation.mutate({ locale, scope, format })}
        >
          <Download aria-hidden="true" />
          {mutation.isPending ? t("i18nAdmin.export.working") : t("i18nAdmin.export.download")}
        </Button>
      </div>
      {mutation.isSuccess ? (
        <p role="status" className="text-xs text-muted-foreground">
          {t("i18nAdmin.export.done", { file: mutation.data })}
        </p>
      ) : null}
      {mutation.error ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{localizeApiError(t, mutation.error)}</AlertDescription>
        </Alert>
      ) : null}
    </section>
  );
}
