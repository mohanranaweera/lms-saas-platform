"use client";

import { useMemo, useState } from "react";
import { useFieldArray, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, CalendarClock, ChevronDown, Info, Lock, PlusCircle, RotateCw, X } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { CalendarDialog } from "./calendar-dialog";
import { DialogBody, DialogFooter } from "./dialog-sections";
import { isApiClientError } from "@/lib/api/error";
import {
  useClassSchedule,
  useSaveClassSchedule,
  WEEKDAY_LABELS,
  WEEKDAYS,
  type ClassScheduleResponse,
  type Weekday,
} from "@/lib/api/class-schedule";
import { sortRows, validateSchedule } from "@/lib/calendar/schedule-validation";
import { formatWallTime, timeZoneLabel } from "@/lib/calendar/time";
import { classScheduleFormSchema, type ClassScheduleFormValues } from "@/lib/validation/class-schedule";
import { cn } from "@/lib/utils";

/**
 * "Edit Class Schedule" (CAL-01, docs/specs/class-calendar.md §6.1/§6.2/§8).
 * Shared by the Tenant Admin course ⋮ menu, the Teacher course list and the
 * course workspace Schedule tab. The weekly rows are local wall-clock times
 * in the tenant zone; the server validates, checks course access and
 * concurrency (`expectedRevisionId`) and appends a revision effective from
 * its own clock — past classes, lessons, sessions, attendance and payments
 * are never changed by a save. `canEdit` from the server only decides
 * whether the editor is offered; the PUT is re-authorized server-side.
 */
export function EditClassScheduleDialog({
  courseId,
  courseName,
  open,
  onOpenChange,
  onSaved,
}: {
  courseId: string;
  courseName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called after a confirmed save (e.g. to show a toast). */
  onSaved?: (schedule: ClassScheduleResponse) => void;
}) {
  const query = useClassSchedule(courseId, open);
  // Bumped on "Reload" after a conflict so the form re-initialises from the fresh server copy.
  const [formVersion, setFormVersion] = useState(0);

  const reload = async () => {
    await query.refetch();
    setFormVersion((version) => version + 1);
  };

  const close = () => onOpenChange(false);

  let content: React.ReactNode;
  if (query.isPending) {
    content = (
      <DialogBody>
        <LoadingState label="Loading class schedule…" />
      </DialogBody>
    );
  } else if (query.isError) {
    const error = query.error;
    const message = isApiClientError(error)
      ? error.status === 403
        ? "You don't have permission to view this course's class schedule."
        : error.status === 404
          ? "This course was not found. It may have been deleted."
          : error.message
      : "We couldn't load the class schedule.";
    content = (
      <>
        <DialogBody>
          <ErrorState
            message={message}
            onRetry={isApiClientError(error) && (error.status === 403 || error.status === 404) ? undefined : () => void query.refetch()}
          />
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={close}>
            Close
          </Button>
        </DialogFooter>
      </>
    );
  } else if (!query.data.canEdit) {
    content = <ReadOnlySchedule schedule={query.data} onClose={close} />;
  } else {
    content = (
      <ScheduleForm
        key={formVersion}
        courseId={courseId}
        schedule={query.data}
        onCancel={close}
        onReload={reload}
        onSaved={(saved) => {
          onSaved?.(saved);
          close();
        }}
      />
    );
  }

  const name = query.data?.courseName ?? courseName;

  return (
    <CalendarDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Edit Class Schedule"
      icon={<CalendarClock className="size-5 text-primary" aria-hidden="true" />}
      description={
        <p>
          Edit the class schedule for <strong className="font-semibold">{name}</strong>. These times will be shown on the
          student and public page.
        </p>
      }
      testId="edit-class-schedule-dialog"
      bare
    >
      {content}
    </CalendarDialog>
  );
}

function ReadOnlySchedule({ schedule, onClose }: { schedule: ClassScheduleResponse; onClose: () => void }) {
  return (
    <>
      <DialogBody className="flex flex-col gap-4">
        <Alert>
          <Lock aria-hidden="true" />
          <AlertDescription>
            You can view this schedule, but only the course&apos;s teacher or an administrator can change it.
          </AlertDescription>
        </Alert>
        <ScheduleSummaryList schedule={schedule} />
      </DialogBody>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>
          Close
        </Button>
      </DialogFooter>
    </>
  );
}

