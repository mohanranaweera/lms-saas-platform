"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import {
  ADVANCED_FIELDS,
  CourseCoverField,
  CourseSettingsFields,
  FIELD_ORDER,
  applyServerFieldErrors,
  courseSettingsFieldIds,
  useLinkableCourseOptions,
  useTeacherOptions,
  validateCoverFile,
  type CourseSettingsFieldName,
  type CourseSettingsFormValues,
  type TeacherFieldConfig,
} from "@/components/courses/admin/course-settings-fields";
import { makeCourseEditSchema } from "@/components/courses/admin/schemas";
import { CourseChargesSection } from "@/components/courses/admin/course-charges-section";
import { isApiClientError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth/auth-context";
import {
  CREATABLE_PRICING_MODELS,
  formatAmount,
  pricingModelTakesPrice,
  toMoneyString,
  useCourseTags,
  useDeleteCourseCover,
  useKlassCourse,
  useUpdateKlassCourse,
  useUploadCourseCover,
  type CreatablePricingModel,
  type KlassCourse,
  type KlassCourseUpdateRequest,
  type KlassPricingModel,
} from "@/lib/api/klass-admin";

const ID_PREFIX = "edit-course";

export type CourseEditPortal = "tenant-admin" | "teacher";

type CoverChange = { kind: "keep" } | { kind: "upload"; file: File; previewUrl: string } | { kind: "remove" };

function isLegacyModel(model: KlassPricingModel): boolean {
  return !(CREATABLE_PRICING_MODELS as string[]).includes(model);
}

/** Primary teacher first (the API's `teacherIds` order). */
function orderedTeachers(course: KlassCourse) {
  return [...course.teachers].sort((a, b) => Number(b.primary) - Number(a.primary));
}

/** Seeds the form from the loaded course. Never includes the slug. */
export function courseToEditFormValues(course: KlassCourse): CourseSettingsFormValues {
  const takesPrice = pricingModelTakesPrice(course.pricingModel);
  const currentPrice =
    course.priceDisplay?.amount ?? (course.pricingModel === "ONE_TIME" ? course.price : null);
  const validDays = course.paymentValidDays ?? course.accessDurationDays;
  return {
    name: course.name,
    teacherIds: orderedTeachers(course).map((teacher) => teacher.id),
    pricingModel: course.pricingModel,
    price: takesPrice ? (formatAmount(currentPrice) ?? "") : "",
    enrollmentFee: formatAmount(course.enrollmentFee) ?? "",
    paymentValidDays: validDays === null || validDays === undefined ? "" : String(validDays),
    commissionRate: formatAmount(course.commissionRate) ?? "",
    whatsappInviteUrl: course.whatsappInviteUrl ?? "",
    telegramInviteUrl: course.telegramInviteUrl ?? "",
    description: course.description ?? "",
    tagNames: course.tags.map((tag) => tag.name),
    linkedCourseIds: course.linkedCourses.map((link) => link.id),
  };
}

function sameList(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function sameSet(a: string[], b: string[]): boolean {
  const left = new Set(a);
  return a.length === b.length && b.every((value) => left.has(value));
}

function moneyKey(value: string): string {
  const trimmed = value.trim();
  return trimmed ? toMoneyString(trimmed) : "";
}

/**
 * Builds the `PATCH /api/v1/courses/{id}` body from the form.
 *
 * IMPORTANT: this always sends `name`, and a PATCH with `name` is a full
 * replacement of the legacy course fields (`subject`, `stream`, `grade`,
 * `academicYear`, `description`, `enrollmentRule`, access duration). The form
 * doesn't show subject/stream/grade/academicYear/enrollmentRule, so their
 * loaded values are sent back unchanged; otherwise saving would wipe them.
 * The access duration is sent as `paymentValidDays` (0 = no limit). The slug
 * and category are never sent (absent = unchanged; slug is server-generated).
 *
 * Everything else is sent only when it changed: teachers (primary first),
 * payment type, price (a monthly fee change starts a new billing period
 * server-side), enrollment fee, commission, invite links, tags, links.
 */
export function toCourseUpdateRequest(
  values: CourseSettingsFormValues,
  original: CourseSettingsFormValues,
  course: KlassCourse,
  options: { canEditTeachers: boolean }
): KlassCourseUpdateRequest {
  const body: KlassCourseUpdateRequest = {
    name: values.name.trim(),
    // Not shown in this form: preserve the loaded values (full replacement under `name`).
    subject: course.subject,
    stream: course.stream,
    grade: course.grade,
    academicYear: course.academicYear,
    enrollmentRule: course.enrollmentRule,
    description: values.description.trim() || null,
    paymentValidDays: values.paymentValidDays.trim() ? Number(values.paymentValidDays.trim()) : 0,
  };

  if (options.canEditTeachers && !sameList(values.teacherIds, original.teacherIds)) {
    body.teacherIds = values.teacherIds;
  }

  const legacy = isLegacyModel(course.pricingModel);
  const modelChanged = !legacy && values.pricingModel !== original.pricingModel;
  if (modelChanged) body.pricingModel = values.pricingModel as CreatablePricingModel;
  if (
    !legacy &&
    pricingModelTakesPrice(values.pricingModel) &&
    (modelChanged || moneyKey(values.price) !== moneyKey(original.price))
  ) {
    body.price = toMoneyString(values.price.trim());
  }

  const fee = moneyKey(values.enrollmentFee) || "0.00";
  if (fee !== (moneyKey(original.enrollmentFee) || "0.00")) body.enrollmentFee = fee;

  if (values.commissionRate.trim() && moneyKey(values.commissionRate) !== moneyKey(original.commissionRate)) {
    body.commissionRate = toMoneyString(values.commissionRate.trim());
  }

  // An empty string clears an invite link.
  if (values.whatsappInviteUrl.trim() !== original.whatsappInviteUrl.trim()) {
    body.whatsappInviteUrl = values.whatsappInviteUrl.trim();
  }
  if (values.telegramInviteUrl.trim() !== original.telegramInviteUrl.trim()) {
    body.telegramInviteUrl = values.telegramInviteUrl.trim();
  }

  const tagKey = (names: string[]) => names.map((name) => name.trim().toLowerCase());
  if (!sameList(tagKey(values.tagNames), tagKey(original.tagNames))) body.tagNames = values.tagNames;
  if (!sameSet(values.linkedCourseIds, original.linkedCourseIds)) body.linkedCourseIds = values.linkedCourseIds;

  return body;
}

/**
 * Course edit form (G2/G3/G4) for the Tenant Admin course Overview and the
 * teacher course edit page. Mirrors the Add New Course modal; Pricing model /
 * Billing configuration / Billing period controls are intentionally not here
 * (R2-D5) — a monthly fee change is recorded as billing-period history by the
 * server. Loads the course through the shared detail cache entry. The
 * facility fee / postage section (V90) sits below and saves on its own.
 */
export function CourseEditDetailsForm({
  courseId,
  portal,
  dashboardHref,
}: {
  courseId: string;
  portal: CourseEditPortal;
  dashboardHref: string;
}) {
  const query = useKlassCourse(courseId);
  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading course details…"
      loginPath="/login"
      permissionDenied={{ dashboardHref }}
      genericErrorMessage="The course details could not be loaded."
    >
      {(course) => (
        <div className="flex flex-col gap-6">
          <CourseEditDetailsFormBody key={course.id} course={course} portal={portal} />
          <CourseChargesSection key={`charges-${course.id}`} course={course} />
        </div>
      )}
    </QueryStateBoundary>
  );
}

function CourseEditDetailsFormBody({ course, portal }: { course: KlassCourse; portal: CourseEditPortal }) {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const toast = useAdminToast();
  const [original, setOriginal] = useState<CourseSettingsFormValues>(() => courseToEditFormValues(course));
  const [showAdvanced, setShowAdvanced] = useState(true);
  const [formError, setFormError] = useState<string | null>(null);
  const [coverChange, setCoverChange] = useState<CoverChange>({ kind: "keep" });
  const [coverError, setCoverError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [focusTarget, setFocusTarget] = useState<{ field: CourseSettingsFieldName; seq: number } | null>(null);

  // The schema depends on the last-saved values; react-hook-form reads the
  // latest resolver from its options on every render.
  const resolver = useMemo(() => zodResolver(makeCourseEditSchema(original)), [original]);

  const {
    register,
    control,
    handleSubmit,
    setError,
    setFocus,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<CourseSettingsFormValues>({
    resolver,
    mode: "onTouched",
    defaultValues: original,
    shouldFocusError: false,
  });

  useEffect(() => {
    if (focusTarget) setFocus(focusTarget.field);
  }, [focusTarget, setFocus]);

  const focusFirstError = (fields: CourseSettingsFieldName[]) => {
    const first = FIELD_ORDER.find((field) => fields.includes(field));
    if (!first) return;
    if (ADVANCED_FIELDS.includes(first)) setShowAdvanced(true);
    setFocusTarget((current) => ({ field: first, seq: (current?.seq ?? 0) + 1 }));
  };

  const stageCover = setCoverChange;
  // Blob preview of a staged cover upload, revoked when replaced or on unmount.
  useEffect(() => {
    if (coverChange.kind !== "upload") return;
    const url = coverChange.previewUrl;
    return () => URL.revokeObjectURL(url);
  }, [coverChange]);

  const isTeacherPortal = portal === "teacher";
  const isCoordinator = role === "COURSE_COORDINATOR";
  const teacherOptions = useTeacherOptions({ enabled: !isTeacherPortal });
  const tagsQuery = useCourseTags();
  const linkable = useLinkableCourseOptions({
    enabled: true,
    excludeCourseId: course.id,
    extra: course.linkedCourses.map((link) => ({ value: link.id, label: link.title })),
  });
  const updateMutation = useUpdateKlassCourse(course.id);
  const uploadCover = useUploadCourseCover();
  const deleteCover = useDeleteCourseCover();

  const assigned = orderedTeachers(course);
  const assignedNames = new Map(assigned.map((teacher) => [teacher.id, teacher.name]));
  const activeIds = new Set(teacherOptions.options.map((option) => option.value));
  // Keep currently assigned teachers who are no longer approved/active visible (so they can be removed).
  const inactiveAssigned = assigned
    .filter((teacher) => !activeIds.has(teacher.id))
    .map((teacher) => ({
      value: teacher.id,
      label: `${teacherOptions.namesByUserId.get(teacher.id) ?? teacher.name} (inactive)`,
    }));
  const pickerOptions = [...teacherOptions.options, ...inactiveAssigned];
  const teacherLabel = (userId: string) =>
    pickerOptions.find((option) => option.value === userId)?.label ??
    assignedNames.get(userId) ??
    teacherOptions.namesByUserId.get(userId) ??
    "Unknown teacher";
  const readOnlySummary = assigned
    .map((teacher, index) => `${teacher.name}${index === 0 ? " (primary)" : ""}`)
    .join(", ");

  const canEditTeachers = !isTeacherPortal && !teacherOptions.forbidden;
  const teachersConfig: TeacherFieldConfig = {
    label: "Teachers",
    helper: "Tick every teacher of this course, then choose the primary teacher.",
    options: pickerOptions,
    loading: teacherOptions.loading,
    loadError: teacherOptions.forbidden ? null : teacherOptions.loadError,
    emptyText: "No approved, active teachers in this institute yet.",
    readOnly: isTeacherPortal
      ? { summary: readOnlySummary, note: "Only institute staff can change the teachers of a course." }
      : teacherOptions.forbidden
        ? {
            summary: readOnlySummary,
            note: "You don't have permission to list teachers, so the teachers can't be changed here.",
          }
        : null,
    primary: {
      labelFor: teacherLabel,
      disabledReason: isCoordinator ? "Only a Tenant Admin can change the primary teacher." : null,
    },
    lockedValues: isCoordinator && original.teacherIds[0] ? [original.teacherIds[0]] : undefined,
  };

  const legacyModel = isLegacyModel(course.pricingModel) ? course.pricingModel : null;
  const existingTagNames = (tagsQuery.data ?? []).map((tag) => tag.name);
  const pending = isSubmitting || updateMutation.isPending || uploadCover.isPending || deleteCover.isPending;
  const ids = courseSettingsFieldIds(ID_PREFIX);

  const handleCoverChosen = (file: File | null) => {
    setCoverError(null);
    if (!file) return;
    const problem = validateCoverFile(file);
    if (problem) {
      setCoverError(problem);
      return;
    }
    stageCover({ kind: "upload", file, previewUrl: URL.createObjectURL(file) });
  };

  const applyCoverChange = async (): Promise<KlassCourse | null> => {
    if (coverChange.kind === "upload") {
      return uploadCover.mutateAsync({ courseId: course.id, file: coverChange.file });
    }
    if (coverChange.kind === "remove") {
      return deleteCover.mutateAsync({ courseId: course.id });
    }
    return null;
  };

  const onSubmit = handleSubmit(
    async (values) => {
      setFormError(null);
      let updated: KlassCourse;
      try {
        updated = await updateMutation.mutateAsync(
          toCourseUpdateRequest(values, original, course, { canEditTeachers })
        );
      } catch (error) {
        if (!isApiClientError(error)) {
          setFormError("Something went wrong. Your changes were not saved. Please try again.");
          toast.show({ kind: "error", text: "Your changes were not saved." });
          return;
        }
        const { mapped, unmapped } = applyServerFieldErrors(error, setError);
        focusFirstError(mapped);
        if (error.status === 403) {
          setFormError(
            `You don't have permission to make one of these changes${
              isCoordinator ? " (only a Tenant Admin can change the primary teacher)" : ""
            }. Nothing was saved.`
          );
        } else if (error.status === 404) {
          setFormError(
            `${error.message} A selected teacher or linked course is no longer available. Refresh the page and try again.`
          );
        } else if (mapped.length === 0 || unmapped.length > 0) {
          setFormError([error.message, ...unmapped].join(" "));
        }
        toast.show({ kind: "error", text: "Your changes were not saved. Check the form for details." });
        return;
      }

      let latest = updated;
      let coverFailure: string | null = null;
      try {
        latest = (await applyCoverChange()) ?? updated;
        stageCover({ kind: "keep" });
      } catch (error) {
        coverFailure = isApiClientError(error) ? error.message : "The cover image could not be updated.";
      }

      const next = courseToEditFormValues(latest);
      setOriginal(next);
      reset(next);
      if (coverFailure) {
        setCoverError(coverFailure);
        toast.show({
          kind: "error",
          text: `Course details saved, but the cover image was not updated: ${coverFailure}`,
        });
      } else {
        toast.show({ kind: "success", text: "Course details saved." });
      }
    },
    (invalid) => {
      focusFirstError(Object.keys(invalid) as CourseSettingsFieldName[]);
    }
  );

  const discard = () => {
    reset(original);
    stageCover({ kind: "keep" });
    setCoverError(null);
    setFormError(null);
  };

  const coverPreview =
    coverChange.kind === "upload" ? coverChange.previewUrl : coverChange.kind === "remove" ? null : course.coverImageUrl;
  const coverHelper =
    coverChange.kind === "upload"
      ? "New image selected. It is uploaded when you save."
      : coverChange.kind === "remove"
        ? "The default cover will be used once you save."
        : "JPEG, PNG or WebP, up to 5 MB. Saved when you click Save changes.";
  const coverSecondary =
    coverChange.kind !== "keep" ? (
      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={() => stageCover({ kind: "keep" })}
        data-testid={`${ID_PREFIX}-cover-undo`}
      >
        Keep current cover
      </Button>
    ) : course.coverImageUrl ? (
      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={() => stageCover({ kind: "remove" })}
        data-testid={`${ID_PREFIX}-cover-remove`}
      >
        Use default
      </Button>
    ) : null;

  return (
    <section
      aria-labelledby={`${ID_PREFIX}-heading`}
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 sm:p-6"
      data-testid="course-edit-details"
    >
      <div className="flex flex-col gap-1">
        <h2 id={`${ID_PREFIX}-heading`} className="text-base font-semibold text-foreground">
          Course details
        </h2>
        <p className="text-sm text-muted-foreground">
          Everything here, including the cover image, is saved together when you click Save changes.
          Enrollment stays free.
        </p>
      </div>

      {formError ? (
        <Alert variant="destructive" data-testid={`${ID_PREFIX}-form-error`}>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}

      <form id={`${ID_PREFIX}-form`} noValidate onSubmit={onSubmit} aria-busy={pending} className="flex flex-col gap-4">
        <span role="status" aria-live="polite" className="sr-only">
          {pending ? "Saving course details…" : ""}
        </span>
        <fieldset disabled={pending} className="flex min-w-0 flex-col gap-4">
          <legend className="sr-only">Course details</legend>
          <CourseSettingsFields
            idPrefix={ID_PREFIX}
            control={control}
            register={register}
            errors={errors}
            teachers={teachersConfig}
            pricing={{
              models: CREATABLE_PRICING_MODELS,
              placeholderOption: false,
              legacyModel,
              priceHelper: (model) =>
                model === "MONTHLY"
                  ? "Changing the monthly fee applies from now; past months keep their price."
                  : "One-time amount for the whole course.",
            }}
            linkedCourses={linkable}
            existingTagNames={existingTagNames}
            advancedOpen={showAdvanced}
            onToggleAdvanced={() => setShowAdvanced((current) => !current)}
            cover={
              <CourseCoverField
                idPrefix={ID_PREFIX}
                ids={ids.cover}
                fileInputRef={fileInputRef}
                previewUrl={coverPreview}
                previewAlt={coverChange.kind === "upload" ? "Selected cover preview" : `Cover of ${course.name}`}
                error={coverError}
                helper={coverHelper}
                onFileChosen={handleCoverChosen}
                secondaryAction={coverSecondary}
              />
            }
          />
        </fieldset>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            type="button"
            variant="outline"
            onClick={discard}
            disabled={pending || (!isDirty && coverChange.kind === "keep")}
            data-testid={`${ID_PREFIX}-discard`}
          >
            Discard changes
          </Button>
          <Button type="submit" disabled={pending} aria-busy={pending} data-testid={`${ID_PREFIX}-submit`}>
            {pending ? "Saving…" : "Save changes"}
          </Button>
        </div>
      </form>

      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </section>
  );
}
