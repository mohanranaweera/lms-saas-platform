"use client";

import type { ReactNode } from "react";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { LoadingState } from "./loading-state";
import { EmptyState } from "./empty-state";
import { ErrorState } from "./error-state";
import { PermissionDeniedState } from "./permission-denied-state";
import { classifyQueryError } from "@/lib/api/query-status";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";

interface EmptyStateContent {
  title: string;
  description: string;
  action?: { label: string; onClick: () => void };
  icon?: ReactNode;
}

interface PermissionDeniedContent {
  dashboardHref?: string;
  action?: { label: string; onClick: () => void };
}

/**
 * Deliberately looser than `Pick<UseQueryResult<T, unknown>, ...>`: this
 * component only ever calls `query.refetch()` and discards the result (see
 * the retry handler below), so it doesn't need — and shouldn't demand — the
 * real `QueryObserverResult` return shape. A real `UseQueryResult<T,
 * unknown>` remains structurally assignable here (TypeScript's function-type
 * covariance makes `() => Promise<QueryObserverResult<T, unknown>>`
 * assignable to `() => Promise<unknown>`), so every existing caller passing
 * an actual React Query result keeps working unchanged. This also lets a
 * hand-combined multi-query result (e.g. `useTenantCourseCounts` in
 * `lib/api/tenant-overview.ts`) satisfy this prop without an unsafe cast.
 */
interface QueryLike<T> {
  status: "pending" | "error" | "success";
  data: T | undefined;
  error: unknown;
  refetch: () => Promise<unknown>;
}

interface QueryStateBoundaryProps<T> {
  query: QueryLike<T>;
  loadingLabel?: string;
  /** Called on success to decide whether to render `emptyState` instead of `children`. Omit both if the surface is never meaningfully empty. */
  isEmpty?: (data: T) => boolean;
  emptyState?: EmptyStateContent;
  permissionDenied?: PermissionDeniedContent;
  /**
   * When set, a 401 ("unauthenticated") classification redirects here
   * (with `?reason=session_expired` appended, matching the existing
   * `login-form.tsx` / auth-context.tsx convention for that query param)
   * instead of rendering anything in place — per plan §11b, 401 means "no
   * valid session, nothing useful to render here", unlike 403 which renders
   * PermissionDeniedState in place. Omit if the caller wants to handle 401
   * itself.
   */
  loginPath?: string;
  /** Fallback error message shown only when the failure is NOT a real `ApiClientError` (e.g. a network failure) — a real API error's own `.message` always takes precedence over this. Defaults to the existing generic copy; override per call site so screen-reader users can distinguish which of several independent boundaries on one page failed. */
  genericErrorMessage?: string;
  children: (data: T) => ReactNode;
}

/**
 * Dispatches a React Query result to the shared `LoadingState` / `EmptyState`
 * / `ErrorState` / `PermissionDeniedState` components based on
 * `classifyQueryError`, so individual pages never hand-roll this branching
 * (and never lose the accessibility attributes those components already
 * carry). See plan §11a.
 */
export function QueryStateBoundary<T>({
  query,
  loadingLabel,
  isEmpty,
  emptyState,
  permissionDenied,
  loginPath,
  genericErrorMessage,
  children,
}: QueryStateBoundaryProps<T>) {
  const router = useRouter();
  const kind = query.status === "error" ? classifyQueryError(query.error) : null;

  useEffect(() => {
    if (kind === "unauthenticated" && loginPath) {
      router.replace(`${loginPath}?reason=session_expired`);
    }
  }, [kind, loginPath, router]);

  if (query.status === "pending") {
    return <LoadingState label={loadingLabel} />;
  }

  if (query.status === "error") {
    if (kind === "unauthenticated" && loginPath) {
      // Redirect effect above handles navigation; keep showing a loading
      // state in the interim so there's no flash of empty/wrong content.
      return <LoadingState label={loadingLabel} />;
    }
    if (kind === "forbidden") {
      return (
        <PermissionDeniedState error={query.error as ApiClientError} {...permissionDenied} />
      );
    }
    return (
      <ErrorState
        message={
          isApiClientError(query.error)
            ? query.error.message
            : (genericErrorMessage ?? "Something went wrong. Please try again.")
        }
        code={isApiClientError(query.error) ? query.error.code : undefined}
        fieldErrors={isApiClientError(query.error) ? query.error.fieldErrors : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }

  // query.status === "success" here. `QueryLike`'s `status`/`data` fields
  // are independent (not a discriminated union keyed on `status`), so
  // TypeScript can't narrow `query.data` from `T | undefined` via `status`
  // alone — `data` is asserted here rather than narrowed structurally.
  const data = query.data as T;

  if (isEmpty && emptyState && isEmpty(data)) {
    return <EmptyState {...emptyState} />;
  }

  return <>{children(data)}</>;
}
