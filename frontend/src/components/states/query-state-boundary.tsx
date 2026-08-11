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

interface NotFoundContent {
  /** Rendered as `ErrorState`'s `message` — no backend `code`/`message` and no
   * "Try again" retry button are ever shown alongside it (see below): the
   * whole point of this branch is that a 403 and a 404 must render
   * identically, so no signal distinguishing the two cases can leak through. */
  title: string;
}

interface QueryStateBoundaryProps<T> {
  query: Pick<UseQueryResult<T, unknown>, "status" | "data" | "error" | "refetch">;
  loadingLabel?: string;
  /** Called on success to decide whether to render `emptyState` instead of `children`. Omit both if the surface is never meaningfully empty. */
  isEmpty?: (data: T) => boolean;
  emptyState?: EmptyStateContent;
  permissionDenied?: PermissionDeniedContent;
  /**
   * When supplied, this query is treated as an id-addressed single-resource
   * lookup where the backend intentionally returns an indistinguishable
   * 403-or-404 for "not yours" (see `.claude/rules` / `classifyQueryError`).
   * Both a 404 and a 403 then render identically via `ErrorState` using only
   * `title` — never the raw backend `error.message`/`error.code`, and never
   * a retry button (this is a permanent condition, not a transient
   * failure). Omit for endpoints where a 403 legitimately means "no access
   * to this feature at all" (e.g. list/create) — those keep rendering
   * `PermissionDeniedState` on 403 as before.
   */
  notFound?: NotFoundContent;
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
  notFound,
  loginPath,
  children,
}: QueryStateBoundaryProps<T>) {
  const router = useRouter();
  const kind =
    query.status === "error"
      ? classifyQueryError(query.error, { treatForbiddenAsNotFound: !!notFound })
      : null;

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
    if (kind === "not_found" && notFound) {
      // Deliberately no `code`/backend `message` and no `onRetry`: a 403 and
      // a 404 must be indistinguishable here (see `notFound` prop doc), and
      // this is a permanent condition, not a transient one worth retrying.
      return <ErrorState message={notFound.title} />;
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
