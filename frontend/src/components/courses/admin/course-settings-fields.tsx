"use client";

import { useState, type ReactNode, type RefObject } from "react";
import {
  Controller,
  useWatch,
  type Control,
  type FieldErrors,
  type UseFormRegister,
  type UseFormSetError,
} from "react-hook-form";
import { ChevronDown, ChevronUp, HelpCircle, ImageIcon, MessageCircle, Send } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { FormField, describedBy, fieldIds, nativeSelectClass } from "@/components/courses/admin/form-field";
import { MultiCheckboxSelect, type CheckboxOption } from "@/components/courses/admin/multi-checkbox-select";
import { TagChipInput } from "@/components/courses/admin/tag-chip-input";
import { MoneyInput } from "@/components/courses/admin/money-input";
import type { CourseCreateFormValues } from "@/components/courses/admin/schemas";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";
import { useCourses } from "@/lib/api/courses";
import { useTeachers, type Teacher } from "@/lib/api/teachers";
import {
  PRICING_MODEL_HELP,
  PRICING_MODEL_LABELS,
  pricingModelTakesPrice,
  toMoneyString,
  type CreatablePricingModel,
  type KlassCourseCreateRequest,
  type KlassPricingModel,
} from "@/lib/api/klass-admin";

/**
 * Shared pieces of the Add New Course modal and the course edit form (G3):
 * the field block, option hooks, request mapping and server-error mapping.
 * Nothing here decides access; the backend validates and authorizes every
 * write and its field errors are mapped back onto these fields.
 */

/** Same shape for create and edit. `teacherIds[0]` is the primary teacher. */
export type CourseSettingsFormValues = CourseCreateFormValues;
export type CourseSettingsFieldName = keyof CourseSettingsFormValues;

export const MAX_COVER_BYTES = 5 * 1024 * 1024;
export const COVER_TYPES = ["image/jpeg", "image/png", "image/webp"];

/** Client pre-check only; the server sniffs the real type and enforces the size again. */
export function validateCoverFile(file: File): string | null {
  if (!COVER_TYPES.includes(file.type)) return "Choose a JPEG, PNG or WebP image.";
  if (file.size > MAX_COVER_BYTES) return "The image must be 5 MB or smaller.";
  return null;
}

export const ADVANCED_FIELDS: CourseSettingsFieldName[] = [
  "paymentValidDays",
  "commissionRate",
  "whatsappInviteUrl",
  "telegramInviteUrl",
  "description",
  "tagNames",
  "linkedCourseIds",
];

/** Field order used to pick which invalid field receives focus after a failed submit. */
export const FIELD_ORDER: CourseSettingsFieldName[] = [
  "name",
  "teacherIds",
  "pricingModel",
  "price",
  "enrollmentFee",
  ...ADVANCED_FIELDS,
];

const SERVER_FIELD_ALIASES: Record<string, CourseSettingsFieldName> = {
  name: "name",
  title: "name",
  teacherIds: "teacherIds",
  teacherId: "teacherIds",
  pricingModel: "pricingModel",
  price: "price",
  enrollmentFee: "enrollmentFee",
  paymentValidDays: "paymentValidDays",
  accessDurationDays: "paymentValidDays",
  commissionRate: "commissionRate",
  whatsappInviteUrl: "whatsappInviteUrl",
  telegramInviteUrl: "telegramInviteUrl",
  description: "description",
  tagNames: "tagNames",
  linkedCourseIds: "linkedCourseIds",
};

/**
 * Puts each backend field error (`teacherIds[0]`, `tagNames.name`, ...) on
 * its form field. Returns the fields that got an error and the messages
 * that matched no visible field (show those in the form-level alert).
 */
