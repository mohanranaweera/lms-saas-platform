"use client";

import { useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { isApiClientError } from "@/lib/api/error";
import {
  useCreateVideoIntegration,
  useIntegrationTeacherChoices,
  useUpdateVideoIntegration,
  type ChannelTeacher,
  type VideoProviderAccountView,
} from "@/lib/api/video-providers";
import { videoIntegrationSchema, type VideoIntegrationFormValues } from "@/lib/validation/video-channel";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";

function teacherLabel(teacher: ChannelTeacher): string {
  return teacher.name ?? teacher.email ?? "Unnamed teacher";
}

/**
 * VIDEO Phase 5d: add or edit a YouTube integration card - Klass's "Add New
 * Integration": name, description and which teachers may upload (none
 * selected = all teachers). Connecting the channel is a separate step on the
 * card. Full-screen sheet on phones. The backend re-validates everything.
 */
export function VideoIntegrationSheet({
  open,
  onOpenChange,
  integration,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The card being edited; omitted to add a new one. */
  integration?: VideoProviderAccountView;
}) {
  const formId = useId();
  const ids = { name: useId(), description: useId(), teachers: useId() };
  const create = useCreateVideoIntegration();
  const update = useUpdateVideoIntegration();
  const mutation = integration ? update : create;
  const teachersQuery = useIntegrationTeacherChoices(open);
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    control,
    reset,
    formState: { errors },
  } = useForm<VideoIntegrationFormValues>({
    resolver: zodResolver(videoIntegrationSchema),
    defaultValues: {
      name: integration?.name ?? "",
      description: integration?.description ?? "",
      uploaders: integration && !integration.allTeachersAllowed ? "SELECTED" : "ALL",
      teacherIds: integration?.allowedTeachers.map((t) => t.userId) ?? [],
    },
  });
  const uploaders = useWatch({ control, name: "uploaders" });
  const teacherIds = useWatch({ control, name: "teacherIds" });
  const choices = [
    ...(teachersQuery.data ?? []),
    ...(integration?.allowedTeachers ?? []).filter(
      (allowed) => !(teachersQuery.data ?? []).some((teacher) => teacher.userId === allowed.userId)
    ),
  ];

  function close(next: boolean) {
    if (!next && mutation.isPending) return;
    if (!next) {
      reset();
      setPageError(null);
    }
    onOpenChange(next);
  }

  function toggleTeacher(userId: string, checked: boolean) {
    const next = checked ? [...teacherIds, userId] : teacherIds.filter((id) => id !== userId);
    setValue("teacherIds", next, { shouldValidate: Boolean(errors.teacherIds) });
  }

  const onSubmit = handleSubmit((values) => {
    setPageError(null);
    const input = {
      name: values.name,
      description: values.description || null,
      allowedTeacherIds: values.uploaders === "ALL" ? [] : values.teacherIds,
    };
    const callbacks = {
      onSuccess: () => close(false),
      onError: (error: unknown) => {
        if (!isApiClientError(error)) {
          setPageError("The integration could not be saved. Please try again.");
          return;
        }
        if (error.code === "VIDEO_INTEGRATION_NAME_TAKEN") {
          setError("name", { message: error.message });
          return;
        }
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "name" || fieldError.field === "description") {
            setError(fieldError.field, { message: fieldError.message });
            mapped = true;
          } else if (fieldError.field === "allowedTeacherIds") {
            setError("teacherIds", { message: "Some of these teachers are no longer part of your institute." });
            mapped = true;
          }
        }
        if (!mapped) setPageError(error.message);
      },
    };
    if (integration) {
      update.mutate({ integrationId: integration.integrationId, ...input }, callbacks);
    } else {
      create.mutate(input, callbacks);
    }
  });

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent
        side="right"
        className="w-full data-[side=right]:w-full data-[side=right]:sm:max-w-full data-[side=right]:md:max-w-md"
      >
        <SheetHeader>
          <SheetTitle>{integration ? "Edit YouTube integration" : "Add YouTube integration"}</SheetTitle>
          <SheetDescription>
            {integration
              ? "Change the name, description or who can upload. The connected channel stays as it is."
              : "Name the channel and choose who can upload. You connect the YouTube channel on its card afterwards."}
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4">
          {pageError ? (
            <Alert variant="destructive" role="alert">
              <AlertDescription>{pageError}</AlertDescription>
            </Alert>
          ) : null}
          <form id={formId} noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
            <fieldset disabled={mutation.isPending} className="flex flex-col gap-4">
              <legend className="sr-only">Integration details</legend>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={ids.name}>Name</Label>
                <Input
                  id={ids.name}
                  maxLength={100}
                  placeholder="e.g. AOC YT"
                  aria-invalid={Boolean(errors.name)}
                  aria-describedby={errors.name ? `${ids.name}-error` : undefined}
                  {...register("name")}
                />
                {errors.name ? (
                  <p id={`${ids.name}-error`} className="text-sm text-destructive">
                    {errors.name.message}
                  </p>
                ) : null}
              </div>
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={ids.description}>Description (optional)</Label>
                <Textarea
                  id={ids.description}
                  rows={3}
                  maxLength={500}
                  aria-invalid={Boolean(errors.description)}
                  aria-describedby={errors.description ? `${ids.description}-error` : undefined}
                  {...register("description")}
                />
                {errors.description ? (
                  <p id={`${ids.description}-error`} className="text-sm text-destructive">
                    {errors.description.message}
                  </p>
                ) : null}
              </div>

              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1 text-sm font-medium text-foreground">Who can upload</legend>
                <label className="flex items-center gap-2 text-sm">
                  <input type="radio" value="ALL" {...register("uploaders")} />
                  All teachers
                </label>
                <label className="flex items-center gap-2 text-sm">
                  <input type="radio" value="SELECTED" disabled={choices.length === 0} {...register("uploaders")} />
                  Selected teachers only
                </label>
                {teachersQuery.isPending ? (
                  <p role="status" aria-busy="true" className="text-sm text-muted-foreground">
                    Loading teachers…
                  </p>
                ) : choices.length === 0 ? (
                  <p className="text-sm text-muted-foreground">Your institute has no teachers yet.</p>
                ) : null}
              </fieldset>

              {uploaders === "SELECTED" && choices.length > 0 ? (
                <fieldset
                  className="flex flex-col gap-2 rounded-md border border-border p-3"
                  aria-describedby={errors.teacherIds ? `${ids.teachers}-error` : undefined}
                >
                  <legend className="px-1 text-sm font-medium text-foreground">Allowed teachers</legend>
                  <ul className="flex max-h-64 flex-col gap-2 overflow-y-auto">
                    {choices.map((teacher) => (
                      <li key={teacher.userId}>
                        <label className="flex items-start gap-2 text-sm">
                          <input
                            type="checkbox"
                            className="mt-1"
                            checked={teacherIds.includes(teacher.userId)}
                            onChange={(event) => toggleTeacher(teacher.userId, event.target.checked)}
                          />
                          <span className="flex flex-col">
                            <span className="text-foreground">{teacherLabel(teacher)}</span>
                            {teacher.name && teacher.email ? (
                              <span className="break-all text-xs text-muted-foreground">{teacher.email}</span>
                            ) : null}
                          </span>
                        </label>
                      </li>
                    ))}
                  </ul>
                  {errors.teacherIds ? (
                    <p id={`${ids.teachers}-error`} className="text-sm text-destructive">
                      {errors.teacherIds.message}
                    </p>
                  ) : null}
                </fieldset>
              ) : null}
            </fieldset>
          </form>
        </div>

        <SheetFooter className="flex-row justify-end gap-2">
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => close(false)}>
            Cancel
          </Button>
          <Button type="submit" form={formId} disabled={mutation.isPending} aria-busy={mutation.isPending}>
            {mutation.isPending ? "Saving…" : integration ? "Save changes" : "Create integration"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
