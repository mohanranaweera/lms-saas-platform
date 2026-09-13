"use client";

import { Fragment, useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Shared responsive data-table primitive: table on `md` and above, card list
 * below `md` — the pattern first hand-rolled in
 * `app/(platform-admin)/platform-admin/tenants/page.tsx` (the scaffold that
 * pattern was deferred from), extracted here so every admin list screen
 * shares one responsive/accessible implementation instead of re-hand-rolling
 * it per module (`.claude/rules/frontend.md` — "Data tables ... should share
 * a common responsive table component").
 *
 * Deliberately minimal: column config + row data in, table-on-desktop/
 * cards-on-mobile out. No built-in sorting, pagination, or column-visibility
 * toggling — none of the modules using this yet need it, and this project's
 * rule against unnecessary abstraction applies.
 */

export interface DataTableColumn<T> {
  /** Unique key for this column; also used as the mobile card's <dt> key. */
  key: string;
  header: string;
  /** Renders this column's value for a given row — used in both the desktop `<td>` and the mobile card's `<dd>`. */
  cell: (row: T) => ReactNode;
  /**
   * Omit this column from the mobile card body — typically because its value
   * is already surfaced via `cardHeading`/`cardHeadingAdornment`, or because
   * it's an actions column better represented by `cardFooter`.
   */
  hideOnCard?: boolean;
  className?: string;
}

export interface DataTableProps<T> {
  columns: DataTableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Accessible table caption (visually hidden) and the mobile list's implicit label. */
  caption: string;
  /** Rendered as each mobile card's heading — typically the row's primary identifying value (e.g. name). */
  cardHeading: (row: T) => ReactNode;
  /** Rendered inline next to the card heading, e.g. a status badge. */
  cardHeadingAdornment?: (row: T) => ReactNode;
  /** Rendered at the bottom of each mobile card, e.g. a "View" link. */
  cardFooter?: (row: T) => ReactNode;
  /**
   * Renders additional detail content for a row that doesn't fit a compact
   * cell or the mobile card body (first consumer: the Audit Log Viewer's
   * `reason`/`metadata`, `app/(tenant-admin)/tenant-admin/audit-log/page.tsx`).
   * When supplied, every expandable row (see `isRowExpandable`) gets an
   * expand/collapse toggle: a full-width `<tr>` rendered below the row on
   * the desktop table, and an appended section inside the card on mobile.
   * Every existing `DataTable` caller omits this prop and renders exactly as
   * before — no toggle column, no extra markup.
   */
  renderExpandedRow?: (row: T) => ReactNode;
  /** Restricts which rows get an expand toggle when `renderExpandedRow` is supplied. Defaults to every row being expandable. */
  isRowExpandable?: (row: T) => boolean;
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  cardHeading,
  cardHeadingAdornment,
  cardFooter,
  renderExpandedRow,
  isRowExpandable,
}: DataTableProps<T>) {
  const cardColumns = columns.filter((column) => !column.hideOnCard);
  // Keyed by `rowKey(row)`, not row identity, so expansion survives a
  // background refetch that returns a structurally-new-but-same-id row.
  const [expandedKeys, setExpandedKeys] = useState<ReadonlySet<string>>(new Set());

  function toggle(key: string) {
    setExpandedKeys((current) => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  function rowIsExpandable(row: T): boolean {
    return Boolean(renderExpandedRow) && (isRowExpandable ? isRowExpandable(row) : true);
  }

  return (
    <>
      {/* Desktop/tablet: table. Below md: card list (.claude/rules/ui-ux.md §5). */}
      <div className="hidden overflow-x-auto rounded-lg border border-border md:block">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead className="border-b border-border bg-muted/40">
            <tr>
              {renderExpandedRow ? (
                <th scope="col" className="w-10 px-2 py-2">
                  <span className="sr-only">Expand row</span>
                </th>
              ) : null}
              {columns.map((column) => (
                <th key={column.key} scope="col" className="px-4 py-2 font-medium text-foreground">
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const key = rowKey(row);
              const expandable = rowIsExpandable(row);
              const expanded = expandable && expandedKeys.has(key);
              const contentId = `data-table-row-detail-${key}`;
              return (
                <Fragment key={key}>
                  <tr className="border-b border-border last:border-0">
                    {renderExpandedRow ? (
                      <td className="px-2 py-2.5">
                        {expandable ? (
                          <button
                            type="button"
                            aria-expanded={expanded}
                            aria-controls={contentId}
                            aria-label={expanded ? "Collapse row details" : "Expand row details"}
                            onClick={() => toggle(key)}
                            className="flex size-6 items-center justify-center rounded-md text-muted-foreground outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
                          >
                            <ChevronDown
                              aria-hidden="true"
                              className={cn(
                                "size-4 transition-transform duration-150 motion-reduce:transition-none",
                                expanded && "rotate-180"
                              )}
                            />
                          </button>
                        ) : null}
                      </td>
                    ) : null}
                    {columns.map((column) => (
                      <td
                        key={column.key}
                        className={cn("px-4 py-2.5 text-muted-foreground", column.className)}
                      >
                        {column.cell(row)}
                      </td>
                    ))}
                  </tr>
                  {expandable ? (
                    <tr className="border-b border-border last:border-0" hidden={!expanded}>
                      <td
                        colSpan={columns.length + 1}
                        id={contentId}
                        className="bg-muted/20 px-4 py-3"
                      >
                        {renderExpandedRow?.(row)}
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      <ul className="flex flex-col gap-3 md:hidden" aria-label={caption}>
        {rows.map((row) => {
          const key = rowKey(row);
          const expandable = rowIsExpandable(row);
          const expanded = expandable && expandedKeys.has(key);
          const contentId = `data-table-row-detail-mobile-${key}`;
          return (
            <li key={key} className="flex flex-col gap-2 rounded-lg border border-border p-4">
              <div className="flex items-start justify-between gap-2">
                <span className="font-medium text-foreground">{cardHeading(row)}</span>
                {cardHeadingAdornment ? cardHeadingAdornment(row) : null}
              </div>
              {cardColumns.length > 0 ? (
                <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-1 text-xs text-muted-foreground">
                  {cardColumns.map((column) => (
                    <Fragment key={column.key}>
                      <dt className="font-medium text-foreground">{column.header}</dt>
                      <dd>{column.cell(row)}</dd>
                    </Fragment>
                  ))}
                </dl>
              ) : null}
              {expandable ? (
                <button
                  type="button"
                  aria-expanded={expanded}
                  aria-controls={contentId}
                  onClick={() => toggle(key)}
                  className="flex items-center gap-1 self-start text-xs font-medium text-foreground outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  <ChevronDown
                    aria-hidden="true"
                    className={cn(
                      "size-3.5 transition-transform duration-150 motion-reduce:transition-none",
                      expanded && "rotate-180"
                    )}
                  />
                  {expanded ? "Hide details" : "Show details"}
                </button>
              ) : null}
              {expandable ? (
                <div id={contentId} hidden={!expanded} className="text-xs text-muted-foreground">
                  {renderExpandedRow?.(row)}
                </div>
              ) : null}
              {cardFooter ? cardFooter(row) : null}
            </li>
          );
        })}
      </ul>
    </>
  );
}
