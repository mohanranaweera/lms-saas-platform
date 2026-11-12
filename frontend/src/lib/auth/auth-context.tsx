"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  authPaths,
  login as loginRequest,
  refresh as refreshRequest,
  type LoginRequest,
  type PrincipalKind,
} from "@/lib/api/auth";
import { apiFetch } from "@/lib/api/client";
import { ApiClientError, isApiClientError } from "@/lib/api/error";
import {
  endImpersonationSession,
  requestImpersonationToken,
  type ImpersonatedStudent,
  type ImpersonationTokenResponse,
} from "@/lib/api/impersonation";
import { env } from "@/lib/env";
import { decodeAccessTokenPayload } from "./jwt";
import { clearAllRememberedExamAttempts } from "@/lib/exam-attempt-storage";
import {
  clearImpersonationMarker,
  isImpersonationTab,
  setImpersonationMarker,
} from "./impersonation-marker";

/** ADR-033: display data for a support ("Log as Student") session — never an authorization signal. */
export interface ImpersonationInfo {
  sessionId: string | null;
  expiresAt: string;
  student: ImpersonatedStudent;
  actorName: string;
}

interface Session {
  accessToken: string;
  role: string | null;
  /**
   * The authenticated user id (JWT `sub` claim), decoded client-side for
   * DISPLAY/SCOPING CONVENIENCE ONLY — same caveat as `role` above, never an
   * authorization signal. Used to scope purely-client-side, best-effort
   * localStorage state (e.g. `lib/exam-attempt-storage.ts`) to the signed-in
   * user on a shared device, never to gate access to anything.
   */
  userId: string | null;
  kind: PrincipalKind;
  /** ADR-033: present only in a support tab; the token is the read-only impersonation token. */
  impersonation?: ImpersonationInfo;
}

export interface LoginResult {
  role: string | null;
  mustChangePassword: boolean;
}

export const IMPERSONATION_ENDED_CODE = "IMPERSONATION_ENDED";
export const IMPERSONATION_READ_ONLY_CODE = "IMPERSONATION_READ_ONLY";

function impersonationEndedError(): ApiClientError {
  return new ApiClientError(
    { code: IMPERSONATION_ENDED_CODE, message: "The support session has ended.", fieldErrors: [] },
    401
  );
}

function sessionFromImpersonation(data: ImpersonationTokenResponse): Session {
  const decoded = decodeAccessTokenPayload(data.accessToken);
  return {
    accessToken: data.accessToken,
    role: decoded?.role ?? "STUDENT",
    userId: decoded?.sub ?? data.student.id,
    kind: "tenant",
    impersonation: {
      sessionId: data.sessionId ?? null,
      expiresAt: data.expiresAt,
      student: data.student,
      actorName: data.actorName,
    },
  };
}

