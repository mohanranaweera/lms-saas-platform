"use client";

import { useMemo, useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { MultiSelect } from "@/components/ui/multi-select";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { EXAM_TYPE_LABELS } from "@/components/exams/config/exam-shared";
import { isApiClientError } from "@/lib/api/error";
import { useExamDirectory } from "@/lib/api/exam-config";
import {
  leaderboardExamIds,
  useCreateLeaderboard,
  useLeaderboard,
  useUpdateLeaderboard,
  type LeaderboardDetail,
} from "@/lib/api/exam-results";

export const LEADERBOARD_NAME_MAX = 120;

const schema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Enter a name for the leaderboard.")
    .max(LEADERBOARD_NAME_MAX, `Use at most ${LEADERBOARD_NAME_MAX} characters.`),
  examIds: z.array(z.string()).min(2, "Choose at least 2 exams."),
  published: z.boolean(),
});

type Values = z.infer<typeof schema>;

function FormBody({
  initial,
  editingId,
  onOpenChange,
  onSaved,
}: {
  initial: Values;
  editingId: string | null;
  onOpenChange: (open: boolean) => void;
  onSaved: (detail: LeaderboardDetail | null, name: string) => void;
}) {
  const directory = useExamDirectory("");
  const create = useCreateLeaderboard();
  const update = useUpdateLeaderboard(editingId ?? "");
  const pending = create.isPending || update.isPending;
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: initial });
  const nameIds = fieldIds("leaderboard", "name");
  const examIds = fieldIds("leaderboard", "exams");
  const publishedId = "leaderboard-published";
  const nameError = form.formState.errors.name?.message ?? null;
  const examsError = form.formState.errors.examIds?.message ?? null;

  const options = useMemo(() => {
    const data = directory.data;
    if (!data) return [];
    const all = [...data.inProgress, ...data.upcoming, ...data.previous];
    return all.map((exam) => ({ value: exam.id, label: `${exam.name} · ${EXAM_TYPE_LABELS[exam.type]} · ${exam.courseName}` }));
  }, [directory.data]);

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null);
    const body = { name: values.name.trim(), examIds: values.examIds, published: values.published };
    try {
      const saved = editingId ? await update.mutateAsync(body) : await create.mutateAsync(body);
      onSaved(saved ?? null, body.name);
      onOpenChange(false);
    } catch (failure) {
      const fieldErrors = isApiClientError(failure) ? failure.fieldErrors : [];
      let mapped = false;
      for (const entry of fieldErrors) {
        if (entry.field === "name" || entry.field === "examIds" || entry.field === "published") {
          form.setError(entry.field, { type: "server", message: entry.message }, { shouldFocus: true });
          mapped = true;
        }
      }
      if (!mapped) setFormError(mutationErrorMessage(failure));
    }
  });

  return (
    <AdminFormDialog
      open
      onOpenChange={(next) => {
        if (!pending) onOpenChange(next);
      }}
      title={editingId ? "Edit leaderboard" : "Add leaderboard"}
      description="Rank students by their combined published marks across the chosen exams."
      testId="leaderboard-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" form="leaderboard-form" disabled={pending} aria-busy={pending} data-testid="leaderboard-save">
            {pending ? "Saving…" : editingId ? "Save changes" : "Add leaderboard"}
          </Button>
        </>
      }
    >
      <form id="leaderboard-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormField ids={nameIds} label="Name" required helper={`Up to ${LEADERBOARD_NAME_MAX} characters.`} error={nameError}>
          <Input
            id={nameIds.inputId}
            maxLength={LEADERBOARD_NAME_MAX + 20}
            aria-invalid={nameError ? true : undefined}
            aria-describedby={describedBy(nameIds, true, Boolean(nameError))}
            data-testid="leaderboard-name"
            {...form.register("name")}
          />
        </FormField>
        <FormField ids={examIds} label="Exams" required helper="Choose at least 2 exams. Only exams with published marks count towards the ranks." error={examsError}>
          {directory.status === "pending" ? (
            <Skeleton className="h-8 w-full" />
          ) : directory.status === "error" ? (
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>
                We couldn&apos;t load the exams.{" "}
                <button type="button" className="underline" onClick={() => void directory.refetch()}>
                  Try again
                </button>
              </AlertDescription>
            </Alert>
          ) : (
            <Controller
              control={form.control}
              name="examIds"
              render={({ field }) => (
                <MultiSelect
                  id={examIds.inputId}
                  options={options}
                  value={field.value}
                  onChange={(next) => field.onChange(next)}
                  placeholder="Select exams"
                  searchPlaceholder="Type to filter exams…"
                  emptyText="No exams yet. Create exams first."
                  invalid={Boolean(examsError)}
                  aria-describedby={describedBy(examIds, true, Boolean(examsError))}
                  testId="leaderboard-exams"
                />
              )}
            />
          )}
        </FormField>
        <div className="flex items-start gap-3">
          <Controller
            control={form.control}
            name="published"
            render={({ field }) => (
              <Switch
                id={publishedId}
                checked={field.value}
                onCheckedChange={field.onChange}
                aria-describedby={`${publishedId}-help`}
                className="mt-0.5"
                data-testid="leaderboard-published"
              />
            )}
          />
          <div className="flex flex-col gap-0.5">
            <Label htmlFor={publishedId}>Visible to students</Label>
            <p id={`${publishedId}-help`} className="text-xs text-muted-foreground">
              Students enrolled in one of these exams can see the ranks. Off keeps it staff-only.
            </p>
          </div>
        </div>
        {formError ? (
          <Alert variant="destructive" data-testid="leaderboard-form-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        ) : null}
      </form>
    </AdminFormDialog>
  );
}

/** Add (no `leaderboardId`) or edit a leaderboard. Edit loads the current exams from `GET /exam-leaderboards/{id}`. */
export function LeaderboardFormDialog({
  leaderboardId,
  onOpenChange,
  onSaved,
}: {
  leaderboardId: string | null;
  onOpenChange: (open: boolean) => void;
  onSaved: (detail: LeaderboardDetail | null, name: string) => void;
}) {
  const detail = useLeaderboard(leaderboardId ?? "", { enabled: Boolean(leaderboardId) });
  if (!leaderboardId) {
    return <FormBody initial={{ name: "", examIds: [], published: false }} editingId={null} onOpenChange={onOpenChange} onSaved={onSaved} />;
  }
  if (detail.status !== "success") {
    return (
      <AdminFormDialog
        open
        onOpenChange={onOpenChange}
        title="Edit leaderboard"
        testId="leaderboard-dialog"
        className="sm:max-w-lg"
        footer={
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
        }
      >
        {detail.status === "pending" ? (
          <div role="status" aria-busy="true" className="flex flex-col gap-3">
            <span className="sr-only">Loading leaderboard…</span>
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{mutationErrorMessage(detail.error)}</AlertDescription>
          </Alert>
        )}
      </AdminFormDialog>
    );
  }
  return (
    <FormBody
      initial={{ name: detail.data.name, examIds: leaderboardExamIds(detail.data), published: detail.data.published }}
      editingId={leaderboardId}
      onOpenChange={onOpenChange}
      onSaved={onSaved}
    />
  );
}
