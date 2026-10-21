"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/auth-context";
import type { PrincipalKind } from "@/lib/api/auth";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { isApiClientError } from "@/lib/api/error";

interface RouteGuardProps {
  kind: PrincipalKind;
  /** Login route to redirect to when no session can be established, e.g. "/login" or "/platform-admin/login". */
  loginPath: string;
  children: ReactNode;
}

/**
 * Route-group guard: attempts to establish a session (refreshing via the
 * HttpOnly refresh cookie if needed) before rendering `children`; redirects
 * to `loginPath` only for a real backend 401 and renders a retryable error for
 * other failures. This is UX convenience only — it exists to avoid a flash of
 * wrong-portal content before a real backend 401/403 is even possible, per plan
 * §11d. It is NOT the authorization mechanism: the
 * backend independently rejects every request regardless of whether this
 * guard ran, and a caller must still handle 401/403 from real data calls
 * (see QueryStateBoundary) rather than relying on this guard alone.
 */
export function RouteGuard({ kind, loginPath, children }: RouteGuardProps) {
  const { ensureAccessToken } = useAuth();
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [authError, setAuthError] = useState<unknown>(null);
  const [retryNumber, setRetryNumber] = useState(0);

  // `ensureAccessToken` is not referentially stable across session changes
  // (auth-context.tsx wraps it in `useCallback` with `[session]` as a dep),
  // and calling it successfully is exactly what changes `session`. Depending
  // on it directly would re-run this effect — and re-issue a refresh call —
  // every time the very session state it just established changes, which
  // this guard only needs to check once per `kind`/`loginPath`. A ref holds
  // the latest callbacks so the effect always calls the current version
  // without those identities being part of the effect's own trigger
  // condition — this is a deliberate choice, not a suppressed lint rule.
  const latest = useRef({ ensureAccessToken, router });
  useEffect(() => {
    latest.current = { ensureAccessToken, router };
  });

  useEffect(() => {
    let cancelled = false;

    latest.current
      .ensureAccessToken(kind)
      .then(() => {
        if (!cancelled) setReady(true);
      })
      .catch((error: unknown) => {
        if (cancelled) return;

        if (isApiClientError(error) && error.status === 401) {
          latest.current.router.replace(`${loginPath}?reason=session_expired`);
          return;
        }

        setAuthError(error);
      });
    return () => {
      cancelled = true;
    };
  }, [kind, loginPath, retryNumber]);

  if (!ready) {
    if (authError) {
      return (
        <ErrorState
          message={
            isApiClientError(authError)
              ? authError.message
              : "We couldn't verify your session. Please try again."
          }
          code={isApiClientError(authError) ? authError.code : undefined}
          onRetry={() => {
            setAuthError(null);
            setRetryNumber((value) => value + 1);
          }}
        />
      );
    }

    return <LoadingState label="Checking your session…" />;
  }

  return <>{children}</>;
}
