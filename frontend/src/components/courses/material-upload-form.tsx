"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, UploadCloud } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useCreateMaterial } from "@/lib/api/materials";
import { isApiClientError } from "@/lib/api/error";
import { cn } from "@/lib/utils";
import {
  ACCEPTED_MATERIAL_MIME_TYPES,
  MATERIAL_UPLOAD_DEFAULT_VALUES,
  MATERIAL_UPLOAD_HELPER_TEXT,
  materialUploadSchema,
  toMaterialCreateRequest,
  type MaterialUploadFormValues,
} from "@/lib/validation/material";

interface MaterialUploadFormProps {
  courseId: string;
  moduleId: string;
  lessonId: string;
  /** Disables the whole form while a sibling reorder is in flight (mirrors every other control in this lesson's materials list). */
  disabled?: boolean;
}

/**
 * Upload form for a single lesson's materials — dashed dropzone (drag-over
 * supported, plus a "Browse" button triggering a visually-hidden native
 * `<input type="file">`), RHF + Zod validation
 * (`lib/validation/material.ts`), and a single `role="alert"` region for
 * both a client-side Zod failure and a backend rejection (413/415/503 etc.)
 * — a second failed attempt replaces the previous message rather than
 * stacking, and focus moves to the alert on each new failure (plan §12; a
 * new requirement, kept local to this form only — not a change to the
 * shared `ErrorState`/`AlertDialog` focus behavior).
 *
 * No determinate upload progress bar: the browser `fetch` API used by
 * `apiFetch` doesn't expose multipart upload progress without an XHR
 * dependency, so a busy spinner + "Uploading…" text is the whole indicator
 * while `useCreateMaterial` is pending (per module brief — out of scope to
 * add XHR just for a progress bar).
 */
