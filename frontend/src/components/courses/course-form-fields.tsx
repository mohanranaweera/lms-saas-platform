import type { FieldErrors, UseFormRegister } from "react-hook-form";
import type { CourseStatus } from "@/lib/api/courses";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  ACCESS_DURATION_HELPER_TEXT,
  PRICE_HELPER_TEXT,
  SLUG_HELPER_TEXT,
  type CourseBasicsFormValues,
  type CourseClassificationFormValues,
  type CourseEnrollmentAccessFormValues,
  type CoursePricingFormValues,
  type CourseVisibilityFormValues,
} from "@/lib/validation/course";
import { COURSE_STATUS_LABELS } from "@/components/courses/course-status-badge";

/**
 * Course Builder step field groups — one component per stepper step, shared
 * between the Teacher create (`new/page.tsx`) and edit
 * (`components/courses/course-edit-form.tsx`, reused by Tenant Admin's
 * detail page) flows. Each accepts the specific slice of RHF's `register`/
 * `errors` its own step's schema defines; a caller's larger form (e.g. the
 * full `CourseCreateFormValues`) is structurally compatible since its field
 * set is a superset of each individual step's schema.
 */

interface StepFieldsProps<T extends Record<string, unknown>> {
  register: UseFormRegister<T>;
  errors: FieldErrors<T>;
  disabled?: boolean;
}

function fieldIds(idPrefix: string, name: string) {
  return {
    inputId: `${idPrefix}-${name}`,
    errorId: `${idPrefix}-${name}-error`,
    helperId: `${idPrefix}-${name}-helper`,
  };
}