interface AuthContextValue {
  /** `null` when signed out, or when this tab hasn't (re)established a session
   * yet (e.g. a fresh page load — the access token is intentionally in-memory
   * only, never persisted, see auth-context.tsx module doc). */
  session: Session | null;
  login: (kind: PrincipalKind, credentials: LoginRequest) => Promise<LoginResult>;
  /**
   * Clears local session state unconditionally (this tab must stop acting as
   * the user regardless of network outcome), then re-throws the server-call
   * failure if the backend revoke didn't actually succeed — callers must
   * surface this to the user (never silently redirect as if logout fully
   * succeeded when the `device_session` row may still be active server-side).
   */
  logout: (kind: PrincipalKind) => Promise<void>;
  /**
   * Returns a usable access token for `kind`, refreshing via the rotated
   * refresh-token cookie first if none is held in memory. Throws `ApiClientError`
   * (401 `INVALID_REFRESH_TOKEN`) if refresh also fails — callers are responsible
   * for redirecting to the correct login route with `?reason=session_expired` on
   * that failure (this module has no other protected data-fetching endpoint to
   * drive a generic redirect from, per plan §11/§21).
   *
   * ADR-033: in a support tab (sessionStorage marker set) the token is
   * re-acquired only from `POST /auth/impersonation/token` — never from
   * `/auth/refresh` — and a failure ends the support session in this tab.
   */
  ensureAccessToken: (kind: PrincipalKind, options?: { force?: boolean }) => Promise<string>;
  /**
   * The reusable "silent refresh" surface for protected requests (plan §11 item
   * 4): attach a usable access token (refreshing first if none is held); on a
   * 401 `UNAUTHENTICATED`/`SESSION_REVOKED` response, refresh exactly once more
   * and retry; otherwise propagate the error for the caller to handle (e.g.
   * redirect to login, or a 403 rendered via `QueryStateBoundary`/
   * `classifyQueryError`). This is the only sanctioned way for a feature module
   * to call an authenticated endpoint — do not call `apiFetch` directly with a
   * manually-attached token. Every authenticated API hook (e.g.
   * `lib/api/students.ts`, `lib/api/courses.ts`) calls this from its
   * `queryFn`/`mutationFn` instead of re-deriving its own auth header/retry
   * handling.
   */
  authorizedFetch: <T>(kind: PrincipalKind, path: string, init?: RequestInit) => Promise<T>;
  /** ADR-033: true once this support tab's session can no longer be used (expired, ended, replaced). */
  impersonationEnded: boolean;
  /** ADR-033: friendly copy for the latest `403 IMPERSONATION_READ_ONLY`; auto-clears. */
  readOnlyNotice: string | null;
  dismissReadOnlyNotice: () => void;
  /** `/impersonate`: marks this tab as a support tab and acquires the impersonation token. */
  startImpersonation: () => Promise<ImpersonationInfo>;
  /** Banner "Exit to admin": ends the session server-side, clears the query cache and the marker, returns to the students list. */
  endImpersonation: () => Promise<void>;
  /** "Support session ended" page: drops the marker and cache and returns to the admin students list. */
  leaveEndedImpersonation: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Holds the access token in memory only (component state, never
 * `localStorage`/`sessionStorage`/a cookie readable by JS) — a bearer token an
 * XSS could exfiltrate has no business surviving in persistent client storage.
 * The refresh token is never read/written here at all: it is `HttpOnly` and only
 * ever travels implicitly via `credentials: "include"` (see lib/api/client.ts).
 *
 * This intentionally means the access token is lost on a full page reload;
 * `ensureAccessToken` re-establishes it from the refresh cookie on demand (or,
 * in an ADR-033 support tab, from the HttpOnly impersonation cookie).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [impersonationEnded, setImpersonationEnded] = useState(false);
  const [readOnlyNotice, setReadOnlyNotice] = useState<string | null>(null);
  const queryClient = useQueryClient();

  // Coalesces overlapping refresh calls for the same principal kind (e.g. two
  // protected requests racing on mount) into a single in-flight request — the
  // refresh endpoint invalidates the previous refresh-token cookie the instant
  // it issues a new one, so firing two rotations concurrently would strand the
  // second caller with an already-rotated-out cookie. A ref is appropriate here
  // (unlike session itself) because it's only ever mutated from inside async
  // callbacks, never read during render.
  const refreshInFlight = useRef<Partial<Record<PrincipalKind, Promise<string>>>>({});

  const login = useCallback(async (kind: PrincipalKind, credentials: LoginRequest) => {
    const data = await loginRequest(kind, credentials);
    // A real sign-in in this tab supersedes any leftover support-tab marker.
    clearImpersonationMarker();
    setImpersonationEnded(false);
    const decoded = decodeAccessTokenPayload(data.accessToken);
    const role = decoded?.role ?? null;
    setSession({ accessToken: data.accessToken, role, userId: decoded?.sub ?? null, kind });
    return { role, mustChangePassword: data.mustChangePassword };
  }, []);

  const markImpersonationEnded = useCallback(() => {
    setSession(null);
    setImpersonationEnded(true);
  }, []);

