"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ErrorState } from "@/components/states/error-state";
import { LiveRegion } from "@/components/ui/live-region";
import { useUpdateClassSession, type ClassSessionResponse } from "@/lib/api/class-sessions";
import { isApiClientError } from "@/lib/api/error";
import { datetimeLocalToIso, isoToDatetimeLocal } from "@/lib/datetime";
import { z } from "zod";

/**
 * Reuses `classSessionFormSchema`'s window-validity refinements but drops
 * `courseId`/`lessonId` entirely — `ClassSessionUpdateRequest` has no such
 * fields on the backend (a session's course/lesson association is fixed at
 * creation), so this is a deliberately narrower schema, not a subset view of
 * the create form's.
 */
const classSessionEditSchema = z
  .object({
    title: z.string().trim().min(1, "Title is required.").max(255, "Title must be 255 characters or fewer."),
    description: z.string().trim().max(5000, "Description must be 5000 characters or fewer."),
    scheduledStart: z.string().min(1, "Start date/time is required."),
    scheduledEnd: z.string().min(1, "End date/time is required."),
  })
  .refine(
    (values) => {
      const start = new Date(values.scheduledStart);
      const end = new Date(values.scheduledEnd);
      return !Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()) && end > start;
    },
    { message: "End time must be after start time.", path: ["scheduledEnd"] }
  );

type ClassSessionEditFormValues = z.infer<typeof classSessionEditSchema>;

/**
 * Teacher "Edit Live Class" form — reachable/enabled only while
 * `status === "SCHEDULED"` (`PATCH /v1/class-sessions/{id}` returns `409`
 * otherwise); fields lock read-only once the session has left `SCHEDULED`
 * rather than letting a submit round-trip fail with a confusing 409, mirroring
 * `exam-draft-form.tsx`'s identical convention.
 */
export function ClassSessionEditForm({ session }: { session: ClassSessionResponse }) {
  const router = useRouter();
  const isScheduled = session.status === "SCHEDULED";
  const mutation = useUpdateClassSession(session.id);
  const [pageError, setPageError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isDirty },
  } = useForm<ClassSessionEditFormValues>({
    resolver: zodResolver(classSessionEditSchema),
    defaultValues: {
      title: session.title,
      description: session.description ?? "",
      scheduledStart: isoToDatetimeLocal(session.scheduledStart),
      scheduledEnd: isoToDatetimeLocal(session.scheduledEnd),
    },
  });

  const isSubmitting = mutation.isPending;
  const disabled = !isScheduled || isSubmitting;

  useEffect(() => {
    if (!isDirty || !isScheduled) return;
    function handleBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
    }
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [isDirty, isScheduled]);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({
        title: values.title,
        description: values.description ? values.description : undefined,
        scheduledStart: datetimeLocalToIso(values.scheduledStart),
        scheduledEnd: datetimeLocalToIso(values.scheduledEnd),
      });
      router.push(`/teacher/live-classes/${session.id}?updated=1`);
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={isSubmitting} onSubmit={onSubmit}>
      <LiveRegion message={isSubmitting ? "Saving changes…" : ""} />
      {!isScheduled ? (
        <Alert>
          <AlertDescription>
            This class session is no longer Scheduled (it is now {session.status}) and can no longer be
            edited here.
          </AlertDescription>
        </Alert>
      ) : null}
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <fieldset disabled={disabled} className="flex flex-col gap-4">
        <legend className="sr-only">Edit live class</legend>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="live-class-edit-title">Title</Label>
          <Input
            id="live-class-edit-title"
            aria-required="true"
            aria-invalid={!!errors.title}
            aria-describedby={errors.title ? "live-class-edit-title-error" : undefined}
            {...register("title")}
          />
          {errors.title ? (
            <p id="live-class-edit-title-error" role="alert" className="text-xs text-destructive">
              {errors.title.message}
            </p>
          ) : null}
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="live-class-edit-description">Description (optional)</Label>
          <Textarea id="live-class-edit-description" {...register("description")} />
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="live-class-edit-start">Start</Label>
            <Input
              id="live-class-edit-start"
              type="datetime-local"
              aria-required="true"
              aria-invalid={!!errors.scheduledStart}
              aria-describedby={errors.scheduledStart ? "live-class-edit-start-error" : undefined}
              {...register("scheduledStart")}
            />
            {errors.scheduledStart ? (
              <p id="live-class-edit-start-error" role="alert" className="text-xs text-destructive">
                {errors.scheduledStart.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="live-class-edit-end">End</Label>
            <Input
              id="live-class-edit-end"
              type="datetime-local"
              aria-required="true"
              aria-invalid={!!errors.scheduledEnd}
              aria-describedby={errors.scheduledEnd ? "live-class-edit-end-error" : undefined}
              {...register("scheduledEnd")}
            />
            {errors.scheduledEnd ? (
              <p id="live-class-edit-end-error" role="alert" className="text-xs text-destructive">
                {errors.scheduledEnd.message}
              </p>
            ) : null}
          </div>
        </div>
      </fieldset>

      {isScheduled ? (
        <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting} className="w-full sm:w-fit">
          {isSubmitting ? "Saving…" : "Save changes"}
        </Button>
      ) : null}
    </form>
  );
}
