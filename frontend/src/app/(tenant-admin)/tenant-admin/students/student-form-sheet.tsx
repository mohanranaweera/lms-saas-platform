"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useForm, useWatch, type FieldPath } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Check, Copy, Eye, EyeOff, Lock } from "lucide-react";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { ProfilePhotoEditor } from "@/components/students/profile-photo-editor";
import { useCourses } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import {
  useRegisterStudent,
  useStudentDetail,
  useUpdateStudentDetail,
  type StudentDetail,
  type StudentDetailUpdateInput,
} from "@/lib/api/student-management";
import {
  studentCreateFormSchema,
  studentEditFormSchema,
  type StudentFormValues,
} from "@/lib/validation/student-management";
import { cn } from "@/lib/utils";

type Mode = { kind: "create" } | { kind: "edit"; studentId: string };

const EMPTY: StudentFormValues = {
  email: "",
  password: "",
  firstName: "",
  lastName: "",
  whatsappNumber: "",
  dateOfBirth: "",
  mobile: "",
  addressLine: "",
  addressLine2: "",
  city: "",
  postalCode: "",
  guardianName: "",
  guardianPhone: "",
  guardianNic: "",
  courseIds: [],
  fixedPhone: "",
  school: "",
  alYear: "",
  stream: "",
};

const FIELD_NAMES = new Set(Object.keys(EMPTY));

function valuesFromDetail(detail: StudentDetail): StudentFormValues {
  return {
    email: detail.email ?? "",
    password: "",
    firstName: detail.firstName ?? "",
    lastName: detail.lastName ?? "",
    whatsappNumber: detail.whatsappNumber ?? "",
    dateOfBirth: detail.dateOfBirth ?? "",
    mobile: detail.mobile ?? "",
    addressLine: detail.addressLine ?? "",
    addressLine2: detail.addressLine2 ?? "",
    city: detail.city ?? "",
    postalCode: detail.postalCode ?? "",
    guardianName: detail.guardianName ?? "",
    guardianPhone: detail.guardianPhone ?? "",
    guardianNic: detail.guardianNic ?? "",
    courseIds: [],
    fixedPhone: detail.fixedPhone ?? "",
    school: detail.school ?? "",
    alYear: detail.alYear !== null && detail.alYear !== undefined ? String(detail.alYear) : "",
    stream: detail.stream ?? "",
  };
}

const blankToNull = (value: string) => (value.trim() === "" ? null : value.trim());

function toPayload(values: StudentFormValues, lockedCourseIds: string[]): StudentDetailUpdateInput {
  return {
    email: values.email.trim(),
    firstName: values.firstName.trim(),
    lastName: values.lastName.trim(),
    whatsappNumber: blankToNull(values.whatsappNumber),
    dateOfBirth: blankToNull(values.dateOfBirth),
    mobile: values.mobile.trim(),
    addressLine: blankToNull(values.addressLine),
    addressLine2: blankToNull(values.addressLine2),
    city: blankToNull(values.city),
    postalCode: blankToNull(values.postalCode),
    guardianName: blankToNull(values.guardianName),
    guardianPhone: blankToNull(values.guardianPhone),
    guardianNic: blankToNull(values.guardianNic),
    courseIds: Array.from(new Set([...lockedCourseIds, ...values.courseIds])),
    fixedPhone: blankToNull(values.fixedPhone),
    school: blankToNull(values.school),
    alYear: values.alYear.trim() === "" ? null : Number(values.alYear),
    stream: blankToNull(values.stream),
  };
}

export interface StudentFormSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  mode: Mode;
  /** Called after a successful save with a short confirmation message. */
  onSaved?: (message: string, student: StudentDetail) => void;
}

/**
 * Add / Edit Student (STU-MGMT-02 §3, Klass field order). One sheet for both:
 * index is server-assigned ("Assigned automatically" on create, read-only on
 * edit); password only on create (blank → a one-time temporary password from
 * the server); courses ≥1 on create and add-only on edit (enrolled courses are
 * checked + locked — revoking stays the audited Enrollments flow). Course
 * selection is free participation only (ADR-028): no order or payment.
 */
