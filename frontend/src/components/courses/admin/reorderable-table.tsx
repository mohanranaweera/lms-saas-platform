"use client";

import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { ChevronDown, ChevronUp, GripVertical } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/** Which layout a cell is rendered in; both are in the DOM, so ids must differ per variant. */
export type ReorderableLayout = "table" | "card";

export interface ReorderableColumn<T> {
  key: string;
  header: ReactNode;
  /** Plain-text header for mobile cards. Defaults to `header` when it is a string. */
  cardLabel?: string;
  cell: (row: T, index: number, layout: ReorderableLayout) => ReactNode;
  className?: string;
  headerClassName?: string;
  hideOnCard?: boolean;
}

/** Moves `items[from]` to position `to`, returning a new array. */
export function moveItem<T>(items: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= items.length || to >= items.length) return items;
  const next = items.slice();
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return next;
}

/**
 * Admin table with a drag handle and keyboard-accessible move up/down
 * buttons (drag-and-drop alone is not keyboard accessible). Table on `md`
 * and wider (horizontal scroll inside its own container only), stacked cards
 * below `md`. Ordering is persisted by the caller through `onMove`.
 *
 * Move buttons use `aria-disabled` (not `disabled`) so a focused button keeps
 * focus while a save is pending or when it reaches the top/bottom; after a
 * move, focus is put back on the same button of the moved row and the new
 * position is announced through a persistent polite live region.
 */