  const ensureAccessToken = useCallback(
    async (kind: PrincipalKind, options?: { force?: boolean }): Promise<string> => {
      // ADR-033: a support tab never falls back to `/auth/refresh` (that would
      // silently turn this tab back into the admin).
      if (kind === "tenant" && isImpersonationTab()) {
        if (!options?.force && session?.impersonation && session.kind === kind) {
          if (Date.parse(session.impersonation.expiresAt) <= Date.now()) {
            markImpersonationEnded();
            throw impersonationEndedError();
          }
          return session.accessToken;
        }
        if (!refreshInFlight.current[kind]) {
          refreshInFlight.current[kind] = (async () => {
            try {
              const data = await requestImpersonationToken();
              setSession(sessionFromImpersonation(data));
              setImpersonationEnded(false);
              return data.accessToken;
            } catch {
              markImpersonationEnded();
              throw impersonationEndedError();
            } finally {
              delete refreshInFlight.current[kind];
            }
          })();
        }
        return refreshInFlight.current[kind]!;
      }

      if (!options?.force && session && session.kind === kind && !session.impersonation) {
        return session.accessToken;
      }

      if (!refreshInFlight.current[kind]) {
        refreshInFlight.current[kind] = (async () => {
          try {
            const data = await refreshRequest(kind);
            const decoded = decodeAccessTokenPayload(data.accessToken);
            setSession({
              accessToken: data.accessToken,
              role: decoded?.role ?? null,
              userId: decoded?.sub ?? null,
              kind,
            });
            return data.accessToken;
          } finally {
            delete refreshInFlight.current[kind];
          }
        })();
      }

      return refreshInFlight.current[kind]!;
    },
    [session, markImpersonationEnded]
  );

  /**
   * The reusable "silent refresh" surface for protected requests (plan §11 item
   * 4): attach a usable access token (refreshing first if none is held); on a
   * 401 `UNAUTHENTICATED`/`SESSION_REVOKED` response, refresh exactly once more
   * and retry; otherwise propagate the error for the caller to handle (e.g.
   * redirect to login). Not a generic retry framework — bounded to one retry,
   * scoped to the two failure codes the contract says are refresh-recoverable.
   */
  const authorizedFetch = useCallback(
    async <T,>(kind: PrincipalKind, path: string, init?: RequestInit): Promise<T> => {
      let token = await ensureAccessToken(kind);

      try {
        return await apiFetch<T>(path, {
          ...init,
          headers: { ...init?.headers, Authorization: `Bearer ${token}` },
        });
      } catch (error) {
        const isRecoverable =
          isApiClientError(error) &&
          error.status === 401 &&
          (error.code === "UNAUTHENTICATED" || error.code === "SESSION_REVOKED");

        // AUTH-PWD-01: the backend lets an account with a temporary password do
        // nothing but change it - send the user there from any page. The error is
        // still thrown so the calling page doesn't render stale data meanwhile.
        if (
          isApiClientError(error) &&
          error.code === "PASSWORD_CHANGE_REQUIRED" &&
          typeof window !== "undefined" &&
          window.location.pathname !== "/change-password"
        ) {
          window.location.assign("/change-password");
        }

        // ADR-033: support sessions are read-only server-side; tell the user why.
        if (isApiClientError(error) && error.code === IMPERSONATION_READ_ONLY_CODE) {
          setReadOnlyNotice(
            "This is a read-only support session. Changes can't be made while viewing as a student."
          );
        }

        if (!isRecoverable) {
          throw error;
        }

        token = await ensureAccessToken(kind, { force: true });
        return apiFetch<T>(path, {
          ...init,
          headers: { ...init?.headers, Authorization: `Bearer ${token}` },
        });
      }
    },
    [ensureAccessToken]
  );

  // ADR-033: a single timer per support session, firing at its absolute expiry.
  const impersonationExpiresAt = session?.impersonation?.expiresAt ?? null;
  useEffect(() => {
    if (!impersonationExpiresAt) return;
    const remaining = Date.parse(impersonationExpiresAt) - Date.now();
    if (!Number.isFinite(remaining)) return;
    const timeout = setTimeout(markImpersonationEnded, Math.max(0, Math.min(remaining, 2_147_000_000)));
    return () => clearTimeout(timeout);
  }, [impersonationExpiresAt, markImpersonationEnded]);