export function StudentFormSheet({ open, onOpenChange, mode, onSaved }: StudentFormSheetProps) {
  const editId = mode.kind === "edit" ? mode.studentId : null;
  const detailQuery = useStudentDetail(editId, { enabled: open && editId !== null });
  const [tempPassword, setTempPassword] = useState<{ name: string; password: string } | null>(null);
  const [copied, setCopied] = useState(false);

  function close() {
    setTempPassword(null);
    setCopied(false);
    onOpenChange(false);
  }

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => {
        if (!next) close();
        else onOpenChange(true);
      }}
    >
      <SheetContent
        side="right"
        className="w-full data-[side=right]:w-full data-[side=right]:sm:max-w-full data-[side=right]:md:max-w-2xl"
        data-testid="student-form-sheet"
      >
        <SheetHeader>
          <SheetTitle>{mode.kind === "create" ? "Add New Student" : "Edit Student"}</SheetTitle>
          <SheetDescription>
            {mode.kind === "create"
              ? "Register a student and enrol them free of charge in at least one course. Paid access is separate."
              : "Update this student's details. New courses are added free of charge; existing enrolments are kept."}
          </SheetDescription>
        </SheetHeader>

        {tempPassword ? (
          <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4" data-testid="temporary-password-notice">
            <Alert>
              <AlertDescription>
                {tempPassword.name} was added. Share this one-time temporary password with them outside this app —
                they must change it on first sign-in. It will not be shown again.
              </AlertDescription>
            </Alert>
            <div className="flex items-center gap-2 rounded-md border border-border bg-muted/40 px-3 py-2">
              <code className="flex-1 overflow-x-auto text-sm font-medium" data-testid="temporary-password">
                {tempPassword.password}
              </code>
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label="Copy temporary password"
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(tempPassword.password);
                    setCopied(true);
                  } catch {
                    /* still visible for manual copy */
                  }
                }}
              >
                {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
              </Button>
            </div>
            <p role="status" className="text-xs text-muted-foreground">
              {copied ? "Copied to clipboard." : ""}
            </p>
            <div className="flex justify-end">
              <Button type="button" onClick={close}>
                Done
              </Button>
            </div>
          </div>
        ) : mode.kind === "edit" ? (
          detailQuery.status === "pending" ? (
            <LoadingState label="Loading student…" />
          ) : detailQuery.status === "error" ? (
            <div className="px-4">
              <ErrorState
                message={isApiClientError(detailQuery.error) ? detailQuery.error.message : "The student couldn't be loaded."}
                onRetry={() => void detailQuery.refetch()}
              />
            </div>
          ) : detailQuery.data.id === mode.studentId ? (
            <StudentForm
              key={detailQuery.data.id}
              mode={mode}
              detail={detailQuery.data}
              onCancel={close}
              onSaved={(message, student) => {
                onSaved?.(message, student);
                close();
              }}
            />
          ) : (
            <LoadingState label="Loading student…" />
          )
        ) : open ? (
          <StudentForm
            mode={mode}
            detail={null}
            onCancel={close}
            onSaved={(message, student, password) => {
              onSaved?.(message, student);
              if (password) setTempPassword({ name: student.fullName, password });
              else close();
            }}
          />
        ) : null}
      </SheetContent>
    </Sheet>
  );
}

