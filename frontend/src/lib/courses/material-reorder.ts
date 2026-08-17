import { sortBySequence } from "./reorder";
import type { MaterialVisibility } from "@/lib/api/materials";

/**
 * Materials variant of `reorder.ts`'s "park on a temporary out-of-range
 * sequence, then swap" 3-step dance. A sibling file rather than a change to
 * `reorder.ts` itself: `UpdateReorderableFn` there only carries
 * `{ title, sequence }`, but the materials PATCH endpoint
 * (`MaterialUpdateRequest`) is a full-resource replace that also requires
 * `visibility` on every call — reusing `reorderAdjacent` as-is would silently
 * drop a material's visibility back to whatever the backend defaults to (it
 * doesn't have one; the field is `@NotNull`, so a call missing it would just
 * 400). Keeping this separate avoids touching the existing module/lesson
 * callers of `reorder.ts` at all.
 *
 * `sortBySequence` is re-exported from `./reorder` (not redefined) so both
 * files always sort identically.
 */

export { sortBySequence };

export interface ReorderableMaterial {
  id: string;
  title: string;
  sequence: number;
  visibility: MaterialVisibility;
}

export type UpdateReorderableMaterialFn = (
  id: string,
  body: { title: string; sequence: number; visibility: MaterialVisibility }
) => Promise<unknown>;

/**
 * Moves `movingId` one position up or down within `items` (all assumed to
 * belong to the same lesson), resending each affected row's current `title`
 * and `visibility` alongside the sequence change on every step — see
 * `reorder.ts`'s `reorderAdjacent` doc comment for the full 3-step rationale
 * (identical here, just with the extra field carried through).
 */
export async function reorderAdjacentMaterial(
  items: ReorderableMaterial[],
  movingId: string,
  direction: "up" | "down",
  updateItem: UpdateReorderableMaterialFn
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

  await updateItem(mover.id, {
    title: mover.title,
    sequence: temporarySequence,
    visibility: mover.visibility,
  });
  await updateItem(displaced.id, {
    title: displaced.title,
    sequence: mover.sequence,
    visibility: displaced.visibility,
  });
  await updateItem(mover.id, {
    title: mover.title,
    sequence: displaced.sequence,
    visibility: mover.visibility,
  });
}
