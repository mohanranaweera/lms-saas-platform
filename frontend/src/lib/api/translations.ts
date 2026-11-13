"use client";

import { useMutation, useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { env } from "@/lib/env";
import { ApiClientError } from "./error";
import type { PageResponse } from "./courses";

/**
 * I18N-01 Translation Management client (`/api/v1/i18n/admin`,
 * docs/architecture/i18n-translation-management.md §5-§7). Tenant scope comes
 * from the authenticated context only; no tenant id is ever sent. Every call
 * is authorized server-side (TRANSLATIONS_VIEW / _EDIT / _PUBLISH); the UI's
 * capability checks only decide what to render.
 */

export type TranslationOrigin = "OVERRIDE" | "PLATFORM" | "SOURCE";
export type PlatformTranslationStatus = "TRANSLATED" | "NEEDS_REVIEW" | "MISSING";
/** `status` filter values (doc §6 `counts` keys, upper-cased enum on the wire). */
export type TranslationStatusFilter = "MISSING" | "TRANSLATED" | "NEEDS_REVIEW" | "OVERRIDDEN" | "DRAFTS";

export interface TranslationDraft {
  action: "SET" | "RESET";
  value: string | null;
  version: number;
  updatedAt: string;
}

export interface TranslationEntry {
  key: string;
  namespace: string;
  source: string;
  context: string | null;
  platformValue: string | null;
  platformStatus: PlatformTranslationStatus;
  /** Fuzzy ("needs review") platform suggestion - never served. */
  suggestion: string | null;
  publishedOverride: string | null;
  overrideNeedsReview: boolean;
  draft: TranslationDraft | null;
  effectiveValue: string;
  origin: TranslationOrigin;
}

export interface TranslationCounts {
  total: number;
  missing: number;
  translated: number;
  needsReview: number;
  overridden: number;
  drafts: number;
}

/** `EntriesPage`: `releaseNo` is the current published version (0 = none). */
export interface TranslationEntriesResponse {
  locale: string;
  releaseNo: number;
  counts: TranslationCounts;
  page: PageResponse<TranslationEntry>;
}

export interface TranslationEntriesParams {
  locale: string;
  namespace?: string | null;
  q?: string;
  status?: TranslationStatusFilter | null;
  page?: number;
  size?: number;
}

/** `ReleaseView` - one published version per (tenant, locale). */
export interface TranslationRelease {
  id: string;
  locale: string;
  versionNo: number;
  summary: string | null;
  entryCount: number;
  rollbackOfReleaseId: string | null;
  publishedBy: string | null;
  publishedAt: string;
  current: boolean;
}

/**
 * `ImportReport`: `valid` = no invalid message (a non-dry run only writes when
 * valid); unknown keys and fuzzy/empty/obsolete entries are skipped and reported.
 */
export interface PoImportReport {
  locale: string;
  dryRun: boolean;
  valid: boolean;
  entries: number;
  created: number;
  updated: number;
  unchanged: number;
  skippedEmpty: number;
  skippedFuzzy: number;
  skippedObsolete: number;
  /** Needs-review (fuzzy) entries imported as drafts; only with `includeNeedsReview`. */
  needsReview: number;
  unknownKeys: string[];
  sourceChanged: string[];
  invalid: Array<{ key: string; problems: string[] }>;
}

export type ExportScope = "OVERRIDES" | "EFFECTIVE";
export type ExportFormat = "po" | "mo";

const BASE = "/v1/i18n/admin";

export const translationKeys = {
  all: ["translations-admin"] as const,
  entries: (params: TranslationEntriesParams) => [...translationKeys.all, "entries", params] as const,
  releases: (locale: string) => [...translationKeys.all, "releases", locale] as const,
};

function draftPath(locale: string, key: string): string {
  return `${BASE}/drafts/${encodeURIComponent(locale)}/${encodeURIComponent(key)}`;
}

function entriesQuery(params: TranslationEntriesParams): string {
  const search = new URLSearchParams();
  search.set("locale", params.locale);
  if (params.namespace) search.set("namespace", params.namespace);
  if (params.q && params.q.trim()) search.set("q", params.q.trim());
  if (params.status) search.set("status", params.status);
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 25));
  return search.toString();
}

