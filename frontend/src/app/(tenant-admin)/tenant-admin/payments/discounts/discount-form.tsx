"use client";

import { useId, useState, type ReactNode } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Settings2, SlidersHorizontal } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { isApiClientError } from "@/lib/api/error";
import {
  DISCOUNT_TYPE_OPTIONS,
  SELECTION_MODE_OPTIONS,
  discountTypeLabel,
  selectionModeHelper,
  type DiscountCourseOption,
  type DiscountRequest,
  type DiscountRule,
} from "@/lib/api/discounts";
import {
  DISCOUNT_FORM_FIELDS,
  discountSchema,
  toDiscountRequest,
  type DiscountFormValues,
} from "@/lib/validation/discounts";

/**
 * DISC-01 create / edit discount form (Klass "Create New Discount" layout:
 * header, "Discount Configuration" per type, "Additional Settings").
 *
 * Client checks mirror the server for fast feedback only - every rule is
 * re-validated by `DiscountAdminService` and its 422 field errors / 409
 * `DISCOUNT_CODE_TAKEN` are shown on the matching field. The discount type of
 * a saved discount can't change (server rule), so it is locked when editing.
 * `readOnly` (no `DISCOUNTS`/CREATE_EDIT) renders every field disabled with no
 * save action; a direct submit would still get a real 403.
 */
