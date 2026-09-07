/**
 * Frontend-only, best-effort `(userId, examId) -> last-started attemptId`
 * mapping, persisted in `localStorage`, used exclusively to give a student a
 * way back to the Results & Review screen
 * (`app/(student)/student/exams/[examId]/results/page.tsx`) after the tab/
 * browser that submitted the attempt is gone.
 *
 * **Why this exists (see the UI/UX review's Fix 2, and both call sites'
 * doc comments for the full writeup):** the backend has no endpoint to list
 * a student's own past attempts, and `GET /my/upcoming` excludes `CLOSED`
 * exams by design, so there is no backend-supported way to rediscover an
 * `attemptId` once it has fallen out of a page's own state/URL. This helper
 * is a client-side mitigation only, not a fix for that gap:
 *
 *  (a) It only works on the same browser/device that took the exam — it is
 *      keyed to this browser's `localStorage`, not the student's account.
 *  (b) It does not survive cleared site data/storage, private browsing that
 *      blocks persistence, or a different device/browser.
 *  (c) The real fix is a backend "list my attempts" endpoint
 *      (`GET /api/v1/exams/my/attempts` or similar); that should be
 *      requested as a follow-up, not simulated further on the frontend.
 *
 * **User scoping (shared-device fix):** the storage key is scoped by BOTH
 * `examId` AND the authenticated user's stable id (the JWT `sub` claim,
 * exposed as `session.userId` by `lib/auth/auth-context.tsx`) — a bare
 * `examId` key would let a second student who signs in on the same shared
 * device/browser inherit the first student's remembered attempt id for the
 * same exam. Every call site must pass the current session's `userId`; a
 * `null` userId (no established session) is treated as "nothing to
 * remember/recall" rather than falling back to an unscoped key.
 * `lib/auth/auth-context.tsx`'s `logout()` also best-effort clears every key
 * under this prefix via `clearAllRememberedExamAttempts` below, so a
 * follow-on sign-in on the same device never finds a stale entry either.
 *
 * No other `localStorage` usage exists elsewhere in this codebase to mirror
 * (auth tokens are deliberately kept in-memory only, see
 * `lib/auth/auth-context.tsx`), so every read/write here is wrapped
 * defensively: a storage failure (private browsing, blocked storage policy,
 * quota exceeded, `localStorage` unavailable during SSR) is swallowed and
 * never crashes the page or the exam-taking/results flow — losing this
 * mapping only means the Fix 2 fallback below won't resolve, which is the
 * same as the gap already existing.
 */

const STORAGE_KEY_PREFIX = "lms:exam-attempt:";

function buildStorageKey(userId: string, examId: string): string {
  return `${STORAGE_KEY_PREFIX}${userId}:${examId}`;
}

/**
 * Best-effort write — call after `useStartAttempt`/`useSubmitAttempt`
 * succeeds. Never throws. No-ops if `userId` is `null` (no established
 * session to scope the key to).
 */
export function rememberExamAttempt(userId: string | null, examId: string, attemptId: string): void {
  try {
    if (typeof window === "undefined" || !userId) return;
    window.localStorage.setItem(buildStorageKey(userId, examId), attemptId);
  } catch {
    // Best-effort only — see file doc comment. Never blocks the exam flow.
  }
}

/**
 * Best-effort read — returns `null` if nothing was remembered, storage is
 * unavailable, or `userId` is `null`. Never throws.
 */
export function getRememberedExamAttempt(userId: string | null, examId: string): string | null {
  try {
    if (typeof window === "undefined" || !userId) return null;
    return window.localStorage.getItem(buildStorageKey(userId, examId));
  } catch {
    return null;
  }
}

/**
 * Best-effort cleanup of every remembered `examId -> attemptId` mapping under
 * this module's storage prefix, regardless of which user they belong to.
 * Called from `lib/auth/auth-context.tsx`'s `logout()` so a second student
 * signing in on the same shared browser/device never has a stale entry left
 * over to inherit — clearing unconditionally (not just the signed-out user's
 * own keys) is simplest and equally safe, since every value here is just an
 * attempt id that is still independently owner-checked server-side on every
 * read. Never throws.
 */
export function clearAllRememberedExamAttempts(): void {
  try {
    if (typeof window === "undefined") return;
    const keysToRemove: string[] = [];
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i);
      if (key && key.startsWith(STORAGE_KEY_PREFIX)) {
        keysToRemove.push(key);
      }
    }
    keysToRemove.forEach((key) => window.localStorage.removeItem(key));
  } catch {
    // Best-effort only — see file doc comment. Never blocks logout.
  }
}