export function applyServerFieldErrors(
  error: ApiClientError,
  setError: UseFormSetError<CourseSettingsFormValues>
): { mapped: CourseSettingsFieldName[]; unmapped: string[] } {
  const mapped: CourseSettingsFieldName[] = [];
  const unmapped: string[] = [];
  for (const fieldError of error.fieldErrors) {
    const base = fieldError.field.replace(/\[\d+\]$/, "").replace(/\..*$/, "");
    const target = SERVER_FIELD_ALIASES[base];
    if (target) {
      setError(target, { type: "server", message: fieldError.message });
      mapped.push(target);
    } else {
      unmapped.push(fieldError.message);
    }
  }
  return { mapped, unmapped };
}

export function fieldErrorText(
  errors: FieldErrors<CourseSettingsFormValues>,
  field: CourseSettingsFieldName
): string | null {
  const message = errors[field]?.message;
  return typeof message === "string" ? message : null;
}

/** Add New Course request body. */
export function toCourseCreateRequest(values: CourseSettingsFormValues): KlassCourseCreateRequest {
  const model = values.pricingModel as CreatablePricingModel;
  const body: KlassCourseCreateRequest = {
    name: values.name.trim(),
    teacherIds: values.teacherIds,
    pricingModel: model,
    enrollmentFee: values.enrollmentFee.trim() ? toMoneyString(values.enrollmentFee.trim()) : "0.00",
    tagNames: values.tagNames,
    linkedCourseIds: values.linkedCourseIds,
  };
  if (pricingModelTakesPrice(model)) body.price = toMoneyString(values.price.trim());
  if (values.paymentValidDays.trim()) body.paymentValidDays = Number(values.paymentValidDays.trim());
  if (values.commissionRate.trim()) body.commissionRate = toMoneyString(values.commissionRate.trim());
  if (values.whatsappInviteUrl.trim()) body.whatsappInviteUrl = values.whatsappInviteUrl.trim();
  if (values.telegramInviteUrl.trim()) body.telegramInviteUrl = values.telegramInviteUrl.trim();
  if (values.description.trim()) body.description = values.description.trim();
  return body;
}

export function courseSettingsFieldIds(prefix: string) {
  return {
    name: fieldIds(prefix, "name"),
    teacherIds: fieldIds(prefix, "teachers"),
    pricingModel: fieldIds(prefix, "payment-type"),
    price: fieldIds(prefix, "price"),
    enrollmentFee: fieldIds(prefix, "enrollment-fee"),
    paymentValidDays: fieldIds(prefix, "payment-valid-days"),
    commissionRate: fieldIds(prefix, "commission"),
    whatsappInviteUrl: fieldIds(prefix, "whatsapp"),
    telegramInviteUrl: fieldIds(prefix, "telegram"),
    description: fieldIds(prefix, "description"),
    tagNames: fieldIds(prefix, "tags"),
    linkedCourseIds: fieldIds(prefix, "linked-courses"),
    cover: fieldIds(prefix, "cover"),
  };
}

export type CourseSettingsFieldIds = ReturnType<typeof courseSettingsFieldIds>;

// ---------------------------------------------------------------------------
// Option hooks
// ---------------------------------------------------------------------------

/** Course teacher assignment takes the teacher's user id, not the teacher record id. */
export function teacherAssignmentId(teacher: Teacher): string {
  return teacher.userId;
}

export interface TeacherOptionsResult {
  /** Approved, active teachers (value = user id). */
  options: CheckboxOption[];
  /** Every teacher the list returned, by user id (any status), for labelling assigned inactive teachers. */
  namesByUserId: Map<string, string>;
  loading: boolean;
  loadError: string | null;
  forbidden: boolean;
}

/** Teacher picker options for the course forms (`GET /api/v1/teachers`, staff only). */
export function useTeacherOptions(options?: { enabled?: boolean }): TeacherOptionsResult {
  const enabled = options?.enabled ?? true;
  const query = useTeachers({ enabled });
  const teachers = query.data ?? [];
  const forbidden = query.isError && isApiClientError(query.error) && query.error.status === 403;
  return {
    options: teachers
      .filter((teacher) => teacher.approvalStatus === "APPROVED" && teacher.accountStatus === "ACTIVE")
      .map((teacher) => ({ value: teacherAssignmentId(teacher), label: teacher.name })),
    namesByUserId: new Map(teachers.map((teacher) => [teacherAssignmentId(teacher), teacher.name])),
    loading: enabled && query.isPending,
    loadError: query.isError
      ? forbidden
        ? "You don't have permission to list teachers."
        : "Teachers could not be loaded."
      : null,
    forbidden,
  };
}

