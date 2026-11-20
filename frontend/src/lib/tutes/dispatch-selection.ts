import type { DispatchFilters, DispatchRowView, LabelsRequest } from "@/lib/api/tutes";

/**
 * Tute Dispatch selection model (spec §7 "Selection"). Pure, so it is unit
 * tested without a browser.
 *
 * - `explicit`: rows the user ticked, kept by `groupKey` across pages (the
 *   row snapshot is kept so its fulfillment ids and address flag are known
 *   even after paging away).
 * - `allFiltered`: "every row matching the current filters" - resolved by the
 *   server at print time, guarded by `expectedLabelCount`.
 *
 * Any filter/search/"2+" change clears the selection (the caller resets to
 * `EMPTY_SELECTION`). The server re-validates everything; nothing here
 * decides what is printable or dispatchable.
 */
export type DispatchSelection =
  | { mode: "explicit"; rows: Readonly<Record<string, DispatchRowView>> }
  | { mode: "allFiltered" };

export const EMPTY_SELECTION: DispatchSelection = { mode: "explicit", rows: {} };

export function isRowSelected(selection: DispatchSelection, row: DispatchRowView): boolean {
  return selection.mode === "allFiltered" || Boolean(selection.rows[row.groupKey]);
}

export function explicitCount(selection: DispatchSelection): number {
  return selection.mode === "explicit" ? Object.keys(selection.rows).length : 0;
}

export function hasExplicitSelection(selection: DispatchSelection): boolean {
  return explicitCount(selection) > 0;
}

/** Header checkbox state for the rows on the current page. */
export function pageSelectionState(selection: DispatchSelection, pageRows: readonly DispatchRowView[]): "all" | "some" | "none" {
  if (pageRows.length === 0) return "none";
  const selected = pageRows.filter((row) => isRowSelected(selection, row)).length;
  if (selected === 0) return "none";
  return selected === pageRows.length ? "all" : "some";
}

/**
 * Toggles one row. In all-filtered mode, unticking a row narrows the
 * selection to the rest of the current page (the only rows still visible).
 */
export function toggleRow(
  selection: DispatchSelection,
  row: DispatchRowView,
  pageRows: readonly DispatchRowView[]
): DispatchSelection {
  if (selection.mode === "allFiltered") {
    const rows: Record<string, DispatchRowView> = {};
    for (const pageRow of pageRows) if (pageRow.groupKey !== row.groupKey) rows[pageRow.groupKey] = pageRow;
    return { mode: "explicit", rows };
  }
  const rows = { ...selection.rows };
  if (rows[row.groupKey]) delete rows[row.groupKey];
  else rows[row.groupKey] = row;
  return { mode: "explicit", rows };
}

/** Header checkbox: select the whole page, or (when it is already all selected) clear it. */
export function togglePage(selection: DispatchSelection, pageRows: readonly DispatchRowView[]): DispatchSelection {
  if (selection.mode === "allFiltered") return EMPTY_SELECTION;
  const rows = { ...selection.rows };
  if (pageSelectionState(selection, pageRows) === "all") {
    for (const row of pageRows) delete rows[row.groupKey];
  } else {
    for (const row of pageRows) rows[row.groupKey] = row;
  }
  return { mode: "explicit", rows };
}

export function selectAllFiltered(): DispatchSelection {
  return { mode: "allFiltered" };
}

/** Every fulfillment id of the explicitly selected rows (each row may carry several tutes). */
export function selectedFulfillmentIds(selection: DispatchSelection): string[] {
  if (selection.mode !== "explicit") return [];
  return Object.values(selection.rows).flatMap((row) => row.items.map((item) => item.fulfillmentId));
}

function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? "" : "s"}`;
}

/** Labels that will print: explicit rows, else every row matching the filters. */
export function labelTargetCount(selection: DispatchSelection, totalElements: number): number {
  return hasExplicitSelection(selection) ? explicitCount(selection) : totalElements;
}

export function printButtonLabel(selection: DispatchSelection, totalElements: number): string {
  const count = explicitCount(selection);
  if (count > 0) return `Print ${plural(count, "selected label")} (PDF)`;
  return `Print all ${plural(totalElements, "label")} (PDF)`;
}

/** Target rows without an address (explicit) or the server's count for the whole filtered set. */
export function missingAddressTargetCount(
  selection: DispatchSelection,
  queueMissingAddressCount: number
): number {
  if (hasExplicitSelection(selection) && selection.mode === "explicit") {
    return Object.values(selection.rows).filter((row) => row.addressMissing).length;
  }
  return queueMissingAddressCount;
}

/**
 * `POST /tute-dispatch/labels` body. An explicit selection sends the exact
 * fulfillment ids; otherwise (all-filtered, or nothing ticked) the current
 * filters plus the count the user saw, so a changed list is refused (409
 * `TUTE_SELECTION_STALE`) instead of printing something unexpected.
 */
export function buildLabelsRequest(
  selection: DispatchSelection,
  filters: DispatchFilters,
  totalElements: number
): LabelsRequest {
  if (hasExplicitSelection(selection)) return { fulfillmentIds: selectedFulfillmentIds(selection) };
  const search = filters.search.trim();
  return {
    allFiltered: true,
    filters: {
      search: search ? search : null,
      courseId: filters.courseId,
      tuteId: filters.tuteId,
      twoPlus: filters.twoPlus,
    },
    expectedLabelCount: totalElements,
  };
}

/** Confirm dispatch is only offered for an explicit selection (never "all matching"). */
export function confirmDispatchIds(selection: DispatchSelection): string[] | null {
  return hasExplicitSelection(selection) ? selectedFulfillmentIds(selection) : null;
}

export function confirmItemCount(selection: DispatchSelection): number {
  return selectedFulfillmentIds(selection).length;
}
