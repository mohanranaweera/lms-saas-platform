"use client";

import { useMemo, useState } from "react";
import { Controller, useForm, useWatch, type FieldErrors } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, ChevronDown, ChevronUp, HelpCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds, nativeSelectClass } from "@/components/courses/admin/form-field";
import { MoneyInput } from "@/components/courses/admin/money-input";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { SessionResources, useSessionResourceDrafts, type ResourcePicker } from "@/components/courses/admin/session-resources";
import { useAuth } from "@/lib/auth/auth-context";
import { tenantDashboardPath } from "@/lib/auth/dashboard-paths";
import {
  END_OF_DAY,
  REQUIRED_MESSAGE,
  makeSessionFormSchema,
  type SessionFormValues,
} from "@/components/courses/admin/schemas";
import { isoToZonedInputs, nowZonedInputs, zonedDateTimeToIso } from "@/components/courses/admin/tenant-time";
import { isApiClientError } from "@/lib/api/error";
import {
  LESSON_PAYMENT_TYPES,
  LESSON_PAYMENT_TYPE_HELP,
  LESSON_PAYMENT_TYPE_LABELS,
  formatAmount,
  toMoneyString,
  useCreateKlassLesson,
  useLessonResourceTypes,
  useUpdateKlassLesson,
  type KlassLesson,
  type KlassLessonRequest,
  type LessonPaymentType,
  type LessonResourceSupport,
  type LessonResourceTypeOption,
  type TenantTimeZone,
} from "@/lib/api/klass-admin";

const ID_PREFIX = "session-form";
type FieldName = keyof SessionFormValues;

const ADVANCED_FIELDS: FieldName[] = ["lessonLabel", "description"];

/** Server error codes from the §4.1 contract, mapped to the field they concern. */
const CODE_TO_FIELD: Record<string, { field: FieldName; message: string }> = {
  ENDS_BEFORE_START: { field: "toDate", message: "The end must be after the start." },
  PRICE_REQUIRED_FOR_PAID: { field: "price", message: REQUIRED_MESSAGE },
  PRICE_NOT_ALLOWED: { field: "price", message: "A price is only allowed for Paid sessions." },
  RESOURCE_TYPE_UNSUPPORTED: {
    field: "resourceType",
    message: "This resource type isn't available yet. Choose another type.",
  },
};

const SERVER_FIELD_ALIASES: Record<string, FieldName> = {
  title: "name",
  name: "name",
  startsAt: "fromDate",
  endsAt: "toDate",
  resourceType: "resourceType",
  paymentType: "paymentType",
  price: "price",
  lessonLabel: "lessonLabel",
  description: "description",
};

export function supportBadgeText(support: LessonResourceSupport): string | null {
  if (support === "LINK_ONLY") return "Link only";
  if (support === "UNSUPPORTED") return "Not available yet";
  return null;
}

function defaultsFor(lesson: KlassLesson | null, timeZone: string): SessionFormValues {
  const now = nowZonedInputs(timeZone);
  const start = lesson?.startsAt ? isoToZonedInputs(lesson.startsAt, timeZone) : lesson ? { date: "", time: "" } : now;
  const end = lesson?.endsAt ? isoToZonedInputs(lesson.endsAt, timeZone) : { date: "", time: "" };
  return {
    name: lesson?.title ?? "",
    fromDate: start.date,
    fromTime: start.time,
    toDate: end.date,
    toTime: end.time,
    resourceType: lesson?.resourceType ?? "",
    paymentType: lesson?.paymentType ?? "",
    price: formatAmount(lesson?.price) ?? "",
    lessonLabel: lesson?.lessonLabel ?? "",
    description: lesson?.description ?? "",
  };
}

function toRequest(values: SessionFormValues, sequence: number, timeZone: string): KlassLessonRequest {
  const startsAt = values.fromDate && values.fromTime
    ? zonedDateTimeToIso(values.fromDate, values.fromTime, timeZone)
    : null;
  const endsAt = values.toDate ? zonedDateTimeToIso(values.toDate, values.toTime || END_OF_DAY, timeZone) : null;
  return {
    title: values.name.trim(),
    sequence,
    startsAt,
    endsAt,
    resourceType: values.resourceType || null,
    paymentType: values.paymentType as LessonPaymentType,
    price: values.paymentType === "PAID" ? toMoneyString(values.price.trim()) : null,
    description: values.description.trim() || null,
    lessonLabel: values.lessonLabel.trim() || null,
    billingYear: null,
    billingMonth: null,
  };
}

