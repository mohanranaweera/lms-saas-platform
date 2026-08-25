"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, UploadCloud } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useUploadSlip, type PaymentSlipResponse } from "@/lib/api/payment-slips";
import { isApiClientError } from "@/lib/api/error";
import { cn } from "@/lib/utils";
import {
  ACCEPTED_SLIP_MIME_TYPES,
  SLIP_UPLOAD_DEFAULT_VALUES,
  SLIP_UPLOAD_HELPER_TEXT,
  slipUploadSchema,
  toSlipUploadInput,
  type SlipUploadFormValues,
} from "@/lib/validation/payment-slip";

interface SlipUploadFormProps {
  orderId: string;
  /**
   * Called once with the backend response on a successful upload. The
   * caller (this route's `page.tsx`) is responsible for replacing this form
   * with the success panel — this component never redirects or shows its
   * own "submitted" copy itself, matching the module's "stay on the page,
   * never auto-redirect" requirement.
   */
  onUploaded: (slip: PaymentSlipResponse) => void;
}

/**
 * Payment slip upload form — mirrors `material-upload-form.tsx`'s exact
 * dropzone/drag-drop/browse-button/accessible-label/helper-text/single-
 * `role="alert"` structure (SLIP-1), with a `referenceNumber` text field in
 * place of `title`. A backend `413 PAYLOAD_TOO_LARGE`/`415
 * UNSUPPORTED_MEDIA_TYPE` rejection renders through the exact same alert
 * region as a client-side Zod failure — no second, bespoke error path.
 *
 * No determinate upload progress bar, for the same reason
 * `material-upload-form.tsx` has none (see that file's doc comment).
 */
export function SlipUploadForm({ orderId, onUploaded }: SlipUploadFormProps) {
  const idPrefix = useId();
  const uploadMutation = useUploadSlip(orderId);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | undefined>(undefined);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  const {
    register,
    handleSubmit,
    setValue,
    setError,
    formState: { errors },
  } = useForm<SlipUploadFormValues>({
    resolver: zodResolver(slipUploadSchema),
    defaultValues: SLIP_UPLOAD_DEFAULT_VALUES,
  });

  useEffect(() => {
    if (submitError) {
      errorRef.current?.focus();
    }
  }, [submitError]);

  const busy = uploadMutation.isPending;

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
        const slip = await uploadMutation.mutateAsync(toSlipUploadInput(values));
        onUploaded(slip);
      } catch (error) {
        if (isApiClientError(error)) {
          let mappedToField = false;
          for (const fieldError of error.fieldErrors) {
            if (fieldError.field.endsWith("referenceNumber")) {
              setError("referenceNumber", { type: "server", message: fieldError.message });
              mappedToField = true;
            }
          }
          if (!mappedToField) {
            setSubmitError(error.message);
          }
          return;
        }
        setSubmitError("Couldn't upload this payment slip. Please try again.");
      }
    },
    (formErrors) => {
      const fileMessage =
        typeof formErrors.file?.message === "string" ? formErrors.file.message : undefined;
      const referenceMessage =
        typeof formErrors.referenceNumber?.message === "string"
          ? formErrors.referenceNumber.message
          : undefined;
      setSubmitError(
        fileMessage ?? referenceMessage ?? "Please fix the highlighted fields and try again."
      );
    }
  );

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <form className="flex flex-col gap-4" noValidate aria-busy={busy} onSubmit={submit}>
        <span role="status" aria-live="polite" className="sr-only">
          {busy ? "Uploading…" : ""}
        </span>

        <fieldset disabled={busy} className="flex flex-col gap-4">
          <legend className="sr-only">Upload a payment slip</legend>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-reference`}>Reference number</Label>
            <Input
              id={`${idPrefix}-reference`}
              autoComplete="off"
              aria-invalid={!!errors.referenceNumber}
              aria-describedby={
                errors.referenceNumber ? `${idPrefix}-reference-error` : undefined
              }
              {...register("referenceNumber")}
            />
            {errors.referenceNumber ? (
              <p
                id={`${idPrefix}-reference-error`}
                role="alert"
                className="text-xs text-destructive"
              >
                {errors.referenceNumber.message}
              </p>
            ) : null}
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-file`}>Slip file</Label>
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
                ref={fileInputRef}
                id={`${idPrefix}-file`}
                type="file"
                className="sr-only"
                accept={ACCEPTED_SLIP_MIME_TYPES.join(",")}
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
              {SLIP_UPLOAD_HELPER_TEXT}
            </p>
            {errors.file ? (
              <p id={`${idPrefix}-file-error`} role="alert" className="text-xs text-destructive">
                {errors.file.message as string}
              </p>
            ) : null}
          </div>

          <div>
            <Button type="submit" disabled={busy} aria-busy={busy}>
              {busy ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                  Uploading…
                </>
              ) : (
                "Submit payment slip"
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
          className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive outline-none"
        >
          {submitError}
        </div>
      ) : null}
    </div>
  );
}
