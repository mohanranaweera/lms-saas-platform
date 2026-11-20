"use client";

import { useEffect, useMemo, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useQueryClient } from "@tanstack/react-query";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useTenantConfigDomain, useUpdateTenantConfigDomain, type TenantConfigProperty } from "@/lib/api/tenant-config";
import { configValue } from "@/lib/validation/tenant-config";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { canonicalizeLocale, LOCALES, SOURCE_LOCALE } from "@/i18n/locales";
import { isApiClientError } from "@/lib/api/error";

/**
 * Settings -> Translations -> Language settings (I18N-01 §1). Saved through
 * the existing tenant-config API (`GENERAL.enabled_locales`,
 * `GENERAL.default_locale`, `GENERAL.browser_language_detection`), which
 * re-validates (default must be enabled) and enforces the institute-settings
 * permission. Read-only for roles that can view but not edit settings.
 */
export function LanguageSettingsSection({ canManage }: { canManage: boolean }) {
  const t = useT();
  const general = useTenantConfigDomain("GENERAL");
  return (
    <section
      aria-labelledby="language-settings-heading"
      className="flex min-w-0 flex-col gap-4 rounded-lg border border-border p-4"
      data-testid="language-settings"
    >
      <div>
        <h2 id="language-settings-heading" className="text-base font-medium text-foreground">
          {t("i18nAdmin.languages.heading")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("i18nAdmin.languages.description")}</p>
      </div>
      <QueryStateBoundary
        query={general}
        loadingLabel={t("i18nAdmin.languages.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(properties) => <LanguageSettingsForm properties={properties} canManage={canManage} />}
      </QueryStateBoundary>
    </section>
  );
}

/** `enabled_locales` may arrive as a JSON list or a comma-separated string; only canonical, supported tags are kept. */
function toLocaleList(value: unknown): string[] {
  let raw: unknown[] = [];
  if (Array.isArray(value)) raw = value;
  else if (typeof value === "string") {
    try {
      const parsed: unknown = JSON.parse(value);
      raw = Array.isArray(parsed) ? parsed : value.split(",");
    } catch {
      raw = value.split(",");
    }
  }
  const tags = raw
    .map((entry) => (typeof entry === "string" ? canonicalizeLocale(entry) : null))
    .filter((tag): tag is string => tag !== null);
  return tags.length > 0 ? Array.from(new Set(tags)) : [SOURCE_LOCALE];
}

function languageSchema(t: TranslateFn) {
  return z
    .object({
      enabledLocales: z.array(z.string()).min(1, t("i18nAdmin.languages.validation.atLeastOne")),
      defaultLocale: z.string().min(1),
      browserLanguageDetection: z.boolean(),
    })
    .refine((values) => values.enabledLocales.includes(values.defaultLocale), {
      path: ["defaultLocale"],
      message: t("i18nAdmin.languages.validation.defaultEnabled"),
    });
}

type LanguageValues = z.infer<ReturnType<typeof languageSchema>>;

function LanguageSettingsForm({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const t = useT();
  const queryClient = useQueryClient();
  const mutation = useUpdateTenantConfigDomain("GENERAL");
  const schema = useMemo(() => languageSchema(t), [t]);
  const [saved, setSaved] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const current: LanguageValues = useMemo(() => {
    const enabledLocales = toLocaleList(configValue(properties, "enabled_locales"));
    const defaultLocale = canonicalizeLocale(String(configValue(properties, "default_locale") ?? "")) ?? enabledLocales[0];
    return {
      enabledLocales,
      defaultLocale,
      browserLanguageDetection: configValue(properties, "browser_language_detection") === true,
    };
  }, [properties]);

  const form = useForm<LanguageValues>({ resolver: zodResolver(schema), defaultValues: current });
  const { reset } = form;
  useEffect(() => reset(current), [reset, current]);

  const enabled = form.watch("enabledLocales");
  const errors = form.formState.errors;

  const onSubmit = form.handleSubmit(async (values) => {
    setSaved(false);
    setServerError(null);
    const changes: Record<string, unknown> = {};
    const sortedNow = [...values.enabledLocales].sort().join(",");
    if (sortedNow !== [...current.enabledLocales].sort().join(",")) changes.enabled_locales = values.enabledLocales;
    if (values.defaultLocale !== current.defaultLocale) changes.default_locale = values.defaultLocale;
    if (values.browserLanguageDetection !== current.browserLanguageDetection) {
      changes.browser_language_detection = values.browserLanguageDetection;
    }
    if (Object.keys(changes).length === 0) {
      setSaved(true);
      return;
    }
    try {
      await mutation.mutateAsync(changes);
      setSaved(true);
      // The switcher and runtime read the public settings; refresh them.
      void queryClient.invalidateQueries({ queryKey: ["i18n"] });
    } catch (error) {
      setServerError(
        isApiClientError(error) && error.status === 403
          ? t("i18nAdmin.languages.noPermission")
          : localizeApiError(t, error)
      );
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <fieldset disabled={!canManage || mutation.isPending} className="flex min-w-0 flex-col gap-4">
        <div className="flex flex-col gap-2">
          <p className="text-sm font-medium text-foreground" id="enabled-locales-legend">
            {t("i18nAdmin.languages.enabled")}
          </p>
          <Controller
            control={form.control}
            name="enabledLocales"
            render={({ field }) => (
              <ul role="group" className="flex flex-wrap gap-x-6 gap-y-2" aria-labelledby="enabled-locales-legend">
                {LOCALES.map((locale) => {
                  const id = `enabled-locale-${locale.tag}`;
                  const checked = field.value.includes(locale.tag);
                  return (
                    <li key={locale.tag} className="flex items-center gap-2">
                      <input
                        id={id}
                        type="checkbox"
                        className="size-4 accent-primary"
                        checked={checked}
                        onChange={(event) =>
                          field.onChange(
                            event.target.checked
                              ? [...field.value, locale.tag]
                              : field.value.filter((tag) => tag !== locale.tag)
                          )
                        }
                      />
                      <Label htmlFor={id} className="font-normal">
                        <span lang={locale.tag}>{locale.autonym}</span>
                        <span className="text-muted-foreground"> ({locale.tag})</span>
                      </Label>
                    </li>
                  );
                })}
              </ul>
            )}
          />
          {errors.enabledLocales ? (
            <p role="alert" className="text-sm text-destructive">
              {errors.enabledLocales.message}
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-1.5 sm:max-w-xs">
          <Label htmlFor="default-locale">{t("i18nAdmin.languages.default")}</Label>
          <select
            id="default-locale"
            className="h-9 rounded-lg border border-input bg-background px-2 text-sm"
            aria-invalid={errors.defaultLocale ? true : undefined}
            aria-describedby={errors.defaultLocale ? "default-locale-error" : "default-locale-help"}
            {...form.register("defaultLocale")}
          >
            {LOCALES.filter((locale) => enabled.includes(locale.tag)).map((locale) => (
              <option key={locale.tag} value={locale.tag} lang={locale.tag}>
                {locale.autonym} ({locale.tag})
              </option>
            ))}
          </select>
          <p id="default-locale-help" className="text-xs text-muted-foreground">
            {t("i18nAdmin.languages.defaultHelp")}
          </p>
          {errors.defaultLocale ? (
            <p id="default-locale-error" role="alert" className="text-sm text-destructive">
              {errors.defaultLocale.message}
            </p>
          ) : null}
        </div>

        <div className="flex items-start gap-3">
          <Controller
            control={form.control}
            name="browserLanguageDetection"
            render={({ field }) => (
              <Switch
                id="browser-language-detection"
                checked={field.value}
                onCheckedChange={field.onChange}
                aria-describedby="browser-language-detection-help"
              />
            )}
          />
          <div className="flex min-w-0 flex-col gap-0.5">
            <Label htmlFor="browser-language-detection">{t("i18nAdmin.languages.detection")}</Label>
            <p id="browser-language-detection-help" className="text-xs text-muted-foreground">
              {t("i18nAdmin.languages.detectionHelp")}
            </p>
          </div>
        </div>
      </fieldset>

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{t("i18nAdmin.languages.saved")}</AlertDescription>
        </Alert>
      ) : null}
      {serverError ? (
        <p role="alert" className="text-sm text-destructive">
          {serverError}
        </p>
      ) : null}
      {canManage ? (
        <div>
          <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
            {mutation.isPending ? t("i18nAdmin.common.saving") : t("i18nAdmin.languages.save")}
          </Button>
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">{t("i18nAdmin.languages.readOnly")}</p>
      )}
    </form>
  );
}