export function DiscountForm({
  initialValues,
  existing,
  courseOptions,
  readOnly,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  initialValues: DiscountFormValues;
  existing?: DiscountRule;
  courseOptions: DiscountCourseOption[];
  readOnly: boolean;
  submitLabel: string;
  onSubmit: (body: DiscountRequest) => Promise<unknown>;
  onCancel: () => void;
}) {
  const formId = useId();
  const [pageError, setPageError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<DiscountFormValues>({
    resolver: zodResolver(discountSchema),
    defaultValues: initialValues,
  });

  const discountType = useWatch({ control, name: "discountType" });
  const selectionMode = useWatch({ control, name: "selectionMode" });
  const minCount = useWatch({ control, name: "minCount" });
  const valueType = useWatch({ control, name: "valueType" });
  const courseIds = useWatch({ control, name: "courseIds" });

  const currency = courseOptions.find((option) => option.currency)?.currency ?? existing?.currency ?? "LKR";
  const typeOption = DISCOUNT_TYPE_OPTIONS.find((option) => option.value === discountType);
  // Server rule: free courses and advance-payment courses must be monthly-priced.
  const requiredOptions =
    discountType === "ADVANCE_PAYMENT" ? courseOptions.filter((option) => option.monthly) : courseOptions;
  const rewardOptions = courseOptions.filter((option) => option.monthly && !courseIds.includes(option.courseId));
  const codeMasked = Boolean(existing?.code && /^\*+$/.test(existing.code));

  const submit = handleSubmit(async (values) => {
    setPageError(null);
    setSaving(true);
    try {
      await onSubmit(toDiscountRequest(values));
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.code === "DISCOUNT_CODE_TAKEN") {
          setError("code", { type: "server", message: "Another discount already uses this code." });
          return;
        }
        if (error.status === 403) {
          setPageError("You don't have permission to change discounts.");
          return;
        }
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          const field = DISCOUNT_FORM_FIELDS.find((name) => name === fieldError.field);
          if (field) {
            setError(field, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (!mapped) setPageError(error.message || "Something went wrong. Please try again.");
        return;
      }
      setPageError("Something went wrong. Please try again.");
    } finally {
      setSaving(false);
    }
  });

  return (
    <form
      className="flex flex-col gap-6"
      noValidate
      aria-busy={saving}
      aria-label="Discount details"
      onSubmit={submit}
      data-testid="discount-form"
    >
      <LiveRegion message={saving ? "Saving discount…" : ""} />
      {pageError ? (
        <Alert variant="destructive" data-testid="discount-form-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{pageError}</AlertDescription>
        </Alert>
      ) : null}

      <fieldset disabled={readOnly || saving} className="flex min-w-0 flex-col gap-6">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <Field
            id={`${formId}-name`}
            label="Discount Name *"
            error={errors.name?.message}
            helper="Discount name is visible to the students. Please pick a meaningful name."
          >
            <Input id={`${formId}-name`} aria-invalid={!!errors.name} maxLength={120} {...register("name")} />
          </Field>

          <Field
            id={`${formId}-type`}
            label="Discount Type *"
            error={errors.discountType?.message}
            helper={existing ? "The type of a saved discount can't be changed." : undefined}
          >
            <Controller
              control={control}
              name="discountType"
              render={({ field }) => (
                <Select
                  value={field.value}
                  onValueChange={(value) => value && field.onChange(value)}
                  disabled={readOnly || saving || existing !== undefined}
                >
                  <SelectTrigger
                    id={`${formId}-type`}
                    className="w-full"
                    aria-invalid={!!errors.discountType}
                    data-testid="discount-type"
                  >
                    <SelectValue>
                      {(value: string) => discountTypeLabel(value as DiscountFormValues["discountType"])}
                    </SelectValue>
                  </SelectTrigger>
                  <SelectContent>
                    {DISCOUNT_TYPE_OPTIONS.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
          </Field>

          <div className="md:col-span-2">
            <Field id={`${formId}-description`} label="Description" error={errors.description?.message}>
              <Textarea id={`${formId}-description`} rows={3} maxLength={1000} {...register("description")} />
            </Field>
          </div>
        </div>

        <Section
          icon={<Settings2 className="size-5" aria-hidden="true" />}
          title="Discount Configuration"
          subtitle={typeOption?.subtitle}
        >
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="flex flex-col gap-4">
              <Controller
                control={control}
                name="courseIds"
                render={({ field }) => (
                  <CoursePicker
                    testId="discount-required-courses"
                    legend="Select Required Courses *"
                    options={requiredOptions}
                    value={field.value}
                    onChange={field.onChange}
                    error={errors.courseIds?.message}
                    emptyText={
                      discountType === "ADVANCE_PAYMENT"
                        ? "No monthly-priced courses yet. Advance payment discounts apply to monthly courses only."
                        : "No courses available. Draft and archived courses can't be used in a discount."
                    }
                  />
                )}
              />

              <Field
                id={`${formId}-mode`}
                label="Course selection mode"
                error={errors.selectionMode?.message}
                helper={selectionModeHelper(selectionMode, Number(minCount) || null)}
              >
                <Controller
                  control={control}
                  name="selectionMode"
                  render={({ field }) => (
                    <Select value={field.value} onValueChange={(value) => value && field.onChange(value)}>
                      <SelectTrigger id={`${formId}-mode`} className="w-full" data-testid="discount-selection-mode">
                        <SelectValue>
                          {(value: string) =>
                            SELECTION_MODE_OPTIONS.find((option) => option.value === value)?.label ?? value
                          }
                        </SelectValue>
                      </SelectTrigger>
                      <SelectContent>
                        {SELECTION_MODE_OPTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                />
              </Field>

              {selectionMode === "MIN_COUNT" ? (
                <Field
                  id={`${formId}-min-count`}
                  label="Minimum Count *"
                  error={errors.minCount?.message}
                  helper="Min required items"
                >
                  <Input
                    id={`${formId}-min-count`}
                    inputMode="numeric"
                    aria-invalid={!!errors.minCount}
                    {...register("minCount")}
                  />
                </Field>
              ) : null}
            </div>

            <div className="flex flex-col gap-4">
              {discountType === "ADVANCE_PAYMENT" ? (
                <Field
                  id={`${formId}-payment-type`}
                  label="Payment Type *"
                  helper="Applies when a student pays for future months of these courses in advance."
                >
                  <Input id={`${formId}-payment-type`} value="Installments" readOnly />
                </Field>
              ) : null}

              {discountType === "DISCOUNT_CODE" ? (
                <Field
                  id={`${formId}-code`}
                  label="Discount Code *"
                  error={errors.code?.message}
                  helper={
                    codeMasked
                      ? "The code is hidden for your role."
                      : "6-40 letters, digits, '-' or '_'. Students type this at checkout (not case-sensitive)."
                  }
                >
                  <Input
                    id={`${formId}-code`}
                    autoComplete="off"
                    className="uppercase"
                    maxLength={40}
                    placeholder={codeMasked ? "******" : undefined}
                    aria-invalid={!!errors.code}
                    {...register("code")}
                  />
                </Field>
              ) : null}

              {discountType === "BUNDLE_FREE_COURSE" ? (
                <>
                  <Controller
                    control={control}
                    name="rewardCourseIds"
                    render={({ field }) => (
                      <CoursePicker
                        testId="discount-free-courses"
                        legend="Select Free Courses *"
                        options={rewardOptions}
                        value={field.value}
                        onChange={field.onChange}
                        error={errors.rewardCourseIds?.message}
                        emptyText="No other monthly-priced courses. Free courses must be monthly-priced and can't also be required courses."
                      />
                    )}
                  />
                  <Field
                    id={`${formId}-free-months`}
                    label="Free For (Months) *"
                    error={errors.freeMonths?.message}
                    helper="Counted from the day the student's payment goes through."
                  >
                    <Input
                      id={`${formId}-free-months`}
                      inputMode="numeric"
                      aria-invalid={!!errors.freeMonths}
                      {...register("freeMonths")}
                    />
                  </Field>
                </>
              ) : (
                <>
                  <Controller
                    control={control}
                    name="valueType"
                    render={({ field }) => (
                      <fieldset className="flex flex-col gap-2">
                        <legend className="mb-1 text-sm font-medium text-foreground">Discount kind</legend>
                        {(
                          [
                            { value: "FIXED", label: `Fixed Amount (${currency})` },
                            { value: "PERCENTAGE", label: "Percentage (%)" },
                          ] as const
                        ).map((option) => (
                          <label key={option.value} className="flex items-center gap-2 text-sm">
                            <input
                              type="radio"
                              name={field.name}
                              value={option.value}
                              checked={field.value === option.value}
                              onChange={() => field.onChange(option.value)}
                              className="size-4 accent-blue-600"
                            />
                            {option.label}
                          </label>
                        ))}
                      </fieldset>
                    )}
                  />
                  <Field
                    id={`${formId}-value`}
                    label={valueType === "PERCENTAGE" ? "Discount Value (%) *" : `Discount Value (${currency}) *`}
                    error={errors.value?.message}
                  >
                    <Input
                      id={`${formId}-value`}
                      inputMode="decimal"
                      autoComplete="off"
                      aria-invalid={!!errors.value}
                      {...register("value")}
                    />
                  </Field>
                </>
              )}
            </div>
          </div>
        </Section>

        <Section icon={<SlidersHorizontal className="size-5" aria-hidden="true" />} title="Additional Settings">
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <Field id={`${formId}-from`} label="Valid From *" error={errors.validFrom?.message}>
              <Input id={`${formId}-from`} type="date" aria-invalid={!!errors.validFrom} {...register("validFrom")} />
            </Field>
            <Field id={`${formId}-to`} label="Valid To *" error={errors.validTo?.message}>
              <Input id={`${formId}-to`} type="date" aria-invalid={!!errors.validTo} {...register("validTo")} />
            </Field>
            <Field
              id={`${formId}-max-uses`}
              label="Maximum Uses (optional)"
              error={errors.maxUses?.message}
              helper="Total uses across all students. Leave empty for no limit."
            >
              <Input
                id={`${formId}-max-uses`}
                inputMode="numeric"
                aria-invalid={!!errors.maxUses}
                {...register("maxUses")}
              />
            </Field>
            <Field
              id={`${formId}-min-purchase`}
              label={`Minimum Purchase Amount (${currency})`}
              error={errors.minPurchaseAmount?.message}
              helper="Optional. The order's course fees must reach this amount."
            >
              <Input
                id={`${formId}-min-purchase`}
                inputMode="decimal"
                autoComplete="off"
                aria-invalid={!!errors.minPurchaseAmount}
                {...register("minPurchaseAmount")}
              />
            </Field>
          </div>
          <label className="flex items-center gap-2 text-sm text-foreground">
            <input type="checkbox" className="size-4 accent-blue-600" {...register("active")} />
            Active (discount is available for use)
          </label>
        </Section>
      </fieldset>

      <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
        <Button type="button" variant="outline" onClick={onCancel} disabled={saving}>
          {readOnly ? "Back" : "Cancel"}
        </Button>
        {readOnly ? null : (
          <Button type="submit" disabled={saving} aria-busy={saving} data-testid="discount-submit">
            {saving ? "Saving…" : submitLabel}
          </Button>
        )}
      </div>
    </form>
  );
}

function Section({
  icon,
  title,
  subtitle,
  children,
}: {
  icon: ReactNode;
  title: string;
  subtitle?: string;
  children: ReactNode;
}) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="flex flex-col gap-4 rounded-lg border border-border p-4">
      <div className="border-b border-border pb-3">
        <h2 id={id} className="flex items-center gap-2 text-base font-semibold text-foreground">
          <span className="text-blue-600 dark:text-blue-300">{icon}</span>
          {title}
        </h2>
        {subtitle ? <p className="text-sm text-muted-foreground">{subtitle}</p> : null}
      </div>
      {children}
    </section>
  );
}

/** Klass-style course multi-select: an "All" checkbox + "<Course> - <price>" options. */
function CoursePicker({
  testId,
  legend,
  options,
  value,
  onChange,
  error,
  emptyText,
}: {
  testId: string;
  legend: string;
  options: DiscountCourseOption[];
  value: string[];
  onChange: (next: string[]) => void;
  error?: string;
  emptyText: string;
}) {
  const errorId = useId();
  const visible = new Set(options.map((option) => option.courseId));
  const allSelected = options.length > 0 && options.every((option) => value.includes(option.courseId));
  const toggle = (courseId: string, checked: boolean) =>
    onChange(checked ? [...value, courseId] : value.filter((existing) => existing !== courseId));

  return (
    <fieldset
      className="flex min-w-0 flex-col gap-1.5"
      aria-describedby={error ? errorId : undefined}
      data-testid={testId}
    >
      <legend className="mb-1 text-sm font-medium text-foreground">{legend}</legend>
      {options.length === 0 ? (
        <p className="rounded-md border border-dashed border-border p-3 text-xs text-muted-foreground">{emptyText}</p>
      ) : (
        <div
          className={`flex max-h-56 flex-col gap-1 overflow-y-auto rounded-md border p-2 ${
            error ? "border-destructive" : "border-input"
          }`}
        >
          <label className="flex items-center gap-2 border-b border-border pb-1 text-sm font-medium">
            <input
              type="checkbox"
              className="size-4 accent-blue-600"
              checked={allSelected}
              onChange={(event) =>
                onChange(
                  event.target.checked
                    ? Array.from(new Set([...value, ...options.map((option) => option.courseId)]))
                    : value.filter((courseId) => !visible.has(courseId))
                )
              }
            />
            All
          </label>
          {options.map((option) => (
            <label key={option.courseId} className="flex items-start gap-2 text-sm">
              <input
                type="checkbox"
                className="mt-0.5 size-4 accent-blue-600"
                checked={value.includes(option.courseId)}
                onChange={(event) => toggle(option.courseId, event.target.checked)}
              />
              <span className="min-w-0 break-words">
                {option.name}
                {option.priceLabel ? <span className="text-muted-foreground"> - {option.priceLabel}</span> : null}
              </span>
            </label>
          ))}
        </div>
      )}
      {value.length > 0 ? (
        <p className="text-xs text-muted-foreground">
          {value.length} selected
          {value.some((courseId) => !visible.has(courseId)) ? " (includes courses not listed here)" : ""}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}

function Field({
  id,
  label,
  error,
  helper,
  children,
}: {
  id: string;
  label: string;
  error?: string;
  helper?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {helper ? <p className="text-xs text-muted-foreground">{helper}</p> : null}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
