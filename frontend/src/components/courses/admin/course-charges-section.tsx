"use client";

import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { MoneyInput } from "@/components/courses/admin/money-input";
import { courseChargesSchema, type CourseChargesFormValues } from "@/components/courses/admin/schemas";
import { cn } from "@/lib/utils";
import { isApiClientError } from "@/lib/api/error";
import {
  formatAmount,
  toMoneyString,
  useUpdateKlassCourse,
  type KlassCourse,
  type KlassCourseUpdateRequest,
} from "@/lib/api/klass-admin";

const ID_PREFIX = "course-charges";

type FacilityFeeBasis = NonNullable<KlassCourse["facilityFeeBasis"]>;

const BASIS_TEXT: Record<FacilityFeeBasis, string> = {
  NONE: "Facility fees are switched off for this institute.",
  PER_COURSE: "Charged once per course in each payment.",
  PER_LINE: "Charged on every item a student pays for.",
  PER_ORDER: "Charged once per payment, at the institute default.",
};

/** Seeds the form. Null-check only: an override of 0 means "no fee", not "inherit". */
export function courseToChargesFormValues(course: KlassCourse): CourseChargesFormValues {
  const override = course.facilityFeeOverride;
  const inherit = override === null || override === undefined;
  return {
    facilityFeeInherit: inherit,
    facilityFeeOverride: inherit ? "" : (formatAmount(override) ?? ""),
    postageFee: formatAmount(course.postageFee) ?? "0.00",
  };
}

/** `PATCH /api/v1/courses/{id}` partial body (no `name`: every other field stays unchanged). Only changed fields are sent. */
export function toCourseChargesRequest(
  values: CourseChargesFormValues,
  original: CourseChargesFormValues
): KlassCourseUpdateRequest {
  const body: KlassCourseUpdateRequest = {};
  if (values.facilityFeeInherit) {
    if (!original.facilityFeeInherit) body.facilityFeeInherit = true;
  } else {
    const amount = toMoneyString(values.facilityFeeOverride.trim());
    const before = original.facilityFeeInherit ? null : toMoneyString(original.facilityFeeOverride.trim());
    if (before !== amount) {
      body.facilityFeeInherit = false;
      body.facilityFeeOverride = amount;
    }
  }
  const postage = toMoneyString(values.postageFee.trim());
  if (postage !== toMoneyString(original.postageFee.trim() || "0")) body.postageFee = postage;
  return body;
}

function amountText(value: KlassCourse["effectiveFacilityFee"], currency: string): string {
  return `${currency} ${formatAmount(value) ?? "0.00"}`;
}

/**
 * Course facility fee + postage (multi-course checkout, V90). "Use institute
 * default facility fee" on = no override (`facilityFeeOverride: null`); off =
 * a course amount (0 allowed = no fee). Postage 0 = no postage charge. The
 * effective fee and basis are the server's; whether an override applies
 * depends on the institute's facility-fee mode. Saved on its own (partial
 * PATCH); the backend validates, authorizes and audit-logs the change.
 */