function StudentForm({
  mode,
  detail,
  onCancel,
  onSaved,
}: {
  mode: Mode;
  detail: StudentDetail | null;
  onCancel: () => void;
  onSaved: (message: string, student: StudentDetail, temporaryPassword?: string | null) => void;
}) {
  const isCreate = mode.kind === "create";
  const coursesQuery = useCourses({ size: 100 });
  const registerStudent = useRegisterStudent();
  const update = useUpdateStudentDetail(detail?.id ?? "");
  const mutationPending = registerStudent.isPending || update.isPending;
  const [showPassword, setShowPassword] = useState(false);
  const [courseFilter, setCourseFilter] = useState("");
  const [pageError, setPageError] = useState<string | null>(null);
  const lockedCourseIds = useMemo(() => detail?.enrolledCourseIds ?? [], [detail]);

  const form = useForm<StudentFormValues>({
    resolver: zodResolver(isCreate ? studentCreateFormSchema : studentEditFormSchema),
    defaultValues: detail ? valuesFromDetail(detail) : EMPTY,
  });
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    formState: { errors },
  } = form;

  useEffect(() => {
    if (detail) form.reset(valuesFromDetail(detail));
  }, [detail, form]);

  const selectedCourses = useWatch({ control: form.control, name: "courseIds" });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      if (isCreate) {
        const payload = { ...toPayload(values, []), password: values.password.trim() === "" ? null : values.password };
        const result = await registerStudent.mutateAsync(payload);
        onSaved(`${result.student.fullName} was added.`, result.student, result.temporaryPassword ?? null);
      } else {
        const saved = await update.mutateAsync(toPayload(values, lockedCourseIds));
        onSaved(`${saved.fullName} was updated.`, saved);
      }
    } catch (error) {
      if (isApiClientError(error)) {
        let mapped = 0;
        for (const fieldError of error.fieldErrors) {
          const field = fieldError.field.replace(/^form\./, "");
          if (FIELD_NAMES.has(field)) {
            setError(field as FieldPath<StudentFormValues>, { type: "server", message: fieldError.message });
            mapped += 1;
          }
        }
        if (mapped === 0 && error.code === "CONFLICT" && /mobile/i.test(error.message)) {
          setError("mobile", { type: "server", message: error.message });
          return;
        }
        if (mapped === 0 && error.code === "CONFLICT") {
          setError("email", { type: "server", message: error.message });
          return;
        }
        if (mapped < error.fieldErrors.length || mapped === 0) setPageError(error.message);
        return;
      }
      setPageError("An unexpected error occurred. Please try again.");
    }
  });

  const courses = coursesQuery.data?.content ?? [];
  const term = courseFilter.trim().toLowerCase();
  const visibleCourses = term ? courses.filter((course) => course.name.toLowerCase().includes(term)) : courses;
  const courseError = errors.courseIds?.message;

  function toggleCourse(id: string, checked: boolean) {
    const next = new Set(selectedCourses);
    if (checked) next.add(id);
    else next.delete(id);
    setValue("courseIds", Array.from(next), { shouldValidate: form.formState.isSubmitted });
  }

  const field = (
    name: Exclude<FieldPath<StudentFormValues>, "courseIds" | `courseIds.${number}`>,
    label: string,
    options: { readOnly?: boolean; required?: boolean; type?: string; autoComplete?: string; inputMode?: "tel" | "numeric" | "email"; full?: boolean; helper?: string; trailing?: ReactNode } = {}
  ) => {
    const id = `student-form-${name}`;
    const error = errors[name]?.message;
    const readOnly = options.readOnly === true;
    const helperId = options.helper ? `${id}-helper` : undefined;
    return (
      <div className={cn("flex flex-col gap-1.5", options.full && "sm:col-span-2")} data-field={name}>
        <Label htmlFor={id}>
          {label}
          {options.required ? <span aria-hidden="true"> *</span> : null}
        </Label>
        <div className="relative">
          <Input
            id={id}
            type={options.type ?? "text"}
            autoComplete={options.autoComplete ?? "off"}
            inputMode={options.inputMode}
            aria-required={options.required || undefined}
            aria-invalid={error ? true : undefined}
            aria-describedby={[helperId, error ? `${id}-error` : undefined].filter(Boolean).join(" ") || undefined}
            className={cn(options.trailing && "pr-9", readOnly && "bg-muted/50 text-muted-foreground")}
            readOnly={readOnly || undefined}
            aria-readonly={readOnly || undefined}
            {...register(name)}
          />
          {options.trailing}
        </div>
        {options.helper ? (
          <p id={helperId} className="text-xs text-muted-foreground">
            {options.helper}
          </p>
        ) : null}
        {error ? (
          <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
            {error}
          </p>
        ) : null}
      </div>
    );
  };

  return (
    <>
      <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4 pb-4">
        {!isCreate && detail ? (
          <ProfilePhotoEditor owner={{ kind: "staff", studentId: detail.id }} name={detail.fullName} />
        ) : null}

        {pageError ? (
          <Alert variant="destructive">
            <AlertDescription role="alert">{pageError}</AlertDescription>
          </Alert>
        ) : null}

        <form id="student-form" onSubmit={onSubmit} noValidate aria-busy={mutationPending}>
          <fieldset disabled={mutationPending} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <legend className="sr-only">{isCreate ? "Add New Student" : "Edit Student"}</legend>
            <div className="flex flex-col gap-1.5 sm:col-span-2" data-field="indexNumber">
              <Label htmlFor="student-form-indexNumber">Index Number</Label>
              <Input
                id="student-form-indexNumber"
                readOnly
                aria-readonly="true"
                value={isCreate ? "Assigned automatically" : (detail?.indexNumber ?? "—")}
                aria-describedby="student-form-indexNumber-helper"
                className="bg-muted/50 text-muted-foreground"
              />
              <p id="student-form-indexNumber-helper" className="text-xs text-muted-foreground">
                {isCreate ? "The next index number is assigned when the student is saved." : "Index numbers can't be changed."}
              </p>
            </div>

            {field("email", "Email", {
              required: true,
              type: "email",
              autoComplete: "email",
              inputMode: "email",
              full: !isCreate,
              // The backend refuses an email change on edit (login identity); shown read-only.
              readOnly: !isCreate,
              helper: isCreate ? undefined : "Email is the sign-in identity and can't be changed here.",
            })}
            {isCreate
              ? field("password", "Password", {
                  type: showPassword ? "text" : "password",
                  autoComplete: "new-password",
                  helper: "Optional. Leave blank to generate a one-time temporary password.",
                  trailing: (
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      className="absolute top-0.5 right-0.5"
                      aria-label={showPassword ? "Hide password" : "Show password"}
                      aria-pressed={showPassword}
                      onClick={() => setShowPassword((value) => !value)}
                    >
                      {showPassword ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                    </Button>
                  ),
                })
              : null}
            {field("firstName", "First Name", { required: true, autoComplete: "given-name" })}
            {field("lastName", "Last Name", { required: true, autoComplete: "family-name" })}
            {field("whatsappNumber", "WhatsApp Number", { type: "tel", inputMode: "tel", full: true })}
            {field("dateOfBirth", "Date of Birth", { type: "date", full: true, helper: "Leave blank if unknown." })}
            {field("mobile", "Mobile Number", { required: true, type: "tel", inputMode: "tel", autoComplete: "tel" })}
            {field("addressLine", "Address Line 1", { autoComplete: "address-line1" })}
            {field("addressLine2", "Address Line 2", { autoComplete: "address-line2" })}
            {field("city", "City", { autoComplete: "address-level2" })}
            {field("postalCode", "Postal Code", { autoComplete: "postal-code" })}
            {field("guardianName", "Name of the Guardian", { full: true })}
            {field("guardianPhone", "Contact Number of the Guardian", { type: "tel", inputMode: "tel" })}
            {field("guardianNic", "NIC of Guardian", { helper: "Staff only. Never printed on QR labels or exports." })}

            <fieldset className="flex flex-col gap-2 sm:col-span-2" data-field="courseIds" aria-describedby="student-form-courses-help">
              <legend className="text-sm font-medium">
                Courses{isCreate ? <span aria-hidden="true"> *</span> : null}
              </legend>
              <p id="student-form-courses-help" className="text-xs text-muted-foreground">
                {isCreate
                  ? "Select at least one course. Enrolment is free; paid lessons are unlocked separately after payment."
                  : "Tick courses to add (free). Already-enrolled courses are locked here — use Manage › Enrollments to revoke."}
              </p>
              <Label htmlFor="student-form-course-filter" className="sr-only">
                Filter courses
              </Label>
              <Input
                id="student-form-course-filter"
                type="search"
                placeholder="Filter courses"
                value={courseFilter}
                onChange={(event) => setCourseFilter(event.target.value)}
              />
              <div
                className={cn(
                  "max-h-56 overflow-y-auto rounded-lg border p-2",
                  courseError ? "border-destructive" : "border-border"
                )}
                data-testid="course-options"
              >
                {coursesQuery.status === "pending" ? (
                  <p className="text-sm text-muted-foreground">Loading courses…</p>
                ) : coursesQuery.status === "error" ? (
                  <p role="alert" className="text-sm text-destructive">
                    Courses couldn&apos;t be loaded.{" "}
                    <button type="button" className="underline" onClick={() => void coursesQuery.refetch()}>
                      Try again
                    </button>
                  </p>
                ) : courses.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No courses exist yet. Create a course first.</p>
                ) : visibleCourses.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No courses match “{courseFilter}”.</p>
                ) : (
                  visibleCourses.map((course) => {
                    const locked = lockedCourseIds.includes(course.id);
                    const checked = locked || selectedCourses.includes(course.id);
                    return (
                      <label key={course.id} className="flex items-center gap-2 py-1 text-sm">
                        <input
                          type="checkbox"
                          className="size-4"
                          checked={checked}
                          disabled={locked}
                          onChange={(event) => toggleCourse(course.id, event.target.checked)}
                        />
                        <span className={locked ? "text-muted-foreground" : undefined}>{course.name}</span>
                        {locked ? (
                          <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                            <Lock className="size-3" aria-hidden="true" /> Enrolled
                          </span>
                        ) : null}
                      </label>
                    );
                  })
                )}
              </div>
              <p className="text-xs text-muted-foreground" aria-live="polite">
                {selectedCourses.length} new course{selectedCourses.length === 1 ? "" : "s"} selected
                {lockedCourseIds.length ? ` · ${lockedCourseIds.length} already enrolled` : ""}
              </p>
              {courseError ? (
                <p role="alert" className="text-xs text-destructive">
                  {courseError}
                </p>
              ) : null}
            </fieldset>

            <h3 className="pt-2 text-sm font-semibold text-foreground sm:col-span-2">Additional details</h3>
            {field("fixedPhone", "Home Telephone", { type: "tel", inputMode: "tel" })}
            {field("school", "School")}
            {field("alYear", "A/L Year", { inputMode: "numeric", helper: "e.g. 2027" })}
            {field("stream", "A/L Stream")}
          </fieldset>
        </form>
      </div>
      <SheetFooter className="flex-row justify-end gap-2 border-t border-border">
        <Button type="button" variant="outline" disabled={mutationPending} onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" form="student-form" disabled={mutationPending}>
          {mutationPending ? "Saving…" : isCreate ? "Create Student" : "Save Changes"}
        </Button>
      </SheetFooter>
      <span role="status" aria-live="polite" className="sr-only">
        {mutationPending ? "Saving student…" : ""}
      </span>
    </>
  );
}
