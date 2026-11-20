"use client";

import { useId, useRef, useState, type ReactNode } from "react";
import {
  CheckCircle2,
  CircleDashed,
  CircleSlash,
  Clock,
  FileText,
  Hourglass,
  Paperclip,
  PenLine,
  UserX,
  X,
  XCircle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { marksNumber } from "@/lib/api/question-bank";
import {
  EXAM_FILE_ACCEPT,
  EXAM_FILE_MAX_COUNT,
  examFileProblem,
  formatFileSize,
  type ResultLabel,
} from "@/lib/api/exam-management";
import { cn } from "@/lib/utils";

const RESULT: Record<ResultLabel, { label: string; icon: typeof CheckCircle2; className: string }> = {
  NOT_PARTICIPATED: { label: "Not participated", icon: CircleSlash, className: "bg-amber-600 text-white dark:bg-amber-500 dark:text-black" },
  PENDING: { label: "Pending", icon: CircleDashed, className: "bg-muted text-muted-foreground" },
  IN_PROGRESS: { label: "In progress", icon: Hourglass, className: "bg-primary/10 text-primary" },
  AWAITING_MARKS: { label: "Awaiting marks", icon: Clock, className: "bg-sky-600/10 text-sky-800 dark:text-sky-300" },
  PASSED: { label: "Passed", icon: CheckCircle2, className: "bg-green-600 text-white dark:bg-green-500 dark:text-black" },
  FAILED: { label: "Failed", icon: XCircle, className: "bg-destructive text-white" },
  MARKED: { label: "Marked", icon: PenLine, className: "bg-green-600/10 text-green-800 dark:text-green-300" },
  ABSENT: { label: "Absent", icon: UserX, className: "bg-muted text-foreground" },
};

/** Klass "Result" chip: icon + uppercase text, never colour alone. */
export function ResultLabelBadge({ result }: { result: ResultLabel }) {
  const entry = RESULT[result] ?? RESULT.PENDING;
  const Icon = entry.icon;
  return (
    <span
      data-testid="result-label"
      data-result={result}
      className={cn(
        "inline-flex h-6 shrink-0 items-center gap-1 rounded-full px-2.5 text-xs font-semibold tracking-wide whitespace-nowrap uppercase",
        entry.className
      )}
    >
      <Icon className="size-3" aria-hidden="true" />
      {entry.label}
    </span>
  );
}

/** "72.5", "0", or "—" for no marks. */
export function marksText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const n = marksNumber(value);
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(2)));
}

export function percentText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "0%";
  const n = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(n)) return "0%";
  return `${Number.isInteger(n) ? n : Number(n.toFixed(1))}%`;
}

/** A single signed-download link button (fetches the signed URL on click). */
export function FileLinkButton({
  fileName,
  onOpen,
  pending,
  testId,
  label,
}: {
  fileName: string;
  onOpen: () => void;
  pending?: boolean;
  testId?: string;
  label?: string;
}) {
  return (
    <button
      type="button"
      onClick={onOpen}
      disabled={pending}
      aria-busy={pending}
      aria-label={label ?? `Open ${fileName}`}
      data-testid={testId}
      className="inline-flex max-w-full min-w-0 items-center gap-1 rounded text-left text-sm font-medium text-primary underline-offset-4 hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none disabled:opacity-60"
    >
      <FileText className="size-3.5 shrink-0" aria-hidden="true" />
      <span className="truncate">{fileName}</span>
    </button>
  );
}

/**
 * Multi-file picker with client pre-checks (PDF/PNG/JPEG, ≤ 20 MB, ≤ `max`
 * files). UX only — the server sniffs content and re-checks every limit.
 */
export function ExamFilePicker({
  files,
  onChange,
  max = EXAM_FILE_MAX_COUNT,
  multiple = true,
  label,
  helper,
  disabled,
  testId = "exam-file-picker",
}: {
  files: File[];
  onChange: (files: File[]) => void;
  max?: number;
  multiple?: boolean;
  label: string;
  helper?: ReactNode;
  disabled?: boolean;
  testId?: string;
}) {
  const inputId = useId();
  const helperId = `${inputId}-helper`;
  const errorId = `${inputId}-error`;
  const inputRef = useRef<HTMLInputElement>(null);
  const [problems, setProblems] = useState<string[]>([]);

  function add(list: FileList | null) {
    if (!list) return;
    const incoming = Array.from(list);
    const next: File[] = multiple ? [...files] : [];
    const found: string[] = [];
    for (const file of incoming) {
      const problem = examFileProblem(file);
      if (problem) {
        found.push(problem);
        continue;
      }
      if (next.length >= max) {
        found.push(`You can attach up to ${max} ${max === 1 ? "file" : "files"} at a time.`);
        break;
      }
      next.push(file);
    }
    setProblems(found);
    onChange(next);
    if (inputRef.current) inputRef.current.value = "";
  }

  return (
    <div className="flex min-w-0 flex-col gap-2" data-testid={testId}>
      <label htmlFor={inputId} className="text-sm font-medium text-foreground">
        {label}
      </label>
      <input
        ref={inputRef}
        id={inputId}
        type="file"
        multiple={multiple}
        accept={EXAM_FILE_ACCEPT}
        disabled={disabled}
        aria-describedby={[helperId, problems.length > 0 ? errorId : null].filter(Boolean).join(" ")}
        onChange={(event) => add(event.target.files)}
        className="block w-full min-w-0 text-sm text-muted-foreground file:mr-3 file:rounded-md file:border-0 file:bg-primary/10 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-primary hover:file:bg-primary/20 disabled:opacity-50"
        data-testid={`${testId}-input`}
      />
      <p id={helperId} className="text-xs text-muted-foreground">
        {helper ?? `PDF, PNG or JPEG, up to 20 MB each${multiple ? `, up to ${max} files` : ""}.`}
      </p>
      {problems.length > 0 ? (
        <ul id={errorId} role="alert" className="flex flex-col gap-0.5 text-xs text-destructive" data-testid={`${testId}-errors`}>
          {problems.map((problem) => (
            <li key={problem}>{problem}</li>
          ))}
        </ul>
      ) : null}
      {files.length > 0 ? (
        <ul className="flex flex-col gap-1" aria-label="Files to upload" data-testid={`${testId}-selected`}>
          {files.map((file, index) => (
            <li
              key={`${file.name}-${index}`}
              className="flex min-w-0 items-center gap-2 rounded-md border border-border px-2 py-1 text-sm"
            >
              <Paperclip className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate">{file.name}</span>
              <span className="shrink-0 text-xs text-muted-foreground">{formatFileSize(file.size)}</span>
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                disabled={disabled}
                aria-label={`Remove ${file.name}`}
                onClick={() => onChange(files.filter((_, position) => position !== index))}
              >
                <X aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

/** "Uploading notes.pdf (2 of 3)" from a sequential-upload progress report. */
export function sequentialLabel(progress: { index: number; total: number; fileName: string }): string {
  return progress.total > 1
    ? `Uploading ${progress.fileName} (${progress.index + 1} of ${progress.total})`
    : `Uploading ${progress.fileName}`;
}

/** Determinate upload progress bar (0..1). */
export function UploadProgress({ value, label = "Uploading" }: { value: number; label?: string }) {
  const percent = Math.round(Math.max(0, Math.min(1, value)) * 100);
  return (
    <div className="flex flex-col gap-1" data-testid="upload-progress">
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>{label}…</span>
        <span>{percent}%</span>
      </div>
      <div
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        className="h-2 overflow-hidden rounded-full bg-muted"
      >
        <div className="h-full bg-primary transition-[width] duration-150" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}
