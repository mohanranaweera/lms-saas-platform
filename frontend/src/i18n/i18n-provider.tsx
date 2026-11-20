"use client";

import {
  createContext,
  Suspense,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye, X } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { formatMessage, type MessageParams } from "./icu";
import {
  fetchI18nCatalog,
  fetchI18nSettings,
  ME_LOCALE_PATH,
  previewCatalogPath,
  type I18nCatalog,
  type I18nSettings,
  type MyLocaleResponse,
} from "./api";
import { canonicalizeLocale, localeDir, SOURCE_LOCALE, SOURCE_MESSAGES } from "./locales";
import { readLocaleCookie, writeLocaleCookie } from "./locale-cookie";
import { LOCALE_HEADER, setRequestLocale } from "./locale-store";

/**
 * I18N-01 client runtime (docs/architecture/i18n-translation-management.md
 * §2, §4, §9). Resolves the UI locale in the documented order - `?lang=` on
 * the page URL, the signed-in user's saved preference, the `lms_locale`
 * cookie, then whatever the server resolves (browser detection / tenant
 * default / en-LK) - and loads only the namespaces the mounted routes asked
 * for. Until a catalog arrives the bundled English source is rendered (never
 * raw keys). Display only: the server enforces every permission.
 */

export type TranslateFn = ((key: string, params?: MessageParams) => string) & {
  /** Locale the translator formats for (absent on ad hoc test doubles). */
  readonly locale?: string;
};

export interface I18nContextValue {
  /** Effective UI locale (the bundled source locale until the server answered). */
  locale: string;
  /** True once a server catalog for the current request has arrived. */
  resolved: boolean;
  dir: "ltr" | "rtl";
  t: TranslateFn;
  settings: I18nSettings | undefined;
  /** Tenant-enabled locales (canonical tags), `[]` until settings load. */
  enabledLocales: string[];
  /** Explicit switcher action: persists the choice and reloads the catalog. */
  changeLocale: (locale: string) => Promise<void>;
  registerNamespaces: (namespaces: readonly string[]) => void;
  /** Translation editor "Preview drafts" (this tab and session only). */
  previewDrafts: boolean;
  setPreviewDrafts: (on: boolean) => void;
  previewError: unknown;
  catalogVersion: string | null;
}

const warned = new Set<string>();

function sourceText(key: string): string {
  const source = SOURCE_MESSAGES[key];
  if (source !== undefined) return source;
  if (process.env.NODE_ENV !== "production" && !warned.has(key)) {
    warned.add(key);
    console.warn(`[i18n] missing message key '${key}' (register it in i18n/source/)`);
  }
  return key;
}

function safeFormat(text: string, locale: string, params?: MessageParams): string | null {
  try {
    return formatMessage(text, locale, params);
  } catch {
    return null;
  }
}

/** Lookup chain: catalog value -> English source -> key (last resort). Blank/invalid count as missing (§4). */
export function createTranslator(messages: Readonly<Record<string, string>>, locale: string): TranslateFn {
  const translate = (key: string, params?: MessageParams) => {
    const value = messages[key];
    if (typeof value === "string" && value.trim() !== "") {
      const formatted = safeFormat(value, locale, params);
      if (formatted !== null) return formatted;
    }
    const source = sourceText(key);
    return safeFormat(source, locale, params) ?? source;
  };
  return Object.assign(translate, { locale });
}

const englishTranslator = createTranslator({}, SOURCE_LOCALE);

const DEFAULT_CONTEXT: I18nContextValue = {
  locale: SOURCE_LOCALE,
  resolved: false,
  dir: "ltr",
  t: englishTranslator,
  settings: undefined,
  enabledLocales: [],
  changeLocale: async () => {},
  registerNamespaces: () => {},
  previewDrafts: false,
  setPreviewDrafts: () => {},
  previewError: null,
  catalogVersion: null,
};

const I18nContext = createContext<I18nContextValue>(DEFAULT_CONTEXT);

export const i18nKeys = {
  all: ["i18n"] as const,
  settings: (host: string) => ["i18n", host, "settings"] as const,
  meLocale: (host: string, userId: string) => ["i18n", host, "me-locale", userId] as const,
  catalog: (host: string, locale: string, namespaces: string, draft: boolean) =>
    ["i18n", host, locale, "latest", namespaces, draft ? "preview" : "published"] as const,
};

function currentHost(): string {
  return typeof window === "undefined" ? "" : window.location.host;
}


/** Same pathname + search with any `lang` param removed (other params preserved). */
export function urlWithoutLang(): string {
  const params = new URLSearchParams(window.location.search);
  params.delete("lang");
  const search = params.toString();
  return `${window.location.pathname}${search ? `?${search}` : ""}${window.location.hash}`;
}

