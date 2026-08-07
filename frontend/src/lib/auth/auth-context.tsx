"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  authPaths,
  login as loginRequest,
  refresh as refreshRequest,
  type LoginRequest,
  type PrincipalKind,
} from "@/lib/api/auth";
import { apiFetch } from "@/lib/api/client";
import { isApiClientError } from "@/lib/api/error";
import { decodeAccessTokenPayload } from "./jwt";

interface Session {
  accessToken: string;
  role: string | null;
  kind: PrincipalKind;
}

export interface LoginResult {
  role: string | null;
  mustChangePassword: boolean;
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
   */
  ensureAccessToken: (kind: PrincipalKind, options?: { force?: boolean }) => Promise<string>;
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
 * `ensureAccessToken` re-establishes it from the refresh cookie on demand.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);

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
    const decoded = decodeAccessTokenPayload(data.accessToken);
    const role = decoded?.role ?? null;
    setSession({ accessToken: data.accessToken, role, kind });
    return { role, mustChangePassword: data.mustChangePassword };
  }, []);

  const ensureAccessToken = useCallback(
    async (kind: PrincipalKind, options?: { force?: boolean }): Promise<string> => {
      if (!options?.force && session && session.kind === kind) {
        return session.accessToken;
      }

      if (!refreshInFlight.current[kind]) {
        refreshInFlight.current[kind] = (async () => {
          try {
            const data = await refreshRequest(kind);
            const decoded = decodeAccessTokenPayload(data.accessToken);
            setSession({ accessToken: data.accessToken, role: decoded?.role ?? null, kind });
            return data.accessToken;
          } finally {
            delete refreshInFlight.current[kind];
          }
        })();
      }

      return refreshInFlight.current[kind]!;
    },
    [session]
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

  const logout = useCallback(
    async (kind: PrincipalKind) => {
      try {
        await authorizedFetch<null>(kind, authPaths[kind].logout, { method: "POST" });
      } finally {
        // Always clear local state, even on failure: this tab must stop acting
        // as the user regardless of the network round-trip outcome (e.g. the
        // access token was already expired/revoked).
        setSession((current) => (current?.kind === kind ? null : current));
      }
      // Deliberately outside the try/finally: a failure above is rethrown here
      // (after local state is already cleared) so the caller can surface it
      // instead of treating a failed server-side revoke as a silent success.
    },
    [authorizedFetch]
  );

  const value = useMemo<AuthContextValue>(
    () => ({ session, login, logout, ensureAccessToken }),
    [session, login, logout, ensureAccessToken]
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