/** Read-only weekly list, Monday→Sunday, then start time. Shared with the course Schedule tab. */
export function ScheduleSummaryList({ schedule }: { schedule: ClassScheduleResponse }) {
  const rows = sortRows(schedule.slots);
  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">No weekly class times are set for this course.</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <ul className="divide-y divide-border rounded-lg border border-border" aria-label="Weekly class times">
        {rows.map((slot) => (
          <li key={slot.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
            <span className="font-medium">{WEEKDAY_LABELS[slot.dayOfWeek]}</span>
            <span className="tabular-nums text-muted-foreground">
              {formatWallTime(slot.startTime)} – {formatWallTime(slot.endTime)}
            </span>
          </li>
        ))}
      </ul>
      <p className="text-xs text-muted-foreground">
        {schedule.effectiveFrom || schedule.effectiveTo
          ? `Shown ${schedule.effectiveFrom ? `from ${schedule.effectiveFrom}` : ""}${schedule.effectiveFrom && schedule.effectiveTo ? " " : ""}${schedule.effectiveTo ? `until ${schedule.effectiveTo}` : ""} (inclusive). `
          : "No date range: repeats every week. "}
        Times are in {timeZoneLabel(schedule.timezone)}.
      </p>
    </div>
  );
}

let rowCounter = 0;
function newRowKey(): string {
  rowCounter += 1;
  return `row-${rowCounter}-${Math.random().toString(36).slice(2, 8)}`;
}

const SLOT_FIELD_PATTERN = /^slots(?:\[(\d+)\]|\.(\d+))\.(startTime|endTime|dayOfWeek)$/;

