"use client";

import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds, nativeSelectClass } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { useCourses } from "@/lib/api/courses";
import { useKlassModules } from "@/lib/api/klass-admin";
import { useCopyModuleQuestions } from "@/lib/api/question-bank";

const ID_PREFIX = "copy-questions";

const copySchema = z.object({
  targetCourseId: z.string({ error: "Course is required." }).min(1, "Course is required."),
  targetModuleId: z.string({ error: "Module is required." }).min(1, "Module is required."),
});
type CopyValues = z.infer<typeof copySchema>;

/**
 * "Copy questions to another module": copies ALL questions of the current
 * module (Klass has no per-question selection, C1) to the end of the chosen
 * module as independent questions. The course list is whatever the backend
 * returns for the caller (a teacher only gets their own courses); the copy
 * endpoint re-checks authoring access to both courses.
 * Mount with a fresh `key` per open.
 */
export function CopyQuestionsDialog({
  open,
  onOpenChange,
  courseId,
  courseName,
  moduleId,
  moduleTitle,
  questionCount,
  onCopied,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  courseId: string;
  courseName: string;
  moduleId: string;
  moduleTitle: string;
  questionCount: number;
  onCopied: (copied: number, targetModuleTitle: string) => void;
}) {
  const [formError, setFormError] = useState<string | null>(null);
  const copy = useCopyModuleQuestions(courseId, moduleId);
  const coursesQuery = useCourses(undefined, { enabled: open });
  const {
    register,
    control,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<CopyValues>({
    resolver: zodResolver(copySchema),
    defaultValues: { targetCourseId: courseId, targetModuleId: "" },
  });
  const targetCourseId = useWatch({ control, name: "targetCourseId" });
  const modulesQuery = useKlassModules(targetCourseId ?? "");
  const moduleOptions = (modulesQuery.data ?? [])
    .filter((item) => item.id !== moduleId)
    .sort((a, b) => a.sequence - b.sequence);
  // The current course is always offered first (so the default selection
  // exists before the course list loads); the rest come from the backend.
  const courses = [
    { id: courseId, name: courseName },
    ...(coursesQuery.data?.content ?? []).filter((course) => course.id !== courseId),
  ];
  const pending = copy.isPending;

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      const result = await copy.mutateAsync(values);
      const target = moduleOptions.find((item) => item.id === values.targetModuleId);
      onCopied(result.copied, target?.title ?? "the selected module");
    } catch (error) {
      setFormError(mutationErrorMessage(error));
    }
  });

  const courseIds = fieldIds(ID_PREFIX, "course");
  const moduleIds = fieldIds(ID_PREFIX, "module");
  const courseError = errors.targetCourseId?.message ?? null;
  const moduleError = errors.targetModuleId?.message ?? null;
  const courseRegister = register("targetCourseId");

  let moduleHelper: string | null = null;
  if (targetCourseId && modulesQuery.status === "pending") moduleHelper = "Loading modules…";
  else if (targetCourseId && modulesQuery.status === "error") moduleHelper = "Modules could not be loaded.";
  else if (targetCourseId && modulesQuery.status === "success" && moduleOptions.length === 0)
    moduleHelper = "This course has no other module to copy into.";

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && pending) return;
        onOpenChange(next);
      }}
      title="Copy questions to another module"
      description={`All ${questionCount} ${questionCount === 1 ? "question" : "questions"} in “${moduleTitle}” are copied to the end of the module you choose. The copies are independent of the originals.`}
      testId="copy-questions-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending} data-testid="copy-cancel">
            Cancel
          </Button>
          <Button type="submit" form="copy-questions-form" disabled={pending} aria-busy={pending} data-testid="copy-submit">
            {pending ? "Copying…" : "Copy"}
          </Button>
        </>
      }
    >
      {formError ? (
        <Alert variant="destructive" className="mb-4" data-testid="copy-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}
      <form id="copy-questions-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <fieldset disabled={pending} className="flex min-w-0 flex-col gap-4">
          <FormField
            ids={courseIds}
            label="Course"
            required
            error={courseError}
            helper={coursesQuery.status === "error" ? "Courses could not be loaded." : null}
          >
            <select
              id={courseIds.inputId}
              className={nativeSelectClass}
              aria-required="true"
              aria-invalid={Boolean(courseError)}
              aria-describedby={describedBy(courseIds, coursesQuery.status === "error", Boolean(courseError))}
              data-testid="copy-course"
              {...courseRegister}
              onChange={(event) => {
                void courseRegister.onChange(event);
                setValue("targetModuleId", "");
              }}
            >
              <option value="">Select a course</option>
              {courses.map((course) => (
                <option key={course.id} value={course.id}>
                  {course.name}
                </option>
              ))}
            </select>
          </FormField>
          <FormField ids={moduleIds} label="Module" required error={moduleError} helper={moduleHelper}>
            <select
              id={moduleIds.inputId}
              className={nativeSelectClass}
              aria-required="true"
              aria-invalid={Boolean(moduleError)}
              aria-describedby={describedBy(moduleIds, Boolean(moduleHelper), Boolean(moduleError))}
              disabled={!targetCourseId || modulesQuery.status !== "success" || moduleOptions.length === 0}
              data-testid="copy-module"
              {...register("targetModuleId")}
            >
              <option value="">Select a module</option>
              {moduleOptions.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title}
                </option>
              ))}
            </select>
          </FormField>
        </fieldset>
      </form>
    </AdminFormDialog>
  );
}
