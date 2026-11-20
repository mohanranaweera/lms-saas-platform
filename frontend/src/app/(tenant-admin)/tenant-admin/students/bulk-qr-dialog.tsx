"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { QrCode } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { StudentDialog } from "@/components/students/student-dialog";
import { isApiClientError } from "@/lib/api/error";
import {
  QR_EXPORT_LIMIT,
  useExportStudentQrs,
  useFetchAllDirectoryIds,
  useStudentDirectory,
} from "@/lib/api/student-management";

const PAGE_SIZE = 10;

/**
 * Bulk Print QRs (STU-MGMT-02 §7). Selection is a set of student ids that
 * survives searching and paging. The header checkbox selects every student
 * matching the current search (ids fetched server-side, capped at the 500
 * export limit). Export sends exactly the selected ids; the server re-filters
 * them to this tenant and builds the label PDF (name + index, never NIC).
 */
export function BulkQrDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [searchInput, setSearchInput] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [matched, setMatched] = useState<{ q: string; ids: string[]; total: number; capped: boolean } | null>(null);
  const [selectingAll, setSelectingAll] = useState(false);
  const [selectAllError, setSelectAllError] = useState<string | null>(null);
  const headerRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const timeout = setTimeout(() => {
      setQ(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timeout);
  }, [searchInput]);

  const query = useStudentDirectory({ tab: "ACTIVE", field: "ALL", q, page, size: PAGE_SIZE }, { enabled: open });
  const fetchAllIds = useFetchAllDirectoryIds();
  const exportQrs = useExportStudentQrs();

  const rows = useMemo(() => query.data?.content ?? [], [query.data]);
  const showNic = rows.some((row) => typeof row.nic === "string" && row.nic.length > 0);
  const matchedForQuery = matched && matched.q === q ? matched : null;
  const allMatchedSelected =
    matchedForQuery !== null && matchedForQuery.ids.length > 0 && matchedForQuery.ids.every((id) => selected.has(id));
  const someVisibleSelected = rows.some((row) => selected.has(row.id));

  useEffect(() => {
    if (headerRef.current) headerRef.current.indeterminate = !allMatchedSelected && someVisibleSelected;
  }, [allMatchedSelected, someVisibleSelected]);

  function toggle(id: string, checked: boolean) {
    setSelected((current) => {
      const next = new Set(current);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  async function toggleAll() {
    setSelectAllError(null);
    if (allMatchedSelected && matchedForQuery) {
      setSelected((current) => {
        const next = new Set(current);
        for (const id of matchedForQuery.ids) next.delete(id);
        return next;
      });
      return;
    }
    setSelectingAll(true);
    try {
      const result = await fetchAllIds({ tab: "ACTIVE", field: "ALL", q });
      setMatched({ q, ...result });
      setSelected((current) => {
        const next = new Set(current);
        for (const id of result.ids) {
          if (next.size >= QR_EXPORT_LIMIT) break;
          next.add(id);
        }
        return next;
      });
    } catch (error) {
      setSelectAllError(isApiClientError(error) ? error.message : "Matching students couldn't be loaded.");
    } finally {
      setSelectingAll(false);
    }
  }

  // A fresh dialog each time it is closed and reopened.
  function close() {
    setSearchInput("");
    setQ("");
    setPage(0);
    setSelected(new Set());
    setMatched(null);
    setSelectAllError(null);
    exportQrs.reset();
    onOpenChange(false);
  }

  const tooMany = selected.size > QR_EXPORT_LIMIT;
  const exportError = exportQrs.isError
    ? isApiClientError(exportQrs.error)
      ? exportQrs.error.message
      : "The QR labels couldn't be exported."
    : null;

  return (
    <StudentDialog
      open={open}
      onOpenChange={(next) => {
        if (next) onOpenChange(true);
        else if (!exportQrs.isPending) close();
      }}
      title="Bulk Print QRs"
      description="Following selected student QRs will be exported"
      icon={<QrCode className="size-5 text-primary" aria-hidden="true" />}
      testId="bulk-qr-dialog"
      className="sm:max-w-3xl"
      footer={
        <>
          <Button type="button" variant="outline" disabled={exportQrs.isPending} onClick={close}>
            Cancel
          </Button>
          <Button
            type="button"
            disabled={selected.size === 0 || tooMany || exportQrs.isPending}
            aria-busy={exportQrs.isPending}
            onClick={() =>
              exportQrs.mutate(Array.from(selected), {
                onSuccess: () => close(),
              })
            }
          >
            {exportQrs.isPending ? "Exporting…" : "Export"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="bulk-qr-search">Search Students</Label>
          <Input
            id="bulk-qr-search"
            type="search"
            placeholder="Index number, name, email or mobile"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
          />
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 text-sm" role="status" aria-live="polite">
          <span data-testid="bulk-qr-selected-count">{selected.size} selected</span>
          {selected.size > 0 ? (
            <Button type="button" variant="ghost" size="sm" onClick={() => setSelected(new Set())}>
              Clear selection
            </Button>
          ) : null}
        </div>
        {matchedForQuery?.capped ? (
          <p className="text-xs text-muted-foreground" data-testid="bulk-qr-capped">
            {matchedForQuery.total} students match. Only the first {QR_EXPORT_LIMIT} were selected — the export limit is{" "}
            {QR_EXPORT_LIMIT} per file.
          </p>
        ) : matchedForQuery && allMatchedSelected ? (
          <p className="text-xs text-muted-foreground">
            All {matchedForQuery.ids.length} students matching {q ? `“${q}”` : "this list"} are selected.
          </p>
        ) : null}
        {tooMany ? (
          <p role="alert" className="text-xs text-destructive">
            Select at most {QR_EXPORT_LIMIT} students per export.
          </p>
        ) : null}
        {selectAllError ? (
          <p role="alert" className="text-xs text-destructive">
            {selectAllError}
          </p>
        ) : null}

        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <caption className="sr-only">Students to export</caption>
            <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
              <tr>
                <th scope="col" className="w-10 px-3 py-2">
                  <input
                    ref={headerRef}
                    type="checkbox"
                    className="size-4"
                    aria-label={q ? `Select all students matching “${q}”` : "Select all students"}
                    checked={allMatchedSelected}
                    disabled={selectingAll || query.status !== "success" || rows.length === 0}
                    onChange={() => void toggleAll()}
                  />
                </th>
                <th scope="col" className="px-3 py-2">Index Number</th>
                <th scope="col" className="px-3 py-2">First Name</th>
                <th scope="col" className="px-3 py-2">Last Name</th>
                {showNic ? (
                  <th scope="col" className="px-3 py-2">
                    NIC
                  </th>
                ) : null}
              </tr>
            </thead>
            <tbody>
              {query.status === "pending" || selectingAll ? (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center text-muted-foreground" role="status">
                    {selectingAll ? "Selecting matching students…" : "Loading students…"}
                  </td>
                </tr>
              ) : query.status === "error" ? (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center" role="alert">
                    <span className="text-destructive">Students couldn&apos;t be loaded.</span>{" "}
                    <button type="button" className="underline" onClick={() => void query.refetch()}>
                      Try again
                    </button>
                  </td>
                </tr>
              ) : rows.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center text-muted-foreground">
                    {q ? `No students match “${q}”.` : "No active students to export yet."}
                  </td>
                </tr>
              ) : (
                rows.map((row) => {
                  const name = [row.firstName, row.lastName].filter(Boolean).join(" ") || row.email;
                  return (
                    <tr key={row.id} className="border-t border-border" data-testid="bulk-qr-row">
                      <td className="px-3 py-2">
                        <input
                          type="checkbox"
                          className="size-4"
                          aria-label={`Select ${name}`}
                          checked={selected.has(row.id)}
                          onChange={(event) => toggle(row.id, event.target.checked)}
                        />
                      </td>
                      <td className="px-3 py-2 font-mono tabular-nums">{row.indexNumber ?? "—"}</td>
                      <td className="px-3 py-2">{row.firstName ?? "—"}</td>
                      <td className="px-3 py-2">{row.lastName ?? "—"}</td>
                      {showNic ? <td className="px-3 py-2">{row.nic ?? "—"}</td> : null}
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>

        {query.data && query.data.totalPages > 1 ? (
          <div className="flex items-center justify-between gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={page === 0 || query.isFetching}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </Button>
            <span className="text-xs text-muted-foreground">
              Page {query.data.page + 1} of {query.data.totalPages}
            </span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={query.data.page + 1 >= query.data.totalPages || query.isFetching}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </Button>
          </div>
        ) : null}

        {exportError ? (
          <Alert variant="destructive">
            <AlertDescription role="alert">{exportError}</AlertDescription>
          </Alert>
        ) : null}
      </div>
    </StudentDialog>
  );
}
