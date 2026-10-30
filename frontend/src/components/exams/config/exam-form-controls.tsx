"use client";

import { useId, useRef, useState, type ComponentProps, type ComponentType, type ReactNode } from "react";
import { Loader2, Paperclip, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { FormField, describedBy, fieldIds, nativeSelectClass } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { useUploadExamDocument, type ExamDocumentPurpose } from "@/lib/api/exam-config";
import { DOCUMENT_ACCEPT, documentFileError, type AttachedDocument } from "@/lib/validation/exam-config";
import { cn } from "@/lib/utils";

export const EXAM_FORM_PREFIX = "exam-form";

type IconType = ComponentType<{ className?: string; "aria-hidden"?: boolean | "true" }>;

/** Leading icon overlay for an input/select (Klass "icon in input"). */
function LeadingIcon({ icon: Icon }: { icon: IconType }) {
  return (
    <Icon
      className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
      aria-hidden="true"
    />
  );
}

/** Labelled input with a leading icon, helper and inline error. */
export function IconInputField({
  name,
  label,
  icon,
  required,
  helper,
  error,
  className,
  inputProps,
}: {
  name: string;
  label: string;
  icon: IconType;
  required?: boolean;
  helper?: ReactNode;
  error?: string | null;
  className?: string;
  inputProps: ComponentProps<"input">;
}) {
  const ids = fieldIds(EXAM_FORM_PREFIX, name);
  return (
    <FormField ids={ids} label={label} required={required} helper={helper} error={error} className={className}>
      <div className="relative">
        <LeadingIcon icon={icon} />
        <Input
          id={ids.inputId}
          aria-required={required || undefined}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy(ids, Boolean(helper), Boolean(error))}
          data-testid={`${EXAM_FORM_PREFIX}-${name}`}
          {...inputProps}
          className={cn("h-9 pl-8", inputProps.className)}
        />
      </div>
    </FormField>
  );
}

/** Labelled native select with a leading icon. */
export function IconSelectField({
  name,
  label,
  icon,
  required,
  helper,
  error,
  className,
  selectProps,
  children,
}: {
  name: string;
  label: string;
  icon: IconType;
  required?: boolean;
  helper?: ReactNode;
  error?: string | null;
  className?: string;
  selectProps: ComponentProps<"select">;
  children: ReactNode;
}) {
  const ids = fieldIds(EXAM_FORM_PREFIX, name);
  return (
    <FormField ids={ids} label={label} required={required} helper={helper} error={error} className={className}>
      <div className="relative">
        <LeadingIcon icon={icon} />
        <select
          id={ids.inputId}
          aria-required={required || undefined}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy(ids, Boolean(helper), Boolean(error))}
          data-testid={`${EXAM_FORM_PREFIX}-${name}`}
          {...selectProps}
          className={cn(nativeSelectClass, "h-9 pl-8", selectProps.className)}
        >
          {children}
        </select>
      </div>
    </FormField>
  );
}

/** Native checkbox with its label (Klass option rows). */
export function CheckboxField({
  name,
  label,
  description,
  inputProps,
}: {
  name: string;
  label: string;
  description?: ReactNode;
  inputProps: ComponentProps<"input">;
}) {
  const id = `${EXAM_FORM_PREFIX}-${name}`;
  const descriptionId = `${id}-description`;
  return (
    <div className="flex items-start gap-2.5">
      <input
        id={id}
        type="checkbox"
        className="mt-0.5 size-4 shrink-0 accent-primary disabled:cursor-not-allowed disabled:opacity-50"
        aria-describedby={description ? descriptionId : undefined}
        data-testid={id}
        {...inputProps}
      />
      <div className="flex min-w-0 flex-col gap-0.5">
        <label htmlFor={id} className="text-sm leading-snug text-foreground">
          {label}
        </label>
        {description ? (
          <p id={descriptionId} className="text-xs text-muted-foreground">
            {description}
          </p>
        ) : null}
      </div>
    </div>
  );
}

/**
 * Question paper / marking scheme picker: uploads the chosen file at once
 * (`POST /exam-documents?purpose=`) and keeps only `{ mediaId, fileName }` in
 * the form. Type and size are pre-checked here; the server sniffs again.
 */