/** "Link another course" options: the first page (100) of the caller's visible courses. */
export function useLinkableCourseOptions(options: {
  enabled: boolean;
  excludeCourseId?: string;
  /** Already-linked courses that may fall outside the first page; kept selectable. */
  extra?: CheckboxOption[];
}) {
  const query = useCourses({ size: 100 }, { enabled: options.enabled });
  const fetched = (query.data?.content ?? [])
    .filter((course) => course.id !== options.excludeCourseId)
    .map((course) => ({ value: course.id, label: course.name }));
  const known = new Set(fetched.map((option) => option.value));
  const extra = (options.extra ?? []).filter((option) => !known.has(option.value));
  const total = query.data?.totalElements ?? 0;
  const pageSize = query.data?.content.length ?? 0;
  return {
    options: [...fetched, ...extra],
    loading: query.isPending,
    loadError: query.isError ? "Courses could not be loaded." : null,
    truncated: total > pageSize ? { shown: pageSize, total } : null,
  };
}

// ---------------------------------------------------------------------------
// Field block
// ---------------------------------------------------------------------------

export interface TeacherFieldConfig {
  label: string;
  helper: ReactNode;
  options: CheckboxOption[];
  loading: boolean;
  loadError: string | null;
  emptyText: string;
  /** Teacher list not editable here: show the assigned names read-only instead of the picker. */
  readOnly?: { summary: string; note: string } | null;
  /** Edit mode: explicit "Primary teacher" radio among the selected teachers. */
  primary?: {
    labelFor: (userId: string) => string;
    /** Radio disabled (e.g. Course Coordinator: changing the primary is Tenant Admin only). */
    disabledReason?: string | null;
  } | null;
  /** Options that can't be unticked (e.g. the primary teacher for a Course Coordinator). */
  lockedValues?: string[];
}

export interface PricingFieldConfig {
  /** Models offered in the dropdown, in display order. */
  models: CreatablePricingModel[];
  /** Create: leading "Select a payment type" placeholder option. */
  placeholderOption: boolean;
  /** A legacy model (SESSION/CUSTOM) the course already has: shown read-only, never sent. */
  legacyModel?: KlassPricingModel | null;
  priceHelper: (model: string) => ReactNode;
}

export interface LinkedCoursesFieldConfig {
  options: CheckboxOption[];
  loading: boolean;
  loadError: string | null;
  truncated: { shown: number; total: number } | null;
}

/**
 * The course settings field block shared by Add New Course and the course
 * edit form: Name, Teachers, Payment Type (+ conditional Price), Enrollment
 * Fee, the collapsible additional settings, and a caller-rendered cover slot.
 * Never shows or edits the slug (server-generated).
 *
 * Ids and test ids are `${idPrefix}-<field>` (e.g. `add-course-price`).
 */