export function useTranslationEntries(params: TranslationEntriesParams, enabled = true) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: translationKeys.entries(params),
    queryFn: () => authorizedFetch<TranslationEntriesResponse>("tenant", `${BASE}/entries?${entriesQuery(params)}`),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useTranslationReleases(locale: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: translationKeys.releases(locale),
    queryFn: () =>
      authorizedFetch<TranslationRelease[]>("tenant", `${BASE}/releases?locale=${encodeURIComponent(locale)}`),
  });
}

/** Current published version for `expectedVersion` (0 when nothing was published yet). */
export function latestReleaseVersion(releases: TranslationRelease[] | undefined): number {
  return (releases ?? []).reduce((max, release) => Math.max(max, release.versionNo), 0);
}

function useInvalidateTranslations() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: translationKeys.all });
    // Published changes alter the effective catalog; preview reflects drafts.
    void queryClient.invalidateQueries({ queryKey: ["i18n"] });
  };
}

export function useSaveTranslationDraft() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTranslations();
  return useMutation({
    // `expectedVersion`: the draft's version, or null when there is no draft yet.
    mutationFn: (input: { locale: string; key: string; value: string; expectedVersion: number | null }) =>
      authorizedFetch<unknown>("tenant", draftPath(input.locale, input.key), {
        method: "PUT",
        body: JSON.stringify({ value: input.value, expectedVersion: input.expectedVersion }),
      }),
    onSuccess: invalidate,
  });
}

export function useResetTranslation() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTranslations();
  return useMutation({
    mutationFn: (input: { locale: string; key: string; expectedVersion: number | null }) =>
      authorizedFetch<unknown>("tenant", `${draftPath(input.locale, input.key)}/reset`, {
        method: "POST",
        body: JSON.stringify({ expectedVersion: input.expectedVersion }),
      }),
    onSuccess: invalidate,
  });
}

export function useDiscardTranslationDraft() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTranslations();
  return useMutation({
    mutationFn: (input: { locale: string; key: string; expectedVersion: number }) =>
      authorizedFetch<unknown>(
        "tenant",
        `${draftPath(input.locale, input.key)}?expectedVersion=${encodeURIComponent(String(input.expectedVersion))}`,
        { method: "DELETE" }
      ),
    onSuccess: invalidate,
  });
}

export function usePublishTranslations() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTranslations();
  return useMutation({
    mutationFn: (input: { locale: string; expectedVersion: number; summary: string }) =>
      authorizedFetch<TranslationRelease>("tenant", `${BASE}/publish`, {
        method: "POST",
        body: JSON.stringify(input),
      }),
    onSuccess: invalidate,
  });
}

export function useRollbackTranslations() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTranslations();
  return useMutation({
    mutationFn: (input: { releaseId: string; expectedVersion: number; summary: string }) =>
      authorizedFetch<TranslationRelease>("tenant", `${BASE}/releases/${encodeURIComponent(input.releaseId)}/rollback`, {
        method: "POST",
        body: JSON.stringify({ expectedVersion: input.expectedVersion, summary: input.summary }),
      }),
    onSuccess: invalidate,
  });
}

export function useImportPo() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateTranslations();
  return useMutation({
    mutationFn: (input: { locale: string; file: File; dryRun: boolean; includeNeedsReview: boolean }) => {
      const body = new FormData();
      body.append("file", input.file);
      return authorizedFetch<PoImportReport>(
        "tenant",
        `${BASE}/import?locale=${encodeURIComponent(input.locale)}&dryRun=${input.dryRun}` +
          `&includeNeedsReview=${input.includeNeedsReview}`,
        { method: "POST", body }
      );
    },
    onSuccess: (_data, input) => {
      if (!input.dryRun) invalidate();
    },
  });
}

/**
 * `GET /export` returns a file (not the JSON envelope), so it is fetched raw
 * with the session's bearer token and handed to the browser as a download.
 */
