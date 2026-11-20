"use client";

import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ErrorState } from "@/components/states/error-state";
import { LiveRegion } from "@/components/ui/live-region";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import {
  useUpdateStudentProfileFieldSettings,
  type StudentProfileFieldSetting,
  type StudentProfileFieldSettingChange,
  type StudentProfileFieldSettings,
} from "@/lib/api/student-profile-fields";
import { GROUP_LABELS, groupFields } from "@/lib/validation/student-profile-fields";

type Flag = "enabled" | "required" | "collectAtRegistration";

interface RowValues {
  enabled: boolean;
  required: boolean;
  collectAtRegistration: boolean;
}

interface FormValues {
  fields: Record<string, RowValues>;
}

const rowSchema = z.object({
  enabled: z.boolean(),
  required: z.boolean(),
  collectAtRegistration: z.boolean(),
});

/** Mirrors the backend rule (and V59 CHECKs): required / asked at registration imply enabled. */
const schema = z.object({
  fields: z.record(z.string(), rowSchema).superRefine((rows, ctx) => {
    for (const [key, row] of Object.entries(rows)) {
      if ((row.required || row.collectAtRegistration) && !row.enabled) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "A field must be enabled to be required or asked at registration.",
          path: [key],
        });
      }
    }
  }),
});

const COLUMNS: Array<{ flag: Flag; label: string }> = [
  { flag: "enabled", label: "Enabled" },
  { flag: "required", label: "Required" },
  { flag: "collectAtRegistration", label: "Ask at registration" },
];

function toFormValues(fields: StudentProfileFieldSetting[]): FormValues {
  return {
    fields: Object.fromEntries(
      fields
        .filter((field) => !field.locked)
        .map((field) => [
          field.key,
          { enabled: field.enabled, required: field.required, collectAtRegistration: field.collectAtRegistration },
        ])
    ),
  };
}

function toChanges(initial: FormValues, current: FormValues): StudentProfileFieldSettingChange[] {
  return Object.entries(current.fields)
    .filter(([key, row]) => {
      const before = initial.fields[key];
      return (
        !before ||
        before.enabled !== row.enabled ||
        before.required !== row.required ||
        before.collectAtRegistration !== row.collectAtRegistration
      );
    })
    .map(([key, row]) => ({ key, ...row }));
}

/**
 * The student field settings table (STU-PROF-01). Unchecking Enabled clears
 * Required and Ask at registration; those two can only be ticked on an
 * enabled field. Saves only the rows that changed (the backend keeps every
 * other row as-is) and resets from the backend's response. Email and Full
 * name are always collected and shown locked. `canManage=false` (Read-only
 * Auditor) renders every checkbox disabled with no Save action.
 */