export function CourseSettingsFields({
  idPrefix,
  control,
  register,
  errors,
  teachers,
  pricing,
  linkedCourses,
  existingTagNames,
  advancedOpen,
  onToggleAdvanced,
  cover,
}: {
  idPrefix: string;
  control: Control<CourseSettingsFormValues>;
  register: UseFormRegister<CourseSettingsFormValues>;
  errors: FieldErrors<CourseSettingsFormValues>;
  teachers: TeacherFieldConfig;
  pricing: PricingFieldConfig;
  linkedCourses: LinkedCoursesFieldConfig;
  existingTagNames: string[];
  advancedOpen: boolean;
  onToggleAdvanced: () => void;
  cover: ReactNode;
}) {
  const [showTypeHelp, setShowTypeHelp] = useState(false);
  const selectedModel = useWatch({ control, name: "pricingModel" });
  const ids = courseSettingsFieldIds(idPrefix);
  const err = (field: CourseSettingsFieldName) => fieldErrorText(errors, field);
  const helpId = `${idPrefix}-payment-type-help`;
  const advancedId = `${idPrefix}-advanced`;
  const legacy = pricing.legacyModel ?? null;

  return (
    <>
      <FormField ids={ids.name} label="Name" required error={err("name")}>
        <Input
          id={ids.name.inputId}
          maxLength={255}
          autoComplete="off"
          aria-required="true"
          aria-invalid={Boolean(err("name"))}
          aria-describedby={describedBy(ids.name, false, Boolean(err("name")))}
          data-testid={`${idPrefix}-name`}
          {...register("name")}
        />
      </FormField>

      {teachers.readOnly ? (
        <FormField ids={ids.teacherIds} label={teachers.label} helper={teachers.readOnly.note}>
          <Input
            id={ids.teacherIds.inputId}
            readOnly
            value={teachers.readOnly.summary}
            aria-describedby={ids.teacherIds.helperId}
            className="bg-muted/40"
            data-testid={`${idPrefix}-teachers-readonly`}
          />
        </FormField>
      ) : (
        <FormField
          ids={ids.teacherIds}
          label={teachers.label}
          required
          error={err("teacherIds")}
          helper={teachers.helper}
        >
          <Controller
            control={control}
            name="teacherIds"
            render={({ field }) => (
              <>
                <MultiCheckboxSelect
                  ref={field.ref}
                  id={ids.teacherIds.inputId}
                  options={teachers.options}
                  value={field.value}
                  onChange={(next) => field.onChange(next)}
                  onBlur={field.onBlur}
                  placeholder="Select teachers"
                  invalid={Boolean(err("teacherIds"))}
                  describedBy={describedBy(ids.teacherIds, true, Boolean(err("teacherIds")))}
                  loading={teachers.loading}
                  loadError={teachers.loadError}
                  emptyText={teachers.emptyText}
                  disabledValues={teachers.lockedValues}
                  testId={`${idPrefix}-teachers`}
                />
                {teachers.primary && field.value.length > 0 ? (
                  <PrimaryTeacherRadios
                    idPrefix={idPrefix}
                    value={field.value}
                    onChange={(next) => field.onChange(next)}
                    labelFor={teachers.primary.labelFor}
                    disabledReason={teachers.primary.disabledReason ?? null}
                  />
                ) : null}
              </>
            )}
          />
        </FormField>
      )}

      {legacy ? (
        <FormField
          ids={ids.pricingModel}
          label="Payment Type"
          helper="This older payment type can no longer be chosen for new settings. It stays as it is."
        >
          <Input
            id={ids.pricingModel.inputId}
            readOnly
            value={PRICING_MODEL_LABELS[legacy]}
            aria-describedby={ids.pricingModel.helperId}
            className="bg-muted/40"
            data-testid={`${idPrefix}-payment-type-readonly`}
          />
        </FormField>
      ) : (
        <FormField
          ids={ids.pricingModel}
          label="Payment Type"
          required
          error={err("pricingModel")}
          helper={
            selectedModel && selectedModel in PRICING_MODEL_HELP
              ? PRICING_MODEL_HELP[selectedModel as CreatablePricingModel]
              : "How students pay for this course's sessions."
          }
          labelAdornment={
            <button
              type="button"
              onClick={() => setShowTypeHelp((current) => !current)}
              aria-expanded={showTypeHelp}
              aria-controls={helpId}
              aria-label="Explain payment types"
              className="rounded text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50"
            >
              <HelpCircle className="size-4" aria-hidden="true" />
            </button>
          }
        >
          <select
            id={ids.pricingModel.inputId}
            className={nativeSelectClass}
            aria-required="true"
            aria-invalid={Boolean(err("pricingModel"))}
            aria-describedby={describedBy(ids.pricingModel, true, Boolean(err("pricingModel")))}
            data-testid={`${idPrefix}-payment-type`}
            {...register("pricingModel")}
          >
            {pricing.placeholderOption ? <option value="">Select a payment type</option> : null}
            {pricing.models.map((model) => (
              <option key={model} value={model}>
                {PRICING_MODEL_LABELS[model]}
              </option>
            ))}
          </select>
          <dl
            id={helpId}
            hidden={!showTypeHelp}
            className="mt-1 flex flex-col gap-1.5 rounded-lg border border-border bg-muted/30 p-3 text-xs"
          >
            {pricing.models.map((model) => (
              <div key={model}>
                <dt className="font-medium text-foreground">{PRICING_MODEL_LABELS[model]}</dt>
                <dd className="text-muted-foreground">{PRICING_MODEL_HELP[model]}</dd>
              </div>
            ))}
          </dl>
        </FormField>
      )}

      {!legacy && pricingModelTakesPrice(selectedModel) ? (
        <FormField
          ids={ids.price}
          label={selectedModel === "MONTHLY" ? "Monthly fee" : "Course fee"}
          required
          error={err("price")}
          helper={pricing.priceHelper(selectedModel)}
        >
          <Controller
            control={control}
            name="price"
            render={({ field }) => (
              <MoneyInput
                ref={field.ref}
                id={ids.price.inputId}
                value={field.value}
                onChange={field.onChange}
                onBlur={field.onBlur}
                invalid={Boolean(err("price"))}
                describedBy={describedBy(ids.price, true, Boolean(err("price")))}
                testId={`${idPrefix}-price`}
                required
              />
            )}
          />
        </FormField>
      ) : null}

      <FormField
        ids={ids.enrollmentFee}
        label="Enrollment Fee"
        error={err("enrollmentFee")}
        helper={
          <>
            Enrollment fee for the course.{" "}
            <span className="font-medium text-foreground">
              Informational — enrollment is free; this is never charged.
            </span>
          </>
        }
      >
        <Controller
          control={control}
          name="enrollmentFee"
          render={({ field }) => (
            <MoneyInput
              ref={field.ref}
              id={ids.enrollmentFee.inputId}
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              invalid={Boolean(err("enrollmentFee"))}
              describedBy={describedBy(ids.enrollmentFee, true, Boolean(err("enrollmentFee")))}
              testId={`${idPrefix}-enrollment-fee`}
            />
          )}
        />
      </FormField>

      <button
        type="button"
        onClick={onToggleAdvanced}
        aria-expanded={advancedOpen}
        aria-controls={advancedId}
        className="inline-flex w-fit items-center gap-1 text-sm font-semibold tracking-wide text-primary uppercase outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring/50"
        data-testid={`${idPrefix}-toggle-advanced`}
      >
        {advancedOpen ? <ChevronUp className="size-4" aria-hidden="true" /> : <ChevronDown className="size-4" aria-hidden="true" />}
        {advancedOpen ? "Hide additional settings" : "Show additional settings"}
      </button>

      {/* Kept mounted while hidden so entered values are retained. */}
      <div id={advancedId} hidden={!advancedOpen} className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField
            ids={ids.paymentValidDays}
            label="Payment Valid Days"
            error={err("paymentValidDays")}
            helper="Days paid access lasts after payment is confirmed (full-course access, or a paid month, module or session). Leave blank for no limit."
          >
            <Input
              id={ids.paymentValidDays.inputId}
              type="number"
              inputMode="numeric"
              min={0}
              step={1}
              aria-invalid={Boolean(err("paymentValidDays"))}
              aria-describedby={describedBy(ids.paymentValidDays, true, Boolean(err("paymentValidDays")))}
              data-testid={`${idPrefix}-payment-valid-days`}
              {...register("paymentValidDays")}
            />
          </FormField>
          <FormField
            ids={ids.commissionRate}
            label="Institute Commission"
            error={err("commissionRate")}
            helper="Commission percentage for the institute"
          >
            <div className="relative">
              <Input
                id={ids.commissionRate.inputId}
                type="number"
                inputMode="decimal"
                min={0}
                max={100}
                step="0.01"
                className="pr-7"
                aria-invalid={Boolean(err("commissionRate"))}
                aria-describedby={describedBy(ids.commissionRate, true, Boolean(err("commissionRate")))}
                data-testid={`${idPrefix}-commission`}
                {...register("commissionRate")}
              />
              <span aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-sm text-muted-foreground">
                %
              </span>
            </div>
          </FormField>
        </div>

        <FormField ids={ids.whatsappInviteUrl} label="Whatsapp Invitation Link" error={err("whatsappInviteUrl")}>
          <div className="relative">
            <MessageCircle aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-green-600" />
            <Input
              id={ids.whatsappInviteUrl.inputId}
              type="url"
              inputMode="url"
              placeholder="https://chat.whatsapp.com/…"
              className="pl-8"
              aria-invalid={Boolean(err("whatsappInviteUrl"))}
              aria-describedby={describedBy(ids.whatsappInviteUrl, false, Boolean(err("whatsappInviteUrl")))}
              data-testid={`${idPrefix}-whatsapp`}
              {...register("whatsappInviteUrl")}
            />
          </div>
        </FormField>

        <FormField ids={ids.telegramInviteUrl} label="Telegram Invitation Link" error={err("telegramInviteUrl")}>
          <div className="relative">
            <Send aria-hidden="true" className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-sky-600" />
            <Input
              id={ids.telegramInviteUrl.inputId}
              type="url"
              inputMode="url"
              placeholder="https://t.me/…"
              className="pl-8"
              aria-invalid={Boolean(err("telegramInviteUrl"))}
              aria-describedby={describedBy(ids.telegramInviteUrl, false, Boolean(err("telegramInviteUrl")))}
              data-testid={`${idPrefix}-telegram`}
              {...register("telegramInviteUrl")}
            />
          </div>
        </FormField>

        <FormField ids={ids.description} label="Description" error={err("description")}>
          <Textarea
            id={ids.description.inputId}
            rows={4}
            aria-describedby={describedBy(ids.description, false, Boolean(err("description")))}
            data-testid={`${idPrefix}-description`}
            {...register("description")}
          />
        </FormField>

        <FormField
          ids={ids.tagNames}
          label="Tags"
          error={err("tagNames")}
          helper="Select existing tags or create new ones, separated by commas or Enter."
        >
          <Controller
            control={control}
            name="tagNames"
            render={({ field }) => (
              <TagChipInput
                ref={field.ref}
                id={ids.tagNames.inputId}
                value={field.value}
                onChange={(next) => field.onChange(next)}
                existingTags={existingTagNames}
                describedBy={describedBy(ids.tagNames, true, Boolean(err("tagNames")))}
              />
            )}
          />
        </FormField>

        <FormField
          ids={ids.linkedCourseIds}
          label="Link another course"
          error={err("linkedCourseIds")}
          helper={
            <>
              Content from linked courses appears in the student portal under this course name. The linked
              course&apos;s own payment rules still apply.
              {linkedCourses.truncated ? (
                <span className="block" data-testid={`${idPrefix}-linked-courses-truncated`}>
                  Showing first {linkedCourses.truncated.shown} courses of {linkedCourses.truncated.total}.
                </span>
              ) : null}
            </>
          }
        >
          <Controller
            control={control}
            name="linkedCourseIds"
            render={({ field }) => (
              <MultiCheckboxSelect
                ref={field.ref}
                id={ids.linkedCourseIds.inputId}
                options={linkedCourses.options}
                value={field.value}
                onChange={(next) => field.onChange(next)}
                onBlur={field.onBlur}
                invalid={Boolean(err("linkedCourseIds"))}
                placeholder="Select courses to link"
                describedBy={describedBy(ids.linkedCourseIds, true, Boolean(err("linkedCourseIds")))}
                loading={linkedCourses.loading}
                loadError={linkedCourses.loadError}
                emptyText="No other courses in this institute yet."
                testId={`${idPrefix}-linked-courses`}
              />
            )}
          />
        </FormField>
      </div>

      {cover}
    </>
  );
}