export function useExportTranslations() {
  const { ensureAccessToken } = useAuth();
  return useMutation({
    mutationFn: async (input: { locale: string; scope: ExportScope; format: ExportFormat }) => {
      const token = await ensureAccessToken("tenant");
      const params = new URLSearchParams({ locale: input.locale, scope: input.scope, format: input.format });
      let response: Response;
      try {
        response = await fetch(`${env.NEXT_PUBLIC_API_BASE_URL}${BASE}/export?${params.toString()}`, {
          credentials: "include",
          headers: { Authorization: `Bearer ${token}` },
        });
      } catch {
        throw new ApiClientError({ code: "NETWORK_ERROR", message: "Unable to reach the server.", fieldErrors: [] }, 0);
      }
      if (!response.ok) {
        let error = { code: "UNKNOWN_ERROR", message: "Export failed.", fieldErrors: [] as never[] };
        try {
          const payload = await response.json();
          if (payload?.error) error = payload.error;
        } catch {
          // non-JSON error body
        }
        throw new ApiClientError(error, response.status);
      }
      const blob = await response.blob();
      const disposition = response.headers.get("Content-Disposition") ?? "";
      const named = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)?.[1];
      const filename = named
        ? decodeURIComponent(named)
        : `translations-${input.locale}-${input.scope.toLowerCase()}.${input.format}`;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return filename;
    },
  });
}

// ---------------------------------------------------------------------------
// Course content variants (§8): `/api/v1/courses/{courseId}/localizations`.
// ---------------------------------------------------------------------------

/** `VariantView`: the working copy plus what is currently published for that locale. */
export interface CourseLocalization {
  locale: string;
  title: string | null;
  description: string | null;
  status: "DRAFT" | "PUBLISHED";
  publishedTitle: string | null;
  publishedDescription: string | null;
  publishedAt: string | null;
  publishedBy: string | null;
  hasUnpublishedChanges: boolean;
  version: number;
  updatedAt: string | null;
}

/** `VariantsView` - `GET /courses/{id}/localizations`. */
export interface CourseLocalizations {
  courseId: string;
  contentLocale: string | null;
  originalTitle: string;
  variants: CourseLocalization[];
}

export const courseLocalizationKeys = {
  list: (courseId: string) => ["course-localizations", courseId] as const,
};

export function useCourseLocalizations(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseLocalizationKeys.list(courseId),
    queryFn: () => authorizedFetch<CourseLocalizations>("tenant", `/v1/courses/${encodeURIComponent(courseId)}/localizations`),
  });
}

function useCourseLocalizationMutation<TInput extends { courseId: string; locale: string }>(
  request: (input: TInput) => { path: string; init: RequestInit }
) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const { path, init } = request(input);
      return authorizedFetch<CourseLocalization>("tenant", path, init);
    },
    onSuccess: (_data, input) => {
      void queryClient.invalidateQueries({ queryKey: courseLocalizationKeys.list(input.courseId) });
      void queryClient.invalidateQueries({ queryKey: ["public-courses"] });
    },
  });
}

function localizationPath(courseId: string, locale: string): string {
  return `/v1/courses/${encodeURIComponent(courseId)}/localizations/${encodeURIComponent(locale)}`;
}

export function useSaveCourseLocalization() {
  return useCourseLocalizationMutation(
    (input: { courseId: string; locale: string; title: string; description: string; expectedVersion: number | null }) => ({
      path: localizationPath(input.courseId, input.locale),
      init: {
        method: "PUT",
        body: JSON.stringify({ title: input.title, description: input.description, expectedVersion: input.expectedVersion }),
      },
    })
  );
}

export function usePublishCourseLocalization() {
  return useCourseLocalizationMutation(
    (input: { courseId: string; locale: string; publish: boolean; expectedVersion: number }) => ({
      path: `${localizationPath(input.courseId, input.locale)}/${input.publish ? "publish" : "unpublish"}`,
      init: { method: "POST", body: JSON.stringify({ expectedVersion: input.expectedVersion }) },
    })
  );
}