export function StudentFieldSettingsForm({
  settings,
  canManage,
}: {
  settings: StudentProfileFieldSettings;
  canManage: boolean;
}) {
  const mutation = useUpdateStudentProfileFieldSettings();
  const [initial, setInitial] = useState<FormValues>(() => toFormValues(settings.fields));
  const [pageError, setPageError] = useState<{ message: string; code?: string; fieldErrors?: FieldError[] } | null>(
    null
  );
  const [saved, setSaved] = useState(false);

  const {
    control,
    handleSubmit,
    setValue,
    reset,
    formState: { isDirty },
  } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: initial });

  const rows = useWatch({ control, name: "fields" });

  function toggle(key: string, flag: Flag, checked: boolean) {
    setSaved(false);
    const options = { shouldDirty: true } as const;
    setValue(`fields.${key}.${flag}`, checked, options);
    if (flag === "enabled" && !checked) {
      setValue(`fields.${key}.required`, false, options);
      setValue(`fields.${key}.collectAtRegistration`, false, options);
    }
  }

  const onSubmit = handleSubmit(
    async (values) => {
      setPageError(null);
      setSaved(false);
      const changes = toChanges(initial, values);
      if (changes.length === 0) {
        setSaved(true);
        return;
      }
      try {
        const updated = await mutation.mutateAsync(changes);
        const next = toFormValues(updated.fields);
        setInitial(next);
        reset(next);
        setSaved(true);
      } catch (error) {
        if (isApiClientError(error)) {
          setPageError({
            message: error.fieldErrors.length > 0 ? "Some settings couldn't be saved." : error.message,
            code: error.code,
            fieldErrors: error.fieldErrors.length > 0 ? error.fieldErrors : undefined,
          });
          return;
        }
        setPageError({ message: "An unexpected error occurred. Please try again." });
      }
    },
    () => setPageError({ message: "A field must be enabled to be required or asked at registration." })
  );

  const addressOff = !rows.ADDRESS_LINE_1?.enabled || !rows.CITY?.enabled;

  return (
    <form className="flex flex-col gap-4" onSubmit={onSubmit} aria-busy={mutation.isPending} noValidate>
      <LiveRegion
        message={mutation.isPending ? "Saving student field settings…" : saved ? "Student field settings saved." : ""}
      />

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

      <div className="overflow-x-auto rounded-lg border border-border">
        <table className="w-full min-w-[34rem] text-sm">
          <caption className="sr-only">Student profile fields</caption>
          <thead className="bg-muted/40 text-left">
            <tr>
              <th scope="col" className="sticky left-0 bg-muted px-3 py-2 font-medium">
                Field
              </th>
              {COLUMNS.map((column) => (
                <th key={column.flag} scope="col" className="w-32 px-3 py-2 text-center font-medium">
                  {column.label}
                </th>
              ))}
            </tr>
          </thead>
          {groupFields(settings.fields).map(({ group, fields }) => (
            <tbody key={group}>
              <tr className="border-t border-border bg-muted/20">
                <th
                  scope="colgroup"
                  colSpan={4}
                  className="px-3 py-1.5 text-left text-xs font-medium text-muted-foreground"
                >
                  {GROUP_LABELS[group]}
                </th>
              </tr>
              {fields.map((field) => (
                <tr key={field.key} className="border-t border-border">
                  <th scope="row" className="sticky left-0 bg-background px-3 py-2 text-left font-normal">
                    <span className="flex flex-wrap items-center gap-2">
                      {field.label}
                      {field.sensitive ? <Badge variant="outline">Sensitive</Badge> : null}
                      {field.locked ? <span className="text-xs text-muted-foreground">Always collected</span> : null}
                    </span>
                  </th>
                  {COLUMNS.map((column) => {
                    const row = rows[field.key];
                    const checked = field.locked ? true : Boolean(row?.[column.flag]);
                    const disabled =
                      field.locked ||
                      !canManage ||
                      mutation.isPending ||
                      (column.flag !== "enabled" && !row?.enabled);
                    return (
                      <td key={column.flag} className="px-3 py-2 text-center">
                        <input
                          type="checkbox"
                          className="size-4 rounded border-input align-middle disabled:cursor-not-allowed disabled:opacity-50"
                          aria-label={`${field.label}: ${column.label}`}
                          checked={checked}
                          disabled={disabled}
                          onChange={(event) => toggle(field.key, column.flag, event.target.checked)}
                        />
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          ))}
        </table>
      </div>

      {addressOff ? (
        <p className="text-sm text-muted-foreground">
          Address line 1 or City is turned off. Students paying online are still asked for a billing address.
        </p>
      ) : null}

      {canManage ? (
        <div className="sticky bottom-0 flex items-center gap-3 border-t border-border bg-background py-3">
          <Button type="submit" disabled={mutation.isPending || !isDirty}>
            {mutation.isPending ? "Saving…" : "Save changes"}
          </Button>
          {saved && !isDirty ? (
            <span role="status" className="text-sm text-muted-foreground">
              Saved.
            </span>
          ) : null}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">You have view-only access to institute configuration.</p>
      )}
    </form>
  );
}