  useEffect(() => {
    if (!readOnlyNotice) return;
    const timeout = setTimeout(() => setReadOnlyNotice(null), 8000);
    return () => clearTimeout(timeout);
  }, [readOnlyNotice]);

  // TP-01: cached server data belongs to one signed-in identity. When the
  // identity changes (sign-out, or a different user/tenant signing in in this
  // tab), drop the cache so no stale course list or profile is shown.
  const identityKey = session ? `${session.kind}:${session.userId ?? ""}` : null;
  const previousIdentity = useRef<string | null>(null);
  useEffect(() => {
    if (previousIdentity.current !== null && previousIdentity.current !== identityKey) {
      queryClient.clear();
    }
    previousIdentity.current = identityKey;
  }, [identityKey, queryClient]);

  const dismissReadOnlyNotice = useCallback(() => setReadOnlyNotice(null), []);

  const startImpersonation = useCallback(async (): Promise<ImpersonationInfo> => {
    setImpersonationMarker();
    setImpersonationEnded(false);
    // Nothing cached by any earlier session in this tab may leak into the support view.
    queryClient.clear();
    let data: ImpersonationTokenResponse;
    try {
      data = await requestImpersonationToken();
    } catch {
      markImpersonationEnded();
      throw impersonationEndedError();
    }
    const next = sessionFromImpersonation(data);
    setSession(next);
    return next.impersonation!;
  }, [markImpersonationEnded, queryClient]);

  const leaveEndedImpersonation = useCallback(() => {
    queryClient.clear();
    clearImpersonationMarker();
    setSession(null);
    window.location.assign("/tenant-admin/students");
  }, [queryClient]);

  const endImpersonation = useCallback(async () => {
    const token = session?.impersonation ? session.accessToken : null;
    try {
      await endImpersonationSession(env.NEXT_PUBLIC_API_BASE_URL, token);
    } catch {
      // This tab stops acting as the student regardless; the server also
      // ends the session at its absolute expiry.
    }
    leaveEndedImpersonation();
  }, [session, leaveEndedImpersonation]);

  const logout = useCallback(
    async (kind: PrincipalKind) => {
      if (kind === "tenant" && isImpersonationTab()) {
        // A support tab has no sign-in of its own: "log out" ends the support session.
        await endImpersonation();
        return;
      }
      try {
        await authorizedFetch<null>(kind, authPaths[kind].logout, { method: "POST" });
      } finally {
        // Always clear local state, even on failure: this tab must stop acting
        // as the user regardless of the network round-trip outcome (e.g. the
        // access token was already expired/revoked).
        setSession((current) => (current?.kind === kind ? null : current));
        // Best-effort cleanup of the exam-attempt "remember" mapping
        // (`lib/exam-attempt-storage.ts`) so a second student signing in on
        // this same shared device/browser never inherits the previous
        // student's remembered attempt id. Clearing unconditionally (not
        // scoped to just this session's userId) is deliberate: it's simplest,
        // and safe — the mapping is a client-only convenience with no
        // sensitive content beyond an attempt id already owner-checked
        // server-side on every read.
        clearAllRememberedExamAttempts();
      }
      // Deliberately outside the try/finally: a failure above is rethrown here
      // (after local state is already cleared) so the caller can surface it
      // instead of treating a failed server-side revoke as a silent success.
    },
    [authorizedFetch, endImpersonation]
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      login,
      logout,
      ensureAccessToken,
      authorizedFetch,
      impersonationEnded,
      readOnlyNotice,
      dismissReadOnlyNotice,
      startImpersonation,
      endImpersonation,
      leaveEndedImpersonation,
    }),
    [
      session,
      login,
      logout,
      ensureAccessToken,
      authorizedFetch,
      impersonationEnded,
      readOnlyNotice,
      dismissReadOnlyNotice,
      startImpersonation,
      endImpersonation,
      leaveEndedImpersonation,
    ]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