function ScheduleForm({
  courseId,
  schedule,
  onCancel,
  onSaved,
  onReload,
}: {
  courseId: string;
  schedule: ClassScheduleResponse;
  onCancel: () => void;
  onSaved: (schedule: ClassScheduleResponse) => void;
  onReload: () => Promise<void>;
}) {
  const save = useSaveClassSchedule(courseId);
  const [conflict, setConflict] = useState<string | null>(null);
  const [serverMessages, setServerMessages] = useState<string[]>([]);
  const [reloading, setReloading] = useState(false);

  const defaultValues = useMemo<ClassScheduleFormValues>(
    () => ({
      effectiveFrom: schedule.effectiveFrom ?? "",
      effectiveTo: schedule.effectiveTo ?? "",
      slots: sortRows(schedule.slots).map((slot) => ({
        key: newRowKey(),
        dayOfWeek: slot.dayOfWeek,
        startTime: slot.startTime,
        endTime: slot.endTime,
      })),
    }),
    [schedule]
  );

  const {
    control,
    register,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors, isSubmitted },
  } = useForm<ClassScheduleFormValues>({
    resolver: zodResolver(classScheduleFormSchema),
    defaultValues,
  });
  const { fields, append, remove } = useFieldArray({ control, name: "slots", keyName: "fieldId" });
  const slots = useWatch({ control, name: "slots" }) ?? [];
  const watchedFrom = useWatch({ control, name: "effectiveFrom" }) ?? "";
  const watchedTo = useWatch({ control, name: "effectiveTo" }) ?? "";

  const grouped = WEEKDAYS.map((day) => ({
    day,
    rows: fields.map((field, index) => ({ field, index })).filter(({ index }) => slots[index]?.dayOfWeek === day),
  })).filter((group) => group.rows.length > 0);

  const addDay = (day: Weekday) => {
    const index = fields.length;
    append({ key: newRowKey(), dayOfWeek: day, startTime: "", endTime: "" }, { shouldFocus: false });
    // After the menu has closed and returned focus to its trigger, move focus to the new row.
    setTimeout(() => setFocus(`slots.${index}.startTime`), 50);
  };

  const onSubmit = (values: ClassScheduleFormValues) => {
    setConflict(null);
    setServerMessages([]);
    const ordered = sortRows(values.slots);
    save.mutate(
      {
        expectedRevisionId: schedule.revisionId,
        effectiveFrom: values.effectiveFrom || null,
        effectiveTo: values.effectiveTo || null,
        slots: ordered.map(({ dayOfWeek, startTime, endTime }) => ({ dayOfWeek, startTime, endTime })),
      },
      {
        onSuccess: onSaved,
        onError: (error) => {
          if (!isApiClientError(error)) {
            setServerMessages(["The schedule could not be saved. Please try again."]);
            return;
          }
          if (error.status === 409 || error.code === "SCHEDULE_CONFLICT") {
            setConflict(error.message || "This schedule was changed by someone else. Reload to see the latest version.");
            return;
          }
          if (error.status === 403) {
            setServerMessages(["You don't have permission to change this course's class schedule."]);
            return;
          }
          const unmatched: string[] = [];
          for (const fieldError of error.fieldErrors ?? []) {
            const match = SLOT_FIELD_PATTERN.exec(fieldError.field);
            if (match) {
              const submitted = ordered[Number(match[1] ?? match[2])];
              const index = submitted ? values.slots.findIndex((slot) => slot.key === submitted.key) : -1;
              if (index >= 0) {
                setError(`slots.${index}.${match[3] as "startTime" | "endTime" | "dayOfWeek"}`, {
                  type: "server",
                  message: fieldError.message,
                });
                continue;
              }
            } else if (fieldError.field === "effectiveFrom" || fieldError.field === "effectiveTo") {
              setError(fieldError.field, { type: "server", message: fieldError.message });
              continue;
            }
            unmatched.push(fieldError.message);
          }
          if (unmatched.length > 0 || (error.fieldErrors ?? []).length === 0) {
            setServerMessages(unmatched.length > 0 ? unmatched : [error.message || "The schedule could not be saved."]);
          }
        },
      }
    );
  };

  const reload = async () => {
    setReloading(true);
    try {
      await onReload();
    } finally {
      setReloading(false);
    }
  };

  const zoneLabel = timeZoneLabel(schedule.timezone);
  const slotsMessage = errors.slots?.message ?? errors.slots?.root?.message;
  // Cross-row/cross-field rules are recomputed live after the first submit attempt (RHF
  // re-validates only the edited field, so duplicate/overlap/range messages would go stale).
  const live = isSubmitted ? validateSchedule(slots, watchedFrom, watchedTo) : null;
  const rowMessage = (index: number): string | undefined => {
    const slot = slots[index];
    const server = errors.slots?.[index]?.dayOfWeek;
    if (server?.type === "server") return server.message;
    return slot ? live?.rows[slot.key]?.row : undefined;
  };
  const effectiveToError = errors.effectiveTo?.type === "server" ? errors.effectiveTo.message : live?.effectiveTo;
  const hasErrors = isSubmitted && (Object.keys(errors).length > 0 || (live != null && !live.valid));

  return (
    <form noValidate onSubmit={handleSubmit(onSubmit)} className="flex min-h-0 flex-1 flex-col" aria-label="Class schedule">
      <DialogBody className="flex flex-col gap-4">
        {conflict ? (
          <Alert variant="destructive" data-testid="schedule-conflict">
            <AlertCircle aria-hidden="true" />
            <AlertDescription className="flex flex-col gap-2">
              <span>{conflict}</span>
              <Button type="button" size="sm" variant="outline" className="w-fit" onClick={() => void reload()} disabled={reloading}>
                <RotateCw aria-hidden="true" />
                {reloading ? "Reloading…" : "Reload"}
              </Button>
            </AlertDescription>
          </Alert>
        ) : null}
        {serverMessages.length > 0 ? (
          <Alert variant="destructive" data-testid="schedule-server-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              {serverMessages.length === 1 ? (
                serverMessages[0]
              ) : (
                <ul className="list-disc pl-4">
                  {serverMessages.map((message) => (
                    <li key={message}>{message}</li>
                  ))}
                </ul>
              )}
            </AlertDescription>
          </Alert>
        ) : null}

        <DropdownMenu>
          <DropdownMenuTrigger
            render={<Button type="button" variant="outline" className="h-10 w-full border-primary/60 text-primary hover:text-primary" />}
            data-testid="schedule-add-day"
          >
            <PlusCircle aria-hidden="true" />
            Add day
            <ChevronDown aria-hidden="true" />
          </DropdownMenuTrigger>
          <DropdownMenuContent data-testid="schedule-add-day-menu">
            {WEEKDAYS.map((day) => (
              <DropdownMenuItem key={day} onClick={() => addDay(day)}>
                {WEEKDAY_LABELS[day]}
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        {grouped.length === 0 ? (
          <div className="rounded-lg border border-dashed border-border px-4 py-6 text-center" data-testid="schedule-empty">
            <p className="font-medium text-foreground">No class times</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Use <strong>Add day</strong> to add a weekly class time. Saving with no class times clears all future recurring
              classes for this course. Past classes, lessons, live sessions and attendance are not changed.
            </p>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            {grouped.map(({ day, rows }) => (
              <fieldset key={day} className="flex flex-col gap-3" data-testid="schedule-day-group" data-day={day}>
                <legend className="mb-2 text-base font-medium text-foreground">{WEEKDAY_LABELS[day]}</legend>
                {rows.map(({ field, index }, position) => {
                  const rowErrors = errors.slots?.[index];
                  const rowError = rowMessage(index);
                  const rowName = `${WEEKDAY_LABELS[day]} class ${position + 1}`;
                  const startId = `slot-${field.fieldId}-start`;
                  const endId = `slot-${field.fieldId}-end`;
                  const rowErrorId = `slot-${field.fieldId}-row-error`;
                  return (
                    <div key={field.fieldId} className="flex flex-col gap-1" data-testid="schedule-row">
                      <div className="flex items-start gap-2">
                        <div className="grid flex-1 grid-cols-1 gap-3 sm:grid-cols-2">
                          <TimeField
                            id={startId}
                            label="Start time"
                            srContext={rowName}
                            error={rowErrors?.startTime?.message}
                            describedBy={rowError ? rowErrorId : undefined}
                            {...register(`slots.${index}.startTime`)}
                          />
                          <TimeField
                            id={endId}
                            label="End time"
                            srContext={rowName}
                            error={rowErrors?.endTime?.message}
                            describedBy={rowError ? rowErrorId : undefined}
                            {...register(`slots.${index}.endTime`)}
                          />
                        </div>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          className="mt-6 text-destructive hover:bg-destructive/10 hover:text-destructive"
                          aria-label={`Remove ${rowName}`}
                          onClick={() => remove(index)}
                          data-testid="schedule-remove-row"
                        >
                          <X aria-hidden="true" />
                        </Button>
                      </div>
                      {rowError ? (
                        <p id={rowErrorId} className="text-sm text-destructive" data-testid="schedule-row-error">
                          {rowError}
                        </p>
                      ) : null}
                    </div>
                  );
                })}
              </fieldset>
            ))}
          </div>
        )}

        {slotsMessage ? (
          <p className="text-sm text-destructive" role="alert">
            {slotsMessage}
          </p>
        ) : null}

        <fieldset className="flex flex-col gap-3 border-t border-border pt-4">
          <legend className="sr-only">Date range (optional)</legend>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <DateField
              id="schedule-effective-from"
              label="From date (optional)"
              error={errors.effectiveFrom?.message}
              {...register("effectiveFrom")}
            />
            <DateField
              id="schedule-effective-to"
              label="To date (optional)"
              error={effectiveToError}
              {...register("effectiveTo")}
            />
          </div>
          <p className="flex items-start gap-2 text-xs text-muted-foreground" id="schedule-range-help">
            <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
            <span>
              Optional. Classes are shown only on dates within this range (inclusive), in {zoneLabel}. Leave both empty to
              repeat every week.
            </span>
          </p>
        </fieldset>

        {schedule.updatedAt ? (
          <p className="text-xs text-muted-foreground" data-testid="schedule-last-updated">
            Last updated {new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeStyle: "short", timeZone: schedule.timezone }).format(new Date(schedule.updatedAt))}
            {schedule.updatedByName ? ` by ${schedule.updatedByName}` : ""}.
          </p>
        ) : null}
      </DialogBody>
      <DialogFooter>
        {hasErrors ? (
          <p className="text-sm text-destructive sm:mr-auto sm:self-center" role="alert">
            Fix the highlighted fields to save.
          </p>
        ) : null}
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={save.isPending} aria-busy={save.isPending} data-testid="schedule-save">
          {save.isPending ? "Saving…" : "Save"}
        </Button>
      </DialogFooter>
    </form>
  );
}

