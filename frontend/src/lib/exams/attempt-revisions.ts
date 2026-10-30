/**
 * Monotonic autosave revision counter for one exam attempt (Klass Exam Module
 * Slice C, PA4). Each answer save carries a `revision`; the server ignores a
 * save whose revision is not greater than the stored one (`stale: true`), so
 * an older save that arrives late can never overwrite a newer answer.
 *
 * One counter per attempt (so every item's revisions are increasing too),
 * kept in `sessionStorage` so it survives a reload of the same tab. On load
 * the counter is raised to at least the highest revision the server reports,
 * so a cleared storage or another tab can never send a lower number. This is
 * ordering metadata only; the server decides what is stored.
 */

const PREFIX = "lms.exam-attempt-revision.";

function read(attemptId: string): number {
  if (typeof window === "undefined") return 0;
  try {
    const raw = window.sessionStorage.getItem(PREFIX + attemptId);
    const value = raw === null ? 0 : Number(raw);
    return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
  } catch {
    return 0;
  }
}

function write(attemptId: string, value: number): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(PREFIX + attemptId, String(value));
  } catch {
    // Storage unavailable (private mode / quota): the in-memory floor below still keeps order within this page.
  }
}

const memory = new Map<string, number>();

function current(attemptId: string): number {
  return Math.max(read(attemptId), memory.get(attemptId) ?? 0);
}

/** Raises the counter to at least `serverRevision` (from `AttemptResponse.items[].revision`). */
export function seedAttemptRevision(attemptId: string, serverRevision: number): void {
  const next = Math.max(current(attemptId), Number.isFinite(serverRevision) ? serverRevision : 0);
  memory.set(attemptId, next);
  write(attemptId, next);
}

/** The next revision to send: strictly greater than anything used or seen before for this attempt. */
export function nextAttemptRevision(attemptId: string): number {
  const next = current(attemptId) + 1;
  memory.set(attemptId, next);
  write(attemptId, next);
  return next;
}

export function clearAttemptRevision(attemptId: string): void {
  memory.delete(attemptId);
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(PREFIX + attemptId);
  } catch {
    // ignore
  }
}