export function CourseBasicsFields({
  register,
  errors,
  disabled,
  idPrefix,
}: StepFieldsProps<CourseBasicsFormValues> & { idPrefix: string }) {
  const nameIds = fieldIds(idPrefix, "name");
  const slugIds = fieldIds(idPrefix, "slug");
  const categoryIds = fieldIds(idPrefix, "category");
  const descriptionIds = fieldIds(idPrefix, "description");

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={nameIds.inputId}>Course name</Label>
        <Input
          id={nameIds.inputId}
          disabled={disabled}
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? nameIds.errorId : undefined}
          {...register("name")}
        />
        {errors.name ? (
          <p id={nameIds.errorId} role="alert" className="text-xs text-destructive">
            {errors.name.message as string}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={slugIds.inputId}>Slug</Label>
        <Input
          id={slugIds.inputId}
          disabled={disabled}
          autoComplete="off"
          aria-invalid={!!errors.slug}
          aria-describedby={[slugIds.helperId, errors.slug ? slugIds.errorId : undefined]
            .filter(Boolean)
            .join(" ")}
          {...register("slug")}
        />
        <p id={slugIds.helperId} className="text-xs text-muted-foreground">
          {SLUG_HELPER_TEXT}
        </p>
        {errors.slug ? (
          <p id={slugIds.errorId} role="alert" className="text-xs text-destructive">
            {errors.slug.message as string}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={categoryIds.inputId}>Category</Label>
        <Input
          id={categoryIds.inputId}
          disabled={disabled}
          aria-invalid={!!errors.category}
          aria-describedby={errors.category ? categoryIds.errorId : undefined}
          {...register("category")}
        />
        {errors.category ? (
          <p id={categoryIds.errorId} role="alert" className="text-xs text-destructive">
            {errors.category.message as string}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={descriptionIds.inputId}>
          Description <span className="font-normal text-muted-foreground">(optional)</span>
        </Label>
        <textarea
          id={descriptionIds.inputId}
          disabled={disabled}
          rows={4}
          aria-invalid={!!errors.description}
          aria-describedby={errors.description ? descriptionIds.errorId : undefined}
          className="w-full rounded-lg border border-input bg-transparent px-2.5 py-1.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30"
          {...register("description")}
        />
        {errors.description ? (
          <p id={descriptionIds.errorId} role="alert" className="text-xs text-destructive">
            {errors.description.message as string}
          </p>
        ) : null}
      </div>
    </div>
  );
}

export function CourseClassificationFields({
  register,
  errors,
  disabled,
  idPrefix,
}: StepFieldsProps<CourseClassificationFormValues> & { idPrefix: string }) {
  const fields: Array<{ name: "subject" | "stream" | "grade" | "academicYear"; label: string }> = [
    { name: "subject", label: "Subject" },
    { name: "stream", label: "Stream" },
    { name: "grade", label: "Grade" },
    { name: "academicYear", label: "Academic year" },
  ];

  return (
    <div className="flex flex-col gap-4">
      {fields.map((field) => {
        const ids = fieldIds(idPrefix, field.name);
        const fieldError = errors[field.name];
        return (
          <div key={field.name} className="flex flex-col gap-1.5">
            <Label htmlFor={ids.inputId}>
              {field.label} <span className="font-normal text-muted-foreground">(optional)</span>
            </Label>
            <Input
              id={ids.inputId}
              disabled={disabled}
              aria-invalid={!!fieldError}
              aria-describedby={fieldError ? ids.errorId : undefined}
              {...register(field.name)}
            />
            {fieldError ? (
              <p id={ids.errorId} role="alert" className="text-xs text-destructive">
                {fieldError.message as string}
              </p>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

export function CoursePricingFields({
  register,
  errors,
  disabled,
  idPrefix,
}: StepFieldsProps<CoursePricingFormValues> & { idPrefix: string }) {
  const ids = fieldIds(idPrefix, "price");
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={ids.inputId}>Price</Label>
      <Input
        id={ids.inputId}
        disabled={disabled}
        inputMode="decimal"
        autoComplete="off"
        aria-invalid={!!errors.price}
        aria-describedby={[ids.helperId, errors.price ? ids.errorId : undefined]
          .filter(Boolean)
          .join(" ")}
        {...register("price")}
      />
      <p id={ids.helperId} className="text-xs text-muted-foreground">
        {PRICE_HELPER_TEXT}
      </p>
      {errors.price ? (
        <p id={ids.errorId} role="alert" className="text-xs text-destructive">
          {errors.price.message as string}
        </p>
      ) : null}
    </div>
  );
}

export function CourseEnrollmentAccessFields({
  register,
  errors,
  disabled,
  idPrefix,
}: StepFieldsProps<CourseEnrollmentAccessFormValues> & { idPrefix: string }) {
  const ruleIds = fieldIds(idPrefix, "enrollmentRule");
  const accessIds = fieldIds(idPrefix, "accessDurationDays");

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={ruleIds.inputId}>
          Enrollment rules <span className="font-normal text-muted-foreground">(optional)</span>
        </Label>
        <textarea
          id={ruleIds.inputId}
          disabled={disabled}
          rows={4}
          aria-invalid={!!errors.enrollmentRule}
          aria-describedby={[
            `${ruleIds.helperId}`,
            errors.enrollmentRule ? ruleIds.errorId : undefined,
          ]
            .filter(Boolean)
            .join(" ")}
          className="w-full rounded-lg border border-input bg-transparent px-2.5 py-1.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30"
          {...register("enrollmentRule")}
        />
        <p id={ruleIds.helperId} className="text-xs text-muted-foreground">
          Free-text notes for staff/students (e.g. prerequisites) — not an enforced rule engine.
        </p>
        {errors.enrollmentRule ? (
          <p id={ruleIds.errorId} role="alert" className="text-xs text-destructive">
            {errors.enrollmentRule.message as string}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={accessIds.inputId}>
          Access duration (days){" "}
          <span className="font-normal text-muted-foreground">(optional)</span>
        </Label>
        <Input
          id={accessIds.inputId}
          disabled={disabled}
          inputMode="numeric"
          autoComplete="off"
          aria-invalid={!!errors.accessDurationDays}
          aria-describedby={[
            accessIds.helperId,
            errors.accessDurationDays ? accessIds.errorId : undefined,
          ]
            .filter(Boolean)
            .join(" ")}
          {...register("accessDurationDays")}
        />
        <p id={accessIds.helperId} className="text-xs text-muted-foreground">
          {ACCESS_DURATION_HELPER_TEXT}
        </p>
        {errors.accessDurationDays ? (
          <p id={accessIds.errorId} role="alert" className="text-xs text-destructive">
            {errors.accessDurationDays.message as string}
          </p>
        ) : null}
      </div>
    </div>
  );
}

const STATUS_OPTIONS: Array<{ value: "DRAFT" | "PRIVATE" | "PUBLIC"; description: string }> = [
  { value: "DRAFT", description: "Not visible to anyone outside your tenant's staff/teachers." },
  { value: "PRIVATE", description: "Not listed publicly; only reachable with a direct link." },
  { value: "PUBLIC", description: "Listed on the public course catalog and enrollable." },
];

export function CourseVisibilityFields({
  errors,
  idPrefix,
  value,
  onChange,
  disabled,
}: {
  errors: FieldErrors<CourseVisibilityFormValues>;
  idPrefix: string;
  value: string;
  onChange: (value: string | null) => void;
  disabled?: boolean;
}) {
  const ids = fieldIds(idPrefix, "status");
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={ids.inputId}>Visibility status</Label>
      <Select value={value} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger
          id={ids.inputId}
          aria-invalid={!!errors.status}
          aria-describedby={errors.status ? ids.errorId : undefined}
          className="w-full sm:w-64"
        >
          <SelectValue placeholder="Select a status">
            {(selected: string | null) =>
              selected ? COURSE_STATUS_LABELS[selected as CourseStatus] : "Select a status"
            }
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          {STATUS_OPTIONS.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {COURSE_STATUS_LABELS[option.value]}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <ul className="flex flex-col gap-0.5 text-xs text-muted-foreground">
        {STATUS_OPTIONS.map((option) => (
          <li key={option.value}>
            <span className="font-medium text-foreground">{COURSE_STATUS_LABELS[option.value]}:</span>{" "}
            {option.description}
          </li>
        ))}
      </ul>
      {errors.status ? (
        <p id={ids.errorId} role="alert" className="text-xs text-destructive">
          {errors.status.message as string}
        </p>
      ) : null}
    </div>
  );
}