export function MaterialUploadForm({
  courseId,
  moduleId,
  lessonId,
  disabled: reorderBusy = false,
}: MaterialUploadFormProps) {
  const idPrefix = useId();
  const createMutation = useCreateMaterial(courseId, moduleId, lessonId);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  // Tracked in local state (rather than RHF's `watch()`) purely to render
  // the "Selected: <filename>" line below the dropzone without opting this
  // component out of React Compiler memoization — `setValue` below still
  // keeps the form's own `file` field (and its Zod validation) in sync.
  const [selectedFile, setSelectedFile] = useState<File | undefined>(undefined);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const errorRef = useRef<HTMLDivElement>(null);
  // Remounts the native `<input type="file">` after a successful upload so
  // its browser-internal value resets too — reading/writing
  // `fileInputRef.current.value` from inside the async submit handler below
  // would be a ref access outside a direct event-handler/effect context
  // (flagged by the React Compiler's ref-safety lint rule), so a `key` bump
  // is used instead of a ref mutation.
  const [fileInputKey, setFileInputKey] = useState(0);

  const {
    register,
    handleSubmit,
    setValue,
    reset,
    formState: { errors },
  } = useForm<MaterialUploadFormValues>({
    resolver: zodResolver(materialUploadSchema),
    defaultValues: MATERIAL_UPLOAD_DEFAULT_VALUES,
  });

  // Move focus to the alert whenever a new failure message appears (client
  // Zod failure or backend rejection alike) so it's announced/reachable
  // immediately — a second failed attempt overwrites `submitError`, so this
  // effect (keyed on the message) also re-fires and re-focuses on a repeat
  // failure.
  useEffect(() => {
    if (submitError) {
      errorRef.current?.focus();
    }
  }, [submitError]);

  const busy = createMutation.isPending || reorderBusy;

  const handleFiles = (files: FileList | null) => {
    const file = files?.[0];
    if (file) {
      setValue("file", file, { shouldValidate: true });
      setSelectedFile(file);
    }
  };

  const submit = handleSubmit(
    async (values) => {
      setSubmitError(null);
      try {
        await createMutation.mutateAsync(toMaterialCreateRequest(values));
        reset(MATERIAL_UPLOAD_DEFAULT_VALUES);
        setSelectedFile(undefined);
        setFileInputKey((key) => key + 1);
      } catch (error) {
        setSubmitError(
          isApiClientError(error)
            ? error.message
            : "Couldn't upload this material. Please try again."
        );
      }
    },
    (formErrors) => {
      // RHF validation (Zod) failure — surface the actual validation message
      // (e.g. an unsupported-file-type or too-large-file rejection) through
      // the exact same alert region a backend rejection (415/413/503) would
      // use, per plan §12: a MIME-mismatch caught client-side and the
      // identical rejection caught server-side must render identically, not
      // as two different UIs.
      const fileMessage =
        typeof formErrors.file?.message === "string" ? formErrors.file.message : undefined;
      const titleMessage =
        typeof formErrors.title?.message === "string" ? formErrors.title.message : undefined;
      setSubmitError(fileMessage ?? titleMessage ?? "Please fix the highlighted fields and try again.");
    }
  );

  return (
    <div className="rounded-md border border-dashed border-border p-3">
      <h5 className="mb-2 text-xs font-medium text-foreground">Upload material</h5>
      <form className="flex flex-col gap-3" noValidate aria-busy={busy} onSubmit={submit}>
        <span role="status" aria-live="polite" className="sr-only">
          {busy ? "Uploading…" : ""}
        </span>

        <fieldset disabled={busy} className="flex flex-col gap-3">
          <legend className="sr-only">Upload a lesson material</legend>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-title`}>Title</Label>
            <Input
              id={`${idPrefix}-title`}
              aria-invalid={!!errors.title}
              aria-describedby={errors.title ? `${idPrefix}-title-error` : undefined}
              {...register("title")}
            />
            {errors.title ? (
              <p id={`${idPrefix}-title-error`} role="alert" className="text-xs text-destructive">
                {errors.title.message}
              </p>
            ) : null}
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-file`}>File</Label>
            <div
              className={cn(
                "flex flex-col items-center gap-2 rounded-md border-2 border-dashed border-border px-4 py-6 text-center transition-colors",
                isDragOver && "border-primary bg-primary/5"
              )}
              onDragOver={(event) => {
                event.preventDefault();
                if (!busy) setIsDragOver(true);
              }}
              onDragLeave={() => setIsDragOver(false)}
              onDrop={(event) => {
                event.preventDefault();
                setIsDragOver(false);
                if (!busy) handleFiles(event.dataTransfer.files);
              }}
            >
              <UploadCloud className="size-5 text-muted-foreground" aria-hidden="true" />
              <p className="text-xs text-muted-foreground">Drag and drop a file here, or</p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
              >
                Browse files
              </Button>
              <input
                key={fileInputKey}
                ref={fileInputRef}
                id={`${idPrefix}-file`}
                type="file"
                className="sr-only"
                accept={ACCEPTED_MATERIAL_MIME_TYPES.join(",")}
                aria-invalid={!!errors.file}
                aria-describedby={[
                  `${idPrefix}-file-helper`,
                  errors.file ? `${idPrefix}-file-error` : undefined,
                ]
                  .filter(Boolean)
                  .join(" ")}
                onChange={(event) => handleFiles(event.target.files)}
              />
              {selectedFile ? (
                <p className="text-xs font-medium text-foreground">Selected: {selectedFile.name}</p>
              ) : null}
              <span role="status" aria-live="polite" className="sr-only">
                {isDragOver ? "File ready to drop" : ""}
              </span>
            </div>
            <p id={`${idPrefix}-file-helper`} className="text-xs text-muted-foreground">
              {MATERIAL_UPLOAD_HELPER_TEXT}
            </p>
            {errors.file ? (
              <p id={`${idPrefix}-file-error`} role="alert" className="text-xs text-destructive">
                {errors.file.message as string}
              </p>
            ) : null}
          </div>

          <div>
            <Button type="submit" size="sm" disabled={busy} aria-busy={busy}>
              {busy ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                  Uploading…
                </>
              ) : (
                "Upload material"
              )}
            </Button>
          </div>
        </fieldset>
      </form>

      {submitError ? (
        <div
          ref={errorRef}
          role="alert"
          tabIndex={-1}
          className="mt-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive outline-none"
        >
          {submitError}
        </div>
      ) : null}
    </div>
  );
}
