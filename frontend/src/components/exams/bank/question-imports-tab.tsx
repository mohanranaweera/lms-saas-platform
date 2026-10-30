"use client";

import { useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Eye, Search, Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { TableSkeleton } from "@/components/courses/admin/reorderable-table";
import { ImportQuestionsDialog } from "@/components/exams/bank/import-questions-dialog";
import { useQuestionImports, type QuestionImport } from "@/lib/api/question-bank";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 10;

function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function randomKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

/** IMPORTS tab: import history (search + pagination) and the IMPORT QUESTIONS dialog. */
export function QuestionImportsTab({
  courseId,
  moduleId,
  dashboardHref,
  onImported,
}: {
  courseId: string;
  moduleId: string;
  dashboardHref: string;
  onImported: (result: QuestionImport) => void;
}) {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState({ open: false, key: 0 });
  const [details, setDetails] = useState<QuestionImport | null>(null);
  const keys = useRef(new Map<string, string>());

  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  const query = useQuestionImports(courseId, moduleId, { search, page, size: PAGE_SIZE });

  const idempotencyKeyFor = (file: File) => {
    const fingerprint = `${file.name}:${file.size}:${file.lastModified}`;
    let key = keys.current.get(fingerprint);
    if (!key) {
      key = randomKey();
      keys.current.set(fingerprint, key);
    }
    return key;
  };

  const openDialog = () => setDialog((current) => ({ open: true, key: current.key + 1 }));

  const columns: DataTableColumn<QuestionImport>[] = [
    {
      key: "fileName",
      header: "File Name",
      cell: (row) => <span className="font-medium break-all text-foreground">{row.fileName}</span>,
      hideOnCard: true,
    },
    { key: "importedAt", header: "Imported Date", cell: (row) => formatDateTime(row.importedAt) },
    { key: "questionCount", header: "Number of Questions", cell: (row) => row.questionCount, className: "tabular-nums" },
    {
      key: "actions",
      header: "Actions",
      hideOnCard: true,
      cell: (row) => (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={`View details of ${row.fileName}`}
          title="View details"
          onClick={() => setDetails(row)}
          data-testid="import-view-details"
        >
          <Eye className="text-primary" aria-hidden="true" />
        </Button>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-4" data-testid="imports-tab">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="relative w-full sm:max-w-sm">
          <label htmlFor="question-imports-search" className="sr-only">
            Search imports
          </label>
          <Input
            id="question-imports-search"
            type="search"
            placeholder="Search"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            className="pr-8"
            data-testid="imports-search"
          />
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        </div>
        <Button type="button" className="h-9 px-4 tracking-wide uppercase" onClick={openDialog} data-testid="imports-open-dialog">
          <Upload aria-hidden="true" />
          Import questions
        </Button>
      </div>

      {query.status === "pending" ? (
        <TableSkeleton rows={4} label="Loading imports…" />
      ) : (
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading imports…"
          loginPath="/login"
          permissionDenied={{ dashboardHref }}
        >
          {(result) => {
            if (result.content.length === 0) {
              return search ? (
                <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-10 text-center" data-testid="imports-no-match">
                  <p className="text-sm font-medium text-foreground">No imports match &ldquo;{search}&rdquo;</p>
                  <Button type="button" size="sm" variant="outline" onClick={() => setSearchInput("")}>
                    Clear search
                  </Button>
                </div>
              ) : (
                <EmptyState
                  title="No imports yet"
                  description="Import questions from a CSV file to add many questions to this module at once. Each import is listed here."
                  action={{ label: "Import questions", onClick: openDialog }}
                />
              );
            }
            const first = result.page * result.size + 1;
            const last = result.page * result.size + result.content.length;
            return (
              <div className={cn("flex flex-col gap-3", query.isFetching && "opacity-70")} aria-busy={query.isFetching || undefined} data-testid="imports-table">
                <DataTable
                  columns={columns}
                  rows={result.content}
                  rowKey={(row) => row.id}
                  caption="Question imports"
                  cardHeading={(row) => <span className="break-all">{row.fileName}</span>}
                  cardFooter={(row) => (
                    <Button type="button" size="sm" variant="outline" onClick={() => setDetails(row)} aria-label={`View details of ${row.fileName}`}>
                      <Eye aria-hidden="true" />
                      View details
                    </Button>
                  )}
                />
                <nav aria-label="Imports pagination" className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
                  <span aria-live="polite">
                    {first}–{last} of {result.totalElements}
                  </span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Previous page"
                    disabled={result.page <= 0}
                    onClick={() => setPage((current) => Math.max(0, current - 1))}
                  >
                    <ChevronLeft aria-hidden="true" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label="Next page"
                    disabled={result.page + 1 >= result.totalPages}
                    onClick={() => setPage((current) => current + 1)}
                    data-testid="imports-next-page"
                  >
                    <ChevronRight aria-hidden="true" />
                  </Button>
                </nav>
              </div>
            );
          }}
        </QueryStateBoundary>
      )}

      <ImportQuestionsDialog
        key={dialog.key}
        open={dialog.open}
        onOpenChange={(open) => setDialog((current) => ({ ...current, open }))}
        courseId={courseId}
        moduleId={moduleId}
        idempotencyKeyFor={idempotencyKeyFor}
        onImported={(result) => {
          setDialog((current) => ({ ...current, open: false }));
          setSearchInput("");
          setPage(0);
          onImported(result);
        }}
      />

      <Sheet open={details !== null} onOpenChange={(open) => (open ? undefined : setDetails(null))}>
        <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-md" data-testid="import-details">
          <SheetHeader className="border-b border-border">
            <SheetTitle>Import details</SheetTitle>
            <SheetDescription>Questions imported from one CSV file.</SheetDescription>
          </SheetHeader>
          {details ? (
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3 px-4 pb-4 text-sm">
              <dt className="text-muted-foreground">File name</dt>
              <dd className="font-medium break-all text-foreground">{details.fileName}</dd>
              <dt className="text-muted-foreground">Imported</dt>
              <dd className="text-foreground">{formatDateTime(details.importedAt)}</dd>
              <dt className="text-muted-foreground">Questions</dt>
              <dd className="text-foreground tabular-nums">{details.questionCount}</dd>
              <dt className="text-muted-foreground">Imported by</dt>
              <dd className="text-foreground">{details.importedByName ?? "—"}</dd>
            </dl>
          ) : null}
        </SheetContent>
      </Sheet>
    </div>
  );
}
