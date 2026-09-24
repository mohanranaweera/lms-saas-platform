"use client";

import { useRef, useState, type FormEvent } from "react";
import Link from "next/link";
import { ArrowLeft, CheckCircle2, Download, XCircle } from "lucide-react";
import { useBulkImportStudents, type BulkImportRowResult } from "@/lib/api/students";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";

/**
 * Bulk Import (Wave 3, PAR-03-03) — `POST /v1/students/bulk-import`,
 * multipart CSV, staff `STUDENTS`/`CREATE_EDIT`. Per-row partial-failure
 * contract (wave-03 plan §4): the response is never all-or-nothing — every
 * row is attempted independently, so this page always renders the full
 * per-row result table on a `200`, never a single pass/fail banner.
 *
 * No client-side role guard on entry (mirrors `teachers/new/page.tsx`'s
 * documented reasoning) — a real `403` from the upload itself is rendered via
 * `PermissionDeniedState`, never fabricated from a client-stored role guess.
 */
// Mirrors `StudentBulkImportService`'s exact expected column order
// (`name,email,password` — see that class's javadoc; this page never
// changes what the backend accepts, only what it shows/offers as a
// starting point) plus one example data row, so staff don't have to guess
// column names/order from prose alone.
const CSV_TEMPLATE = "name,email,password\nAda Lovelace,ada@example.com,TempPassw0rd!\n";

function downloadCsvTemplate() {
  const blob = new Blob([CSV_TEMPLATE], { type: "text/csv" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = "student-bulk-import-template.csv";
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

const columns: DataTableColumn<BulkImportRowResult>[] = [
  { key: "row", header: "Row", cell: (row) => row.row, hideOnCard: true },
  {
    key: "status",
    header: "Result",
    cell: (row) =>
      row.status === "CREATED" ? (
        <Badge variant="default">
          <CheckCircle2 className="size-3.5" aria-hidden="true" />
          Created
        </Badge>
      ) : (
        <Badge variant="destructive">
          <XCircle className="size-3.5" aria-hidden="true" />
          Failed
        </Badge>
      ),
  },
  {
    key: "detail",
    header: "Detail",
    cell: (row) =>
      row.status === "CREATED" && row.studentId ? (
        <Link href={`/tenant-admin/students/${row.studentId}`} className="font-medium text-foreground hover:underline">
          View student
        </Link>
      ) : (
        (row.reason ?? "Unknown error")
      ),
  },
];

export default function StudentBulkImportPage() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [permissionDeniedError, setPermissionDeniedError] = useState<ApiClientError | null>(null);

  const mutation = useBulkImportStudents();

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setFileError(null);
    setPermissionDeniedError(null);
    if (!selectedFile) {
      setFileError("Choose a CSV file to import.");
      return;
    }
    try {
      await mutation.mutateAsync(selectedFile);
    } catch (error) {
      if (isApiClientError(error) && error.status === 403) {
        setPermissionDeniedError(error);
      }
      // Any other failure is surfaced via `mutation.error` below.
    }
  }

  if (permissionDeniedError) {
    return (
      <div className="flex flex-col gap-6">
        <BackLink />
        <PermissionDeniedState error={permissionDeniedError} dashboardHref="/tenant-admin/dashboard" />
      </div>
    );
  }

  const results = mutation.data;

  return (
    <div className="flex flex-col gap-6">
      <BackLink />
      <div>
        <h1 className="text-xl font-semibold text-foreground">Bulk import students</h1>
        <p className="text-sm text-muted-foreground">
          Upload a CSV file to create multiple student accounts at once. Each row is processed
          independently — some rows can succeed while others fail.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardDescription>
            The file must be a CSV with a header row: <code>name,email,password</code>. Rows that
            fail (e.g. a duplicate email or a missing required column) are reported individually
            below — they do not block the other rows from being created.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-col gap-2 rounded-md border border-border bg-muted/40 p-3 text-xs text-muted-foreground">
            <p>
              Example: <code>name,email,password</code>
              <br />
              <code>Ada Lovelace,ada@example.com,TempPassw0rd!</code>
            </p>
            <Button type="button" variant="outline" size="sm" className="w-fit" onClick={downloadCsvTemplate}>
              <Download aria-hidden="true" />
              Download CSV template
            </Button>
          </div>
          {mutation.isError && !isApiClientError(mutation.error) ? (
            <ErrorState message="An unexpected error occurred. Please try again." onRetry={() => mutation.reset()} />
          ) : null}
          {mutation.isError && isApiClientError(mutation.error) && mutation.error.status !== 403 ? (
            <ErrorState
              message={mutation.error.message}
              code={mutation.error.code}
              fieldErrors={mutation.error.fieldErrors}
              onRetry={() => mutation.reset()}
            />
          ) : null}

          <form className="flex flex-col gap-4" onSubmit={handleSubmit} aria-busy={mutation.isPending} noValidate>
            <span role="status" aria-live="polite" className="sr-only">
              {mutation.isPending ? "Uploading and importing…" : ""}
            </span>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="bulk-import-file">CSV file</Label>
              <input
                ref={inputRef}
                id="bulk-import-file"
                type="file"
                accept=".csv,text/csv"
                disabled={mutation.isPending}
                aria-invalid={fileError ? true : undefined}
                aria-describedby={fileError ? "bulk-import-file-error" : undefined}
                onChange={(event) => {
                  setSelectedFile(event.target.files?.[0] ?? null);
                  setFileError(null);
                }}
                className="text-sm file:mr-3 file:h-8 file:rounded-lg file:border file:border-input file:bg-background file:px-2.5 file:text-sm file:font-medium file:hover:bg-muted"
              />
              {fileError ? (
                <p id="bulk-import-file-error" role="alert" className="text-xs text-destructive">
                  {fileError}
                </p>
              ) : null}
            </div>
            <Button type="submit" className="w-fit" disabled={mutation.isPending}>
              {mutation.isPending ? "Importing…" : "Import"}
            </Button>
          </form>
        </CardContent>
      </Card>

      {results ? (
        results.length === 0 ? (
          <p className="text-sm text-muted-foreground">The file contained no rows to import.</p>
        ) : (
          <div className="flex flex-col gap-3">
            <p role="status" aria-live="polite" className="text-sm text-foreground">
              {results.filter((row) => row.status === "CREATED").length} of {results.length} row
              {results.length === 1 ? "" : "s"} created successfully.
            </p>
            <DataTable
              columns={columns}
              rows={results}
              rowKey={(row) => String(row.row)}
              caption="Bulk import results"
              cardHeading={(row) => `Row ${row.row}`}
              cardHeadingAdornment={(row) =>
                row.status === "CREATED" ? (
                  <Badge variant="default">Created</Badge>
                ) : (
                  <Badge variant="destructive">Failed</Badge>
                )
              }
            />
          </div>
        )
      ) : null}
    </div>
  );
}

function BackLink() {
  return (
    <Link
      href="/tenant-admin/students"
      className="inline-flex w-fit items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground"
    >
      <ArrowLeft className="size-4" aria-hidden="true" />
      Back to students
    </Link>
  );
}
