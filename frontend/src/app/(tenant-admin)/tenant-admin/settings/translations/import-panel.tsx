"use client";

import { useId, useState } from "react";
import { AlertCircle, CheckCircle2, FileUp } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { isApiClientError } from "@/lib/api/error";
import { useImportPo, type PoImportReport } from "@/lib/api/translations";
import { useT } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { localeAutonym } from "@/i18n/locales";
import { describeProblem, describeServerProblem, serverProblems } from "./problems";

/** Server limit (doc §5); checked here only to fail fast. */
const MAX_PO_BYTES = 1024 * 1024;

/**
 * PO import (doc §5): a dry run first shows additions, changes, invalid
 * messages (with reasons) and unknown keys; only then can the import be
 * confirmed. Import writes drafts only - nothing is served until published.
 * Entries marked "needs review" (fuzzy) are skipped unless the admin opts in.
 */
export function ImportPanel({ locale }: { locale: string }) {
  const t = useT();
  const id = useId();
  const mutation = useImportPo();
  const [file, setFile] = useState<File | null>(null);
  const [report, setReport] = useState<PoImportReport | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [includeNeedsReview, setIncludeNeedsReview] = useState(false);

  function onIncludeNeedsReview(next: boolean) {
    // The previous file check no longer describes what would be imported.
    setIncludeNeedsReview(next);
    setReport(null);
    setDone(null);
    mutation.reset();
  }

  function onFile(next: File | null) {
    setReport(null);
    setDone(null);
    mutation.reset();
    setFileError(null);
    if (next && next.size > MAX_PO_BYTES) {
      setFile(null);
      setFileError(t("errors.PAYLOAD_TOO_LARGE"));
      return;
    }
    setFile(next);
  }

  function run(dryRun: boolean) {
    if (!file) return;
    setDone(null);
    mutation.mutate(
      { locale, file, dryRun, includeNeedsReview },
      {
        onSuccess: (result) => {
          if (dryRun) setReport(result);
          else {
            setReport(null);
            setFile(null);
            setDone(t("i18nAdmin.import.done", { count: result ? result.created + result.updated : 0 }));
          }
        },
      }
    );
  }

  const error = mutation.error;
  const invalidCount = report?.invalid.length ?? 0;
  const importable = report ? report.created + report.updated : 0;

  return (
    <section aria-labelledby={`${id}-heading`} className="flex min-w-0 flex-col gap-3 rounded-lg border border-border p-4" data-testid="import-panel">
      <div>
        <h3 id={`${id}-heading`} className="flex items-center gap-2 text-sm font-medium text-foreground">
          <FileUp className="size-4" aria-hidden="true" />
          {t("i18nAdmin.import.heading", { language: localeAutonym(locale) })}
        </h3>
        <p className="text-xs text-muted-foreground">{t("i18nAdmin.import.description")}</p>
      </div>
      <div className="flex min-w-0 flex-col gap-1.5">
        <Label htmlFor={`${id}-file`}>{t("i18nAdmin.import.file")}</Label>
        <input
          id={`${id}-file`}
          type="file"
          accept=".po,text/x-gettext-translation,text/plain"
          className="min-w-0 text-sm file:mr-3 file:rounded-md file:border file:border-border file:bg-muted file:px-3 file:py-1.5 file:text-sm"
          aria-describedby={fileError ? `${id}-file-error` : undefined}
          onChange={(event) => onFile(event.target.files?.[0] ?? null)}
        />
        {fileError ? (
          <p id={`${id}-file-error`} role="alert" className="text-sm text-destructive">
            {fileError}
          </p>
        ) : null}
      </div>
      <div className="flex items-start gap-3">
        <Switch
          id={`${id}-needs-review`}
          checked={includeNeedsReview}
          onCheckedChange={onIncludeNeedsReview}
          aria-describedby={`${id}-needs-review-help`}
          data-testid="include-needs-review"
        />
        <div className="flex min-w-0 flex-col gap-0.5">
          <Label htmlFor={`${id}-needs-review`}>{t("i18nAdmin.import.includeNeedsReview")}</Label>
          <p id={`${id}-needs-review-help`} className="text-xs text-muted-foreground">
            {t("i18nAdmin.import.includeNeedsReviewHelp")}
          </p>
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button type="button" size="sm" variant="outline" disabled={!file || mutation.isPending} aria-busy={mutation.isPending} onClick={() => run(true)}>
          {t("i18nAdmin.import.dryRun")}
        </Button>
        {report ? (
          <Button
            type="button"
            size="sm"
            disabled={mutation.isPending || invalidCount > 0 || !report.valid || importable === 0}
            onClick={() => run(false)}
            data-testid="confirm-import"
          >
            {t("i18nAdmin.import.confirm", { count: importable })}
          </Button>
        ) : null}
      </div>

      {report ? <ImportReport report={report} /> : null}

      {done ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{done}</AlertDescription>
        </Alert>
      ) : null}
      {error ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            <span className="block">{localizeApiError(t, error)}</span>
            {isApiClientError(error) ? (
              <ul className="mt-1 list-disc pl-5">
                {serverProblems(error.details).map((problem) => (
                  <li key={problem}>{describeServerProblem(t, problem)}</li>
                ))}
              </ul>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
    </section>
  );
}

function ImportReport({ report }: { report: PoImportReport }) {
  const t = useT();
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-md bg-muted/50 p-3 text-sm" data-testid="import-report" aria-live="polite">
      <p className="font-medium">{t("i18nAdmin.import.reportTitle")}</p>
      <ul className="flex flex-col gap-0.5">
        <li>{t("i18nAdmin.import.additions", { count: report.created })}</li>
        <li>{t("i18nAdmin.import.changes", { count: report.updated })}</li>
        {report.needsReview > 0 ? (
          <li className="text-amber-700 dark:text-amber-400">
            {t("i18nAdmin.import.needsReviewIncluded", { count: report.needsReview })}
          </li>
        ) : null}
        <li>{t("i18nAdmin.import.unchanged", { count: report.unchanged })}</li>
        <li>{t("i18nAdmin.import.skipped", { count: report.skippedEmpty + report.skippedFuzzy + report.skippedObsolete })}</li>
        <li className={report.invalid.length > 0 ? "text-destructive" : undefined}>
          {t("i18nAdmin.import.invalid", { count: report.invalid.length })}
        </li>
        <li>{t("i18nAdmin.import.unknown", { count: report.unknownKeys.length })}</li>
      </ul>
      {report.invalid.length > 0 ? (
        <details open>
          <summary className="cursor-pointer text-destructive">{t("i18nAdmin.import.invalidDetails")}</summary>
          <ul className="mt-1 flex flex-col gap-1">
            {report.invalid.map((item) => (
              <li key={item.key} className="min-w-0 break-words">
                <span className="font-mono text-xs break-all">{item.key}</span>:{" "}
                {item.problems.map((problem) => describeProblem(t, problem)).join("; ")}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
      {report.unknownKeys.length > 0 ? (
        <details>
          <summary className="cursor-pointer">{t("i18nAdmin.import.unknownDetails")}</summary>
          <ul className="mt-1 flex flex-col gap-0.5 font-mono text-xs break-all">
            {report.unknownKeys.map((key) => (
              <li key={key}>{key}</li>
            ))}
          </ul>
        </details>
      ) : null}
      {report.invalid.length > 0 ? <p className="text-xs text-destructive">{t("i18nAdmin.import.blocked")}</p> : null}
    </div>
  );
}
