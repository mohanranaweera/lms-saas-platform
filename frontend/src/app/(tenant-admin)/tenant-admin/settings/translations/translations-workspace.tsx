"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { cn } from "@/lib/utils";
import {
  useTranslationEntries,
  type TranslationCounts,
  type TranslationEntry,
  type TranslationStatusFilter,
} from "@/lib/api/translations";
import { TRANSLATION_CAP, useTranslationCapabilities } from "@/lib/i18n-admin/use-translation-capabilities";
import { useI18n, useT, type TranslateFn } from "@/i18n/i18n-provider";
import { LOCALES, SOURCE_LOCALE, SOURCE_MESSAGES } from "@/i18n/locales";
import { EntryBadges } from "./entry-badges";
import { EntryEditor } from "./entry-editor";
import { ReleasesPanel } from "./releases-panel";
import { ImportPanel } from "./import-panel";
import { ExportPanel } from "./export-panel";

const PAGE_SIZE = 25;

const STATUS_FILTERS: Array<{
  value: TranslationStatusFilter | null;
  count: keyof TranslationCounts;
  label: (t: TranslateFn) => string;
}> = [
  { value: null, count: "total", label: (t) => t("i18nAdmin.status.all") },
  { value: "MISSING", count: "missing", label: (t) => t("i18nAdmin.status.missing") },
  { value: "TRANSLATED", count: "translated", label: (t) => t("i18nAdmin.status.translated") },
  { value: "NEEDS_REVIEW", count: "needsReview", label: (t) => t("i18nAdmin.status.needsReview") },
  { value: "OVERRIDDEN", count: "overridden", label: (t) => t("i18nAdmin.status.overridden") },
  { value: "DRAFTS", count: "drafts", label: (t) => t("i18nAdmin.status.drafts") },
];

/** Namespaces known to this build (first key segment of the bundled source). */
const NAMESPACES = Array.from(new Set(Object.keys(SOURCE_MESSAGES).map((key) => key.split(".")[0]))).sort();

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