export function DocumentAttachField({
  name,
  label,
  icon: Icon,
  purpose,
  required,
  value,
  onChange,
  error,
  disabled,
  onUploadingChange,
}: {
  name: string;
  label: string;
  icon: IconType;
  purpose: ExamDocumentPurpose;
  required?: boolean;
  value: AttachedDocument | null;
  onChange: (next: AttachedDocument | null) => void;
  error?: string | null;
  disabled?: boolean;
  onUploadingChange: (uploading: boolean) => void;
}) {
  const ids = fieldIds(EXAM_FORM_PREFIX, name);
  const fileInput = useRef<HTMLInputElement>(null);
  const upload = useUploadExamDocument(purpose);
  const [localError, setLocalError] = useState<string | null>(null);
  const statusId = useId();
  const shownError = localError ?? error ?? null;
  const helper = "PDF, PNG or JPEG, up to 20 MB.";

  async function handleFile(file: File | undefined) {
    if (!file) return;
    setLocalError(null);
    const problem = documentFileError(file);
    if (problem) {
      setLocalError(problem);
      return;
    }
    onUploadingChange(true);
    try {
      const result = await upload.mutateAsync(file);
      onChange({ mediaId: result.mediaId, fileName: result.fileName || file.name });
    } catch (uploadError) {
      setLocalError(mutationErrorMessage(uploadError));
    } finally {
      onUploadingChange(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  return (
    <FormField ids={ids} label={label} required={required} helper={helper} error={shownError}>
      <div className="flex min-w-0 items-center gap-2">
        <input
          ref={fileInput}
          id={ids.inputId}
          type="file"
          accept={DOCUMENT_ACCEPT}
          className="sr-only"
          tabIndex={-1}
          aria-required={required || undefined}
          aria-invalid={Boolean(shownError)}
          aria-describedby={describedBy(ids, true, Boolean(shownError))}
          disabled={disabled || upload.isPending}
          onChange={(event) => void handleFile(event.target.files?.[0])}
          data-testid={`${EXAM_FORM_PREFIX}-${name}-input`}
        />
        <Button
          type="button"
          variant="outline"
          size="icon"
          className="size-9 shrink-0"
          onClick={() => fileInput.current?.click()}
          disabled={disabled || upload.isPending}
          aria-label={value ? `Replace ${label.toLowerCase()}` : `Attach ${label.toLowerCase()}`}
          data-testid={`${EXAM_FORM_PREFIX}-${name}-attach`}
        >
          <Paperclip aria-hidden="true" />
        </Button>
        <div
          className={cn(
            "flex h-9 min-w-0 flex-1 items-center gap-2 rounded-lg border border-input px-2.5 text-sm",
            shownError && "border-destructive",
            disabled && "opacity-50"
          )}
        >
          {upload.isPending ? (
            <Loader2 className="size-4 shrink-0 animate-spin text-muted-foreground" aria-hidden="true" />
          ) : (
            <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          )}
          <span
            id={statusId}
            role="status"
            className={cn("min-w-0 flex-1 truncate", value ? "text-foreground" : "text-muted-foreground")}
            data-testid={`${EXAM_FORM_PREFIX}-${name}-file`}
          >
            {upload.isPending ? "Uploading…" : value ? value.fileName : "No file attached"}
          </span>
          {value && !required && !disabled ? (
            <button
              type="button"
              onClick={() => onChange(null)}
              className="rounded text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
              aria-label={`Remove ${label.toLowerCase()}`}
              data-testid={`${EXAM_FORM_PREFIX}-${name}-remove`}
            >
              <X className="size-4" aria-hidden="true" />
            </button>
          ) : null}
        </div>
      </div>
    </FormField>
  );
}

/** Removable chips + a native month/date input to add values (payment criteria). */
export function ValueListPicker({
  name,
  label,
  inputType,
  values,
  onChange,
  formatValue,
  error,
}: {
  name: string;
  label: string;
  inputType: "month" | "date";
  values: string[];
  onChange: (next: string[]) => void;
  formatValue: (value: string) => string;
  error?: string | null;
}) {
  const ids = fieldIds(EXAM_FORM_PREFIX, name);
  const [draft, setDraft] = useState("");
  const add = () => {
    if (!draft || values.includes(draft)) return;
    onChange([...values, draft].sort());
    setDraft("");
  };
  return (
    <FormField ids={ids} label={label} required error={error}>
      <div className="flex flex-wrap items-center gap-2">
        <Input
          id={ids.inputId}
          type={inputType}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              add();
            }
          }}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy(ids, false, Boolean(error))}
          className="h-9 w-auto min-w-0 flex-1 sm:max-w-56"
          data-testid={`${EXAM_FORM_PREFIX}-${name}-input`}
        />
        <Button type="button" variant="outline" onClick={add} disabled={!draft} data-testid={`${EXAM_FORM_PREFIX}-${name}-add`}>
          Add
        </Button>
      </div>
      {values.length > 0 ? (
        <ul className="flex flex-wrap gap-1.5" aria-label={`Selected ${label.toLowerCase()}`} data-testid={`${EXAM_FORM_PREFIX}-${name}-chips`}>
          {values.map((value) => (
            <li key={value} className="inline-flex items-center gap-1 rounded-full bg-muted px-2.5 py-0.5 text-xs text-foreground">
              {formatValue(value)}
              <button
                type="button"
                onClick={() => onChange(values.filter((entry) => entry !== value))}
                className="rounded-full text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
                aria-label={`Remove ${formatValue(value)}`}
              >
                <X className="size-3" aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </FormField>
  );
}
