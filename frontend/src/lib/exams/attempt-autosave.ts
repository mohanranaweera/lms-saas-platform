import { isApiClientError } from "@/lib/api/error";
import { isAttemptClosedError, isTransientSaveError, type SaveItemResponse } from "@/lib/api/student-exams";
import { nextAttemptRevision, seedAttemptRevision } from "./attempt-revisions";

/**
 * Autosave queue for one exam attempt (Klass Exam Module Slice C).
 *
 * - Every selection is sent at once with a new, strictly increasing
 *   `revision` (see `attempt-revisions.ts`), so the server keeps the newest
 *   answer even when requests arrive out of order (`stale: true` responses
 *   are expected and silently treated as done).
 * - A transient failure (offline, timeout, 5xx) is retried with exponential
 *   backoff using the *same* revision, until a newer selection supersedes it,
 *   the caller says retrying is no longer useful (deadline reached), or the
 *   queue stops. Retrying never extends the deadline — the server rejects
 *   saves after it (409 `DEADLINE_PASSED`).
 * - 409 `FINALIZED` / `DEADLINE_PASSED` stops the queue and reports the
 *   attempt as closed.
 */

export type ItemSaveStatus = "saving" | "retrying" | "saved" | "error";

export interface ItemSaveState {
  status: ItemSaveStatus;
  message?: string;
}

export type AttemptItemSaver = (itemId: string, optionId: string | null, revision: number) => Promise<SaveItemResponse>;

export interface AutosaveCallbacks {
  saver: AttemptItemSaver;
  onState: (itemId: string, state: ItemSaveState) => void;
  onClosed: () => void;
  onServerNow: (serverNow: string) => void;
  /** False once the (server-anchored) deadline has passed: pending retries are dropped. */
  canRetry: () => boolean;
}

export const AUTOSAVE_RETRY_BASE_MS = 1_000;
export const AUTOSAVE_RETRY_MAX_MS = 15_000;

export function retryDelayMs(failures: number): number {
  return Math.min(AUTOSAVE_RETRY_BASE_MS * 2 ** Math.max(0, failures - 1), AUTOSAVE_RETRY_MAX_MS);
}

export class AttemptAutosaveQueue {
  private readonly latest = new Map<string, number>();
  private readonly retries = new Map<string, { handle: number; run: () => void }>();
  private readonly inflight = new Set<Promise<void>>();
  private stopped = false;
  private callbacks: AutosaveCallbacks;

  constructor(
    private readonly attemptId: string,
    serverRevisions: number[],
    callbacks: AutosaveCallbacks
  ) {
    this.callbacks = callbacks;
    seedAttemptRevision(attemptId, Math.max(0, ...serverRevisions));
  }

  update(callbacks: Partial<AutosaveCallbacks>): void {
    this.callbacks = { ...this.callbacks, ...callbacks };
  }

  /** Saves the latest choice for an item (null clears it). */
  save(itemId: string, optionId: string | null): void {
    if (this.stopped) return;
    this.clearRetry(itemId);
    const revision = nextAttemptRevision(this.attemptId);
    this.latest.set(itemId, revision);
    this.send(itemId, optionId, revision, 0);
  }

  /** Re-sends every waiting retry immediately (e.g. the browser came back online). */
  retryNow(): void {
    for (const [itemId, retry] of [...this.retries.entries()]) {
      window.clearTimeout(retry.handle);
      this.retries.delete(itemId);
      retry.run();
    }
  }

  /** Waits (bounded) for requests already on the wire. */
  async flush(timeoutMs: number): Promise<void> {
    if (this.inflight.size === 0) return;
    await Promise.race([
      Promise.allSettled([...this.inflight]),
      new Promise<void>((resolve) => window.setTimeout(resolve, timeoutMs)),
    ]);
  }

  /** (Re)activates the queue — effects mount/unmount more than once in development (StrictMode). */
  resume(): void {
    this.stopped = false;
  }

  stop(): void {
    this.stopped = true;
    for (const retry of this.retries.values()) window.clearTimeout(retry.handle);
    this.retries.clear();
  }

  private clearRetry(itemId: string): void {
    const retry = this.retries.get(itemId);
    if (retry) {
      window.clearTimeout(retry.handle);
      this.retries.delete(itemId);
    }
  }

  private send(itemId: string, optionId: string | null, revision: number, failures: number): void {
    if (this.stopped) return;
    if (failures === 0) this.callbacks.onState(itemId, { status: "saving" });
    const request = this.callbacks
      .saver(itemId, optionId, revision)
      .then((response) => {
        if (response?.serverNow) this.callbacks.onServerNow(response.serverNow);
        // A stale reply carries the stored (newer) revision: move the counter past it so later choices win.
        if (response?.stale && Number.isFinite(response.revision)) seedAttemptRevision(this.attemptId, response.revision);
        if (this.stopped || this.latest.get(itemId) !== revision) return;
        // `stale: true` means the server already holds this or a newer revision — nothing left to do.
        this.callbacks.onState(itemId, { status: "saved" });
      })
      .catch((error: unknown) => {
        if (this.stopped) return;
        if (isAttemptClosedError(error)) {
          this.stop();
          this.callbacks.onClosed();
          return;
        }
        if (this.latest.get(itemId) !== revision) return;
        if (isTransientSaveError(error)) {
          if (!this.callbacks.canRetry()) {
            this.callbacks.onState(itemId, { status: "error", message: "Time is up — this answer could not be saved." });
            return;
          }
          const next = failures + 1;
          const run = () => this.send(itemId, optionId, revision, next);
          const handle = window.setTimeout(() => {
            this.retries.delete(itemId);
            run();
          }, retryDelayMs(next));
          this.retries.set(itemId, { handle, run });
          this.callbacks.onState(itemId, { status: "retrying" });
          return;
        }
        this.callbacks.onState(itemId, {
          status: "error",
          message: isApiClientError(error) ? error.message : "This answer could not be saved.",
        });
      })
      .finally(() => {
        this.inflight.delete(request);
      });
    this.inflight.add(request);
  }
}