type RegisteredInputProps = React.InputHTMLAttributes<HTMLInputElement> & { ref?: React.Ref<HTMLInputElement> };

function TimeField({
  id,
  label,
  srContext,
  error,
  describedBy,
  ...input
}: RegisteredInputProps & { id: string; label: string; srContext: string; error?: string; describedBy?: string }) {
  const errorId = `${id}-error`;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs font-medium text-muted-foreground">
        {label}
        <span aria-hidden="true"> *</span>
        <span className="sr-only"> for {srContext}</span>
      </label>
      <Input
        id={id}
        type="time"
        aria-required="true"
        aria-invalid={error ? true : undefined}
        aria-describedby={[error ? errorId : null, describedBy].filter(Boolean).join(" ") || undefined}
        className={cn("h-10 tabular-nums")}
        {...input}
      />
      {error ? (
        <p id={errorId} className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function DateField({ id, label, error, ...input }: RegisteredInputProps & { id: string; label: string; error?: string }) {
  const errorId = `${id}-error`;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs font-medium text-muted-foreground">
        {label}
      </label>
      <Input
        id={id}
        type="date"
        aria-invalid={error ? true : undefined}
        aria-describedby={[error ? errorId : null, "schedule-range-help"].filter(Boolean).join(" ")}
        className="h-10"
        {...input}
      />
      {error ? (
        <p id={errorId} className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}