export function ReorderableTable<T>({
  rows,
  rowKey,
  rowLabel,
  columns,
  caption,
  reorderEnabled,
  reorderPending,
  onMove,
  indexOffset = 0,
  actions,
  rowTestId,
  showMoveButtons = true,
  showPosition = true,
}: {
  rows: T[];
  rowKey: (row: T) => string;
  /** Used in move-button labels, e.g. "Move Biology up". */
  rowLabel: (row: T) => string;
  columns: ReorderableColumn<T>[];
  caption: string;
  reorderEnabled: boolean;
  reorderPending?: boolean;
  onMove: (from: number, to: number) => void;
  indexOffset?: number;
  actions: (row: T) => ReactNode;
  rowTestId?: string;
  showMoveButtons?: boolean;
  showPosition?: boolean;
}) {
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);
  const [announcement, setAnnouncement] = useState({ text: "", count: 0 });
  const containerRef = useRef<HTMLDivElement>(null);
  const pendingFocus = useRef<{ key: string; direction: "up" | "down"; layout: ReorderableLayout } | null>(null);
  const canMove = reorderEnabled && !reorderPending;

  const move = (from: number, to: number) => {
    const row = rows[from];
    if (!canMove || !row || to < 0 || to >= rows.length || from === to) return false;
    onMove(from, to);
    setAnnouncement((current) => ({
      text: `Moved ${rowLabel(row)} to position ${indexOffset + to + 1} of ${indexOffset + rows.length}`,
      count: current.count + 1,
    }));
    return true;
  };

  // Moving a focused node in the DOM drops its focus; restore it on the same
  // button of the moved row once the reordered rows have rendered.
  useEffect(() => {
    const target = pendingFocus.current;
    if (!target || !containerRef.current) return;
    const button = containerRef.current.querySelector<HTMLElement>(
      `[data-move-key="${CSS.escape(target.key)}"][data-move-direction="${target.direction}"][data-move-layout="${target.layout}"]`
    );
    if (button && document.activeElement !== button) button.focus();
    pendingFocus.current = null;
  }, [rows]);

  const moveButton = (row: T, index: number, layout: ReorderableLayout, direction: "up" | "down") => {
    const to = direction === "up" ? index - 1 : index + 1;
    const unavailable = !canMove || to < 0 || to >= rows.length;
    const Icon = direction === "up" ? ChevronUp : ChevronDown;
    return (
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
        aria-label={`Move ${rowLabel(row)} ${direction}`}
        title={direction === "up" ? "Move up" : "Move down"}
        aria-disabled={unavailable || undefined}
        className="aria-disabled:cursor-not-allowed aria-disabled:opacity-50"
        onClick={() => {
          if (unavailable) return;
          pendingFocus.current = { key: rowKey(row), direction, layout };
          if (!move(index, to)) pendingFocus.current = null;
        }}
        data-move-key={rowKey(row)}
        data-move-direction={direction}
        data-move-layout={layout}
        data-testid={direction === "up" ? "row-move-up" : "row-move-down"}
      >
        <Icon aria-hidden="true" />
      </Button>
    );
  };

  const moveButtons = (row: T, index: number, layout: ReorderableLayout) => (
    <div className="flex items-center gap-0.5">
      {moveButton(row, index, layout, "up")}
      {moveButton(row, index, layout, "down")}
    </div>
  );

  const resetDrag = () => {
    setDragIndex(null);
    setOverIndex(null);
  };

  return (
    <div ref={containerRef}>
      {/* Always mounted so screen readers pick up each change; the alternating no-break space re-announces a repeated message. */}
      <LiveRegion message={announcement.text ? `${announcement.text}${announcement.count % 2 ? "\u00a0" : ""}` : ""} />
      <div className="hidden overflow-x-auto rounded-lg border border-border md:block">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead className="border-b border-border bg-muted/40">
            <tr>
              <th scope="col" className="w-20 px-2 py-2">
                <span className="sr-only">Reorder</span>
              </th>
              {showPosition ? <th scope="col" className="w-10 px-2 py-2 font-medium text-foreground">
                #
              </th> : null}
              {columns.map((column) => (
                <th
                  key={column.key}
                  scope="col"
                  className={cn("px-3 py-2 font-medium text-foreground", column.headerClassName)}
                >
                  {column.header}
                </th>
              ))}
              <th scope="col" className="px-3 py-2 text-right font-medium text-foreground">
                Actions
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr
                key={rowKey(row)}
                data-testid={rowTestId}
                data-row-id={rowKey(row)}
                draggable={canMove}
                onDragStart={(event) => {
                  if (!canMove) return;
                  setDragIndex(index);
                  event.dataTransfer.effectAllowed = "move";
                  event.dataTransfer.setData("text/plain", rowKey(row));
                }}
                onDragOver={(event) => {
                  if (dragIndex === null) return;
                  event.preventDefault();
                  event.dataTransfer.dropEffect = "move";
                  if (overIndex !== index) setOverIndex(index);
                }}
                onDrop={(event) => {
                  event.preventDefault();
                  if (dragIndex !== null && dragIndex !== index) move(dragIndex, index);
                  resetDrag();
                }}
                onDragEnd={resetDrag}
                className={cn(
                  "border-b border-border last:border-0",
                  dragIndex === index && "opacity-50",
                  overIndex === index && dragIndex !== index && "bg-muted/60"
                )}
              >
                <td className="px-2 py-2">
                  <div className="flex items-center gap-1">
                    <GripVertical
                      aria-hidden="true"
                      className={cn(
                        "size-4 text-muted-foreground",
                        canMove ? "cursor-grab" : "opacity-40"
                      )}
                    />
                    {showMoveButtons ? moveButtons(row, index, "table") : null}
                  </div>
                </td>
                {showPosition ? <td className="px-2 py-2 text-muted-foreground">{indexOffset + index + 1}</td> : null}
                {columns.map((column) => (
                  <td key={column.key} className={cn("px-3 py-2 text-muted-foreground", column.className)}>
                    {column.cell(row, index, "table")}
                  </td>
                ))}
                <td className="px-3 py-2">
                  <div className="flex items-center justify-end gap-1">{actions(row)}</div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul className="flex flex-col gap-3 md:hidden" aria-label={caption}>
        {rows.map((row, index) => (
          <li
            key={rowKey(row)}
            data-testid={rowTestId ? `${rowTestId}-card` : undefined}
            data-row-id={rowKey(row)}
            className="flex flex-col gap-2 rounded-lg border border-border p-3"
          >
            <div className="flex items-start justify-between gap-2">
              <span className="text-sm font-medium text-foreground">
                {showPosition ? `${indexOffset + index + 1}. ` : ""}{rowLabel(row)}
              </span>
              {showMoveButtons ? moveButtons(row, index, "card") : null}
            </div>
            <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs text-muted-foreground">
              {columns
                .filter((column) => !column.hideOnCard)
                .map((column) => (
                  <Fragment key={column.key}>
                    <dt className="font-medium text-foreground">
                      {column.cardLabel ?? (typeof column.header === "string" ? column.header : column.key)}
                    </dt>
                    <dd>{column.cell(row, index, "card")}</dd>
                  </Fragment>
                ))}
            </dl>
            <div className="flex flex-wrap items-center gap-1 border-t border-border pt-2">{actions(row)}</div>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Loading skeleton matching the table/card layout. */
export function TableSkeleton({ rows = 5, label }: { rows?: number; label: string }) {
  return (
    <div role="status" aria-busy="true" aria-live="polite" className="flex flex-col gap-2">
      <span className="sr-only">{label}</span>
      <Skeleton className="h-9 w-full" />
      {Array.from({ length: rows }, (_, index) => (
        <Skeleton key={index} className="h-11 w-full" />
      ))}
    </div>
  );
}
