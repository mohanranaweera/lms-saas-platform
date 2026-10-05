"use client";

import { useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  useUpdateVideoChannelSettings,
  type ChannelSettingsView,
  type ChannelTeacher,
} from "@/lib/api/video-providers";
import { channelSettingsSchema, type ChannelSettingsFormValues } from "@/lib/validation/video-channel";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

/** "All teachers" / "1 teacher" / "N teachers" - the card's Klass-style summary. */
export function uploadersSummary(settings: ChannelSettingsView): string {
  if (settings.allTeachersAllowed) return "All teachers";
  const count = settings.allowedTeachers.length;
  return count === 1 ? "1 teacher" : `${count} teachers`;
}

function teacherLabel(teacher: ChannelTeacher): string {
  return teacher.name ?? teacher.email ?? "Unnamed teacher";
}

/**
 * VIDEO Phase 5b (ADR-018 Amendment 4): the institute channel's name,
 * description and which teachers may upload to it. No teacher selected means
 * every teacher may upload. Saved separately from Connect. The backend
 * enforces the list on every upload; this form only edits it.
 */
export function TenantYouTubeChannelSettings({
  settings,
  canManage,
}: {
  settings: ChannelSettingsView;
  canManage: boolean;
}) {
  const ids = { name: useId(), description: useId(), teachers: useId() };
  const mutation = useUpdateVideoChannelSettings();
  const [saved, setSaved] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    control,
    formState: { errors },
  } = useForm<ChannelSettingsFormValues>({
    resolver: zodResolver(channelSettingsSchema),
    defaultValues: {
      displayName: settings.displayName ?? "",
      description: settings.description ?? "",
      uploaders: settings.allTeachersAllowed ? "ALL" : "SELECTED",
      teacherIds: settings.allowedTeachers.map((teacher) => teacher.userId),
    },
  });
  const uploaders = useWatch({ control, name: "uploaders" });
  const teacherIds = useWatch({ control, name: "teacherIds" });
  const serverFieldError = isApiClientError(mutation.error) && mutation.error.fieldErrors.length > 0;
  // A teacher already on the list stays visible even if the lookup no longer returns them.
  const choices = [
    ...settings.availableTeachers,
    ...settings.allowedTeachers.filter(
      (allowed) => !settings.availableTeachers.some((teacher) => teacher.userId === allowed.userId)
    ),
  ];

  function toggleTeacher(userId: string, checked: boolean) {
    const next = checked ? [...teacherIds, userId] : teacherIds.filter((id) => id !== userId);
    setValue("teacherIds", next, { shouldValidate: Boolean(errors.teacherIds) });
  }

  const onSubmit = handleSubmit((values) => {
    setSaved(false);
    mutation.mutate(
      {
        displayName: values.displayName || null,
        description: values.description || null,
        allowedTeacherIds: values.uploaders === "ALL" ? [] : values.teacherIds,
      },
      {
        onSuccess: () => setSaved(true),
        onError: (error) => {
          if (!isApiClientError(error)) return;
          for (const fieldError of error.fieldErrors) {
            if (fieldError.field === "displayName" || fieldError.field === "description") {
              setError(fieldError.field, { message: fieldError.message });
            } else if (fieldError.field === "allowedTeacherIds") {
              setError("teacherIds", { message: "Some of these teachers are no longer part of your institute." });
            }
          }
        },
      }
    );
  });

  return (
    <form
      aria-labelledby="youtube-channel-settings-heading"
      noValidate
      onSubmit={onSubmit}
      className="flex flex-col gap-4 rounded-lg border border-border p-4"
    >
      <div>
        <h2 id="youtube-channel-settings-heading" className="text-base font-medium text-foreground">
          Channel settings
        </h2>
        <p className="text-sm text-muted-foreground">
          How the channel is named in the LMS and which teachers may upload videos to it. Staff with Media Library
          access can always upload.
        </p>
      </div>

      <fieldset disabled={!canManage || mutation.isPending} className="flex flex-col gap-4">
        <legend className="sr-only">Channel settings</legend>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.name}>Name (optional)</Label>
          <Input
            id={ids.name}
            maxLength={100}
            placeholder="e.g. Institute lessons"
            aria-invalid={Boolean(errors.displayName)}
            aria-describedby={errors.displayName ? `${ids.name}-error` : undefined}
            {...register("displayName")}
          />
          {errors.displayName ? (
            <p id={`${ids.name}-error`} className="text-sm text-destructive">
              {errors.displayName.message}
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
          {choices.length === 0 ? (
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

      {mutation.isError && !serverFieldError ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {isApiClientError(mutation.error) ? mutation.error.message : "The settings could not be saved."}
          </AlertDescription>
        </Alert>
      ) : null}
      {saved && !mutation.isPending ? (
        <p role="status" className="text-sm text-muted-foreground">
          Channel settings saved.
        </p>
      ) : null}

      {canManage ? (
        <Button type="submit" className="w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save channel settings"}
        </Button>
      ) : (
        <p className="text-sm text-muted-foreground">Only a Tenant Admin can change these settings.</p>
      )}
    </form>
  );
}
