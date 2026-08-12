import { Fragment, type ReactNode } from "react";
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
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  caption,
  cardHeading,
  cardHeadingAdornment,
  cardFooter,
}: DataTableProps<T>) {
  const cardColumns = columns.filter((column) => !column.hideOnCard);

  return (
    <>
      {/* Desktop/tablet: table. Below md: card list (.claude/rules/ui-ux.md §5). */}
      <div className="hidden overflow-x-auto rounded-lg border border-border md:block">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead className="border-b border-border bg-muted/40">
            <tr>
              {columns.map((column) => (
                <th key={column.key} scope="col" className="px-4 py-2 font-medium text-foreground">
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={rowKey(row)} className="border-b border-border last:border-0">
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={cn("px-4 py-2.5 text-muted-foreground", column.className)}
                  >
                    {column.cell(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul className="flex flex-col gap-3 md:hidden" aria-label={caption}>
        {rows.map((row) => (
          <li key={rowKey(row)} className="flex flex-col gap-2 rounded-lg border border-border p-4">
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
            {cardFooter ? cardFooter(row) : null}
          </li>
        ))}
      </ul>
    </>
  );
}