export function CourseChargesSection({ course }: { course: KlassCourse }) {
  const toast = useAdminToast();
  const [original, setOriginal] = useState<CourseChargesFormValues>(() => courseToChargesFormValues(course));
  const [formError, setFormError] = useState<string | null>(null);
  const updateMutation = useUpdateKlassCourse(course.id);
  const {
    control,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<CourseChargesFormValues>({
    resolver: zodResolver(courseChargesSchema),
    mode: "onTouched",
    defaultValues: original,
  });
  const inherit = useWatch({ control, name: "facilityFeeInherit" });
  const pending = isSubmitting || updateMutation.isPending;
  const currency = course.priceDisplay?.currency ?? "Rs.";
  const basis: FacilityFeeBasis = course.facilityFeeBasis ?? "NONE";
  const overrideApplies = basis === "PER_COURSE" || basis === "PER_LINE";
  const ids = {
    inherit: fieldIds(ID_PREFIX, "inherit"),
    override: fieldIds(ID_PREFIX, "override"),
    postage: fieldIds(ID_PREFIX, "postage"),
  };

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const body = toCourseChargesRequest(values, original);
    if (Object.keys(body).length === 0) {
      reset(values);
      return;
    }
    try {
      const updated = await updateMutation.mutateAsync(body);
      const next = courseToChargesFormValues(updated);
      setOriginal(next);
      reset(next);
      toast.show({ kind: "success", text: "Fees and postage saved." });
    } catch (error) {
      if (!isApiClientError(error)) {
        setFormError("Something went wrong. Your changes were not saved. Please try again.");
        return;
      }
      let mapped = false;
      for (const fieldError of error.fieldErrors) {
        if (fieldError.field === "facilityFeeOverride" || fieldError.field === "postageFee") {
          setError(fieldError.field, { type: "server", message: fieldError.message });
          mapped = true;
        }
      }
      if (error.status === 403) {
        setFormError("You don't have permission to change this course's fees. Nothing was saved.");
      } else if (!mapped) {
        setFormError(error.message || "Your changes were not saved.");
      }
      toast.show({ kind: "error", text: "Fees and postage were not saved." });
    }
  });

  return (
    <section
      aria-labelledby={`${ID_PREFIX}-heading`}
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 sm:p-6"
      data-testid="course-charges"
    >
      <div className="flex flex-col gap-1">
        <h2 id={`${ID_PREFIX}-heading`} className="text-base font-semibold text-foreground">
          Facility fee and postage
        </h2>
        <p className="text-sm text-muted-foreground">
          Extra charges added at checkout. Changes apply to new payments only; enrollment stays free.
        </p>
      </div>

      {formError ? (
        <Alert variant="destructive" data-testid={`${ID_PREFIX}-form-error`}>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}

      <dl className="grid grid-cols-1 gap-x-4 gap-y-2 rounded-lg border border-border bg-muted/20 p-3 text-sm sm:grid-cols-3">
        <div>
          <dt className="text-xs text-muted-foreground">Institute default</dt>
          <dd className="font-medium text-foreground" data-testid={`${ID_PREFIX}-tenant-default`}>
            {amountText(course.tenantDefaultFacilityFee, currency)}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">Effective facility fee</dt>
          <dd className="font-medium text-foreground" data-testid={`${ID_PREFIX}-effective`}>
            {basis === "NONE" ? "None" : amountText(course.effectiveFacilityFee, currency)}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-muted-foreground">How it is charged</dt>
          <dd className="text-foreground" data-testid={`${ID_PREFIX}-basis`}>
            {BASIS_TEXT[basis]}
          </dd>
        </div>
      </dl>

      {!overrideApplies ? (
        <p className="text-xs text-muted-foreground" role="note" data-testid={`${ID_PREFIX}-override-note`}>
          {basis === "NONE"
            ? "Facility fees are off for the institute, so a course facility fee isn't charged. You can still set one for later."
            : "The institute charges its default fee once per payment, so a course facility fee isn't used."}
        </p>
      ) : null}

      <form noValidate onSubmit={onSubmit} aria-busy={pending} className="flex flex-col gap-4">
        <span role="status" aria-live="polite" className="sr-only">
          {pending ? "Saving fees and postage…" : ""}
        </span>
        <fieldset disabled={pending} className="flex min-w-0 flex-col gap-4">
          <legend className="sr-only">Facility fee and postage</legend>
          <Controller
            control={control}
            name="facilityFeeInherit"
            render={({ field }) => (
              <div className="flex items-start gap-3">
                <button
                  type="button"
                  role="switch"
                  id={ids.inherit.inputId}
                  aria-checked={field.value}
                  aria-describedby={ids.inherit.helperId}
                  onClick={() => field.onChange(!field.value)}
                  onBlur={field.onBlur}
                  data-testid={`${ID_PREFIX}-inherit`}
                  data-state={field.value ? "on" : "off"}
                  className={cn(
                    "relative mt-0.5 inline-flex h-5 w-9 shrink-0 items-center rounded-full border-2 border-transparent transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-60",
                    field.value ? "bg-primary" : "bg-muted-foreground/40"
                  )}
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      "inline-block size-4 rounded-full bg-background shadow transition-transform",
                      field.value ? "translate-x-4" : "translate-x-0"
                    )}
                  />
                </button>
                <div className="flex flex-col">
                  <label htmlFor={ids.inherit.inputId} className="text-sm font-medium text-foreground">
                    Use institute default facility fee
                  </label>
                  <p id={ids.inherit.helperId} className="text-xs text-muted-foreground">
                    {field.value
                      ? `This course uses the institute default (${amountText(course.tenantDefaultFacilityFee, currency)}).`
                      : "This course uses its own facility fee below."}
                  </p>
                </div>
              </div>
            )}
          />

          {!inherit ? (
            <FormField
              ids={ids.override}
              label="Course facility fee"
              required
              helper="0 or more, up to 2 decimals. Enter 0 for no facility fee on this course."
              error={errors.facilityFeeOverride?.message ?? null}
            >
              <Controller
                control={control}
                name="facilityFeeOverride"
                render={({ field }) => (
                  <MoneyInput
                    ref={field.ref}
                    id={ids.override.inputId}
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    required
                    invalid={Boolean(errors.facilityFeeOverride)}
                    describedBy={describedBy(ids.override, true, Boolean(errors.facilityFeeOverride))}
                    testId={`${ID_PREFIX}-override`}
                  />
                )}
              />
            </FormField>
          ) : null}

          <FormField
            ids={ids.postage}
            label="Postage fee"
            required
            helper="Charged when a student asks for printed materials by post. Enter 0 for no postage charge."
            error={errors.postageFee?.message ?? null}
          >
            <Controller
              control={control}
              name="postageFee"
              render={({ field }) => (
                <MoneyInput
                  ref={field.ref}
                  id={ids.postage.inputId}
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  required
                  invalid={Boolean(errors.postageFee)}
                  describedBy={describedBy(ids.postage, true, Boolean(errors.postageFee))}
                  testId={`${ID_PREFIX}-postage`}
                />
              )}
            />
          </FormField>
        </fieldset>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              reset(original);
              setFormError(null);
            }}
            disabled={pending || !isDirty}
            data-testid={`${ID_PREFIX}-discard`}
          >
            Discard changes
          </Button>
          <Button type="submit" disabled={pending} aria-busy={pending} data-testid={`${ID_PREFIX}-submit`}>
            {pending ? "Saving…" : "Save fees"}
          </Button>
        </div>
      </form>

      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </section>
  );
}