/**
 * Reports the page URL's `?lang=` (canonicalized) to the provider. Kept in its
 * own Suspense boundary so `useSearchParams` never forces the app shell into
 * client-side rendering. Rendered after `children`, so its first effect runs
 * after the initial route's `useNamespaces` registrations: the first catalog
 * request already includes them.
 */
function LangParamWatcher({ onChange }: { onChange: (locale: string | null) => void }) {
  const searchParams = useSearchParams();
  const lang = searchParams.get("lang");
  useEffect(() => {
    onChange(canonicalizeLocale(lang));
  }, [lang, onChange]);
  return null;
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const { session, authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  // Client-only reads; the server render (and hydration) is English, so
  // nothing here changes the hydrated markup.
  const [cookieLocale, setCookieLocale] = useState<string | null>(() =>
    typeof document === "undefined" ? null : canonicalizeLocale(readLocaleCookie())
  );
  // `undefined` until the URL was read once (then the catalog may load).
  const [urlLocale, setUrlLocale] = useState<string | null | undefined>(undefined);
  const [switchedLocale, setSwitchedLocale] = useState<string | null>(null);
  // `common` (always served) and `errors` (API error texts, used on every surface).
  const [namespaces, setNamespaces] = useState<string[]>(["common", "errors"]);
  const [previewDrafts, setPreviewDraftsState] = useState(false);
  const mounted = urlLocale !== undefined;
  const host = mounted ? currentHost() : "";

  const tenantSession = session?.kind === "tenant" ? session : null;
  const userId = tenantSession?.userId ?? null;

  // A switcher choice applies until the next navigation (it is persisted, so
  // the next page resolves it from the user preference / cookie anyway).
  const [seenPath, setSeenPath] = useState(pathname);
  if (seenPath !== pathname) {
    setSeenPath(pathname);
    setSwitchedLocale(null);
  }
  // Signing out / switching user ends any draft preview.
  const [seenUser, setSeenUser] = useState(userId);
  if (seenUser !== userId) {
    setSeenUser(userId);
    setPreviewDraftsState(false);
  }

  const onUrlLocale = useCallback((locale: string | null) => setUrlLocale(locale), []);

  const settingsQuery = useQuery({
    queryKey: i18nKeys.settings(host),
    queryFn: fetchI18nSettings,
    enabled: mounted,
    staleTime: 5 * 60_000,
    retry: 1,
  });

  const meLocaleQuery = useQuery({
    queryKey: i18nKeys.meLocale(host, userId ?? ""),
    queryFn: () => authorizedFetch<MyLocaleResponse>("tenant", ME_LOCALE_PATH),
    enabled: mounted && userId !== null,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const userLocale = userId ? canonicalizeLocale(meLocaleQuery.data?.locale ?? null) : null;
  // Order (doc §2): explicit (switcher action / `?lang=`), user preference, cookie, server.
  const requested = switchedLocale ?? urlLocale ?? userLocale ?? cookieLocale ?? null;
  // A signed-in user's saved preference outranks the cookie: wait for it.
  const waitingForUser = userId !== null && !urlLocale && !switchedLocale && meLocaleQuery.isPending;
  const nsKey = namespaces.join(",");

  const publishedQuery = useQuery({
    queryKey: i18nKeys.catalog(host, requested ?? "auto", nsKey, false),
    queryFn: () => fetchI18nCatalog(requested, namespaces),
    enabled: mounted && !waitingForUser,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60_000,
    retry: 1,
  });

  const published = publishedQuery.data;
  const previewLocale = published?.locale ?? requested ?? SOURCE_LOCALE;
  const previewQuery = useQuery({
    queryKey: i18nKeys.catalog(host, previewLocale, nsKey, true),
    queryFn: () =>
      authorizedFetch<I18nCatalog>("tenant", previewCatalogPath(previewLocale, namespaces), {
        headers: { [LOCALE_HEADER]: previewLocale },
        cache: "no-store",
      }),
    enabled: mounted && previewDrafts && userId !== null,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });

  const active: I18nCatalog | undefined =
    previewDrafts && previewQuery.data ? previewQuery.data : published;
  const locale = active?.locale ?? SOURCE_LOCALE;
  const resolved = active !== undefined;

  // Header + <html lang/dir> follow the resolved locale only.
  const previousLocale = useRef<string | null>(null);
  const firstUrlLocale = useRef<string | null>(null);
  useEffect(() => {
    if (previousLocale.current === null) firstUrlLocale.current = urlLocale ?? null;
  }, [urlLocale]);
  useEffect(() => {
    if (!resolved) return;
    setRequestLocale(locale);
    document.documentElement.lang = locale;
    document.documentElement.dir = localeDir(locale);
    const before = previousLocale.current;
    previousLocale.current = locale;
    // Localized API content (course titles, ...) was fetched in the old language.
    if (before !== null && before !== locale) {
      void queryClient.invalidateQueries({ predicate: (query) => query.queryKey[0] !== "i18n" });
    } else if (before === null && firstUrlLocale.current !== null) {
      // Requests sent before resolution let the server resolve without the
      // page's `?lang=`; only that case can differ from the resolved locale.
      void queryClient.invalidateQueries({ predicate: (query) => query.queryKey[0] !== "i18n" });
    }
  }, [resolved, locale, queryClient]);

  const t = useMemo(
    () => (active ? createTranslator(active.messages ?? {}, locale) : englishTranslator),
    [active, locale]
  );

  const enabledLocales = useMemo(() => {
    const list = settingsQuery.data?.enabledLocales ?? [];
    return list.map((tag) => canonicalizeLocale(tag)).filter((tag): tag is string => tag !== null);
  }, [settingsQuery.data]);

  const registerNamespaces = useCallback((list: readonly string[]) => {
    setNamespaces((current) => {
      const next = new Set(current);
      for (const ns of list) next.add(ns);
      if (next.size === current.length) return current;
      return [...next].sort();
    });
  }, []);

  const changeLocale = useCallback(
    async (input: string) => {
      const next = canonicalizeLocale(input);
      if (!next) return;
      if (userId !== null && !tenantSession?.impersonation) {
        await authorizedFetch<MyLocaleResponse>("tenant", ME_LOCALE_PATH, {
          method: "PUT",
          body: JSON.stringify({ locale: next }),
        });
        queryClient.setQueryData<MyLocaleResponse>(i18nKeys.meLocale(currentHost(), userId), { locale: next });
      }
      writeLocaleCookie(next);
      setCookieLocale(next);
      setSwitchedLocale(next);
      router.replace(urlWithoutLang(), { scroll: false });
    },
    [authorizedFetch, queryClient, router, tenantSession?.impersonation, userId]
  );

  const setPreviewDrafts = useCallback(
    (on: boolean) => {
      setPreviewDraftsState(on);
      if (!on) {
        // A preview catalog must never be reused as the production catalog.
        queryClient.removeQueries({ predicate: (query) => query.queryKey[0] === "i18n" && query.queryKey[5] === "preview" });
      }
    },
    [queryClient]
  );

  const value = useMemo<I18nContextValue>(
    () => ({
      locale,
      resolved,
      dir: localeDir(locale),
      t,
      settings: settingsQuery.data,
      enabledLocales,
      changeLocale,
      registerNamespaces,
      previewDrafts,
      setPreviewDrafts,
      previewError: previewDrafts ? previewQuery.error : null,
      catalogVersion: active?.version ?? null,
    }),
    [
      locale,
      resolved,
      t,
      settingsQuery.data,
      enabledLocales,
      changeLocale,
      registerNamespaces,
      previewDrafts,
      setPreviewDrafts,
      previewQuery.error,
      active?.version,
    ]
  );

  return (
    <I18nContext.Provider value={value}>
      {previewDrafts ? <PreviewBanner /> : null}
      {children}
      <Suspense fallback={null}>
        <LangParamWatcher onChange={onUrlLocale} />
      </Suspense>
    </I18nContext.Provider>
  );
}

function PreviewBanner() {
  const { t, setPreviewDrafts, previewError } = useI18n();
  return (
    <div
      role="status"
      data-testid="i18n-preview-banner"
      className="sticky top-0 z-[70] flex flex-wrap items-center justify-center gap-x-3 gap-y-1 border-b border-amber-300 bg-amber-100 px-4 py-2 text-sm text-amber-950 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-50"
    >
      <Eye className="size-4 shrink-0" aria-hidden="true" />
      <span className="min-w-0 break-words">
        {previewError ? t("i18nAdmin.preview.failed") : t("i18nAdmin.preview.banner")}
      </span>
      <button
        type="button"
        onClick={() => setPreviewDrafts(false)}
        className="inline-flex items-center gap-1 rounded font-medium underline underline-offset-2 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <X className="size-3.5" aria-hidden="true" />
        {t("i18nAdmin.preview.exit")}
      </button>
    </div>
  );
}

export function useI18n(): I18nContextValue {
  return useContext(I18nContext);
}

/** `t(key, params?)` for the resolved locale. Always call it with a literal key. */
export function useT(): TranslateFn {
  return useContext(I18nContext).t;
}

/** Requests extra namespaces (besides `common`) for the mounted route. */
export function useNamespaces(namespaces: readonly string[]): void {
  const { registerNamespaces } = useContext(I18nContext);
  const key = namespaces.join(",");
  useEffect(() => {
    registerNamespaces(key ? key.split(",") : []);
  }, [key, registerNamespaces]);
}

/** Route-group helper: `<I18nNamespaces ns={["teacher"]} />` in a layout. */
export function I18nNamespaces({ ns }: { ns: readonly string[] }) {
  useNamespaces(ns);
  return null;
}