/**
 * "Primary teacher" radio group among the selected teachers. The primary is
 * `teacherIds[0]`; choosing another radio moves that id to the front.
 */
function PrimaryTeacherRadios({
  idPrefix,
  value,
  onChange,
  labelFor,
  disabledReason,
}: {
  idPrefix: string;
  value: string[];
  onChange: (next: string[]) => void;
  labelFor: (userId: string) => string;
  disabledReason: string | null;
}) {
  const name = `${idPrefix}-primary-teacher`;
  const reasonId = `${name}-reason`;
  const disabled = Boolean(disabledReason);
  return (
    <fieldset
      className="mt-1 flex flex-col gap-1 rounded-lg border border-border p-2"
      aria-describedby={disabled ? reasonId : undefined}
      data-testid={name}
    >
      <legend className="px-1 text-xs font-medium text-foreground">Primary teacher</legend>
      {value.map((userId, index) => {
        const radioId = `${name}-${userId}`;
        return (
          <label key={userId} htmlFor={radioId} className="flex items-center gap-2 rounded-md px-1.5 py-1 text-sm">
            <input
              id={radioId}
              type="radio"
              name={name}
              value={userId}
              checked={index === 0}
              disabled={disabled}
              onChange={() => onChange([userId, ...value.filter((id) => id !== userId)])}
              className="size-4"
              data-testid={`${name}-option`}
            />
            <span>{labelFor(userId)}</span>
          </label>
        );
      })}
      {disabledReason ? (
        <p id={reasonId} className="px-1 text-xs text-muted-foreground">
          {disabledReason}
        </p>
      ) : null}
    </fieldset>
  );
}

