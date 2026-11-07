/**
 * ADR-033 §7: a non-credential, per-tab `sessionStorage` marker that says
 * "this tab is a support (impersonation) tab". It carries no token or id - it
 * only selects which endpoint re-acquires the in-memory access token after a
 * reload (`/auth/impersonation/token` instead of `/auth/refresh`). Other tabs
 * never see it, so they stay admin tabs.
 */
export const IMPERSONATION_MARKER_KEY = "lms.impersonation";

export function isImpersonationTab(): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.sessionStorage.getItem(IMPERSONATION_MARKER_KEY) === "1";
  } catch {
    return false;
  }
}

export function setImpersonationMarker(): void {
  try {
    window.sessionStorage.setItem(IMPERSONATION_MARKER_KEY, "1");
  } catch {
    /* storage unavailable - the session still works until reload */
  }
}

export function clearImpersonationMarker(): void {
  try {
    window.sessionStorage.removeItem(IMPERSONATION_MARKER_KEY);
  } catch {
    /* ignore */
  }
}
