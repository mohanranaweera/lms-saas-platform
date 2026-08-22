/**
 * Sequence math + the "safe move" 3-step dance shared by module and lesson
 * reordering (see `docs/plans/MVP-008 Course Management.md` §11/§12). Both
 * `course_module` (`UNIQUE (tenant_id, course_id, sequence)`) and
 * `course_lesson` (the equivalent scoped to `module_id`) carry a unique
 * constraint on sequence within their parent — a naive two-call swap (PATCH
 * A -> B's sequence, then PATCH B -> A's old sequence) 409s on the very
 * first call, since the target value is still held by the other row at that
 * instant. `reorderAdjacent` avoids that by parking the item being moved on
 * a temporary, definitely-unused sequence first.
 *
 * Pure logic only — no React Query/fetch dependency — so both the module
 * list (`module-lesson-editor.tsx`) and each module's lesson list
 * (`course-module-card.tsx`) can reuse the exact same steps against their
 * own mutation hook.
 */

export interface ReorderableItem {
  id: string;
  title: string;
  sequence: number;
}

export type UpdateReorderableFn = (
  id: string,
  body: { title: string; sequence: number }
) => Promise<unknown>;

/** Sorts a copy of `items` by `sequence` ascending — never mutates the input. */
export function sortBySequence<T extends { sequence: number }>(items: T[]): T[] {
  return [...items].sort((a, b) => a.sequence - b.sequence);
}

/**
 * `(current max sequence in the list, or 0) + 1` — the sequence a
 * newly-created module/lesson receives. Sequence is never a user-entered
 * field per the module plan; this is the only place it's computed.
 */
export function nextSequence(items: { sequence: number }[]): number {
  const max = items.reduce((highest, item) => Math.max(highest, item.sequence), 0);
  return max + 1;
}

/**
 * Moves `movingId` one position up or down within `items` (all assumed to
 * belong to the same parent scope — the same course for modules, the same
 * module for lessons), via three sequential, awaited updates:
 *
 * 1. Park the moving item on an unused temporary sequence
 *    (`max(existing sequences) + 1000`).
 * 2. Move the displaced neighbor into the mover's original sequence.
 * 3. Move the mover into the neighbor's original sequence.
 *
 * Each step is `await`ed before the next fires — never `Promise.all` —
 * because correctness depends on step ordering, not just eventual
 * completion: step 2 would 409 if it raced ahead of step 1.
 *
 * No-ops (resolves immediately, no calls made) if `movingId` isn't found or
 * is already at the boundary in the requested direction — callers should
 * also disable the corresponding Move Up/Down control in that case, so this
 * is a defensive guard rather than the primary UX signal.
 *
 * Callers are responsible for refetching the list afterward regardless of
 * outcome: if step 2 or 3 throws after step 1 already succeeded, the list is
 * left in a transiently "off by one" state server-side that only a refetch
 * (not local state) can resolve.
 */
export async function reorderAdjacent(
  items: ReorderableItem[],
  movingId: string,
  direction: "up" | "down",
  updateItem: UpdateReorderableFn
): Promise<void> {
  const sorted = sortBySequence(items);
  const index = sorted.findIndex((item) => item.id === movingId);
  if (index === -1) return;

  const targetIndex = direction === "up" ? index - 1 : index + 1;
  if (targetIndex < 0 || targetIndex >= sorted.length) return;

  const mover = sorted[index];
  const displaced = sorted[targetIndex];
  const maxSequence = sorted.reduce((highest, item) => Math.max(highest, item.sequence), 0);
  const temporarySequence = maxSequence + 1000;

  await updateItem(mover.id, { title: mover.title, sequence: temporarySequence });
  await updateItem(displaced.id, { title: displaced.title, sequence: mover.sequence });
  await updateItem(mover.id, { title: mover.title, sequence: displaced.sequence });
}