/**
 * Cover image picker: preview box (selected file, current cover, or the
 * default placeholder), a visually hidden file input and caller actions.
 */
export function CourseCoverField({
  idPrefix,
  ids,
  fileInputRef,
  previewUrl,
  previewAlt,
  error,
  helper,
  onFileChosen,
  secondaryAction,
}: {
  idPrefix: string;
  ids: { inputId: string; helperId: string; errorId: string };
  fileInputRef: RefObject<HTMLInputElement | null>;
  /** `null` = default cover. */
  previewUrl: string | null;
  previewAlt: string;
  error: string | null;
  helper: ReactNode;
  onFileChosen: (file: File | null) => void;
  secondaryAction?: ReactNode;
}) {
  return (
    <FormField ids={ids} label="Cover Image" error={error} helper={helper}>
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex h-24 w-40 items-center justify-center overflow-hidden rounded-lg border border-border bg-muted">
          {previewUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- blob preview or short-lived signed URL, not an optimisable static image
            <img src={previewUrl} alt={previewAlt} className="size-full object-cover" data-testid={`${idPrefix}-cover-preview`} />
          ) : (
            <span className="flex flex-col items-center gap-1 text-xs text-muted-foreground" data-testid={`${idPrefix}-cover-default`}>
              <ImageIcon className="size-6" aria-hidden="true" />
              Default cover
            </span>
          )}
        </div>
        <input
          ref={fileInputRef}
          id={ids.inputId}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          className="sr-only"
          aria-describedby={describedBy(ids, true, Boolean(error))}
          onChange={(event) => {
            onFileChosen(event.target.files?.[0] ?? null);
            event.target.value = "";
          }}
          data-testid={`${idPrefix}-cover-input`}
        />
        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => fileInputRef.current?.click()}
            data-testid={`${idPrefix}-cover-change`}
          >
            Change
          </Button>
          {secondaryAction}
        </div>
      </div>
    </FormField>
  );
}