export function TranslationsWorkspace() {
  const t = useT();
  const { previewDrafts, setPreviewDrafts } = useI18n();
  const capabilities = useTranslationCapabilities();
  const canEdit = capabilities.can(TRANSLATION_CAP.EDIT);
  const canPublish = capabilities.can(TRANSLATION_CAP.PUBLISH);
  const [locale, setLocale] = useState<string>(LOCALES.find((entry) => entry.tag !== SOURCE_LOCALE)?.tag ?? SOURCE_LOCALE);
  const [namespace, setNamespace] = useState<string>("");
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<TranslationStatusFilter | null>(null);
  const [page, setPage] = useState(0);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const q = useDebounced(search, 300);
  const editorRef = useRef<HTMLDivElement>(null);

  const params = useMemo(
    () => ({ locale, namespace: namespace || null, q, status, page, size: PAGE_SIZE }),
    [locale, namespace, q, status, page]
  );
  const query = useTranslationEntries(params);
  const entries = query.data?.page.content ?? [];
  const selected = entries.find((entry) => entry.key === selectedKey) ?? null;

  const resetPaging = () => {
    setPage(0);
    setSelectedKey(null);
  };

  const filtersActive = Boolean(namespace || search.trim() || status);

  function selectEntry(entry: TranslationEntry) {
    setSelectedKey(entry.key);
    // On narrow screens the editor sits below the list.
    requestAnimationFrame(() => editorRef.current?.focus({ preventScroll: false }));
  }

  return (
    <section aria-labelledby="translations-heading" className="flex min-w-0 flex-col gap-4" data-testid="translations-workspace">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id="translations-heading" className="text-base font-medium break-words text-foreground">
            {t("i18nAdmin.workspace.heading")}
          </h2>
          <p className="text-sm text-muted-foreground">{t("i18nAdmin.workspace.description")}</p>
        </div>
        <div className="flex items-center gap-2">
          <Switch
            id="translations-preview-drafts"
            checked={previewDrafts}
            onCheckedChange={setPreviewDrafts}
            aria-describedby="translations-preview-help"
            data-testid="preview-drafts-toggle"
          />
          <div className="flex min-w-0 flex-col">
            <Label htmlFor="translations-preview-drafts">{t("i18nAdmin.preview.toggle")}</Label>
            <span id="translations-preview-help" className="text-xs text-muted-foreground">
              {t("i18nAdmin.preview.help")}
            </span>
          </div>
        </div>
      </div>

      <div className="grid min-w-0 gap-3 sm:grid-cols-2 lg:grid-cols-[12rem_12rem_minmax(0,1fr)]">
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor="translations-locale">{t("i18nAdmin.filter.locale")}</Label>
          <select
            id="translations-locale"
            value={locale}
            onChange={(event) => {
              setLocale(event.target.value);
              resetPaging();
            }}
            className="h-9 min-w-0 rounded-lg border border-input bg-background px-2 text-sm"
          >
            {LOCALES.map((entry) => (
              <option key={entry.tag} value={entry.tag} lang={entry.tag}>
                {entry.autonym} ({entry.tag})
              </option>
            ))}
          </select>
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor="translations-namespace">{t("i18nAdmin.filter.namespace")}</Label>
          <select
            id="translations-namespace"
            value={namespace}
            onChange={(event) => {
              setNamespace(event.target.value);
              resetPaging();
            }}
            className="h-9 min-w-0 rounded-lg border border-input bg-background px-2 text-sm"
          >
            <option value="">{t("i18nAdmin.filter.allNamespaces")}</option>
            {NAMESPACES.map((ns) => (
              <option key={ns} value={ns}>
                {ns}
              </option>
            ))}
          </select>
        </div>
        <div className="flex min-w-0 flex-col gap-1.5 sm:col-span-2 lg:col-span-1">
          <Label htmlFor="translations-search">{t("i18nAdmin.filter.search")}</Label>
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              id="translations-search"
              type="search"
              className="pl-8"
              placeholder={t("i18nAdmin.filter.searchPlaceholder")}
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                resetPaging();
              }}
            />
          </div>
        </div>
      </div>

      <div role="group" aria-label={t("i18nAdmin.filter.status")} className="flex flex-wrap gap-2" data-testid="status-filters">
        {STATUS_FILTERS.map((filter) => {
          const active = status === filter.value;
          const count = query.data?.counts?.[filter.count];
          return (
            <Button
              key={filter.count}
              type="button"
              size="sm"
              variant={active ? "default" : "outline"}
              aria-pressed={active}
              onClick={() => {
                setStatus(filter.value);
                resetPaging();
              }}
              className="h-auto min-h-8 whitespace-normal"
            >
              {filter.label(t)}
              {typeof count === "number" ? (
                <span className={cn("rounded-full px-1.5 text-xs tabular-nums", active ? "bg-primary-foreground/20" : "bg-muted")}>
                  {count}
                </span>
              ) : null}
            </Button>
          );
        })}
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel={t("i18nAdmin.workspace.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(data) =>
          data.page.content.length === 0 ? (
            filtersActive ? (
              <EmptyState
                title={t("i18nAdmin.workspace.noMatch.title")}
                description={t("i18nAdmin.workspace.noMatch.description")}
                action={{
                  label: t("i18nAdmin.workspace.noMatch.clear"),
                  onClick: () => {
                    setNamespace("");
                    setSearch("");
                    setStatus(null);
                    resetPaging();
                  },
                }}
              />
            ) : (
              <EmptyState
                title={t("i18nAdmin.workspace.empty.title")}
                description={t("i18nAdmin.workspace.empty.description")}
              />
            )
          ) : (
            <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
              <div className="flex min-w-0 flex-col gap-3">
                <ul className="flex flex-col gap-2" aria-label={t("i18nAdmin.workspace.listLabel")} data-testid="translation-entries">
                  {data.page.content.map((entry) => {
                    const active = entry.key === selectedKey;
                    return (
                      <li key={entry.key} className="min-w-0">
                        <button
                          type="button"
                          onClick={() => selectEntry(entry)}
                          aria-pressed={active}
                          data-testid="translation-entry"
                          data-key={entry.key}
                          className={cn(
                            "flex w-full min-w-0 flex-col gap-1.5 rounded-lg border p-3 text-left outline-none transition-colors hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50",
                            active ? "border-primary bg-muted" : "border-border"
                          )}
                        >
                          <span className="font-mono text-xs break-all text-muted-foreground">{entry.key}</span>
                          <span className="line-clamp-2 text-sm break-words text-foreground" lang="en">
                            {entry.source}
                          </span>
                          <span className="line-clamp-2 text-sm break-words text-muted-foreground" lang={locale}>
                            {entry.draft?.action === "SET" && entry.draft.value ? entry.draft.value : entry.effectiveValue}
                          </span>
                          <EntryBadges entry={entry} />
                        </button>
                      </li>
                    );
                  })}
                </ul>
                <div className="flex flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
                  <span aria-live="polite">
                    {t("i18nAdmin.workspace.pageOf", {
                      page: data.page.page + 1,
                      total: Math.max(1, data.page.totalPages),
                    })}
                  </span>
                  <div className="flex gap-2">
                    <Button type="button" variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>
                      <ChevronLeft aria-hidden="true" />
                      {t("i18nAdmin.workspace.previous")}
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={data.page.page + 1 >= data.page.totalPages}
                      onClick={() => setPage((value) => value + 1)}
                    >
                      {t("i18nAdmin.workspace.next")}
                      <ChevronRight aria-hidden="true" />
                    </Button>
                  </div>
                </div>
              </div>
              <div ref={editorRef} tabIndex={-1} className="min-w-0 outline-none">
                {selected ? (
                  <EntryEditor key={`${locale}:${selected.key}`} entry={selected} locale={locale} canEdit={canEdit} onReload={() => void query.refetch()} />
                ) : (
                  <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
                    {t("i18nAdmin.editor.selectPrompt")}
                  </p>
                )}
              </div>
            </div>
          )
        }
      </QueryStateBoundary>

      <div className="grid min-w-0 gap-4 lg:grid-cols-2">
        <ReleasesPanel locale={locale} canPublish={canPublish} draftCount={query.data?.counts?.drafts ?? null} />
        <div className="flex min-w-0 flex-col gap-4">
          {canEdit ? <ImportPanel locale={locale} /> : null}
          <ExportPanel defaultLocale={locale} />
        </div>
      </div>
    </section>
  );
}
