"use client";

import type { ReactNode } from "react";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import type { UseQueryResult } from "@tanstack/react-query";
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

interface QueryStateBoundaryProps<T> {
  query: Pick<UseQueryResult<T, unknown>, "status" | "data" | "error" | "refetch">;
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
        message={isApiClientError(query.error) ? query.error.message : "Something went wrong. Please try again."}
        code={isApiClientError(query.error) ? query.error.code : undefined}
        fieldErrors={isApiClientError(query.error) ? query.error.fieldErrors : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }

  // query.status === "success" here (Pick<UseQueryResult, ...>'s status is
  // narrowed to "pending" | "error" | "success" in @tanstack/react-query v5;
  // TypeScript can narrow `query.data` from the union via `status` alone only
  // when discriminating the full `UseQueryResult` type, which this `Pick`
  // intentionally loosens for caller convenience — so `data` is asserted here
  // rather than narrowed structurally.
  const data = query.data as T;

  if (isEmpty && emptyState && isEmpty(data)) {
    return <EmptyState {...emptyState} />;
  }

  return <>{children(data)}</>;
}