function errorText(errors: FieldErrors<SessionFormValues>, field: FieldName): string | null {
  const message = errors[field]?.message;
  return typeof message === "string" ? message : null;
}

/**
 * Add New Session / Edit Session modal (CM-A04/A05), field order per the
 * Klass reference. Dates and times are entered and shown in the tenant
 * timezone and sent as ISO instants. Price appears only for Paid sessions
 * (our design: Klass's paid-price placement was not recorded). Billing month
 * is derived by the backend for Monthly/Covered sessions; it is not a form
 * field.
 *
 * Mount with a fresh `key` per open.
 */
export function SessionFormDialog({
  courseId,
  moduleId,
  open,
  onOpenChange,
  lesson,
  nextSequence,
  timeZone,
  onSaved,
}: {
  courseId: string;
  moduleId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lesson: KlassLesson | null;
  nextSequence: number;
  timeZone: TenantTimeZone;
  onSaved: (saved: KlassLesson, mode: "create" | "edit") => void;
}) {
  const tz = timeZone.timeZone;
  const schema = useMemo(
    () => makeSessionFormSchema(tz, { allowEmptyStart: Boolean(lesson), allowEmptyType: Boolean(lesson && !lesson.resourceType) }),
    [tz, lesson]
  );
  const [defaults] = useState(() => defaultsFor(lesson, tz));
  const [formError, setFormError] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(
    Boolean(lesson?.lessonLabel || lesson?.description)
  );
  const [showTypeHelp, setShowTypeHelp] = useState(false);
  const resourceTypesQuery = useLessonResourceTypes();
  const create = useCreateKlassLesson(courseId, moduleId);
  const update = useUpdateKlassLesson(courseId, moduleId);
  const { session } = useAuth();
  const dashboardHref = tenantDashboardPath(session?.role ?? null) ?? "/login";
  const resources = useSessionResourceDrafts(courseId, moduleId);
  const [picker, setPicker] = useState<ResourcePicker>(null);
  const [savedLesson, setSavedLesson] = useState<KlassLesson | null>(null);
  const [savedBody, setSavedBody] = useState<KlassLessonRequest | null>(null);

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SessionFormValues>({
    resolver: zodResolver(schema),
    mode: "onTouched",
    defaultValues: defaults,
  });

  const pending = isSubmitting || create.isPending || update.isPending;
  const paymentType = useWatch({ control, name: "paymentType" });
  const resourceType = useWatch({ control, name: "resourceType" });
  const toDate = useWatch({ control, name: "toDate" });
  const toTime = useWatch({ control, name: "toTime" });
  const err = (field: FieldName) => errorText(errors, field);
  const resourceTypes: LessonResourceTypeOption[] = resourceTypesQuery.data ?? [];
  const selectedResource = resourceTypes.find((option) => option.type === resourceType) ?? null;

  const onSubmit = handleSubmit(
    async (values) => {
      setFormError(null);
      const body = toRequest(values, lesson?.sequence ?? savedLesson?.sequence ?? nextSequence, tz);
      const persisted = lesson;
      if (persisted && values.fromDate === defaults.fromDate && values.fromTime === defaults.fromTime) body.startsAt = persisted.startsAt;
      if (persisted && values.toDate === defaults.toDate && values.toTime === defaults.toTime) body.endsAt = persisted.endsAt;
      if (persisted && values.description === defaults.description) body.description = persisted.description;
      if (persisted && values.lessonLabel === defaults.lessonLabel) body.lessonLabel = persisted.lessonLabel;
      if (persisted && values.price === defaults.price && values.paymentType === defaults.paymentType) {
        body.price = persisted.price == null ? null : String(persisted.price);
      }
      try {
        let saved = savedLesson;
        if (!saved || !savedBody || JSON.stringify(savedBody) !== JSON.stringify(body)) {
          saved = lesson
            ? await update.mutateAsync({ lessonId: lesson.id, body })
            : savedLesson
              ? await update.mutateAsync({ lessonId: savedLesson.id, body })
              : await create.mutateAsync(body);
          setSavedLesson(saved);
          setSavedBody(body);
        }
        if (resources.drafts.length) {
          try {
            await resources.save(saved.id);
          } catch (resourceError) {
            const detail = resourceError instanceof Error ? resourceError.message : mutationErrorMessage(resourceError);
            setFormError(`Session saved, but some resources were not added. ${detail} Retry Save to add only the outstanding resources.`);
            return;
          }
        }
        onSaved(saved, lesson ? "edit" : "create");
      } catch (error) {
        if (!isApiClientError(error)) {
          setFormError(mutationErrorMessage(error));
          return;
        }
        let handled = false;
        const codeTarget = CODE_TO_FIELD[error.code];
        if (codeTarget) {
          setError(codeTarget.field, { type: "server", message: codeTarget.message });
          handled = true;
        }
        const rest: string[] = [];
        for (const fieldError of error.fieldErrors) {
          const coded = CODE_TO_FIELD[fieldError.message];
          const target = coded?.field ?? SERVER_FIELD_ALIASES[fieldError.field];
          if (target) {
            setError(target, { type: "server", message: coded?.message ?? fieldError.message });
            if (ADVANCED_FIELDS.includes(target)) setShowAdvanced(true);
            handled = true;
          } else {
            rest.push(fieldError.message);
          }
        }
        if (!handled || rest.length > 0) setFormError([mutationErrorMessage(error), ...rest].join(" "));
      }
    },
    (invalid) => {
      if (ADVANCED_FIELDS.some((field) => invalid[field]) && paymentType !== "COVERED_BY_CLASS_FEES") {
        setShowAdvanced(true);
      }
    }
  );

  const ids = {
    name: fieldIds(ID_PREFIX, "name"),
    fromDate: fieldIds(ID_PREFIX, "from-date"),
    fromTime: fieldIds(ID_PREFIX, "from-time"),
    toDate: fieldIds(ID_PREFIX, "to-date"),
    toTime: fieldIds(ID_PREFIX, "to-time"),
    resourceType: fieldIds(ID_PREFIX, "resource-type"),
    paymentType: fieldIds(ID_PREFIX, "payment-type"),
    price: fieldIds(ID_PREFIX, "price"),
    lessonLabel: fieldIds(ID_PREFIX, "lesson"),
    description: fieldIds(ID_PREFIX, "description"),
  };

  const timeZoneNote =
    timeZone.source === "tenant"
      ? `Dates and times are in the institute's timezone (${tz}).`
      : `Dates and times are in your browser's timezone (${tz}); the institute timezone could not be read.`;

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && pending) return;
        onOpenChange(next);
      }}
      title={lesson || savedLesson ? "Edit Session" : "Add New Session"}
      testId="session-form-dialog"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending} data-testid="session-form-cancel">
            Cancel
          </Button>
          <Button type="submit" form="session-form" disabled={pending} aria-busy={pending} data-testid="session-form-submit">
            {pending ? "Saving…" : lesson || savedLesson ? "Save session" : "Create session"}
          </Button>
        </>
      }
    >
      {formError ? (
        <Alert variant="destructive" className="mb-4" data-testid="session-form-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}
      <form id="session-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <fieldset disabled={pending} className="flex min-w-0 flex-col gap-4">
          <FormField ids={ids.name} label="Name" required error={err("name")}>
            <Input
              id={ids.name.inputId}
              maxLength={255}
              autoComplete="off"
              aria-required="true"
              aria-invalid={Boolean(err("name"))}
              aria-describedby={describedBy(ids.name, false, Boolean(err("name")))}
              data-testid="session-form-name"
              {...register("name")}
            />
          </FormField>

          <p className="text-xs text-muted-foreground" data-testid="session-form-timezone">
            {timeZoneNote}
            {lesson && !lesson.startsAt ? " This legacy session has no start window; leaving both start fields empty keeps it unchanged." : ""}
          </p>

          <div className="grid gap-4 sm:grid-cols-2">
            <FormField ids={ids.fromDate} label="From Date" required={!lesson} error={err("fromDate")}>
              <Input
                id={ids.fromDate.inputId}
                type="date"
                aria-required={!lesson}
                aria-invalid={Boolean(err("fromDate"))}
                aria-describedby={describedBy(ids.fromDate, false, Boolean(err("fromDate")))}
                data-testid="session-form-from-date"
                {...register("fromDate")}
              />
            </FormField>
            <FormField ids={ids.fromTime} label="From Time" required={!lesson} error={err("fromTime")}>
              <Input
                id={ids.fromTime.inputId}
                type="time"
                aria-required={!lesson}
                aria-invalid={Boolean(err("fromTime"))}
                aria-describedby={describedBy(ids.fromTime, false, Boolean(err("fromTime")))}
                data-testid="session-form-from-time"
                {...register("fromTime")}
              />
            </FormField>
            <FormField
              ids={ids.toDate}
              label="To Date"
              error={err("toDate")}
              helper={
                toDate && !toTime
                  ? "No end time set: the session ends at 23:59 on this date."
                  : "Optional. With a date but no time, the session ends at 23:59 that day."
              }
            >
              <Input
                id={ids.toDate.inputId}
                type="date"
                aria-invalid={Boolean(err("toDate"))}
                aria-describedby={describedBy(ids.toDate, true, Boolean(err("toDate")))}
                data-testid="session-form-to-date"
                {...register("toDate")}
              />
            </FormField>
            <FormField ids={ids.toTime} label="To Time" error={err("toTime")}>
              <Input
                id={ids.toTime.inputId}
                type="time"
                aria-invalid={Boolean(err("toTime"))}
                aria-describedby={describedBy(ids.toTime, false, Boolean(err("toTime")))}
                data-testid="session-form-to-time"
                {...register("toTime")}
              />
            </FormField>
          </div>

          <FormField
            ids={ids.resourceType}
            label="Resource Type"
            required={!lesson || Boolean(lesson.resourceType)}
            error={err("resourceType")}
            helper={
              selectedResource ? (
                <span className="flex flex-col gap-1">
                  {supportBadgeText(selectedResource.support) ? (
                    <Badge variant="outline" className="w-fit">
                      {supportBadgeText(selectedResource.support)}
                    </Badge>
                  ) : null}
                  <span>{selectedResource.help}</span>
                </span>
              ) : (
                "What students open for this session."
              )
            }
            labelAdornment={
              <button
                type="button"
                onClick={() => setShowTypeHelp((current) => !current)}
                aria-expanded={showTypeHelp}
                aria-controls="session-form-resource-help"
                aria-label="Explain resource types"
                className="rounded text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50"
              >
                <HelpCircle className="size-4" aria-hidden="true" />
              </button>
            }
          >
            {resourceTypesQuery.isError ? (
              <div className="flex items-center gap-2 text-xs text-destructive" role="alert">
                Resource types could not be loaded.
                <Button type="button" variant="outline" size="xs" onClick={() => resourceTypesQuery.refetch()}>
                  Retry
                </Button>
              </div>
            ) : null}
            <select
              id={ids.resourceType.inputId}
              className={nativeSelectClass}
              aria-required={!lesson || Boolean(lesson.resourceType)}
              aria-invalid={Boolean(err("resourceType"))}
              aria-describedby={describedBy(ids.resourceType, true, Boolean(err("resourceType")))}
              aria-busy={resourceTypesQuery.isPending || undefined}
              data-testid="session-form-resource-type"
              {...register("resourceType", {
                onChange: (event) => {
                  const type = event.target.value;
                  if (type === "IMAGE" || type === "DOCUMENT" || type === "SECURE_VIDEO" || type === "ZOOM_RECORDING") setPicker(type);
                  else setPicker(null);
                },
              })}
            >
              <option value="">{resourceTypesQuery.isPending ? "Loading resource types…" : "Select a resource type"}</option>
              {resourceTypes.map((option) => {
                const badge = supportBadgeText(option.support);
                return (
                  <option
                    key={option.type}
                    value={option.type}
                    disabled={option.support === "UNSUPPORTED" && option.type !== "ZOOM_RECORDING"}
                    title={option.help}
                  >
                    {option.label}
                    {badge ? ` (${badge})` : ""}
                  </option>
                );
              })}
            </select>
            <dl
              id="session-form-resource-help"
              hidden={!showTypeHelp}
              className="mt-1 flex max-h-64 flex-col gap-2 overflow-y-auto rounded-lg border border-border bg-muted/30 p-3 text-xs"
            >
              {resourceTypes.map((option) => (
                <div key={option.type}>
                  <dt className="flex items-center gap-2 font-medium text-foreground">
                    {option.label}
                    {supportBadgeText(option.support) ? (
                      <Badge variant="outline">{supportBadgeText(option.support)}</Badge>
                    ) : null}
                  </dt>
                  <dd className="text-muted-foreground">{option.help}</dd>
                </div>
              ))}
            </dl>
          </FormField>

          <SessionResources
            courseId={courseId}
            moduleId={moduleId}
            lessonId={savedLesson?.id ?? lesson?.id ?? ""}
            resourceType={resourceType}
            drafts={resources.drafts}
            add={resources.add}
            remove={resources.remove}
            picker={picker}
            setPicker={setPicker}
            disabled={pending}
            dashboardHref={dashboardHref}
          />

          <FormField
            ids={ids.paymentType}
            label="Payment Type"
            required
            error={err("paymentType")}
            helper={
              paymentType && paymentType in LESSON_PAYMENT_TYPE_HELP
                ? LESSON_PAYMENT_TYPE_HELP[paymentType as LessonPaymentType]
                : undefined
            }
          >
            <select
              id={ids.paymentType.inputId}
              className={nativeSelectClass}
              aria-required="true"
              aria-invalid={Boolean(err("paymentType"))}
              aria-describedby={describedBy(ids.paymentType, Boolean(paymentType), Boolean(err("paymentType")))}
              data-testid="session-form-payment-type"
              {...register("paymentType")}
            >
              <option value="">Select a payment type</option>
              {LESSON_PAYMENT_TYPES.map((type) => (
                <option key={type} value={type}>
                  {LESSON_PAYMENT_TYPE_LABELS[type]}
                </option>
              ))}
            </select>
          </FormField>

          {paymentType === "PAID" ? (
            <FormField
              ids={ids.price}
              label="Price"
              required
              error={err("price")}
              helper="What students pay for this session on its own."
            >
              <Controller
                control={control}
                name="price"
                render={({ field }) => (
                  <MoneyInput
                    id={ids.price.inputId}
                    value={field.value}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    invalid={Boolean(err("price"))}
                    describedBy={describedBy(ids.price, true, Boolean(err("price")))}
                    testId="session-form-price"
                    required
                  />
                )}
              />
            </FormField>
          ) : null}

          <button
            type="button"
            onClick={() => setShowAdvanced((current) => !current)}
            aria-expanded={showAdvanced}
            aria-controls="session-form-advanced"
            className="inline-flex w-fit items-center gap-1 text-sm font-semibold tracking-wide text-primary uppercase outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring/50"
            data-testid="session-form-toggle-advanced"
          >
            {showAdvanced ? <ChevronUp className="size-4" aria-hidden="true" /> : <ChevronDown className="size-4" aria-hidden="true" />}
            {showAdvanced ? "Hide additional settings" : "Show additional settings"}
          </button>

          <div id="session-form-advanced" hidden={!showAdvanced} className="flex flex-col gap-4">
            <FormField
              ids={ids.lessonLabel}
              label="Session"
              error={err("lessonLabel")}
              helper="Optional label shown with the session, for example “Session 5”."
            >
              <Input
                id={ids.lessonLabel.inputId}
                maxLength={255}
                aria-describedby={describedBy(ids.lessonLabel, true, Boolean(err("lessonLabel")))}
                data-testid="session-form-lesson"
                {...register("lessonLabel")}
              />
            </FormField>
            <FormField ids={ids.description} label="Description" error={err("description")}>
              <Textarea
                id={ids.description.inputId}
                rows={4}
                aria-describedby={describedBy(ids.description, false, Boolean(err("description")))}
                data-testid="session-form-description"
                {...register("description")}
              />
            </FormField>
          </div>
        </fieldset>
      </form>
    </AdminFormDialog>
  );
}
