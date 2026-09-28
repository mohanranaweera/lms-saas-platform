"use client";

import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import type { StudentProfileFieldEntry, StudentProfileFields } from "@/lib/api/student-profile-fields";
import {
  buildProfileFieldsSchema,
  formFieldNames,
  toFormFieldName,
  toFormValues,
  toPayloadValues,
  type ProfileFieldFormValues,
} from "@/lib/validation/student-profile-fields";
import { ProfileFieldInputs } from "./profile-field-inputs";

interface ProfileFieldsFormProps {
  fields: StudentProfileFieldEntry[];
  idPrefix: string;
  /** Student self-service: `true`; staff corrections: `false` (mirrors the backend rule). */
  enforceRequired: boolean;
  save: (values: Record<string, unknown>) => Promise<StudentProfileFields>;
  savingLabel: string;
  savedLabel: string;
}

/**
 * Editable tenant-configured profile fields (STU-PROF-01) for one student —
 * shared by the student's own "Additional details" and the staff profile
 * tab. Sends every shown field on save (blank clears it), resets from the
 * backend's response, and maps the backend's `values.<KEY>` field errors
 * back onto the matching inputs.
 */
export function ProfileFieldsForm({
  fields,
  idPrefix,
  enforceRequired,
  save,
  savingLabel,
  savedLabel,
}: ProfileFieldsFormProps) {
  const [pageError, setPageError] = useState<{ message: string; code?: string; fieldErrors?: FieldError[] } | null>(
    null
  );
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const knownNames = useMemo(() => new Set(fields.flatMap(formFieldNames)), [fields]);

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<ProfileFieldFormValues>({
    resolver: zodResolver(buildProfileFieldsSchema(fields, { enforceRequired })),
    defaultValues: toFormValues(fields),
  });

  useEffect(() => {
    reset(toFormValues(fields));
  }, [fields, reset]);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    setSaving(true);
    try {
      const updated = await save(toPayloadValues(fields, values));
      reset(toFormValues(updated.fields));
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        const unmapped: FieldError[] = [];
        for (const fieldError of error.fieldErrors) {
          const name = toFormFieldName(fieldError.field, "values");
          if (name && knownNames.has(name)) {
            setError(name, { type: "server", message: fieldError.message });
          } else {
            unmapped.push(fieldError);
          }
        }
        if (error.fieldErrors.length === 0 || unmapped.length > 0) {
          setPageError({
            message: error.fieldErrors.length === 0 ? error.message : "Some details couldn't be saved.",
            code: error.code,
            fieldErrors: unmapped.length > 0 ? unmapped : undefined,
          });
        }
      } else {
        setPageError({ message: "An unexpected error occurred. Please try again." });
      }
    } finally {
      setSaving(false);
    }
  });

  return (
    <form className="flex flex-col gap-4" onSubmit={onSubmit} aria-busy={saving} noValidate>
      <LiveRegion message={saving ? savingLabel : saved ? savedLabel : ""} />
      {pageError ? (
        <ErrorState
          message={pageError.message}
          code={pageError.code}
          fieldErrors={pageError.fieldErrors}
          onRetry={() => {
            setPageError(null);
            void onSubmit();
          }}
        />
      ) : null}
      <fieldset disabled={saving} className="min-w-0">
        <legend className="sr-only">Additional details</legend>
        <ProfileFieldInputs
          fields={fields}
          idPrefix={idPrefix}
          register={(name) => register(name)}
          errorFor={(name) => errors[name]?.message}
          showRequired={enforceRequired}
        />
      </fieldset>
      <div className="flex items-center gap-3">
        <Button type="submit" disabled={saving || !isDirty}>
          {saving ? "Saving…" : "Save details"}
        </Button>
        {saved && !isDirty ? (
          <span role="status" className="text-sm text-muted-foreground">
            {savedLabel}
          </span>
        ) : null}
      </div>
    </form>
  );
}
