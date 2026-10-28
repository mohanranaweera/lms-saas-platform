"use client";

import { useEffect, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { useAuth } from "@/lib/auth/auth-context";
import { Button } from "@/components/ui/button";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import {
  ADVANCED_FIELDS,
  CourseCoverField,
  CourseSettingsFields,
  FIELD_ORDER,
  applyServerFieldErrors,
  courseSettingsFieldIds,
  toCourseCreateRequest,
  useLinkableCourseOptions,
  useTeacherOptions,
  validateCoverFile,
  type CourseSettingsFieldName,
} from "@/components/courses/admin/course-settings-fields";
import {
  COURSE_CREATE_DEFAULTS,
  courseCreateSchema,
  type CourseCreateFormValues,
} from "@/components/courses/admin/schemas";
import { isApiClientError } from "@/lib/api/error";
import {
  CREATABLE_PRICING_MODELS,
  useCourseTags,
  useCreateKlassCourse,
  useUploadCourseCover,
  type KlassCourse,
} from "@/lib/api/klass-admin";
import { randomUuid } from "@/lib/uuid";

const ID_PREFIX = "add-course";

type FieldName = CourseSettingsFieldName;

/**
 * Add New Course modal (EP §3A). Creates through the backend with an
 * Idempotency-Key fixed for this modal instance, then uploads the optional
 * cover. A failed cover upload leaves the (already created) course in place
 * and says so, with a retry. Enrollment Fee is informational only (ADR-028):
 * nothing here charges it.
 *
 * Mount with a fresh `key` per open so form state and the idempotency key reset.
 */
export function CourseCreateDialog({
  open,
  onOpenChange,
  onCreated,
  mode = "staff",
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated: (course: KlassCourse, outcome: { coverUploaded: boolean | null }) => void;
  /** Teacher mode keeps assignment server-owned and removes staff-only teacher selection. */
  mode?: "staff" | "teacher";
}) {
  const { session } = useAuth();
  const isTeacher = mode === "teacher";
  const teacherUserId = isTeacher ? (session?.userId ?? "") : "";
  const [idempotencyKey] = useState(() => randomUuid());
  const teacherDefaults = isTeacher && teacherUserId ? { teacherIds: [teacherUserId] } : {};
  const [formError, setFormError] = useState<string | null>(null);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [coverFile, setCoverFile] = useState<File | null>(null);
  const [coverPreview, setCoverPreview] = useState<string | null>(null);
  const [coverError, setCoverError] = useState<string | null>(null);
  const [createdCourse, setCreatedCourse] = useState<KlassCourse | null>(null);
  const [coverUploadError, setCoverUploadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [focusTarget, setFocusTarget] = useState<{ field: FieldName; seq: number } | null>(null);

  const {
    register,
    control,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<CourseCreateFormValues>({
    resolver: zodResolver(courseCreateSchema),
    mode: "onTouched",
    defaultValues: {
      ...COURSE_CREATE_DEFAULTS,
      ...teacherDefaults,
    },
    // Focus is moved by `focusFirstError` below: the default would try to
    // focus a field inside the collapsed (hidden) additional settings.
    shouldFocusError: false,
  });

  // Runs after the render that un-hides the additional settings, so the
  // target field is focusable by then.
  useEffect(() => {
    if (focusTarget) setFocus(focusTarget.field);
  }, [focusTarget, setFocus]);

  const focusFirstError = (fields: FieldName[]) => {
    const first = FIELD_ORDER.find((field) => fields.includes(field));
    if (!first) return;
    if (ADVANCED_FIELDS.includes(first)) setShowAdvanced(true);
    setFocusTarget((current) => ({ field: first, seq: (current?.seq ?? 0) + 1 }));
  };

  const createMutation = useCreateKlassCourse();
  const coverMutation = useUploadCourseCover();
  const teachers = useTeacherOptions({ enabled: open && !isTeacher });
  const tagsQuery = useCourseTags({ enabled: open });
  const linkable = useLinkableCourseOptions({ enabled: open });

  // Blob preview URLs are created on selection and revoked on replace/unmount.
  const previewRef = useRef<string | null>(null);
  const selectCover = (file: File | null) => {
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    previewRef.current = file ? URL.createObjectURL(file) : null;
    setCoverFile(file);
    setCoverPreview(previewRef.current);
  };
  useEffect(
    () => () => {
      if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    },
    []
  );

  const existingTagNames = (tagsQuery.data ?? []).map((tag) => tag.name);

  const pending = isSubmitting || createMutation.isPending || coverMutation.isPending;

  const handleCoverChange = (file: File | null) => {
    setCoverError(null);
    if (!file) return;
    const problem = validateCoverFile(file);
    if (problem) {
      setCoverError(problem);
      return;
    }
    selectCover(file);
  };

  const uploadCover = async (course: KlassCourse) => {
    if (!coverFile) {
      onCreated(course, { coverUploaded: null });
      return;
    }
    try {
      await coverMutation.mutateAsync({ courseId: course.id, file: coverFile });
      onCreated(course, { coverUploaded: true });
    } catch (error) {
      setCreatedCourse(course);
      setCoverUploadError(isApiClientError(error) ? error.message : "The cover image could not be uploaded.");
    }
  };

  const onSubmit = handleSubmit(
    async (values) => {
      if (createdCourse) return;
      setFormError(null);
      let course: KlassCourse;
      try {
        course = await createMutation.mutateAsync({ body: toCourseCreateRequest(values), idempotencyKey });
      } catch (error) {
        if (!isApiClientError(error)) {
          setFormError("Something went wrong. The course was not created. Please try again.");
          return;
        }
        const { mapped: mappedFields, unmapped } = applyServerFieldErrors(error, setError);
        focusFirstError(mappedFields);
        if (error.status === 403) {
          setFormError("You don't have permission to create courses.");
        } else if (error.status === 404) {
          setFormError(
            `${error.message} A selected teacher or linked course is no longer available. Refresh the options and try again.`
          );
        } else if (mappedFields.length === 0 || unmapped.length > 0) {
          setFormError([error.message, ...unmapped].join(" "));
        }
        return;
      }
      await uploadCover(course);
    },
    (invalid) => {
      focusFirstError(Object.keys(invalid) as FieldName[]);
    }
  );

  const retryCover = async () => {
    if (!createdCourse || !coverFile) return;
    setCoverUploadError(null);
    try {
      await coverMutation.mutateAsync({ courseId: createdCourse.id, file: coverFile });
      onCreated(createdCourse, { coverUploaded: true });
    } catch (error) {
      setCoverUploadError(isApiClientError(error) ? error.message : "The cover image could not be uploaded.");
    }
  };

  const handleOpenChange = (next: boolean) => {
    if (!next && createdCourse) {
      // The course exists already; closing only skips the cover.
      onCreated(createdCourse, { coverUploaded: false });
      return;
    }
    if (!next && pending) return;
    onOpenChange(next);
  };

  const ids = courseSettingsFieldIds(ID_PREFIX);
  const teacherSummary = teacherUserId ? "You (current teacher)" : "Current teacher";

  const footer = createdCourse ? (
    <>
      <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} data-testid="add-course-skip-cover">
        Close without cover
      </Button>
      <Button type="button" onClick={retryCover} disabled={coverMutation.isPending} data-testid="add-course-retry-cover">
        {coverMutation.isPending ? "Uploading…" : "Retry cover upload"}
      </Button>
    </>
  ) : (
    <>
      <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending} data-testid="add-course-cancel">
        Cancel
      </Button>
      <Button type="submit" form="add-course-form" disabled={pending} aria-busy={pending} data-testid="add-course-submit">
        {createMutation.isPending ? "Creating…" : coverMutation.isPending ? "Uploading cover…" : "Create course"}
      </Button>
    </>
  );

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={handleOpenChange}
      title={isTeacher ? "Create New Course" : "Add New Course"}
      description={isTeacher ? "Create a course you will own as its teacher. Enrollment stays free." : undefined}
      footer={footer}
      testId="add-course-dialog"
    >
      {createdCourse ? (
        <Alert variant="destructive" data-testid="add-course-cover-error" className="mb-4">
          <AlertCircle aria-hidden="true" />
          <AlertTitle>Course created, but the cover image was not saved</AlertTitle>
          <AlertDescription>
            &ldquo;{createdCourse.name}&rdquo; now exists. {coverUploadError} Retry the upload, or close and add a
            cover later.
          </AlertDescription>
        </Alert>
      ) : null}
      {formError ? (
        <Alert variant="destructive" data-testid="add-course-form-error" className="mb-4">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}

      <form id="add-course-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <fieldset disabled={pending || Boolean(createdCourse)} className="flex min-w-0 flex-col gap-4">
          <CourseSettingsFields
            idPrefix={ID_PREFIX}
            control={control}
            register={register}
            errors={errors}
            teachers={{
              label: "Teacher",
              helper: isTeacher
                ? "This course is assigned to you. Teacher assignment is controlled by the server."
                : "The first teacher you tick is the primary teacher.",
              options: teachers.options,
              loading: teachers.loading,
              loadError: teachers.loadError,
              emptyText: "No approved, active teachers in this institute yet.",
              readOnly: isTeacher
                ? { summary: teacherSummary, note: "Only institute staff can change course teachers." }
                : null,
            }}
            pricing={{
              models: CREATABLE_PRICING_MODELS,
              placeholderOption: true,
              priceHelper: (model) =>
                model === "MONTHLY"
                  ? "Amount students pay for each month. You can change it later from the course's edit page."
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
                previewAlt="Selected cover preview"
                error={coverError}
                helper="Optional. JPEG, PNG or WebP, up to 5 MB. Uploaded after the course is created."
                onFileChosen={handleCoverChange}
                secondaryAction={
                  coverFile ? (
                    <Button type="button" variant="ghost" size="sm" onClick={() => selectCover(null)}>
                      Use default
                    </Button>
                  ) : null
                }
              />
            }
          />
        </fieldset>
      </form>
    </AdminFormDialog>
  );
}
