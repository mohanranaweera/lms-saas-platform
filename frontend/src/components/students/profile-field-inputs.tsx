"use client";

import { useMemo } from "react";
import type { UseFormRegisterReturn } from "react-hook-form";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { ProfileFieldDescriptor, StudentProfileFieldEntry } from "@/lib/api/student-profile-fields";
import {
  countryOptions,
  formatProfileFieldValue,
  GROUP_LABELS,
  groupFields,
  guardianNameField,
  guardianPhoneField,
} from "@/lib/validation/student-profile-fields";

const SELECT_CLASS =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive dark:bg-input/30";

interface ProfileFieldInputsProps {
  fields: ProfileFieldDescriptor[];
  /** Prefix for element ids, unique per form on the page. */
  idPrefix: string;
  /** Registers a form field by its name (see `formFieldNames`). */
  register: (name: string) => UseFormRegisterReturn;
  /** The current error message for a form field name, if any. */
  errorFor: (name: string) => string | undefined;
  /** When `false`, no field is marked optional (staff edits aren't held to required-ness). */
  showRequired?: boolean;
}

/**
 * Renders tenant-configured student profile fields (STU-PROF-01), grouped,
 * one labelled input per value: text, phone, date, year, yes/no, country, or
 * the guardian name + phone pair. Optional fields are labelled "(optional)".
 * Validation is the caller's Zod schema (`buildProfileFieldsSchema`); the
 * backend remains authoritative.
 */
export function ProfileFieldInputs({
  fields,
  idPrefix,
  register,
  errorFor,
  showRequired = true,
}: ProfileFieldInputsProps) {
  const groups = groupFields(fields);
  const showGroupHeadings = groups.length > 1;

  return (
    <div className="flex flex-col gap-6">
      {groups.map(({ group, fields: groupFieldsList }) => (
        <fieldset key={group} className="flex flex-col gap-4">
          <legend className={showGroupHeadings ? "mb-1 text-sm font-medium text-foreground" : "sr-only"}>
            {GROUP_LABELS[group]}
          </legend>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {groupFieldsList.map((field) =>
              field.type === "GUARDIAN" ? (
                <GuardianInputs
                  key={field.key}
                  field={field}
                  idPrefix={idPrefix}
                  register={register}
                  errorFor={errorFor}
                  optional={showRequired && !field.required}
                />
              ) : (
                <SingleInput
                  key={field.key}
                  field={field}
                  name={field.key}
                  label={field.label}
                  idPrefix={idPrefix}
                  register={register}
                  errorFor={errorFor}
                  optional={showRequired && !field.required}
                />
              )
            )}
          </div>
        </fieldset>
      ))}
    </div>
  );
}

interface InputProps {
  field: ProfileFieldDescriptor;
  idPrefix: string;
  register: (name: string) => UseFormRegisterReturn;
  errorFor: (name: string) => string | undefined;
  optional: boolean;
}

function GuardianInputs({ field, ...rest }: InputProps) {
  return (
    <>
      <SingleInput
        {...rest}
        field={{ ...field, type: "TEXT" }}
        name={guardianNameField(field.key)}
        label={`${field.label} name`}
      />
      <SingleInput
        {...rest}
        field={{ ...field, type: "PHONE", maxLength: 50 }}
        name={guardianPhoneField(field.key)}
        label={`${field.label} phone`}
      />
    </>
  );
}

function SingleInput({
  field,
  name,
  label,
  idPrefix,
  register,
  errorFor,
  optional,
}: InputProps & { name: string; label: string }) {
  const id = `${idPrefix}-${name}`;
  const errorId = `${id}-error`;
  const error = errorFor(name);
  const common = {
    id,
    "aria-invalid": error ? true : undefined,
    "aria-describedby": error ? errorId : undefined,
    ...register(name),
  };

  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>
        {label}
        {optional ? <span className="font-normal text-muted-foreground"> (optional)</span> : null}
      </Label>
      {field.type === "BOOLEAN" ? (
        <select className={SELECT_CLASS} {...common}>
          <option value="">Not specified</option>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      ) : field.type === "COUNTRY" ? (
        <CountrySelect {...common} />
      ) : (
        <Input
          type={field.type === "PHONE" ? "tel" : field.type === "DATE" ? "date" : "text"}
          inputMode={field.type === "YEAR" ? "numeric" : undefined}
          maxLength={field.type === "TEXT" ? field.maxLength : undefined}
          autoComplete="off"
          {...common}
        />
      )}
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function CountrySelect(props: React.ComponentProps<"select">) {
  const options = useMemo(() => countryOptions(), []);
  return (
    <select className={SELECT_CLASS} {...props}>
      <option value="">Not specified</option>
      {options.map((option) => (
        <option key={option.code} value={option.code}>
          {option.name}
        </option>
      ))}
    </select>
  );
}

/** Read-only, grouped display of stored profile field values (view-only staff). */
export function ProfileFieldValuesList({ fields }: { fields: StudentProfileFieldEntry[] }) {
  return (
    <div className="flex flex-col gap-4">
      {groupFields(fields).map(({ group, fields: groupFieldsList }) => (
        <section key={group} aria-label={GROUP_LABELS[group]} className="flex flex-col gap-2">
          <h4 className="text-sm font-medium text-foreground">{GROUP_LABELS[group]}</h4>
          <dl className="grid grid-cols-1 gap-x-3 gap-y-2 text-sm sm:grid-cols-[minmax(10rem,auto)_1fr]">
            {groupFieldsList.map((field) => (
              <div key={field.key} className="contents">
                <dt className="font-medium text-muted-foreground">{field.label}</dt>
                <dd className="break-words text-foreground">{formatProfileFieldValue(field)}</dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
    </div>
  );
}
